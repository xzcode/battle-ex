#!/usr/bin/env python3
"""Native Windows integration. Only run on a disposable GitHub-hosted VM."""
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
assert os.name == "nt" and os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("RUNNER_OS") == "Windows", "disposable Windows runner required"
EVIDENCE = ROOT / "artifacts/windows-network"
EVIDENCE.mkdir(parents=True, exist_ok=True)
AGENT = ROOT / "agent/bin/battle-agent.exe"
RELAY = ROOT / "agent/bin/battle-relay.exe"
JAR = ROOT / "server/target/battle-ex-server-0.1.0-SNAPSHOT.jar"
WINTUN_URL = "https://www.wintun.net/builds/wintun-0.14.1.zip"
WINTUN_HASH = "07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51"
PS = Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"
OPENSSL = shutil.which("openssl") or str(Path(os.environ["ProgramFiles"]) / "Git/usr/bin/openssl.exe")
JAVA = Path(os.environ["JAVA_HOME"]) / "bin/java.exe"

def run(*args, **kw):
    return subprocess.run([str(x) for x in args], check=True, **kw)

def ps(script, env=None, capture=False):
    # Avoid Windows PowerShell's native -Command quoting/multiline parsing.
    # Script bodies contain environment references, not credential values.
    source = "$ErrorActionPreference='Stop';\ntry {\n" + script + "\nexit 0\n} catch {\n[Console]::Error.WriteLine($_.Exception.Message); exit 1\n}"
    encoded = base64.b64encode(source.encode("utf-16-le")).decode("ascii")
    result = subprocess.run([str(PS), "-NoLogo", "-NoProfile", "-NonInteractive",
                             "-EncodedCommand", encoded], env=env, capture_output=True)
    stdout = result.stdout.decode("utf-8", errors="replace")
    stderr = result.stderr.decode("utf-8", errors="replace")
    # Never emit ephemeral fixture secrets even on errors.
    effective = env if env is not None else os.environ
    for name in ("BATTLE_TEST_PASSWORD", "BATTLE_TEST_P12_PASSWORD", "SERVER_SSL_KEY_STORE_PASSWORD"):
        value = effective.get(name)
        if value:
            stdout = stdout.replace(value, "[redacted]")
            stderr = stderr.replace(value, "[redacted]")
    if result.returncode:
        raise RuntimeError("Windows PowerShell failed ({}): {}".format(result.returncode, stderr.strip() or stdout.strip()))
    if not capture and stdout.strip():
        print(stdout.strip(), flush=True)
    return subprocess.CompletedProcess(["powershell", "-EncodedCommand", "<fixture>"], result.returncode, stdout, stderr)

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]

def stop(p):
    if p and p.poll() is None:
        p.terminate()
        try:
            p.wait(10)
        except subprocess.TimeoutExpired:
            p.kill()
            p.wait()

# Never overwrite a previous service or state directory, even in CI.
ps("if ((Get-Service BattleEXNetwork -ErrorAction SilentlyContinue) -or (Test-Path (Join-Path $env:ProgramFiles 'Battle EX Network Service'))) { throw 'Existing service/state; refusing test' }")
with tempfile.TemporaryDirectory(prefix="bex-windows-ci-") as work:
    d = Path(work)
    archive = d / "wintun.zip"
    with urllib.request.urlopen(WINTUN_URL, timeout=45) as response, archive.open("wb") as output:
        shutil.copyfileobj(response, output)
    assert sha(archive) == WINTUN_HASH, "official Wintun ZIP SHA256 mismatch"
    dll = d / "wintun.dll"
    with zipfile.ZipFile(archive) as z:
        dll.write_bytes(z.read("wintun/bin/amd64/wintun.dll"))
    print("PASS: pinned official Wintun archive hash verified", flush=True)
    cert, key, p12 = d/"tls.crt", d/"tls.key", d/"tls.p12"
    p12pass = secrets.token_hex(24)
    env = {**os.environ, "BATTLE_TEST_P12_PASSWORD": p12pass}
    run(OPENSSL, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
        "-subj", "/CN=Battle EX CI "+secrets.token_hex(8),
        "-addext", "subjectAltName=IP:127.0.0.1", "-addext", "extendedKeyUsage=serverAuth",
        "-keyout", key, "-out", cert, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    run(OPENSSL, "pkcs12", "-export", "-in", cert, "-inkey", key, "-out", p12,
        "-passout", "env:BATTLE_TEST_P12_PASSWORD", env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    cert_hash = hashlib.sha1(ssl.PEM_cert_to_DER_cert(cert.read_text())).hexdigest()
    issuer_path = d/"issuer.key"
    issuer = run(RELAY, "keygen", "-out", issuer_path, capture_output=True, text=True).stdout.strip()
    secret = d/"relay.secret"
    secret.write_text(secrets.token_hex(32)+"\n", encoding="ascii")
    api_port, relay_port = port(), port()
    while relay_port == api_port:
        relay_port = port()
    origin = f"https://127.0.0.1:{api_port}"
    relay_url = f"wss://127.0.0.1:{relay_port}/relay/v1"
    suffix = secrets.token_hex(4)
    allowed, denied = "bexallow"+suffix, "bexdeny"+suffix
    password = "Ci!Aa1-"+secrets.token_hex(24)
    env.update(BATTLE_TEST_ORIGIN=origin, BATTLE_TEST_ISSUER=issuer,
               BATTLE_TEST_ALLOWED=allowed, BATTLE_TEST_DENIED=denied,
               BATTLE_TEST_PASSWORD=password, BATTLE_WINDOWS_NATIVE_TEST="true")
    java, relay = None, None
    root_added = False
    installed = False
    try:
        run("certutil", "-addstore", "Root", cert, stdout=subprocess.DEVNULL)
        root_added = True
        # Real non-administrator local accounts. Password is ephemeral and read
        # from environment rather than printed or placed in a command line.
        sid = ps("""
            Import-Module Microsoft.PowerShell.LocalAccounts
            $password=ConvertTo-SecureString -String $env:BATTLE_TEST_PASSWORD -AsPlainText -Force
            $users=Get-LocalGroup -SID ([System.Security.Principal.SecurityIdentifier]::new('S-1-5-32-545'))
            $admins=Get-LocalGroup -SID ([System.Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))
            foreach($name in @($env:BATTLE_TEST_ALLOWED,$env:BATTLE_TEST_DENIED)){
                $u=New-LocalUser -Name $name -Password $password -Description 'Disposable Battle EX CI'
                $memberId=$u.SID.Value
                if(-not @(Get-LocalGroupMember -Group $users.Name | Where-Object {$_.SID.Value -eq $memberId}).Count){
                    Add-LocalGroupMember -Group $users.Name -Member $u
                }
                if(@(Get-LocalGroupMember -Group $admins.Name | Where-Object {$_.SID.Value -eq $memberId}).Count){
                    throw 'Fixture user must not be an administrator'
                }
            }
            (Get-LocalUser -Name $env:BATTLE_TEST_ALLOWED).SID.Value
        """, env=env, capture=True).stdout.strip()
        assert sid.startswith("S-1-5-21-")
        server_env = {**env, "BATTLE_BIND":"127.0.0.1","BATTLE_PORT":str(api_port),
            "BATTLE_MONGO_URI":"","BATTLE_STATE_MODE":"memory",
            "SERVER_SSL_ENABLED":"true","SERVER_SSL_KEY_STORE":str(p12),"SERVER_SSL_KEY_STORE_TYPE":"PKCS12",
            "SERVER_SSL_KEY_STORE_PASSWORD":p12pass,
            "BATTLE_NETWORK_ISSUER_KEY_FILE":str(issuer_path),"BATTLE_NETWORK_RELAY_SECRET_FILE":str(secret),
            "BATTLE_NETWORK_RELAY_NODE":"battle-relay","BATTLE_NETWORK_RELAY_URL":relay_url,
            "BATTLE_NETWORK_ICE":"false","BATTLE_NETWORK_GAME_DISCOVERY":"true",
            "BATTLE_NETWORK_RELAY_DIRECTORY_FILE":""}
        with (EVIDENCE/"webflux.log").open("wb") as log:
            java=subprocess.Popen([str(JAVA),"-jar",str(JAR)],env=server_env,stdout=log,stderr=subprocess.STDOUT)
        tls=ssl.create_default_context(cafile=str(cert))
        for attempt in range(80):
            if java.poll() is not None:
                raise RuntimeError("WebFlux process exited")
            try:
                urllib.request.urlopen(origin+"/api/v1/lobby",context=tls,timeout=2)
            except urllib.error.HTTPError as e:
                if e.code==401:
                    break
            except (OSError,urllib.error.URLError):
                pass
            time.sleep(.5)
        else:
            raise RuntimeError("TLS WebFlux startup timed out")
        with (EVIDENCE/"relay.log").open("wb") as log:
            relay=subprocess.Popen([str(RELAY),"--listen",f"127.0.0.1:{relay_port}","--issuer",issuer,
               "--secret-file",str(secret),"--authority",origin,"--authority-ca",str(cert),
               "--cert",str(cert),"--key",str(key)],stdout=log,stderr=subprocess.STDOUT)
        for attempt in range(30):
            if relay.poll() is not None:
                raise RuntimeError("relay process exited")
            try:
                with urllib.request.urlopen(urllib.request.Request(f"https://127.0.0.1:{relay_port}/health/v1",method="HEAD"),context=tls,timeout=2) as response:
                    if response.status==204:
                        break
            except (OSError,urllib.error.URLError):
                pass
            time.sleep(.5)
        else:
            raise RuntimeError("relay startup timed out")
        # Execute the repository's real installer, not a test-only fake service.
        run(PS,"-NoLogo","-NoProfile","-NonInteractive","-File",ROOT/"deploy/windows/install-network-service.ps1",
            "-AgentPath",AGENT,"-AgentSha256",sha(AGENT),"-WintunPath",dll,"-WintunSha256",sha(dll),
            "-Origin",origin,"-Issuer",issuer,"-AllowedUserSid",sid)
        installed=True
        # Go test does not emit the temporary bearer tokens or passwords.
        run("go","test","-v","-count=1","-timeout=240s","-run","^TestWindowsInstalledService$","./internal/serviceipc",cwd=ROOT/"agent",env=env)
        ps("Get-NetIPAddress -ErrorAction SilentlyContinue | Where-Object {$_.InterfaceAlias -eq 'battle0' -and $_.IPAddress -like '10.1*'} | ForEach-Object {throw 'Virtual IP was not removed'}")
        print("PASS: Windows service integration complete",flush=True)
    finally:
        try:
            # Only remove the service and installation created in this fresh VM.
            if installed or ps("if(Get-Service BattleEXNetwork -ErrorAction SilentlyContinue){'yes'}",capture=True).stdout.strip()=="yes":
                run(PS,"-NoLogo","-NoProfile","-NonInteractive","-File",ROOT/"deploy/windows/uninstall-network-service.ps1")
            ps("Get-Service | Where-Object Name -eq BattleEXNetwork | Format-List Name,Status; Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object Name -eq battle0 | Format-List Name,Status",capture=True)
        finally:
            stop(relay)
            stop(java)
            ps("foreach($name in @($env:BATTLE_TEST_ALLOWED,$env:BATTLE_TEST_DENIED)){if(Get-LocalUser -Name $name -ErrorAction SilentlyContinue){Remove-LocalUser -Name $name}}",env=env)
            if root_added:
                run("certutil","-delstore","Root",cert_hash,stdout=subprocess.DEVNULL)
    # Never upload private state, TLS material, user passwords or tokens.
    (EVIDENCE/"result.txt").write_text("PASS: native SCM, allowed and denied real standard-user pipe access, Wintun address, WSS UDP echo, directed discovery capture and leave cleanup.\n",encoding="utf-8")
