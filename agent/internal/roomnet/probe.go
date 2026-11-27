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

type probeSample struct {
	rtt float64
	ok  bool
}
type quality struct {
	RTT, Jitter, Loss *float64
	Samples           int
}
type probes struct {
	mu      sync.Mutex
	history map[string][]probeSample
}

// Rolling measured UDP echo quality, not estimates of game-frame loss.
func (p *probes) record(ip string, rtt float64, ok bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.history == nil {
		p.history = make(map[string][]probeSample)
	}
	samples := append(p.history[ip], probeSample{rtt, ok})
	if len(samples) > 20 {
		samples = append([]probeSample(nil), samples[len(samples)-20:]...)
	}
	p.history[ip] = samples
}
func (p *probes) get(ip string) quality {
	p.mu.Lock()
	defer p.mu.Unlock()
	samples := p.history[ip]
	result := quality{Samples: len(samples)}
	if len(samples) == 0 {
		return result
	}
	last := samples[len(samples)-1]
	if last.ok {
		rtt := last.rtt
		result.RTT = &rtt
	}
	failures, pairs := 0, 0
	var sum float64
	for i, s := range samples {
		if !s.ok {
			failures++
			continue
		}
		if i > 0 && samples[i-1].ok {
			delta := s.rtt - samples[i-1].rtt
			if delta < 0 {
				delta = -delta
			}
			sum += delta
			pairs++
		}
	}
	loss := float64(failures) * 100 / float64(len(samples))
	result.Loss = &loss
	if pairs > 0 {
		jitter := sum / float64(pairs)
		result.Jitter = &jitter
	}
	return result
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
				p.record(ip, rtt, ok)
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
