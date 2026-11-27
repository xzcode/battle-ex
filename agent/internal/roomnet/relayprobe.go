package roomnet

import (
	"context"
	"net/http"
	"net/url"
	"sync"
	"time"
)

// Candidate metadata originates only from a verified, signed room snapshot.
type RelayCandidate struct {
	ID       string `json:"id"`
	Region   string `json:"region"`
	RelayURL string `json:"relayURL"`
}

type relayProbes struct {
	mu         sync.Mutex
	candidates []RelayCandidate
	samples    map[string]int
	sampledAt  time.Time
	http       *http.Client
}

func (p *relayProbes) update(nodes []RelayCandidate) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.candidates = append([]RelayCandidate(nil), nodes...)
	if len(nodes) == 0 {
		p.samples = nil
		p.sampledAt = time.Time{}
	}
}
func (p *relayProbes) measurements() map[string]int {
	p.mu.Lock()
	defer p.mu.Unlock()
	if time.Since(p.sampledAt) > 12*time.Second {
		return nil
	}
	result := make(map[string]int, len(p.samples))
	for k, v := range p.samples {
		result[k] = v
	}
	return result
}
func (p *relayProbes) run(ctx context.Context) {
	defer p.http.CloseIdleConnections()
	for {
		p.measure(ctx)
		select {
		case <-ctx.Done():
			return
		case <-time.After(4 * time.Second):
		}
	}
}
func (p *relayProbes) measure(parent context.Context) {
	p.mu.Lock()
	nodes := append([]RelayCandidate(nil), p.candidates...)
	p.mu.Unlock()
	if len(nodes) == 0 {
		return
	}
	ctx, cancel := context.WithTimeout(parent, 3*time.Second)
	defer cancel()
	result := make(map[string]int)
	var mu sync.Mutex
	jobs := make(chan RelayCandidate)
	var wg sync.WaitGroup
	// Bounded concurrent probes, independent of the snapshot renewal loop.
	for i := 0; i < min(8, len(nodes)); i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for n := range jobs {
				value := p.measureNode(ctx, n)
				if ctx.Err() != nil {
					return
				}
				mu.Lock()
				result[n.ID] = value
				mu.Unlock()
			}
		}()
	}
send:
	for _, n := range nodes {
		select {
		case jobs <- n:
		case <-ctx.Done():
			break send
		}
	}
	close(jobs)
	wg.Wait()
	if parent.Err() != nil {
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	p.samples = result
	p.sampledAt = time.Now()
}
func (p *relayProbes) measureNode(parent context.Context, n RelayCandidate) int {
	u, e := url.Parse(n.RelayURL)
	if e != nil {
		return -1
	}
	u.Scheme = "https"
	u.Path = "/health/v1"
	u.RawPath = ""
	ctx, cancel := context.WithTimeout(parent, 1500*time.Millisecond)
	defer cancel()
	req, e := http.NewRequestWithContext(ctx, http.MethodHead, u.String(), nil)
	if e != nil {
		return -1
	}
	start := time.Now()
	// Never forward the login token or relay-service secret to a probe endpoint.
	response, e := p.http.Do(req)
	if e != nil {
		return -1
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusNoContent || response.Header.Get("X-Battle-Relay") != n.ID {
		return -1
	}
	return int(time.Since(start).Milliseconds())
}
