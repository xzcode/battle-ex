#!/usr/bin/env python3
"""Real Battle EX room networks. Run only inside a new isolated network namespace."""
import argparse, base64, hashlib, json, os, pathlib, signal, ssl, subprocess, sys, tempfile, threading, time, urllib.error, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
P = argparse.ArgumentParser()
P.add_argument("--inside", action="store_true")
P.add_argument("--ice", action="store_true")
P.add_argument("--multi", action="store_true")
args = P.parse_args()
if not args.inside:
    os.execvp("unshare", ["unshare","--net","--",sys.executable,__file__,"--inside",*(["--ice"] if args.ice else []),*(["--multi"] if args.multi else [])])
# This script never creates routes or listeners in the host network namespace.
assert os.readlink("/proc/self/ns/net") != os.readlink("/proc/1/ns/net"), "isolated namespace required"
procs = []
logs = []
def cmd(*v, check=True, **kwargs):
    return subprocess.run([str(x) for x in v], check=check, **kwargs)
def wait_for(fn, description, timeout=35):
    until=time.monotonic()+timeout
    last=None
    while time.monotonic()<until:
        try:
            result=fn()
            if result:return result
        except (OSError,ValueError,KeyError,urllib.error.URLError) as e:last=e
        time.sleep(.25)
    raise AssertionError("timeout: "+description+(" ("+str(last)+")" if last else ""))
def terminate(p):
    if p.poll() is None:
        p.terminate()
        try:p.wait(8)
        except subprocess.TimeoutExpired:p.kill();p.wait()
def spawn(argv,**kw):
    p=subprocess.Popen([str(x) for x in argv],**kw);procs.append(p);return p
def namespace(name, index):
    p=spawn(["unshare","--net","--","sleep","600"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    wait_for(lambda:os.readlink(f"/proc/{p.pid}/ns/net")!=os.readlink("/proc/self/ns/net"),"child namespace",5)
    cmd("ip","link","add","h"+name,"type","veth","peer","name","n"+name)
    cmd("ip","link","set","h"+name,"master","bexbr");cmd("ip","link","set","h"+name,"up")
    cmd("ip","link","set","n"+name,"netns",p.pid)
    ns=["nsenter","-t",str(p.pid),"-n"]
    cmd(*ns,"ip","link","set","lo","up")
    cmd(*ns,"ip","addr","add",f"192.0.2.{index}/24","dev","n"+name)
    cmd(*ns,"ip","link","set","n"+name,"up")
    return ns
class Agent:
    def __init__(self, name, ns, token, issuer, d, cert):
        self.name=name;self.ns=ns;self.token=token;self.latest={};self.history=[]
        self.state=d/name
        err=open(d/(name+".log"),"w");logs.append(err)
        self.proc=spawn(ns+[str(ROOT/"agent/bin/battle-agent"),"--state-dir",str(self.state),"--issuer",issuer,"--api-ca",str(cert),"--relay-ca",str(cert),"--ice-interfaces","n"+name] if args.ice else ns+[str(ROOT/"agent/bin/battle-agent"),"--state-dir",str(self.state),"--issuer",issuer,"--api-ca",str(cert),"--relay-ca",str(cert)],
                        stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=err,text=True,bufsize=1)
        self.proc.stdin.write(json.dumps({"origin":ORIGIN,"token":token})+"\n");self.proc.stdin.flush()
        threading.Thread(target=self.read,daemon=True).start()
    def read(self):
        for line in self.proc.stdout:
            try:
                v=json.loads(line)
                if isinstance(v,dict) and "state" in v:self.latest=v;self.history.append(v)
            except ValueError:pass
    def peer(self,ip):
        return next((p for p in self.latest.get("peers") or [] if p["ip"]==ip),{})
    def ping(self,ip,ok=True):
        p=cmd(*self.ns,"ping","-c","1","-W","1",ip,check=False,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        return (p.returncode==0)==ok
    def stop(self):
        if self.proc.stdin and not self.proc.stdin.closed:self.proc.stdin.close()
        try:self.proc.wait(8)
        except subprocess.TimeoutExpired:raise AssertionError("agent failed graceful pipe shutdown: "+self.name)
    def no_interface(self):
        return cmd(*self.ns,"ip","link","show","battle0",check=False,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0

ORIGIN="https://192.0.2.1:18443"
RELAY="wss://192.0.2.1:18444/relay/v1"
agents=[]
try:
    with tempfile.TemporaryDirectory(prefix="battle-m2-") as temp:
        d=pathlib.Path(temp);os.chmod(d,0o700)
        cmd("ip","link","set","lo","up")
        cmd("ip","link","add","bexbr","type","bridge")
        cmd("ip","addr","add","192.0.2.1/24","dev","bexbr");cmd("ip","link","set","bexbr","up")
        ns={name:namespace(name,i) for i,name in enumerate(["a","b","c","d"],2)}
        if args.ice:
            # Block ONLY C's physical UDP path, not its virtual interface.
            # C can still reach the WSS relay; A/B have working ICE.
            cmd(*ns["c"],"iptables","-A","OUTPUT","-o","nc","-p","udp","-j","DROP")
            cmd(*ns["c"],"iptables","-A","INPUT","-i","nc","-p","udp","-j","DROP")
        issuer=cmd(ROOT/"agent/bin/battle-relay","keygen","-out",d/"issuer.key",capture_output=True,text=True).stdout.strip()
        secret=os.urandom(32).hex();(d/"relay.secret").write_text(secret+"\n");os.chmod(d/"relay.secret",0o600)
        cert=d/"tls.crt";key=d/"tls.key";p12=d/"tls.p12"
        cmd("openssl","req","-x509","-newkey","rsa:2048","-nodes","-days","1","-subj","/CN=192.0.2.1","-addext","subjectAltName=IP:192.0.2.1","-keyout",key,"-out",cert,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        password=os.urandom(24).hex()
        cmd("openssl","pkcs12","-export","-in",cert,"-inkey",key,"-out",p12,"-passout","env:BATTLE_TEST_P12_PASSWORD",env={**os.environ,"BATTLE_TEST_P12_PASSWORD":password},stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        os.chmod(key,0o600);os.chmod(p12,0o600)
        env={**os.environ,"BATTLE_BIND":"192.0.2.1","BATTLE_PORT":"18443","BATTLE_MONGO_URI":"",
             "SERVER_SSL_ENABLED":"true","SERVER_SSL_KEY_STORE":str(p12),"SERVER_SSL_KEY_STORE_TYPE":"PKCS12","SERVER_SSL_KEY_STORE_PASSWORD":password,
             "BATTLE_NETWORK_ISSUER_KEY_FILE":str(d/"issuer.key"),"BATTLE_NETWORK_RELAY_SECRET_FILE":str(d/"relay.secret"),
             "BATTLE_NETWORK_RELAY_NODE":"battle-relay","BATTLE_NETWORK_RELAY_URL":RELAY,"BATTLE_NETWORK_ICE":str(args.ice).lower(),
             "BATTLE_NETWORK_STUN_URL":"stun:192.0.2.1:3478"}
        if args.multi:
            backup_file=d/"relay2.secret"
            backup_file.write_text(os.urandom(32).hex()+"\n");os.chmod(backup_file,0o600)
            relay_directory=d/"relays.json"
            relay_directory.write_text(json.dumps([
                {"id":"battle-relay","region":"primary","relayURL":RELAY,"stunURL":"stun:192.0.2.1:3478","secretFile":str(d/"relay.secret"),"priority":0,"maxConnections":256,"bytesPerSecond":10000000},
                {"id":"battle-relay-2","region":"backup","relayURL":"wss://192.0.2.1:18445/relay/v1","stunURL":"stun:192.0.2.1:3479","secretFile":str(backup_file),"priority":1,"maxConnections":256,"bytesPerSecond":10000000}
            ]))
            env.update(BATTLE_NETWORK_RELAY_SECRET_FILE="",BATTLE_NETWORK_RELAY_URL="",BATTLE_NETWORK_RELAY_DIRECTORY_FILE=str(relay_directory))
        java=pathlib.Path(os.environ["JAVA_HOME"])/"bin/java" if os.environ.get("JAVA_HOME") else "java"
        serverlog=open(d/"server.log","w");logs.append(serverlog)
        server=spawn([java,"-jar",ROOT/"server/target/battle-ex-server-0.1.0-SNAPSHOT.jar"],env=env,stdout=serverlog,stderr=subprocess.STDOUT)
        ctx=ssl.create_default_context(cafile=str(cert))
        def api(path,token=None,body=None,status=200,method=None,relay_secret=None):
            headers={"Content-Type":"application/json"}
            if token:headers["Authorization"]="Bearer "+token
            if relay_secret:headers["X-Relay-Secret"]=relay_secret
            req=urllib.request.Request(ORIGIN+"/api/v1"+path,headers=headers,method=method or ("POST" if body is not None else "GET"),data=None if body is None else json.dumps(body).encode())
            try:
                r=urllib.request.urlopen(req,context=ctx,timeout=5)
            except urllib.error.HTTPError as e:r=e
            with r:
                if r.status!=status:raise AssertionError(f"{path}: expected HTTP {status}, got {r.status}")
                data=r.read(); return json.loads(data) if data else None
        wait_for(lambda:api("/lobby",status=401),"TLS authority",45)
        def launch_relay(second=False):
            label="relay2" if second else "relay"
            log=open(d/(label+".log"),"a");logs.append(log)
            port=18445 if second else 18444
            stun=3479 if second else 3478
            secretpath=d/("relay2.secret" if second else "relay.secret")
            options=["--node","battle-relay-2" if second else "battle-relay"]
            if args.multi:options+=["--register","--drain-file",str(d/(label+".drain"))]
            return spawn([ROOT/"agent/bin/battle-relay","--listen",f"192.0.2.1:{port}","--issuer",issuer,"--secret-file",secretpath,
                         "--authority",ORIGIN,"--authority-ca",cert,"--cert",cert,"--key",key,"--stun-listen",f"192.0.2.1:{stun}",*options],stdout=log,stderr=subprocess.STDOUT)
        relayproc=launch_relay()
        relayproc2=launch_relay(True) if args.multi else None
        users={n:api("/register",body={"username":"player_"+n,"password":"test-"+os.urandom(12).hex()},status=201) for n in ns}
        tokens={n:v["token"] for n,v in users.items()}
        if args.multi:
            def relay_nodes():return {n["id"]:n for n in api("/network/relays",tokens["a"])}
            wait_for(lambda:len(relay_nodes())==2 and all(n["state"]=="online" for n in relay_nodes().values()),"authenticated node registration",20)
            print("PASS: two registered relay nodes",flush=True)
        api("/network/info",status=401)
        assert api("/network/info",tokens["a"])["issuer"]==issuer
        api("/network/device",tokens["a"],{"identityKey":"01"*32,"wireguardKey":"02"*32,"proof":"bad"},status=403)
        api("/network/snapshot",tokens["a"],{"nonce":os.urandom(32).hex()},status=403)
        r1=api("/rooms",tokens["a"],{"name":"Room one","gameId":"custom","capacity":8,"visibility":"PUBLIC"},status=201)
        for name in ("b","c"):api("/rooms/"+r1["id"]+"/join",tokens[name],{})
        r2=api("/rooms",tokens["d"],{"name":"Room two","gameId":"custom","capacity":8,"visibility":"PRIVATE"},status=201)
        for name in ns:agents.append(Agent(name,ns[name],tokens[name],issuer,d,cert))
        a,b,c,other=agents
        try:
            for agent in agents:
                wait_for(lambda ag=agent:ag.latest.get("state")=="running","running virtual network "+agent.name)
            ips={ag.name:ag.latest["ip"] for ag in agents}
            assert len(set(ips.values()))==4
            assert ips["a"].rsplit(".",1)[0]==ips["b"].rsplit(".",1)[0]==ips["c"].rsplit(".",1)[0]
            assert ips["a"].rsplit(".",1)[0]!=ips["d"].rsplit(".",1)[0]
            wait_for(lambda: a.ping(ips["b"]) and a.ping(ips["c"]),"real room ICMP direct/relay",45)
            wait_for(lambda: a.peer(ips["b"]).get("rttMillis") is not None and a.peer(ips["c"]).get("rttMillis") is not None,"real overlay UDP RTT",30)
            if args.ice:
                wait_for(lambda:a.peer(ips["b"]).get("mode")=="ice-selected" and a.peer(ips["c"]).get("mode")=="relay-fallback","mixed ICE and WSS",45)
            else:
                assert a.peer(ips["b"])["mode"]=="relay-configured" and a.peer(ips["c"])["relayConnected"]
            print("PASS: real room virtual IPs, ICMP, measured UDP RTT, "+("ICE direct and WSS fallback simultaneously" if args.ice else "forced WSS relay"),flush=True)
            # Exchange actual TCP and UDP application payloads over the overlay.
            echo_code = '''import socket, threading, sys
ip=sys.argv[1]
udp=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); udp.bind((ip,39100))
tcp=socket.socket(socket.AF_INET,socket.SOCK_STREAM); tcp.bind((ip,39101)); tcp.listen()
def datagram():
    while True:
        data,addr=udp.recvfrom(2048); udp.sendto(data,addr)
threading.Thread(target=datagram,daemon=True).start()
print("ready",flush=True)
while True:
    connection,_=tcp.accept()
    with connection:
        data=connection.recv(2048); connection.sendall(data)
'''
            test_code = '''import socket, sys
ip=sys.argv[1]
for kind,port in [(socket.SOCK_STREAM,39101),(socket.SOCK_DGRAM,39100)]:
    with socket.socket(socket.AF_INET,kind) as sock:
        sock.settimeout(5);sock.connect((ip,port))
        payload=b"Battle EX application payload";sock.sendall(payload)
        assert sock.recv(2048)==payload
'''
            echo_procs=[]
            for name in ("b","c"):
                p=spawn(ns[name]+[sys.executable,"-u","-c",echo_code,ips[name]],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True)
                echo_procs.append(p)
                assert p.stdout.readline().strip()=="ready"
                cmd(*ns["a"],sys.executable,"-c",test_code,ips[name])
            for p in echo_procs:terminate(p)
            print("PASS: TCP and UDP application payloads on both peer paths",flush=True)
            assert a.ping(ips["d"],False) and other.ping(ips["a"],False)
            # Cross-room snapshot content must exclude all other room members.
            nonce=os.urandom(32).hex()
            signed=api("/network/snapshot",tokens["a"],{"nonce":nonce})
            payload=json.loads(base64.urlsafe_b64decode(signed["payload"]+"="*((4-len(signed["payload"])%4)%4)))
            assert payload["nonce"]==nonce and payload["networkId"]==r1["networkId"]
            assert {p["ip"] for p in payload["peers"]}=={ips["b"],ips["c"]}
            lease_string=next(p["lease"] for p in payload["peers"] if p["ip"]==ips["c"])
            lease_json=lease_string.split(".")[0]
            lease=json.loads(base64.urlsafe_b64decode(lease_json+"="*((4-len(lease_json)%4)%4)))
            api("/network/relay/authorize",body=lease,relay_secret=secret,status=204)
            api("/network/relay/authorize",body=lease,relay_secret="bad",status=403)
            print("PASS: room isolation and authenticated relay lease",flush=True)
            wait_for(lambda:all(a.peer(ip).get("probeSamples",0)>0 and a.peer(ip).get("probeLossPct") is not None for ip in (ips["b"],ips["c"])),"measured peer quality",20)
            if args.multi:
                wait_for(lambda:len(a.latest.get("relayCandidates",[]))==2 and all(n.get("probeMillis") is not None and n["probeMillis"]>=0 for n in a.latest["relayCandidates"]),"actual TLS relay probes",20)
                print("PASS: measured UDP probe quality and TLS health response times",flush=True)
                initial=a.latest.get("relayNode")
                assert initial=="battle-relay",initial
                wait_for(lambda:relay_nodes()["battle-relay"]["stats"]["ingress_bytes"]>0,"reported relay traffic",20)
                started=time.monotonic()
                terminate(relayproc)
                wait_for(lambda:relay_nodes()["battle-relay"]["state"]=="offline","failed node heartbeat expiry",20)
                wait_for(lambda:all(ag.latest.get("relayNode")=="battle-relay-2" and ag.latest.get("ip")==ips[ag.name] for ag in agents)
                         and a.ping(ips["b"]) and a.ping(ips["c"]),"failover real traffic same IPs",45)
                print(f"PASS: automatic relay failover with unchanged virtual IPs and live traffic ({time.monotonic()-started:.1f}s)",flush=True)
                relayproc=launch_relay()
                wait_for(lambda:relay_nodes()["battle-relay"]["state"]=="online","re-register restarted node",20)
                (d/"relay2.drain").touch()
                wait_for(lambda:relay_nodes()["battle-relay-2"]["state"]=="draining","maintenance heartbeat",20)
                wait_for(lambda:all(ag.latest.get("relayNode")=="battle-relay" and ag.latest.get("ip")==ips[ag.name] for ag in agents)
                         and a.ping(ips["b"]) and a.ping(ips["c"]),"maintenance migration real traffic",45)
                print("PASS: node re-registration and maintenance migration",flush=True)
                v=api("/network/snapshot",tokens["a"],{"nonce":os.urandom(32).hex()})["payload"]
                payload=json.loads(base64.urlsafe_b64decode(v+"="*((4-len(v)%4)%4)))
                lease_json=next(p["lease"] for p in payload["peers"] if p["ip"]==ips["c"]).split(".")[0]
                lease=json.loads(base64.urlsafe_b64decode(lease_json+"="*((4-len(lease_json)%4)%4)))
                api("/network/relay/authorize",body=lease,relay_secret=secret,status=204)

            # Departing client must lose old virtual interface and peers must revoke it.
            api("/rooms/"+r1["id"]+"/leave",tokens["c"],{},status=204)
            api("/network/relay/authorize",body=lease,relay_secret=secret,status=403)
            wait_for(c.no_interface,"departed client's route cleanup",20)
            wait_for(lambda:not a.peer(ips["c"]) and a.ping(ips["c"],False),"remaining peer removed departed route",20)
            invite=api("/rooms/"+r2["id"]+"/invitation",tokens["d"])["invite"]
            api("/rooms/"+r2["id"]+"/join",tokens["c"],{"invite":invite})
            wait_for(lambda:c.latest.get("state")=="running" and c.latest.get("networkId")==r2["networkId"] and c.ping(ips["d"]),"join second room and exchange packets",45)
            assert c.latest["ip"].rsplit(".",1)[0]==ips["d"].rsplit(".",1)[0]
            assert c.ping(ips["a"],False) and a.ping(c.latest["ip"],False)
            print("PASS: leave revocation, old relay lease denied, room switch and cross-room packets rejected",flush=True)
            # Shutdown the authority: a stale signed snapshot cannot keep an honest agent online.
            terminate(server)
            wait_for(lambda:all(ag.no_interface() for ag in agents),"fail-closed interface cleanup without authority",25)
            for ag in agents:ag.stop()
            print("PASS: authority loss stops virtual interfaces at bounded snapshot expiry; stdin IPC shutdown",flush=True)
            print(("M3 MULTI-RELAY" if args.multi else "M2 SINGLE-RELAY")+" REAL NETWORK INTEGRATION PASS",flush=True)
        except BaseException:
            for ag in agents:print("DIAGNOSTIC",ag.name,json.dumps(ag.latest),file=sys.stderr)
            for name in ["server","relay","relay2","a","b","c","d"]:
                path=d/(name+".log")
                if path.exists():print("LOG",name,path.read_text(errors="replace")[-1800:],file=sys.stderr)
            raise
        finally:
            for ag in agents:
                if ag.proc.poll() is None:
                    try:ag.stop()
                    except Exception:terminate(ag.proc)
            terminate(relayproc)
            if relayproc2 is not None:terminate(relayproc2)
            terminate(server)
finally:
    for p in reversed(procs):terminate(p)
    for f in logs:f.close()
