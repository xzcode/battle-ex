package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"github.com/xzcode/battle-ex/agent/internal/roomnet"
	"io"
	"os"
	"os/signal"
	"strings"
	"syscall"
)

func run() error {
	var o roomnet.Options
	var port uint
	var ifaces string
	flag.StringVar(&o.StateDir, "state-dir", "", "private state directory")
	flag.StringVar(&o.Issuer, "issuer", "", "pinned server Ed25519 public key (hex)")
	flag.StringVar(&o.APICA, "api-ca", "", "optional API CA PEM")
	flag.StringVar(&o.RelayCA, "relay-ca", "", "optional relay CA PEM")
	flag.StringVar(&o.Interface, "interface", "", "virtual interface name")
	flag.UintVar(&port, "port", 51821, "local UDP port")
	flag.StringVar(&ifaces, "ice-interfaces", "", "optional comma-separated ICE interface allowlist")
	flag.Parse()
	if port == 0 || port > 65535 {
		return errors.New("invalid listen port")
	}
	o.Port = uint16(port)
	if ifaces != "" {
		o.ICEInterfaces = strings.Split(ifaces, ",")
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	reader := bufio.NewReaderSize(os.Stdin, 8192)
	line, e := reader.ReadSlice('\n')
	if e != nil {
		return errors.New("credentials must be supplied as one JSON line on stdin")
	}
	var creds roomnet.Credentials
	if e = json.Unmarshal(line, &creds); e != nil {
		return errors.New("invalid credentials")
	}
	// Closing the parent's pipe is the shutdown IPC. No bearer token in argv or environment.
	go func() { _, _ = io.Copy(io.Discard, reader); cancel() }()
	encoder := json.NewEncoder(os.Stdout)
	return roomnet.Run(ctx, creds, o, func(status roomnet.Status) { _ = encoder.Encode(status) })
}
func main() {
	if e := run(); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
}
