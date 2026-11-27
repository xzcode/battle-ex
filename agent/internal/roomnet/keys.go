package roomnet

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/crypto/curve25519"
)

func StateDir(dir string) error {
	if dir == "" || !filepath.IsAbs(dir) {
		return errors.New("absolute private state directory required")
	}
	_, e := os.Lstat(dir)
	if os.IsNotExist(e) {
		if e = os.MkdirAll(filepath.Dir(dir), 0700); e != nil {
			return e
		}
		if e = os.Mkdir(dir, 0700); e != nil {
			return e
		}
		if e = secureNewDirectory(dir); e != nil {
			return e
		}
	} else if e != nil {
		return e
	}
	return checkPrivate(dir, true)
}
func readOrCreate(path string, size int, generate func() ([]byte, error)) ([]byte, error) {
	if _, e := os.Lstat(path); os.IsNotExist(e) {
		raw, e := generate()
		if e != nil {
			return nil, e
		}
		f, e := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
		if e != nil {
			return nil, e
		}
		_, write := f.WriteString(hex.EncodeToString(raw) + "\n")
		closeErr := f.Close()
		if write != nil {
			return nil, write
		}
		if closeErr != nil {
			return nil, closeErr
		}
	} else if e != nil {
		return nil, e
	}
	if e := checkPrivate(path, false); e != nil {
		return nil, e
	}
	raw, e := os.ReadFile(path)
	if e != nil {
		return nil, e
	}
	b, e := hex.DecodeString(strings.TrimSpace(string(raw)))
	if e != nil || len(b) != size {
		return nil, errors.New("invalid stored private key")
	}
	return b, nil
}
func LoadDevice(dir string) (Device, error) {
	identity, e := readOrCreate(filepath.Join(dir, "identity.key"), 64, func() ([]byte, error) { _, key, e := ed25519.GenerateKey(rand.Reader); return key, e })
	if e != nil {
		return Device{}, e
	}
	if !bytes.Equal(ed25519.NewKeyFromSeed(identity[:32]), identity) {
		return Device{}, errors.New("invalid Ed25519 private key")
	}
	wg, e := readOrCreate(filepath.Join(dir, "wireguard.key"), 32, func() ([]byte, error) {
		b := make([]byte, 32)
		_, e := rand.Read(b)
		b[0] &= 248
		b[31] &= 127
		b[31] |= 64
		return b, e
	})
	if e != nil {
		return Device{}, e
	}
	pub, e := curve25519.X25519(wg, curve25519.Basepoint)
	if e != nil {
		return Device{}, e
	}
	return Device{ed25519.PrivateKey(identity), wg, hex.EncodeToString(pub)}, nil
}
