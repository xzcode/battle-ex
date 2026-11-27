//go:build windows

package roomnet

import (
	"errors"
	"golang.org/x/sys/windows"
	"os"
	"unsafe"
)

func secureNewDirectory(path string) error {
	user, e := windows.GetCurrentProcessToken().GetTokenUser()
	if e != nil {
		return e
	}
	sd, e := windows.SecurityDescriptorFromString("D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;FA;;;" + user.User.Sid.String() + ")")
	if e != nil {
		return e
	}
	acl, _, e := sd.DACL()
	if e != nil {
		return e
	}
	return windows.SetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, acl, nil)
}
func checkPrivate(path string, dir bool) error          { return checkPrivateLimit(path, dir, 4096) }
func CheckPrivateStatePath(path string, dir bool) error { return checkPrivate(path, dir) }
func CheckServiceBinary(path string) error              { return checkPrivateLimit(path, false, 64*1024*1024) }
func checkPrivateLimit(path string, dir bool, limit int64) error {
	st, e := os.Lstat(path)
	if e != nil {
		return e
	}
	if (dir && !st.IsDir()) || (!dir && !st.Mode().IsRegular()) || st.Mode()&os.ModeSymlink != 0 || (!dir && st.Size() > limit) {
		return errors.New("unsafe private state path")
	}
	wide, e := windows.UTF16PtrFromString(path)
	if e != nil {
		return e
	}
	attrs, e := windows.GetFileAttributes(wide)
	if e != nil || attrs&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0 {
		return errors.New("unsafe private reparse point")
	}
	sd, e := windows.GetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.OWNER_SECURITY_INFORMATION|windows.DACL_SECURITY_INFORMATION)
	if e != nil {
		return e
	}
	user, e := windows.GetCurrentProcessToken().GetTokenUser()
	if e != nil {
		return e
	}
	system, e := windows.CreateWellKnownSid(windows.WinLocalSystemSid)
	if e != nil {
		return e
	}
	admin, e := windows.CreateWellKnownSid(windows.WinBuiltinAdministratorsSid)
	if e != nil {
		return e
	}
	trusted := func(s *windows.SID) bool {
		return s != nil && (s.Equals(user.User.Sid) || s.Equals(system) || s.Equals(admin))
	}
	owner, _, e := sd.Owner()
	if e != nil || !trusted(owner) {
		return errors.New("private state owner mismatch")
	}
	acl, _, e := sd.DACL()
	if e != nil || acl == nil {
		return errors.New("private state ACL required")
	}
	type header struct {
		Revision, Reserved     byte
		Size, Count, Reserved2 uint16
	}
	type aceHeader struct {
		Type, Flags byte
		Size        uint16
	}
	count := (*header)(unsafe.Pointer(acl)).Count
	if count == 0 {
		return errors.New("empty private state ACL")
	}
	getAce := windows.NewLazySystemDLL("advapi32.dll").NewProc("GetAce")
	for i := uint16(0); i < count; i++ {
		var ptr unsafe.Pointer
		r, _, e := getAce.Call(uintptr(unsafe.Pointer(acl)), uintptr(i), uintptr(unsafe.Pointer(&ptr)))
		if r == 0 || ptr == nil {
			return e
		}
		h := (*aceHeader)(ptr)
		if h.Type == 1 {
			continue
		}
		if h.Type != 0 || h.Size < 16 || !trusted((*windows.SID)(unsafe.Add(ptr, 8))) {
			return errors.New("private state grants access to an untrusted principal")
		}
	}
	return nil
}
func lockState(path string) (func(), error) {
	f, e := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0600)
	if e != nil {
		return nil, e
	}
	if e = checkPrivate(path, false); e != nil {
		f.Close()
		return nil, e
	}
	var lock windows.Overlapped
	if e = windows.LockFileEx(windows.Handle(f.Fd()), windows.LOCKFILE_EXCLUSIVE_LOCK|windows.LOCKFILE_FAIL_IMMEDIATELY, 0, 1, 0, &lock); e != nil {
		f.Close()
		return nil, errors.New("network agent state already in use")
	}
	return func() { _ = windows.UnlockFileEx(windows.Handle(f.Fd()), 0, 1, 0, &lock); _ = f.Close() }, nil
}
