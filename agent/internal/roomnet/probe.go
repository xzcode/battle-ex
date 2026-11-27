package roomnet

import (
	"bytes"
	"context"
	"crypto/rand"
	"net"
	"net/netip"
	"sync"
	"time"
)

const ProbePort = 39491

type probes struct {
	mu  sync.Mutex
	rtt map[string]float64
}

func (p *probes) get(ip string) *float64 {
	p.mu.Lock()
	defer p.mu.Unlock()
	v, ok := p.rtt[ip]
	if !ok {
		return nil
	}
	return &v
}
func (p *probes) run(ctx context.Context, s Snapshot) {
	allow := map[string]bool{}
	for _, peer := range s.Peers {
		allow[peer.IP] = true
	}
	var socket *net.UDPConn
	for ctx.Err() == nil {
		a := netip.AddrPortFrom(netip.MustParseAddr(s.LocalIP), ProbePort)
		c, e := net.ListenUDP("udp4", net.UDPAddrFromAddrPort(a))
		if e == nil {
			socket = c
			break
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Second):
		}
	}
	if socket == nil {
		return
	}
	defer socket.Close()
	go func() {
		<-ctx.Done()
		_ = socket.Close()
	}()
	go func() {
		b := make([]byte, 33)
		for {
			n, addr, e := socket.ReadFromUDPAddrPort(b)
			if e != nil {
				return
			}
			if n == 32 && allow[addr.Addr().String()] {
				_, _ = socket.WriteToUDPAddrPort(b[:n], addr)
			}
		}
	}()
	for ctx.Err() == nil {
		var wg sync.WaitGroup
		for _, peer := range s.Peers {
			wg.Add(1)
			go func(ip string) {
				defer wg.Done()
				rtt, ok := echo(ctx, s.LocalIP, ip)
				p.mu.Lock()
				defer p.mu.Unlock()
				if ok {
					p.rtt[ip] = rtt
				} else {
					delete(p.rtt, ip)
				}
			}(peer.IP)
		}
		wg.Wait()
		select {
		case <-ctx.Done():
			return
		case <-time.After(3 * time.Second):
		}
	}
}
func echo(ctx context.Context, local, remote string) (float64, bool) {
	dialer := net.Dialer{LocalAddr: &net.UDPAddr{IP: net.ParseIP(local)}, Timeout: time.Second}
	c, e := dialer.DialContext(ctx, "udp4", net.JoinHostPort(remote, "39491"))
	if e != nil {
		return 0, false
	}
	defer c.Close()
	stop := context.AfterFunc(ctx, func() { _ = c.Close() })
	defer stop()
	_ = c.SetDeadline(time.Now().Add(time.Second))
	var payload [32]byte
	if _, e = rand.Read(payload[:]); e != nil {
		return 0, false
	}
	start := time.Now()
	if _, e = c.Write(payload[:]); e != nil {
		return 0, false
	}
	var response [33]byte
	n, e := c.Read(response[:])
	if e != nil || n != 32 || !bytes.Equal(payload[:], response[:n]) {
		return 0, false
	}
	return float64(time.Since(start).Microseconds()) / 1000, true
}
