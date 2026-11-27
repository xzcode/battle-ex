package roomnet

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"strings"
	"time"

	core "github.com/xzcode/network-core-ex/pkg/relay"
)

type Credentials struct {
	Origin string `json:"origin"`
	Token  string `json:"token"`
}
type Device struct {
	Identity  ed25519.PrivateKey
	WGPrivate []byte
	WGPublic  string
}

func (d Device) IdentityPublic() string {
	return hex.EncodeToString(d.Identity.Public().(ed25519.PublicKey))
}

type Peer struct {
	UserID       string `json:"userId"`
	IP           string `json:"ip"`
	IdentityKey  string `json:"identityKey"`
	WireguardKey string `json:"wireguardKey"`
	Lease        string `json:"lease"`
}
type Snapshot struct {
	Version       int    `json:"version"`
	RoomID        string `json:"roomId"`
	NetworkID     string `json:"networkId"`
	Revision      int64  `json:"revision"`
	IssuedAt      int64  `json:"issuedAt"`
	ValidUntil    int64  `json:"validUntil"`
	LocalIP       string `json:"localIP"`
	IdentityKey   string `json:"identityKey"`
	WireguardKey  string `json:"wireguardKey"`
	RelayNode     string `json:"relayNode"`
	RelayURL      string `json:"relayURL"`
	StunURL       string `json:"stunURL"`
	ICE           bool   `json:"ice"`
	Peers         []Peer `json:"peers"`
	Nonce         string `json:"nonce"`
	SessionDigest string `json:"sessionDigest"`
}
type signed struct {
	Payload   string `json:"payload"`
	Signature string `json:"signature"`
}
type Client struct {
	origin string
	token  string
	digest string
	issuer ed25519.PublicKey
	http   *http.Client
	device Device
}

var ErrUnauthorized = errors.New("network authorization revoked")
var ErrInvalidSnapshot = errors.New("invalid signed room snapshot")

func Origin(raw string) (string, error) {
	u, e := url.Parse(raw)
	if e != nil || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return "", errors.New("invalid server origin")
	}
	host := u.Hostname()
	loop := host == "localhost" || net.ParseIP(host) != nil && net.ParseIP(host).IsLoopback()
	if u.Scheme != "https" && !(u.Scheme == "http" && loop) {
		return "", errors.New("HTTPS required for remote server")
	}
	return u.Scheme + "://" + u.Host, nil
}
func HTTPClient(ca string, timeout time.Duration) (*http.Client, error) {
	roots, e := x509.SystemCertPool()
	if e != nil || roots == nil {
		roots = x509.NewCertPool()
	}
	if ca != "" {
		pem, e := os.ReadFile(ca)
		if e != nil {
			return nil, e
		}
		if !roots.AppendCertsFromPEM(pem) {
			return nil, errors.New("invalid CA file")
		}
	}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.TLSClientConfig = &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: roots}
	return &http.Client{Transport: transport, Timeout: timeout, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}, nil
}
func NewClient(c Credentials, issuerHex, ca string, d Device) (*Client, error) {
	origin, e := Origin(c.Origin)
	if e != nil {
		return nil, e
	}
	pub, e := hex.DecodeString(issuerHex)
	if e != nil || len(pub) != 32 || issuerHex != hex.EncodeToString(pub) {
		return nil, errors.New("pinned Ed25519 issuer public key required")
	}
	if len(c.Token) != 43 {
		return nil, errors.New("invalid login token")
	}
	h, e := HTTPClient(ca, 3*time.Second)
	if e != nil {
		return nil, e
	}
	sum := sha256.Sum256([]byte(c.Token))
	return &Client{origin, c.Token, hex.EncodeToString(sum[:]), ed25519.PublicKey(pub), h, d}, nil
}
func (c *Client) post(ctx context.Context, path string, body any, result any) (int, error) {
	raw, e := json.Marshal(body)
	if e != nil {
		return 0, e
	}
	req, e := http.NewRequestWithContext(ctx, http.MethodPost, c.origin+"/api/v1/network/"+path, bytes.NewReader(raw))
	if e != nil {
		return 0, e
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Content-Type", "application/json")
	res, e := c.http.Do(req)
	if e != nil {
		return 0, e
	}
	defer res.Body.Close()
	if res.StatusCode == 401 || res.StatusCode == 403 || res.StatusCode == 409 {
		return res.StatusCode, ErrUnauthorized
	}
	if res.StatusCode == 204 {
		return 204, nil
	}
	if res.StatusCode != 200 {
		return res.StatusCode, fmt.Errorf("network API HTTP %d", res.StatusCode)
	}
	data, e := io.ReadAll(io.LimitReader(res.Body, 1024*1024+1))
	if e != nil {
		return 0, e
	}
	if len(data) > 1024*1024 {
		return 0, ErrInvalidSnapshot
	}
	if e = json.Unmarshal(data, result); e != nil {
		return 0, ErrInvalidSnapshot
	}
	return 200, nil
}
func (c *Client) Enroll(ctx context.Context) error {
	identity := c.device.IdentityPublic()
	proof := ed25519.Sign(c.device.Identity, []byte("battle-ex/device/v1\x00"+c.digest+"\n"+identity+"\n"+c.device.WGPublic))
	var reply struct{ IdentityKey, WireguardKey, Issuer string }
	status, e := c.post(ctx, "device", map[string]string{"identityKey": identity, "wireguardKey": c.device.WGPublic, "proof": base64.RawURLEncoding.EncodeToString(proof)}, &reply)
	if e != nil {
		return e
	}
	if status != 200 || reply.Issuer != hex.EncodeToString(c.issuer) || reply.IdentityKey != identity || reply.WireguardKey != c.device.WGPublic {
		return ErrInvalidSnapshot
	}
	return nil
}
func (c *Client) Snapshot(ctx context.Context) (*Snapshot, error) {
	nonce := make([]byte, 32)
	if _, e := rand.Read(nonce); e != nil {
		return nil, e
	}
	n := hex.EncodeToString(nonce)
	var r signed
	status, e := c.post(ctx, "snapshot", map[string]string{"nonce": n}, &r)
	if e != nil {
		return nil, e
	}
	if status == 204 {
		return nil, nil
	}
	data, e := base64.RawURLEncoding.DecodeString(r.Payload)
	if e != nil {
		return nil, ErrInvalidSnapshot
	}
	sig, e := base64.RawURLEncoding.DecodeString(r.Signature)
	if e != nil || !ed25519.Verify(c.issuer, append([]byte("battle-ex/snapshot/v1\x00"), data...), sig) {
		return nil, ErrInvalidSnapshot
	}
	var s Snapshot
	if e = json.Unmarshal(data, &s); e != nil {
		return nil, ErrInvalidSnapshot
	}
	if e = c.validate(&s, n, time.Now()); e != nil {
		return nil, e
	}
	return &s, nil
}
func (c *Client) validate(s *Snapshot, nonce string, now time.Time) error {
	fail := ErrInvalidSnapshot
	if s.Version != 1 || s.RoomID == "" || s.NetworkID == "" || s.Revision <= 0 || s.Nonce != nonce || s.SessionDigest != c.digest ||
		s.IdentityKey != c.device.IdentityPublic() || s.WireguardKey != c.device.WGPublic || s.IssuedAt > now.Unix()+5 ||
		s.ValidUntil <= now.Unix() || s.ValidUntil-s.IssuedAt != 12 || s.ValidUntil > now.Unix()+17 || len(s.Peers) > 64 || s.RelayNode == "" {
		return fail
	}
	local, e := netip.ParseAddr(s.LocalIP)
	if e != nil || !local.Is4() || !netip.MustParsePrefix("10.160.0.0/12").Contains(local) {
		return fail
	}
	prefix := netip.PrefixFrom(local, 24).Masked()
	relay, e := url.Parse(s.RelayURL)
	if e != nil || relay.Scheme != "wss" || relay.Host == "" || relay.User != nil || relay.Path != "/relay/v1" || relay.RawQuery != "" || relay.Fragment != "" {
		return fail
	}
	if s.ICE != strings.HasPrefix(s.StunURL, "stun:") {
		return fail
	}
	ips := map[string]bool{s.LocalIP: true}
	keys := map[string]bool{s.IdentityKey: true, s.WireguardKey: true}
	users := map[string]bool{}
	for _, p := range s.Peers {
		a, e := netip.ParseAddr(p.IP)
		if e != nil || !a.Is4() || !prefix.Contains(a) || ips[p.IP] || p.UserID == "" || users[p.UserID] || keys[p.IdentityKey] || keys[p.WireguardKey] || p.IdentityKey == p.WireguardKey || !publicKey(p.IdentityKey) || !publicKey(p.WireguardKey) {
			return fail
		}
		lease, e := core.Verify(c.issuer, p.Lease, now)
		if e != nil || lease.Network != s.NetworkID || lease.Node != s.RelayNode || lease.Expires < s.ValidUntil || !((lease.A == s.IdentityKey && lease.B == p.IdentityKey) || (lease.B == s.IdentityKey && lease.A == p.IdentityKey)) {
			return fail
		}
		ips[p.IP] = true
		keys[p.IdentityKey] = true
		keys[p.WireguardKey] = true
		users[p.UserID] = true
	}
	return nil
}
func publicKey(s string) bool {
	b, e := hex.DecodeString(s)
	return e == nil && len(b) == 32 && hex.EncodeToString(b) == s && s != strings.Repeat("0", 64)
}
func (c *Client) Disconnect() {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_, _ = c.post(ctx, "disconnect", map[string]string{}, nil)
}
