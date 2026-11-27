package roomnet

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"time"

	"github.com/xzcode/network-core-ex/pkg/engine"
)

type Options struct {
	StateDir, Issuer, APICA, RelayCA, Interface string
	Port                                        uint16
	ICEInterfaces                               []string
}
type PeerStatus struct {
	UserID         string   `json:"userId"`
	IP             string   `json:"ip"`
	Mode           string   `json:"mode"`
	HandshakeUnix  string   `json:"handshakeUnix"`
	RxBytes        string   `json:"rxBytes"`
	TxBytes        string   `json:"txBytes"`
	RTTMillis      *float64 `json:"rttMillis"`
	RelayConnected bool     `json:"relayConnected"`
	JitterMillis   *float64 `json:"jitterMillis"`
	ProbeLossPct   *float64 `json:"probeLossPct"`
	ProbeSamples   int      `json:"probeSamples"`
}
type CandidateStatus struct {
	ID          string `json:"id"`
	Region      string `json:"region"`
	ProbeMillis *int   `json:"probeMillis"`
}
type Status struct {
	State           string            `json:"state"`
	RoomID          string            `json:"roomId,omitempty"`
	NetworkID       string            `json:"networkId,omitempty"`
	Revision        int64             `json:"revision,omitempty"`
	IP              string            `json:"ip,omitempty"`
	RelayNode       string            `json:"relayNode,omitempty"`
	RelayRegion     string            `json:"relayRegion,omitempty"`
	ExpiresAt       int64             `json:"expiresAt,omitempty"`
	Peers           []PeerStatus      `json:"peers"`
	RelayCandidates []CandidateStatus `json:"relayCandidates,omitempty"`
	Error           string            `json:"error,omitempty"`
}
type active struct {
	s      Snapshot
	dir    string
	cancel context.CancelFunc
	done   chan error
	probes *probes
}
type pollResult struct {
	s *Snapshot
	e error
}

func Run(ctx context.Context, creds Credentials, o Options, report func(Status)) error {
	if e := StateDir(o.StateDir); e != nil {
		return e
	}
	unlock, e := lockState(filepath.Join(o.StateDir, "agent.lock"))
	if e != nil {
		return e
	}
	defer unlock()
	device, e := LoadDevice(o.StateDir)
	if e != nil {
		return e
	}
	c, e := NewClient(creds, o.Issuer, o.APICA, device)
	if e != nil {
		return e
	}
	report(Status{State: "enrolling"})
	if e = c.Enroll(ctx); e != nil {
		return e
	}
	defer c.Disconnect()
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	relayHTTP, e := HTTPClient(o.RelayCA, 2*time.Second)
	if e != nil {
		return e
	}
	c.relayProbes = &relayProbes{http: relayHTTP}
	go c.relayProbes.run(ctx)
	results := make(chan pollResult, 1)
	go func() {
		for ctx.Err() == nil {
			s, e := c.Snapshot(ctx)
			select {
			case results <- pollResult{s, e}:
			case <-ctx.Done():
				return
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(2 * time.Second):
			}
		}
	}()
	var a *active
	var last *Snapshot
	var transient string
	stop := func() {
		if a == nil {
			return
		}
		a.cancel()
		<-a.done
		_ = os.RemoveAll(a.dir)
		a = nil
	}
	defer stop()
	tick := time.NewTicker(200 * time.Millisecond)
	defer tick.Stop()
	var lastStatus time.Time
	report(Status{State: "waiting"})
	for {
		var done <-chan error
		if a != nil {
			done = a.done
		}
		select {
		case <-ctx.Done():
			return nil
		case e := <-done:
			a.cancel()
			_ = os.RemoveAll(a.dir)
			a = nil
			if e != nil {
				transient = e.Error()
			} else {
				transient = "network engine stopped"
			}
			report(Status{State: "error", Error: transient})
		case result := <-results:
			if result.e != nil {
				if errors.Is(result.e, ErrUnauthorized) || errors.Is(result.e, ErrInvalidSnapshot) {
					stop()
					report(Status{State: "revoked", Error: result.e.Error()})
					return result.e
				}
				transient = result.e.Error()
				if a == nil {
					report(Status{State: "retrying", Error: transient})
				}
				continue
			}
			s := result.s
			transient = ""
			if s == nil {
				stop()
				last = nil
				report(Status{State: "waiting"})
				continue
			}
			if s.ValidUntil <= time.Now().Unix() {
				continue
			}
			if last != nil && last.NetworkID == s.NetworkID {
				if s.Revision < last.Revision || (s.Revision == last.Revision && !sameTopology(*s, *last)) {
					stop()
					return ErrInvalidSnapshot
				}
			}
			last = s
			if a != nil && sameTopology(a.s, *s) {
				a.s = *s
				continue
			}
			stop()
			if ctx.Err() != nil {
				return nil
			}
			if s.ValidUntil <= time.Now().Unix() {
				continue
			}
			a, e = start(ctx, *s, o)
			if e != nil {
				report(Status{State: "error", Error: e.Error()})
				a = nil
				continue
			}
			report(status(a, "connecting", "", c.relayProbes.measurements()))
		case <-tick.C:
			if a != nil && time.Now().Unix() >= a.s.ValidUntil {
				stop()
				report(Status{State: "expired", Error: "room authorization expired"})
				continue
			}
			if a != nil && time.Since(lastStatus) >= time.Second {
				lastStatus = time.Now()
				report(status(a, "connecting", transient, c.relayProbes.measurements()))
			}
		}
	}
}
func sameTopology(a, b Snapshot) bool {
	return a.RoomID == b.RoomID && a.NetworkID == b.NetworkID && a.Revision == b.Revision && a.LocalIP == b.LocalIP && a.IdentityKey == b.IdentityKey && a.WireguardKey == b.WireguardKey && a.RelayNode == b.RelayNode && a.RelayURL == b.RelayURL && a.StunURL == b.StunURL && a.ICE == b.ICE && reflect.DeepEqual(a.Peers, b.Peers)
}
func start(ctx context.Context, s Snapshot, o Options) (*active, error) {
	dir, e := os.MkdirTemp(o.StateDir, "run-")
	if e != nil {
		return nil, e
	}
	cleanup := true
	defer func() {
		if cleanup {
			_ = os.RemoveAll(dir)
		}
	}()
	if e = checkPrivate(dir, true); e != nil {
		return nil, e
	}
	iface := o.Interface
	if iface == "" {
		iface = "battle0"
		if runtime.GOOS == "darwin" {
			iface = "utun"
		}
	}
	port := o.Port
	if port == 0 {
		port = 51821
	}
	cfg := engine.Config{Interface: iface, IP: s.LocalIP, ListenPort: port, WGKeyFile: filepath.Join(o.StateDir, "wireguard.key"), IdentityKeyFile: filepath.Join(o.StateDir, "identity.key"), StatusFile: filepath.Join(dir, "status.json")}
	for i, p := range s.Peers {
		f := filepath.Join(dir, fmt.Sprintf("lease-%d", i))
		if e = os.WriteFile(f, []byte(p.Lease+"\n"), 0600); e != nil {
			return nil, e
		}
		cfg.Peers = append(cfg.Peers, engine.Peer{IP: p.IP, PublicKey: p.WireguardKey, RelayURL: s.RelayURL, RelayTokenFile: f, RelayCA: o.RelayCA, ICE: s.ICE, STUNURL: s.StunURL, ICEInterfaces: o.ICEInterfaces})
	}
	if e = engine.Check(cfg); e != nil {
		return nil, e
	}
	runCtx, cancel := context.WithCancel(ctx)
	a := &active{s: s, dir: dir, cancel: cancel, done: make(chan error, 1), probes: &probes{}}
	go func() { a.done <- engine.Run(runCtx, cfg) }()
	go a.probes.run(runCtx, s)
	cleanup = false
	return a, nil
}
func status(a *active, fallback, errText string, measurements map[string]int) Status {
	result := Status{State: fallback, RoomID: a.s.RoomID, NetworkID: a.s.NetworkID, Revision: a.s.Revision, IP: a.s.LocalIP, RelayNode: a.s.RelayNode, RelayRegion: a.s.RelayRegion, ExpiresAt: a.s.ValidUntil, Peers: []PeerStatus{}, Error: errText}
	for _, candidate := range a.s.RelayCandidates {
		var probe *int
		if v, ok := measurements[candidate.ID]; ok {
			probe = &v
		}
		result.RelayCandidates = append(result.RelayCandidates, CandidateStatus{ID: candidate.ID, Region: candidate.Region, ProbeMillis: probe})
	}
	b, e := os.ReadFile(filepath.Join(a.dir, "status.json"))
	if e != nil {
		return result
	}
	var s engine.Status
	if json.Unmarshal(b, &s) != nil || s.State != "running" {
		return result
	}
	result.State = "running"
	for _, p := range s.Peers {
		user := ""
		for _, peer := range a.s.Peers {
			if peer.IP == p.IP {
				user = peer.UserID
				break
			}
		}
		q := a.probes.get(p.IP)
		result.Peers = append(result.Peers, PeerStatus{UserID: user, IP: p.IP, Mode: p.Mode, HandshakeUnix: p.HandshakeUnix, RxBytes: p.RxBytes, TxBytes: p.TxBytes, RTTMillis: q.RTT, RelayConnected: p.RelayConnected, JitterMillis: q.Jitter, ProbeLossPct: q.Loss, ProbeSamples: q.Samples})
	}
	return result
}
