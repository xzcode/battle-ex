//go:build !windows

package serviceipc

import (
	"errors"
	"io"
)

func RunService() error { return errors.New("Windows service mode requires Windows") }
func RunClient(io.Reader, io.Writer) error {
	return errors.New("Windows service client requires Windows")
}
