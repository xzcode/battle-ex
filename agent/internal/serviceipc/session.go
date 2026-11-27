// Package serviceipc implements the fixed, bounded local network-service protocol.
package serviceipc

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"regexp"
	"strings"
	"time"

	"github.com/xzcode/battle-ex/agent/internal/roomnet"
)

const Name = "BattleEXNetwork"
const PipeName = `\\.\pipe\BattleEXNetwork-v1`

type Policy struct {
	Origin     string `json:"origin"`
	Issuer     string `json:"issuer"`
	AllowedSID string `json:"allowedSid"`
}
type Request struct {
	Origin string `json:"origin"`
	Token  string `json:"token"`
	Issuer string `json:"issuer"`
	UserID string `json:"userId"`
}

var hexKey = regexp.MustCompile("^[a-f0-9]{64}$")
var tokenPattern = regexp.MustCompile("^[A-Za-z0-9_-]{43}$")
var userPattern = regexp.MustCompile("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$")
var sidPattern = regexp.MustCompile("^S-1-(5-21-[0-9]+-[0-9]+-[0-9]+-[0-9]+|12-1-[0-9]+-[0-9]+-[0-9]+-[0-9]+)$")

func (p Policy) Validate() error {
	origin, e := roomnet.Origin(p.Origin)
	if e != nil || origin != p.Origin || !strings.HasPrefix(origin, "https://") || !hexKey.MatchString(p.Issuer) || p.Issuer == strings.Repeat("0", 64) || !sidPattern.MatchString(p.AllowedSID) {
		return errors.New("invalid service policy: canonical HTTPS origin, issuer key and explicit local SID required")
	}
	return nil
}
func (p Policy) Authorize(r Request) error {
	if p.Validate() != nil || r.Origin != p.Origin || r.Issuer != p.Issuer || !tokenPattern.MatchString(r.Token) || !userPattern.MatchString(r.UserID) {
		return errors.New("network request does not match the installed service policy")
	}
	return nil
}
func ReadRequest(reader *bufio.Reader) (Request, error) {
	var request Request
	line, e := reader.ReadSlice('\n')
	if e != nil {
		return request, errors.New("invalid bounded service request")
	}
	d := json.NewDecoder(strings.NewReader(string(line)))
	d.DisallowUnknownFields()
	if e = d.Decode(&request); e != nil {
		return request, errors.New("invalid service request")
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		return request, errors.New("unexpected service request data")
	}
	return request, nil
}

// Authenticate the account on the configured server before selecting its
// service-owned key directory. A claimed client user ID is not authority.
func Authenticate(ctx context.Context, r Request) error {
	client, e := roomnet.HTTPClient("", 3*time.Second)
	if e != nil {
		return e
	}
	defer client.CloseIdleConnections()
	return roomnet.VerifyAccount(ctx, client, roomnet.Credentials{Origin: r.Origin, Token: r.Token}, r.UserID)
}

// ServeSession accepts one request, runs exactly one authorized tunnel, and
// cancels it on EOF, service shutdown, extra input, or a blocked status writer.
func ServeSession(parent context.Context, conn net.Conn, p Policy, run func(context.Context, Request, func(roomnet.Status)) error) {
	defer conn.Close()
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	defer context.AfterFunc(ctx, func() { _ = conn.Close() })()
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	reader := bufio.NewReaderSize(conn, 8192)
	request, e := ReadRequest(reader)
	encoder := json.NewEncoder(conn)
	report := func(s roomnet.Status) {
		_ = conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
		if encoder.Encode(s) != nil {
			cancel()
			_ = conn.Close()
		}
	}
	if e != nil || p.Authorize(request) != nil {
		report(roomnet.Status{State: "error", Error: "request rejected by local network service"})
		return
	}
	_ = conn.SetReadDeadline(time.Time{})
	go func() { var data [1]byte; _, _ = reader.Read(data[:]); cancel() }()
	if e = run(ctx, request, report); e != nil && ctx.Err() == nil {
		// Do not send credentials, paths or internal error strings to clients.
		report(roomnet.Status{State: "error", Error: "network service could not establish an authorized tunnel"})
	}
}
