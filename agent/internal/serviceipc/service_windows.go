//go:build windows

package serviceipc

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unsafe"

	"github.com/Microsoft/go-winio"
	"github.com/xzcode/battle-ex/agent/internal/roomnet"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/svc"
)

type service struct{}

func RunService() error {
	// Only the real Service Control Manager may invoke the privileged mode.
	active, e := svc.IsWindowsService()
	if e != nil || !active {
		return errors.New("network service must run under Windows SCM")
	}
	return svc.Run(Name, &service{})
}
func (s *service) Execute(_ []string, requests <-chan svc.ChangeRequest, status chan<- svc.Status) (bool, uint32) {
	status <- svc.Status{State: svc.StartPending}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	exe, e := os.Executable()
	if e != nil {
		return false, 1
	}
	root := filepath.Dir(exe)
	// The installer provisions an admin/SYSTEM-only directory. Do not run
	// from a per-user path or load a user-provided DLL/CA/key file.
	if e = roomnet.CheckPrivateStatePath(root, true); e != nil {
		return false, 2
	}
	if e = roomnet.CheckPrivateStatePath(filepath.Join(root, "service-policy.json"), false); e != nil {
		return false, 2
	}
	if e = roomnet.CheckServiceBinary(exe); e != nil {
		return false, 2
	}
	if e = roomnet.CheckServiceBinary(filepath.Join(root, "wintun.dll")); e != nil {
		return false, 2
	}

	// Disallow the working directory and PATH when loading native libraries.
	set := windows.NewLazySystemDLL("kernel32.dll").NewProc("SetDefaultDllDirectories")
	if r, _, _ := set.Call(0x00000200 | 0x00000800); r == 0 {
		return false, 2
	}
	var p Policy
	data, e := os.ReadFile(filepath.Join(root, "service-policy.json"))
	if e != nil || json.Unmarshal(data, &p) != nil || p.Validate() != nil {
		return false, 3
	}
	sid, e := windows.StringToSid(p.AllowedSID)
	if e != nil || sid.String() != p.AllowedSID {
		return false, 3
	}
	_, _, kind, e := sid.LookupAccount("")
	if e != nil || kind != windows.SidTypeUser {
		return false, 3
	}
	stateRoot := filepath.Join(root, "state")
	if e = roomnet.StateDir(stateRoot); e != nil {
		return false, 2
	}
	// go-winio uses FILE_PIPE_REJECT_REMOTE_CLIENTS. Exact SID rather than
	// Everyone/Authenticated Users. Network logons are explicitly denied.
	listener, e := winio.ListenPipe(PipeName, &winio.PipeConfig{
		SecurityDescriptor: "D:P(D;;GA;;;NU)(A;;GA;;;SY)(A;;GA;;;BA)(A;;0x120183;;;" + sid.String() + ")",
		InputBufferSize:    8192, OutputBufferSize: 65536,
	})
	if e != nil {
		return false, 4
	}
	defer listener.Close()
	finished := make(chan struct{})
	go func() {
		defer close(finished)
		for ctx.Err() == nil {
			conn, e := listener.Accept()
			if e != nil {
				return
			}
			// One active native overlay per installed service. Refuse concurrent
			// ownership; OS/room authorization determines access.
			ServeSession(ctx, conn, p, func(session context.Context, r Request, report func(roomnet.Status)) error {
				if e := Authenticate(session, r); e != nil {
					return e
				}
				dir := filepath.Join(stateRoot, r.UserID)
				return roomnet.Run(session, roomnet.Credentials{Origin: p.Origin, Token: r.Token}, roomnet.Options{
					StateDir: dir, Issuer: p.Issuer, Interface: "battle0", Port: 51821,
				}, report)
			})
		}
	}()
	current := svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}
	status <- current
	for {
		select {
		case <-finished:
			return false, 0
		case c, ok := <-requests:
			if !ok || c.Cmd == svc.Stop || c.Cmd == svc.Shutdown {
				status <- svc.Status{State: svc.StopPending, WaitHint: 20000}
				cancel()
				_ = listener.Close()
				<-finished
				return false, 0
			}
			if c.Cmd == svc.Interrogate {
				status <- current
			}
		}
	}
}

// Check the pipe endpoint belongs to the running registered SCM service,
// not a user-created pipe with the same name. Verify before sending tokens.
func verifyServer(conn net.Conn) error {
	handle, ok := conn.(interface{ Fd() uintptr })
	if !ok {
		return errors.New("cannot identify network service pipe")
	}
	var actual uint32
	if e := windows.GetNamedPipeServerProcessId(windows.Handle(handle.Fd()), &actual); e != nil {
		return e
	}
	scm, e := windows.OpenSCManager(nil, nil, windows.SC_MANAGER_CONNECT)
	if e != nil {
		return e
	}
	defer windows.CloseServiceHandle(scm)
	name, _ := windows.UTF16PtrFromString(Name)
	service, e := windows.OpenService(scm, name, windows.SERVICE_QUERY_STATUS)
	if e != nil {
		return e
	}
	defer windows.CloseServiceHandle(service)
	var info windows.SERVICE_STATUS_PROCESS
	var needed uint32
	e = windows.QueryServiceStatusEx(service, windows.SC_STATUS_PROCESS_INFO, (*byte)(unsafe.Pointer(&info)), uint32(unsafe.Sizeof(info)), &needed)
	if e != nil || actual == 0 || info.ProcessId != actual || info.CurrentState != windows.SERVICE_RUNNING {
		return errors.New("network service pipe identity mismatch")
	}
	return nil
}
func RunClient(input io.Reader, output io.Writer) error {
	reader := bufio.NewReaderSize(input, 8192)
	r, e := ReadRequest(reader)
	if e != nil {
		return e
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, e := winio.DialPipeAccess(ctx, PipeName, windows.FILE_READ_DATA|windows.FILE_WRITE_DATA|windows.FILE_READ_ATTRIBUTES|windows.FILE_WRITE_ATTRIBUTES|windows.READ_CONTROL|windows.SYNCHRONIZE)
	if e != nil {
		return errors.New("Windows Battle EX Network service is not installed, stopped or busy")
	}
	defer conn.Close()
	if e = verifyServer(conn); e != nil {
		return fmt.Errorf("cannot authenticate installed network service: %w", e)
	}
	_ = conn.SetWriteDeadline(time.Now().Add(3 * time.Second))
	if e = json.NewEncoder(conn).Encode(r); e != nil {
		return e
	}
	_ = conn.SetWriteDeadline(time.Time{})
	go func() { _, _ = io.Copy(io.Discard, reader); _ = conn.Close() }()
	// The pipe carries only local status JSON. Do not interpret any received
	// data as commands. EOF on the parent pipe stops the service session.
	_, e = io.Copy(output, conn)
	if e != nil && !errors.Is(e, net.ErrClosed) && !strings.Contains(e.Error(), "closed") {
		return e
	}
	return nil
}
