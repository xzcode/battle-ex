package roomnet

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestProbeQualityWindow(t *testing.T) {
	var p probes
	if v := p.get("peer"); v.Samples != 0 || v.RTT != nil || v.Loss != nil || v.Jitter != nil {
		t.Fatal(v)
	}
	p.record("peer", 10, true)
	p.record("peer", 14, true)
	p.record("peer", 0, false)
	v := p.get("peer")
	if v.Samples != 3 || v.RTT != nil || v.Jitter == nil || *v.Jitter != 4 || v.Loss == nil || *v.Loss < 33 || *v.Loss > 34 {
		t.Fatal(v)
	}
	for i := 0; i < 20; i++ {
		p.record("peer", 9, true)
	}
	v = p.get("peer")
	if v.Samples != 20 || v.RTT == nil || *v.RTT != 9 || v.Loss == nil || *v.Loss != 0 || v.Jitter == nil || *v.Jitter != 0 {
		t.Fatal(v)
	}
}
func TestRelayHealthProbe(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "HEAD" || r.URL.Path != "/health/v1" || r.Header.Get("Authorization") != "" {
			t.Error("unexpected health request")
		}
		w.Header().Set("X-Battle-Relay", "test")
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	p := relayProbes{http: server.Client()}
	node := RelayCandidate{ID: "test", RelayURL: strings.Replace(server.URL, "https://", "wss://", 1) + "/relay/v1"}
	if v := p.measureNode(context.Background(), node); v < 0 {
		t.Fatal("TLS health probe failed")
	}
	node.ID = "different"
	if v := p.measureNode(context.Background(), node); v != -1 {
		t.Fatal("wrong node accepted")
	}
}
