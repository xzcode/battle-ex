package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"time"

	"github.com/xzcode/network-core-ex/pkg/protocol"
	"github.com/xzcode/network-core-ex/pkg/relay"
)

func drainRequested(file string) bool {
	if file == "" {
		return false
	}
	_, e := os.Lstat(file)
	// On inaccessible control file, fail safe rather than advertise readiness.
	return e == nil || !errors.Is(e, os.ErrNotExist)
}
func newInstanceID() (string, error) {
	var instance [16]byte
	if _, e := rand.Read(instance[:]); e != nil {
		return "", e
	}
	return hex.EncodeToString(instance[:]), nil
}
func runHeartbeat(ctx context.Context, client *http.Client, origin, secret, node, drainFile, id string, rs *relay.Server) error {
	send := func() {
		body, e := json.Marshal(struct {
			Node     string             `json:"node"`
			Instance string             `json:"instance"`
			Draining bool               `json:"draining"`
			Stats    protocol.NodeStats `json:"stats"`
		}{node, id, drainRequested(drainFile), rs.Stats()})
		if e != nil {
			return
		}
		req, e := http.NewRequestWithContext(ctx, http.MethodPost, origin+"/api/v1/network/relay/heartbeat", bytes.NewReader(body))
		if e != nil {
			return
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Relay-Secret", secret)
		response, e := client.Do(req)
		if e != nil {
			return
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusOK {
			return
		}
		var limits struct {
			MaxConnections int   `json:"maxConnections"`
			BytesPerSecond int64 `json:"bytesPerSecond"`
		}
		if json.NewDecoder(io.LimitReader(response.Body, 4096)).Decode(&limits) == nil {
			rs.SetLimits(protocol.NodeLimits{MaxConnections: limits.MaxConnections, BytesPerSecond: limits.BytesPerSecond})
		}
	}
	send()
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			send()
		}
	}
}
