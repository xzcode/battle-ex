//go:build !windows

package roomnet

import (
	"errors"
	"golang.org/x/sys/unix"
	"os"
	"syscall"
)

func secureNewDirectory(path string) error { return os.Chmod(path, 0700) }
func checkPrivate(path string, dir bool) error {
	st, e := os.Lstat(path)
	if e != nil {
		return e
	}
	if (dir && !st.IsDir()) || (!dir && !st.Mode().IsRegular()) || st.Mode().Perm()&0077 != 0 || (!dir && st.Size() > 4096) {
		return errors.New("unsafe private state file permissions or type")
	}
	if info, ok := st.Sys().(*syscall.Stat_t); !ok || int(info.Uid) != os.Geteuid() {
		return errors.New("private state owner mismatch")
	}
	return nil
}
func lockState(path string) (func(), error) {
	f, e := os.OpenFile(path, os.O_CREATE|os.O_RDWR|syscall.O_NOFOLLOW, 0600)
	if e != nil {
		return nil, e
	}
	if e = checkPrivate(path, false); e != nil {
		f.Close()
		return nil, e
	}
	if e = unix.Flock(int(f.Fd()), unix.LOCK_EX|unix.LOCK_NB); e != nil {
		f.Close()
		return nil, errors.New("network agent state already in use")
	}
	return func() { _ = unix.Flock(int(f.Fd()), unix.LOCK_UN); _ = f.Close() }, nil
}
