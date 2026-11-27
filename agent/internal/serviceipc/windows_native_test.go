//go:build windows

package serviceipc

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
	"unsafe"

	"github.com/Microsoft/go-winio"
	"github.com/xzcode/battle-ex/agent/internal/roomnet"
	"github.com/xzcode/network-core-ex/pkg/adapter"
	"github.com/xzcode/network-core-ex/pkg/engine"
	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/tun"
)

// The disposable CI runner provisions two temporary local standard users.
// Open the exact same pipe access mask and verify its SCM identity while
// impersonating their real logon tokens; revert before using the connection.
func dialAs(ctx context.Context, user, password string) (net.Conn, error) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	u, e := windows.UTF16PtrFromString(user)
	if e != nil {
		return nil, e
	}
	p, e := windows.UTF16PtrFromString(password)
	if e != nil {
		return nil, e
	}
	domain, _ := windows.UTF16PtrFromString(".")
	advapi := windows.NewLazySystemDLL("advapi32.dll")
	var token windows.Token
	ok, _, callErr := advapi.NewProc("LogonUserW").Call(uintptr(unsafe.Pointer(u)), uintptr(unsafe.Pointer(domain)), uintptr(unsafe.Pointer(p)), 2, 0, uintptr(unsafe.Pointer(&token)))
	if ok == 0 {
		return nil, fmt.Errorf("temporary test account logon: %w", callErr)
	}
	defer token.Close()
	ok, _, callErr = advapi.NewProc("ImpersonateLoggedOnUser").Call(uintptr(token))
	if ok == 0 {
		return nil, fmt.Errorf("test impersonation: %w", callErr)
	}
	defer func() {
		if e := windows.RevertToSelf(); e != nil {
			panic("could not revert test impersonation")
		}
	}()
	conn, e := winio.DialPipeAccess(ctx, PipeName, windows.FILE_READ_DATA|windows.FILE_WRITE_DATA|windows.FILE_READ_ATTRIBUTES|windows.FILE_WRITE_ATTRIBUTES|windows.READ_CONTROL|windows.SYNCHRONIZE)
	if e != nil {
		return nil, e
	}
	if e = verifyServer(conn); e != nil {
		conn.Close()
		return nil, e
	}
	return conn, nil
}

type testAccount struct {
	Token string `json:"token"`
	User  struct {
		ID string `json:"id"`
	} `json:"user"`
}
type testAPI struct {
	base   string
	client *http.Client
}

func (a testAPI) call(t *testing.T, method, path, token string, body any, want int, result any) {
	t.Helper()
	var input io.Reader
	if body != nil {
		raw, e := json.Marshal(body)
		if e != nil {
			t.Fatal(e)
		}
		input = bytes.NewReader(raw)
	}
	req, e := http.NewRequest(method, a.base+"/api/v1"+path, input)
	if e != nil {
		t.Fatal(e)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	response, e := a.client.Do(req)
	if e != nil {
		t.Fatalf("%s %s: %v", method, path, e)
	}
	defer response.Body.Close()
	if response.StatusCode != want {
		t.Fatalf("%s %s returned %d; expected %d", method, path, response.StatusCode, want)
	}
	if result != nil {
		if e = json.NewDecoder(io.LimitReader(response.Body, 1024*1024)).Decode(result); e != nil {
			t.Fatal(e)
		}
	}
}
func waitFor(t *testing.T, desc string, timeout time.Duration, f func() bool) {
	t.Helper()
	end := time.Now().Add(timeout)
	for time.Now().Before(end) {
		if f() {
			return
		}
		time.Sleep(250 * time.Millisecond)
	}
	t.Fatalf("timeout: %s", desc)
}
func testMarker(t *testing.T) string {
	t.Helper()
	var raw [12]byte
	if _, e := rand.Read(raw[:]); e != nil {
		t.Fatal(e)
	}
	return hex.EncodeToString(raw[:])
}
func checksum(data []byte) uint16 {
	var sum uint32
	for len(data) >= 2 {
		sum += uint32(binary.BigEndian.Uint16(data[:2]))
		data = data[2:]
	}
	if len(data) > 0 {
		sum += uint32(data[0]) << 8
	}
	for sum>>16 != 0 {
		sum = (sum & 65535) + (sum >> 16)
	}
	return ^uint16(sum)
}

// Test-only virtual peer: the actual shared WireGuard/WSS engine uses a
// packet queue instead of a second OS TUN, avoiding conflicting routes in
// a single Windows VM. Respond only to the exact synthetic UDP test payload.
func echoPacket(p []byte, marker string) ([]byte, bool) {
	if len(p) != 28+len(marker) || p[0] != 0x45 || p[9] != 17 || binary.BigEndian.Uint16(p[22:24]) != 39120 || !bytes.Equal(p[28:], []byte(marker)) {
		return nil, false
	}
	out := append([]byte(nil), p...)
	copy(out[12:16], p[16:20])
	copy(out[16:20], p[12:16])
	copy(out[20:22], p[22:24])
	copy(out[22:24], p[20:22])
	out[8] = 64
	out[10] = 0
	out[11] = 0
	out[26] = 0
	out[27] = 0
	binary.BigEndian.PutUint16(out[10:12], checksum(out[:20]))
	return out, true
}
func adapterHasIP(ip string) bool {
	iface, e := net.InterfaceByName("battle0")
	if e != nil {
		return false
	}
	addresses, e := iface.Addrs()
	if e != nil {
		return false
	}
	for _, address := range addresses {
		if strings.HasPrefix(address.String(), ip+"/") {
			return true
		}
	}
	return false
}
func TestWindowsInstalledService(t *testing.T) {
	if os.Getenv("BATTLE_WINDOWS_NATIVE_TEST") != "true" {
		t.Skip("requires disposable runner with the installed service")
	}
	if os.Getenv("GITHUB_ACTIONS") != "true" || os.Getenv("RUNNER_OS") != "Windows" {
		t.Fatal("this integration is restricted to an ephemeral Windows runner")
	}
	origin, issuer := os.Getenv("BATTLE_TEST_ORIGIN"), os.Getenv("BATTLE_TEST_ISSUER")
	allowed, denied, password := os.Getenv("BATTLE_TEST_ALLOWED"), os.Getenv("BATTLE_TEST_DENIED"), os.Getenv("BATTLE_TEST_PASSWORD")
	if allowed == "" || denied == "" || password == "" {
		t.Fatal("temporary logon credentials required")
	}
	marker := testMarker(t)
	client, e := roomnet.HTTPClient("", 5*time.Second)
	if e != nil {
		t.Fatal(e)
	}
	defer client.CloseIdleConnections()
	api := testAPI{origin, client}
	var a, b testAccount
	api.call(t, "POST", "/register", "", map[string]string{"username": "a_" + marker, "password": "Fixture-" + marker}, 201, &a)
	api.call(t, "POST", "/register", "", map[string]string{"username": "b_" + marker, "password": "Fixture-" + marker}, 201, &b)
	var room struct {
		ID string `json:"id"`
	}
	api.call(t, "POST", "/rooms", a.Token, map[string]any{"name": "Native CI fixture", "gameId": "warcraft3", "capacity": 2, "visibility": "PUBLIC"}, 201, &room)
	api.call(t, "POST", "/rooms/"+room.ID+"/join", b.Token, map[string]string{}, 200, nil)
	// Reject the non-allowlisted local user (not merely a syntactic SID test).
	ctx, stop := context.WithTimeout(context.Background(), 4*time.Second)
	deniedConn, denyErr := dialAs(ctx, denied, password)
	stop()
	if deniedConn != nil {
		deniedConn.Close()
		t.Fatal("unapproved standard user opened service pipe")
	}
	if denyErr == nil || !errors.Is(denyErr, windows.ERROR_ACCESS_DENIED) {
		t.Fatalf("expected pipe access denied; got %v", denyErr)
	}
	t.Log("PASS: real non-allowlisted local user denied")
	dir := filepath.Join(t.TempDir(), "peer")
	if e = roomnet.StateDir(dir); e != nil {
		t.Fatal(e)
	}
	keys, e := roomnet.LoadDevice(dir)
	if e != nil {
		t.Fatal(e)
	}
	peer, e := roomnet.NewClient(roomnet.Credentials{Origin: origin, Token: b.Token}, issuer, "", keys)
	if e != nil {
		t.Fatal(e)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if e = peer.Enroll(ctx); e != nil {
		t.Fatal(e)
	}
	defer peer.Disconnect()
	// Keep the test peer enrolled during native service and adapter setup.
	snapshots := make(chan *roomnet.Snapshot, 1)
	peerErr := make(chan error, 1)
	go func() {
		for ctx.Err() == nil {
			s, e := peer.Snapshot(ctx)
			if e != nil {
				select {
				case peerErr <- e:
				default:
				}
			}
			if s != nil && len(s.Peers) == 1 {
				select {
				case snapshots <- s:
				default:
				}
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(2 * time.Second):
			}
		}
	}()
	dialCtx, stopDial := context.WithTimeout(ctx, 5*time.Second)
	conn, e := dialAs(dialCtx, allowed, password)
	stopDial()
	if e != nil {
		t.Fatalf("allowed standard-user pipe connection: %v", e)
	}
	defer conn.Close()
	if e = json.NewEncoder(conn).Encode(Request{Origin: origin, Issuer: issuer, UserID: a.User.ID, Token: a.Token}); e != nil {
		t.Fatal(e)
	}
	var mu sync.Mutex
	var latest roomnet.Status
	go func() {
		reader := bufio.NewScanner(conn)
		reader.Buffer(make([]byte, 8192), 1024*1024)
		for reader.Scan() {
			var s roomnet.Status
			if json.Unmarshal(reader.Bytes(), &s) == nil {
				mu.Lock()
				latest = s
				mu.Unlock()
			}
		}
	}()
	status := func() roomnet.Status { mu.Lock(); defer mu.Unlock(); return latest }
	var snap *roomnet.Snapshot
	select {
	case snap = <-snapshots:
	case e := <-peerErr:
		t.Fatal(e)
	case <-time.After(40 * time.Second):
		t.Fatal("no signed peer snapshot")
	}
	if snap.RoomID != room.ID || snap.ICE || snap.DiscoveryProfile != "warcraft3-directed-v1" {
		t.Fatal("unexpected test network grant")
	}
	peerIP, localIP := snap.LocalIP, snap.Peers[0].IP
	leaseFile := filepath.Join(dir, "lease")
	if e = os.WriteFile(leaseFile, []byte(snap.Peers[0].Lease+"\n"), 0600); e != nil {
		t.Fatal(e)
	}
	var queue *adapter.PacketQueue
	queue = adapter.NewPacketQueue(func(p []byte) bool {
		reply, ok := echoPacket(p, marker)
		if !ok {
			return true
		}
		return queue.Push(reply)
	}, nil)
	runCtx, stopPeer := context.WithCancel(ctx)
	defer stopPeer()
	engineDone := make(chan error, 1)
	go func() {
		engineDone <- engine.Run(runCtx, engine.Config{
			Interface: "mobile0", IP: peerIP, ListenPort: 51822,
			WGKeyFile: filepath.Join(dir, "wireguard.key"), IdentityKeyFile: filepath.Join(dir, "identity.key"),
			Peers:    []engine.Peer{{IP: localIP, PublicKey: snap.Peers[0].WireguardKey, RelayURL: snap.RelayURL, RelayTokenFile: leaseFile}},
			External: &engine.External{Open: func(context.Context, netip.Addr, []netip.Prefix) (tun.Device, error) { return queue, nil }, Protect: func(int) bool { return true }},
		})
	}()
	defer func() {
		stopPeer()
		select {
		case <-engineDone:
		case <-time.After(10 * time.Second):
			t.Error("test peer did not stop")
		}
	}()
	waitFor(t, "real Wintun adapter and room address", 60*time.Second, func() bool {
		s := status()
		return s.State == "running" && s.IP == localIP && len(s.Peers) == 1 && adapterHasIP(localIP)
	})
	t.Log("PASS: installed SCM service, authenticated standard-user pipe, Wintun address")
	// Real Windows UDP socket -> Wintun -> WireGuard/WSS -> test peer -> Windows.
	waitFor(t, "Windows UDP roundtrip through encrypted relay", 40*time.Second, func() bool {
		s, e := net.DialUDP("udp4", &net.UDPAddr{IP: net.ParseIP(localIP)}, &net.UDPAddr{IP: net.ParseIP(peerIP), Port: 39120})
		if e != nil {
			return false
		}
		defer s.Close()
		_ = s.SetDeadline(time.Now().Add(2 * time.Second))
		if _, e = s.Write([]byte(marker)); e != nil {
			return false
		}
		var buf [128]byte
		n, e := s.Read(buf[:])
		return e == nil && string(buf[:n]) == marker
	})
	t.Log("PASS: real Windows UDP roundtrip over authorized WSS relay")
	// Windows-only directed broadcast acceptance: native sender and engine
	// counters. The queue peer deliberately has no native receive stack for
	// broadcasts, so do not label this two-Windows game discovery.
	s, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP(localIP)})
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	raw, e := s.SyscallConn()
	if e != nil {
		t.Fatal(e)
	}
	var socketErr error
	if e = raw.Control(func(fd uintptr) {
		socketErr = windows.SetsockoptInt(windows.Handle(fd), windows.SOL_SOCKET, windows.SO_BROADCAST, 1)
	}); e != nil || socketErr != nil {
		t.Fatal("enable directed broadcast", e, socketErr)
	}
	broadcast := net.ParseIP(localIP).To4()
	broadcast[3] = 255
	prior := status().Discovery
	if prior == nil {
		t.Fatal("missing native discovery counters")
	}
	waitFor(t, "native directed broadcast capture", 15*time.Second, func() bool {
		_, _ = s.WriteToUDP([]byte(marker), &net.UDPAddr{IP: broadcast, Port: 6112})
		current := status().Discovery
		return current != nil && current.Sent > prior.Sent
	})
	waitFor(t, "non-allowlisted broadcast dropped", 15*time.Second, func() bool {
		_, _ = s.WriteToUDP([]byte(marker), &net.UDPAddr{IP: broadcast, Port: 6113})
		current := status().Discovery
		return current != nil && current.Dropped > prior.Dropped
	})
	t.Log("PASS: Windows directed UDP capture and non-allowlisted port rejection")
	s.Close()
	api.call(t, "POST", "/rooms/"+room.ID+"/leave", a.Token, nil, 204, nil)
	waitFor(t, "leave removes native virtual address", 45*time.Second, func() bool { return !adapterHasIP(localIP) })
	if e = conn.Close(); e != nil && !errors.Is(e, net.ErrClosed) {
		t.Log("pipe already closed")
	}
	t.Log("PASS: room leave revoked native address; pipe closed")
}
