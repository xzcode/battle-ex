#!/usr/bin/env python3
"""Install and start a built Battle EX desktop on a disposable Windows CI VM."""
import argparse
import ctypes
from ctypes import wintypes
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
if os.name != "nt" or os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_OS") != "Windows":
    raise RuntimeError("This installer test is restricted to an ephemeral Windows runner")
parser = argparse.ArgumentParser()
parser.add_argument("--format", choices=("msi", "nsis"), required=True)
parser.add_argument("--artifacts", type=Path, required=True)
args = parser.parse_args()
artifacts = args.artifacts.resolve(strict=True)
evidence = ROOT / "artifacts" / ("installer-" + args.format)
evidence.mkdir(parents=True, exist_ok=True)

def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def require(condition, message):
    if not condition:
        raise RuntimeError(message)

# Check exactly the uploaded files against the build manifest before running
# any installer. No downloaded scripts or additional network sources.
checksums = {}
for line in (artifacts / "SHA256SUMS").read_text(encoding="ascii").splitlines():
    match = re.fullmatch(r"([0-9a-f]{64})  ([^/\\]+)", line)
    require(match is not None, "Invalid artifact checksum entry")
    expected, name = match.groups()
    require(name not in (".", "..", "SHA256SUMS") and name not in checksums, "Invalid artifact filename")
    path = artifacts / name
    require(path.is_file() and not path.is_symlink() and digest(path) == expected, "Artifact digest mismatch: " + name)
    checksums[name] = expected
require(set(checksums) == {p.name for p in artifacts.iterdir() if p.is_file() and p.name != "SHA256SUMS"},
        "Unlisted or missing artifact")
for name in ("battle-ex.exe", "battle-agent.exe", "windows-network-service.zip"):
    require(name in checksums, "Missing " + name)
with zipfile.ZipFile(artifacts / "windows-network-service.zip") as kit:
    for name in ("battle-agent.exe", "SHA256SUMS", "install-network-service.ps1", "uninstall-network-service.ps1", "README.md"):
        require(name in kit.namelist(), "Incomplete service kit: " + name)
    require(hashlib.sha256(kit.read("battle-agent.exe")).hexdigest() == checksums["battle-agent.exe"],
            "Service kit agent differs from the desktop sidecar")
pattern = "*.msi" if args.format == "msi" else "*-setup.exe"
candidates = sorted(artifacts.glob(pattern))
require(len(candidates) == 1, "Exactly one installer expected")
installer = candidates[0]
require(installer.name in checksums, "Unverified installer")
print("PASS: complete artifact manifest and network-service kit", flush=True)

user32 = ctypes.WinDLL("user32", use_last_error=True)
enum_proc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
user32.EnumWindows.argtypes = (enum_proc, wintypes.LPARAM)
user32.EnumWindows.restype = wintypes.BOOL
user32.GetWindowThreadProcessId.argtypes = (wintypes.HWND, ctypes.POINTER(wintypes.DWORD))
user32.GetWindowThreadProcessId.restype = wintypes.DWORD
user32.IsWindowVisible.argtypes = (wintypes.HWND,)
user32.IsWindowVisible.restype = wintypes.BOOL
user32.GetWindowTextW.argtypes = (wintypes.HWND, wintypes.LPWSTR, ctypes.c_int)
user32.GetWindowTextW.restype = ctypes.c_int
user32.PostMessageW.argtypes = (wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)
user32.PostMessageW.restype = wintypes.BOOL

def main_window(pid):
    windows = []
    @enum_proc
    def visit(hwnd, _):
        owner = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
        if owner.value == pid and user32.IsWindowVisible(hwnd):
            title = ctypes.create_unicode_buffer(512)
            user32.GetWindowTextW(hwnd, title, len(title))
            if title.value == "Battle EX":
                windows.append(hwnd)
        return True
    user32.EnumWindows(visit, 0)
    return windows[0] if windows else None

def run(argv, **kwargs):
    result = subprocess.run(argv, timeout=180, **kwargs)
    require(result.returncode == 0, "Installer command exited with code " + str(result.returncode))

msiexec = Path(os.environ["SystemRoot"]) / "System32/msiexec.exe"
with tempfile.TemporaryDirectory(prefix="battle-desktop-", dir=os.environ["RUNNER_TEMP"]) as temp:
    install = Path(temp) / "installed"
    app = install / "battle-ex.exe"
    sidecar = install / "battle-agent.exe"
    attempted = False
    process = None
    try:
        attempted = True
        if args.format == "msi":
            run([str(msiexec), "/i", str(installer), "/qn", "/norestart",
                 "INSTALLDIR=" + str(install), "/l*v", str(evidence / "install.log")])
        else:
            # NSIS documents /D as the final, unquoted argument. Use a
            # controlled runner path rather than a shell or arbitrary input.
            command = subprocess.list2cmdline([str(installer), "/S"]) + " /D=" + str(install)
            run(command, executable=str(installer))
        require(app.is_file() and sidecar.is_file(), "Installed desktop executable or network sidecar missing")
        require(digest(app) == checksums["battle-ex.exe"], "Installed desktop executable differs from build")
        require(digest(sidecar) == checksums["battle-agent.exe"], "Installed network sidecar differs from build")
        print("PASS: " + args.format.upper() + " installation and both installed executable hashes", flush=True)

        # Start the installed application (not the binary in Cargo target).
        # A visible titled native window proves startup, not game/UI login.
        process = subprocess.Popen([str(app)], cwd=str(install))
        deadline = time.monotonic() + 45
        hwnd = None
        while time.monotonic() < deadline:
            require(process.poll() is None, "Installed desktop exited before its main window")
            hwnd = main_window(process.pid)
            if hwnd:
                break
            time.sleep(.25)
        require(hwnd is not None, "No visible Battle EX main window")
        time.sleep(3)
        require(process.poll() is None and main_window(process.pid), "Desktop failed to remain open")
        print("PASS: installed native Tauri main window starts and remains open", flush=True)
        require(user32.PostMessageW(hwnd, 0x0010, 0, 0), "Could not close the test window")
        require(process.wait(timeout=20) == 0, "Desktop did not exit cleanly")
        print("PASS: desktop exited cleanly", flush=True)
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        if attempted:
            if args.format == "msi":
                # The fresh VM has no previous installation. Remove only the
                # product installed by this exact test MSI.
                if app.exists():
                    run([str(msiexec), "/x", str(installer), "/qn", "/norestart",
                         "/l*v", str(evidence / "uninstall.log")])
            else:
                uninstall = install / "uninstall.exe"
                if uninstall.is_file():
                    run([str(uninstall), "/S"])
                elif app.exists():
                    raise RuntimeError("Installed NSIS uninstaller missing")
        deadline = time.monotonic() + 30
        while (app.exists() or sidecar.exists()) and time.monotonic() < deadline:
            time.sleep(.25)
        require(not app.exists() and not sidecar.exists(), "Uninstall left application executables")
        print("PASS: " + args.format.upper() + " uninstalled application executables", flush=True)

(evidence / "result.txt").write_text(
    "PASS: verified artifacts, installed EXE and sidecar, launched native window, clean exit and uninstall.\n",
    encoding="utf-8",
)
