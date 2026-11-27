package serviceipc

import (
	"bufio"
	"context"
	"encoding/json"
	"github.com/xzcode/battle-ex/agent/internal/roomnet"
	"net"
	"strings"
	"testing"
	"time"
)

func policy() Policy {
	return Policy{"https://battle.example", strings.Repeat("a", 64), "S-1-5-21-1-2-3-1001"}
}
func request() Request {
	p := policy()
	return Request{p.Origin, strings.Repeat("b", 43), p.Issuer, "12345678-1234-1234-1234-123456789abc"}
}
func TestPolicy(t *testing.T) {
	p, r := policy(), request()
	if e := p.Authorize(r); e != nil {
		t.Fatal(e)
	}
	r.Origin = "https://other.example"
	if p.Authorize(r) == nil {
		t.Fatal("origin accepted")
	}
	r = request()
	r.UserID = "../state"
	if p.Authorize(r) == nil {
		t.Fatal("invalid account accepted")
	}
	r = request()
	r.Issuer = strings.Repeat("c", 64)
	if p.Authorize(r) == nil {
		t.Fatal("issuer accepted")
	}
	if _, e := ReadRequest(bufio.NewReader(strings.NewReader("{\"command\":\"shell\"}\n"))); e == nil {
		t.Fatal("unknown command accepted")
	}
}
func TestSessionDisconnect(t *testing.T) {
	server, client := net.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		ServeSession(context.Background(), server, policy(), func(ctx context.Context, _ Request, report func(roomnet.Status)) error {
			report(roomnet.Status{State: "running"})
			<-ctx.Done()
			return nil
		})
	}()
	if e := json.NewEncoder(client).Encode(request()); e != nil {
		t.Fatal(e)
	}
	var s roomnet.Status
	if e := json.NewDecoder(client).Decode(&s); e != nil || s.State != "running" {
		t.Fatal(s, e)
	}
	client.Close()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("session leaked on disconnect")
	}
}
func TestSessionRejectsUnapprovedOrigin(t *testing.T) {
	server, client := net.Pipe()
	defer client.Close()
	go ServeSession(context.Background(), server, policy(), func(context.Context, Request, func(roomnet.Status)) error {
		t.Error("unauthorized request ran")
		return nil
	})
	r := request()
	r.Origin = "https://bad.example"
	if e := json.NewEncoder(client).Encode(r); e != nil {
		t.Fatal(e)
	}
	var s roomnet.Status
	if e := json.NewDecoder(client).Decode(&s); e != nil || s.State != "error" {
		t.Fatal(s, e)
	}
}
