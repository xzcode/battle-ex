package main

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/xzcode/battle-ex/agent/internal/roomnet"
	"github.com/xzcode/network-core-ex/pkg/relay"
	"github.com/xzcode/network-core-ex/pkg/stun"
)

func keygen(args []string) error {
	f := flag.NewFlagSet("keygen", flag.ContinueOnError)
	path := f.String("out", "", "new private issuer file")
	if e := f.Parse(args); e != nil {
		return e
	}
	if *path == "" {
		return errors.New("-out required")
	}
	pub, key, e := ed25519.GenerateKey(rand.Reader)
	if e != nil {
		return e
	}
	out, e := os.OpenFile(*path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if e != nil {
		return e
	}
	defer out.Close()
	if _, e = fmt.Fprintf(out, "%x\n", key); e != nil {
		return e
	}
	fmt.Printf("%x\n", pub)
	return nil
}
func run() error {
	if len(os.Args) > 1 && os.Args[1] == "keygen" {
		return keygen(os.Args[2:])
	}
	listen := flag.String("listen", "127.0.0.1:8443", "TLS listener")
	node := flag.String("node", "battle-relay", "relay node ID")
	issuer := flag.String("issuer", "", "issuer public key hex")
	secretFile := flag.String("secret-file", "", "private relay service credential file")
	origin := flag.String("authority", "", "platform origin")
	ca := flag.String("authority-ca", "", "optional authority CA")
	cert := flag.String("cert", "", "TLS cert")
	key := flag.String("key", "", "TLS key")
	stunListen := flag.String("stun-listen", "", "optional STUN UDP4 address")
	register := flag.Bool("register", false, "register and heartbeat to configured relay directory")
	drainFile := flag.String("drain-file", "", "existing file requests maintenance drain")
	flag.Parse()
	pub, e := hex.DecodeString(*issuer)
	if e != nil || len(pub) != 32 {
		return errors.New("issuer public key required")
	}
	secret, e := os.ReadFile(*secretFile)
	if e != nil {
		return e
	}
	shared := strings.TrimSpace(string(secret))
	if decoded, e := hex.DecodeString(shared); e != nil || len(decoded) != 32 {
		return errors.New("invalid relay credential")
	}
	authority, e := roomnet.Origin(*origin)
	if e != nil {
		return e
	}
	hc, e := roomnet.HTTPClient(*ca, 1500*time.Millisecond)
	if e != nil {
		return e
	}
	instance := ""
	if *register {
		instance, e = newInstanceID()
		if e != nil {
			return e
		}
	}
	authorize := func(lease relay.Lease) bool {
		data, e := json.Marshal(lease)
		if e != nil {
			return false
		}
		req, e := http.NewRequest(http.MethodPost, authority+"/api/v1/network/relay/authorize", bytes.NewReader(data))
		if e != nil {
			return false
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Relay-Secret", shared)
		if instance != "" {
			req.Header.Set("X-Relay-Instance", instance)
		}
		res, e := hc.Do(req)
		if e != nil {
			return false
		}
		defer res.Body.Close()
		return res.StatusCode == 204
	}
	rs, ss := relay.NewServer(*node, ed25519.PublicKey(pub)), relay.NewSignalServer(*node, ed25519.PublicKey(pub))
	rs.Authorize = authorize
	ss.Authorize = authorize
	mux := http.NewServeMux()
	mux.Handle("/relay/v1", rs)
	mux.Handle("/signal/v1", ss)
	mux.HandleFunc("/health/v1", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodHead {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		if drainRequested(*drainFile) {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("X-Battle-Relay", *node)
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusNoContent)
	})
	pair, e := tls.LoadX509KeyPair(*cert, *key)
	if e != nil {
		return e
	}
	l, e := net.Listen("tcp", *listen)
	if e != nil {
		return e
	}
	defer l.Close()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	srv := &http.Server{Handler: mux, ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 30 * time.Second, TLSConfig: &tls.Config{MinVersion: tls.VersionTLS12, Certificates: []tls.Certificate{pair}}}
	errs := make(chan error, 2)
	if *stunListen != "" {
		addr, e := net.ResolveUDPAddr("udp4", *stunListen)
		if e != nil {
			return e
		}
		c, e := net.ListenUDP("udp4", addr)
		if e != nil {
			return e
		}
		defer c.Close()
		go func() { errs <- stun.Serve(ctx, c) }()
	}
	go func() { errs <- srv.ServeTLS(l, "", "") }()
	if *register {
		go func() {
			if e := runHeartbeat(ctx, hc, authority, shared, *node, *drainFile, instance, rs); e != nil {
				errs <- e
			}
		}()
	}
	select {
	case <-ctx.Done():
		shutdown, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		return srv.Shutdown(shutdown)
	case e := <-errs:
		if errors.Is(e, http.ErrServerClosed) || ctx.Err() != nil {
			return nil
		}
		return e
	}
}
func main() {
	if e := run(); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
}
