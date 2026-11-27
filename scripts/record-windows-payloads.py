#!/usr/bin/env python3
"""Record the exact per-format executable payloads of this build's installers."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

if os.name != "nt" or os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_OS") != "Windows":
    raise RuntimeError("Windows runner required")
root = Path(sys.argv[1]).resolve(strict=True)
msiexec = Path(os.environ["SystemRoot"]) / "System32/msiexec.exe"
sevenzip = shutil.which("7z.exe") or str(Path(os.environ["ProgramFiles"]) / "7-Zip/7z.exe")

def single(paths):
    paths = list(paths)
    if len(paths) != 1:
        raise RuntimeError("Expected exactly one installer or payload: " + str(paths))
    return paths[0]

def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def run(argv):
    subprocess.run([str(arg) for arg in argv], check=True, timeout=180)

manifest = {}
with tempfile.TemporaryDirectory(prefix="bex-bundle-payload-", dir=os.environ["RUNNER_TEMP"]) as work:
    temp = Path(work)
    for format, pattern in (("msi", "*.msi"), ("nsis", "*-setup.exe")):
        installer = single(root.glob(pattern))
        extract = temp / format
        extract.mkdir()
        if format == "msi":
            run([msiexec, "/a", installer, "/qn", "/norestart", "TARGETDIR=" + str(extract)])
        else:
            run([sevenzip, "x", "-y", "-o" + str(extract), installer])
        app = single(p for p in extract.rglob("*") if p.is_file() and p.name.lower() == "battle-ex.exe")
        agent = single(p for p in extract.rglob("*") if p.is_file() and p.name.lower() == "battle-agent.exe")
        if sha(agent) != sha(root / "battle-agent.exe"):
            raise RuntimeError("Installer sidecar differs from the native build")
        with app.open("rb") as stream:
            if stream.read(2) != b"MZ":
                raise RuntimeError("Invalid executable payload")
        manifest[format] = {"installer": installer.name, "installerSha256": sha(installer),
                            "desktopSha256": sha(app), "agentSha256": sha(agent)}
        print("Recorded verified", format, "payload", manifest[format]["desktopSha256"], flush=True)
(root / "installer-payloads.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="ascii")
