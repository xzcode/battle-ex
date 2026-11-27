package com.battleex.network;

import com.battleex.platform.Platform;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;

/** Room authority; all state changes and grant issuance serialize on Platform. */
@Component
public final class NetworkAuthority {
    public record DeviceRequest(String identityKey, String wireguardKey, String proof) {}
    public record Enrollment(String identityKey, String wireguardKey, String issuer) {}
    public record SignedSnapshot(String payload, String signature) {}
    public record Lease(String node, String network, String flow, String a, String b, long expires) {}
    public record Peer(UUID userId, String ip, String identityKey, String wireguardKey, String lease) {}
    public record Snapshot(int version, UUID roomId, UUID networkId, long revision, long issuedAt,
                           long validUntil, String localIP, String identityKey, String wireguardKey,
                           String relayNode, String relayURL, String stunURL, boolean ice,
                           List<Peer> peers, String nonce, String sessionDigest, String relayRegion,
                           List<RelayDirectory.Candidate> relayCandidates) {}
    public record Device(UUID userId, String session, String identity, String wireguard, long seen) {}
    public record RoomState(int subnet, String fingerprint, long revision, long leaseExpiry,
                             Map<UUID,Integer> addresses, String relayNode, long changedAt) {}
    private static final byte[] SNAP_DOMAIN = "battle-ex/snapshot/v1\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LEASE_DOMAIN = "network-ex/lease/v1\0".getBytes(StandardCharsets.UTF_8);
    private static final HexFormat HEX = HexFormat.of();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final int POOL = 4096; // 10.160.0.0/12, /24 per active room.
    private static final long HEARTBEAT_SECONDS = 15, SNAPSHOT_SECONDS = 12, MAX_LEASE_SECONDS = 840;
    private static final Logger LOG=LoggerFactory.getLogger(NetworkAuthority.class);
    private final Platform platform;
    private final ObjectMapper json;
    private final PrivateKey signer;
    private final String issuer;
    private final RelayDirectory relays;
    private final boolean ice;
    private final String directoryFile;
    private final boolean clustered;
    private final int retentionMinutes;
    private final long leaseSeconds;
    private boolean reloadRejected;
    private final Map<UUID,Device> devices = new HashMap<>();
    private final Map<UUID,RoomState> networks = new HashMap<>();
    private final Map<Integer,Long> quarantine = new HashMap<>();
    private final Map<UUID,Long> revisions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    public NetworkAuthority(Platform platform, ObjectMapper json,
            @Value("${battle.network.issuer-key-file:}") String keyFile,
            @Value("${battle.network.relay-node:battle-relay}") String node,
            @Value("${battle.network.relay-url:}") String relayURL,
            @Value("${battle.network.stun-url:}") String stunURL,
            @Value("${battle.network.ice:true}") boolean ice,
            @Value("${battle.network.relay-secret-file:}") String secretFile,
            @Value("${battle.network.relay-directory-file:}") String directoryFile,
            @Value("${battle.network.lease-seconds:840}") long leaseSeconds,
            @Value("${battle.network.metrics-retention-minutes:1440}") int retentionMinutes,
            @Value("${battle.state-mode:memory}") String stateMode) throws Exception {
        this.platform=platform; this.json=json; this.ice=ice; this.clustered=stateMode.equals("mongo");
        if(leaseSeconds<30||leaseSeconds>MAX_LEASE_SECONDS)throw new IllegalArgumentException("lease seconds must be 30–840");
        this.leaseSeconds=leaseSeconds;this.directoryFile=directoryFile;this.retentionMinutes=retentionMinutes;
        relays=new RelayDirectory(json,directoryFile,ice,node,relayURL,stunURL,secretFile,retentionMinutes);
        if(keyFile.isBlank()) {
            if(!relays.empty()) throw new IllegalArgumentException("network issuer required");
            signer=null; issuer=""; return;
        }
        if(relays.empty())throw new IllegalArgumentException("at least one relay node required");
        byte[] raw=HEX.parseHex(readSecret(keyFile).strip());
        if(raw.length!=64)throw new IllegalArgumentException("issuer key must be 64-byte Ed25519 private key");
        byte[] prefix=HEX.parseHex("302e020100300506032b657004220420");
        byte[] encoded=Arrays.copyOf(prefix,prefix.length+32);
        System.arraycopy(raw,0,encoded,prefix.length,32);
        signer=KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        issuer=HEX.formatHex(Arrays.copyOfRange(raw,32,64));
        byte[] probe="battle-ex/key-check".getBytes(StandardCharsets.UTF_8);
        if(!verify(issuer,probe,sign(new byte[0],probe)))throw new IllegalArgumentException("issuer key mismatch");
        Arrays.fill(raw,(byte)0);Arrays.fill(encoded,(byte)0);
    }
    // File I/O and validation execute on Spring's scheduling thread, not a
    // Reactor Netty thread and not under the synchronized platform lock.
    @Scheduled(fixedDelay=3000)
    public void reloadRelayDirectory(){
        if(clustered||directoryFile.isBlank()||!enabled())return;
        try{
            var replacement=new RelayDirectory(json,directoryFile,ice,"","","","",retentionMinutes);
            synchronized(platform){
                if(relays.reload(replacement))LOG.info("Applied updated relay directory");
            }
            if(reloadRejected)LOG.info("Relay directory configuration recovered");
            reloadRejected=false;
        }catch(Exception e){
            if(!reloadRejected)LOG.warn("Rejected invalid relay directory update; retaining last valid configuration");
            reloadRejected=true;
        }
    }
    public record StateDump(Map<UUID,Device> devices,Map<UUID,RoomState> networks,
                            Map<Integer,Long> quarantine,Map<UUID,Long> revisions,
                            RelayDirectory.StateDump relays) {}
    public StateDump exportState(){
        synchronized(platform){return new StateDump(Map.copyOf(devices),Map.copyOf(networks),
                Map.copyOf(quarantine),Map.copyOf(revisions),relays.exportState());}
    }
    public void importState(StateDump state){
        synchronized(platform){
            relays.importState(state.relays());
            devices.clear();devices.putAll(state.devices());networks.clear();networks.putAll(state.networks());
            quarantine.clear();quarantine.putAll(state.quarantine());revisions.clear();revisions.putAll(state.revisions());
        }
    }
    public String configurationFingerprint(){
        return sha256("battle-authority-v1:"+issuer+":"+ice+":"+leaseSeconds+":"+retentionMinutes+":"+relays.configurationFingerprint());
    }
    public boolean enabled(){return signer!=null;}
    public String issuer(){return issuer;}
    private void requireEnabled(){if(!enabled())throw new IllegalStateException("room networking is not configured");}
    public Enrollment enroll(String token, DeviceRequest req) {
        synchronized(platform){
            requireEnabled();
            var ctx=platform.networkContext(token);
            long now=Instant.now().getEpochSecond();
            prune(now);
            if(req==null||!key(req.identityKey())||!key(req.wireguardKey())
                    ||req.identityKey().equals(req.wireguardKey())||req.proof()==null)
                throw new IllegalArgumentException("invalid device public keys");
            byte[] message=("battle-ex/device/v1\0"+ctx.sessionDigest()+"\n"+req.identityKey()+"\n"+req.wireguardKey()).getBytes(StandardCharsets.UTF_8);
            if(!verify(req.identityKey(),message,decodeSignature(req.proof())))throw new SecurityException("device possession proof failed");
            Device prior=devices.get(ctx.user().id());
            if(prior!=null && prior.session().equals(ctx.sessionDigest()) &&
                    (!prior.identity().equals(req.identityKey())||!prior.wireguard().equals(req.wireguardKey())))
                throw new IllegalStateException("a different device is already enrolled for this session");
            for(Device d:devices.values())if(!d.userId().equals(ctx.user().id()) &&
                    (d.identity().equals(req.identityKey())||d.wireguard().equals(req.wireguardKey())||d.identity().equals(req.wireguardKey())||d.wireguard().equals(req.identityKey())))
                throw new SecurityException("device key already belongs to another session");
            devices.put(ctx.user().id(),new Device(ctx.user().id(),ctx.sessionDigest(),req.identityKey(),req.wireguardKey(),now));
            return new Enrollment(req.identityKey(),req.wireguardKey(),issuer);
        }
    }
    public SignedSnapshot snapshot(String token,String nonce,Map<String,Integer> measurements){
        if(nonce==null||!nonce.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("invalid snapshot nonce");
        synchronized(platform){
            requireEnabled(); var ctx=platform.networkContext(token);
            long now=Instant.now().getEpochSecond(); prune(now);
            Device device=devices.get(ctx.user().id());
            if(device==null||!device.session().equals(ctx.sessionDigest()))throw new SecurityException("enroll this device first");
            if(ctx.room()==null)throw new NoRoom();
            devices.put(device.userId(),new Device(device.userId(),device.session(),device.identity(),device.wireguard(),now));
            relays.report(ctx.sessionDigest(),measurements,now);
            var room=ctx.room();
            var active=activeDevices(room,now);
            RoomState state=state(room,active,now);
            List<Peer> peers=new ArrayList<>();
            for(Device peer:active)if(!peer.userId().equals(device.userId())){
                Lease lease=lease(room.networkId(),state,device,peer);
                byte[] payload=bytes(lease);
                String tokenValue=B64.encodeToString(payload)+"."+B64.encodeToString(sign(LEASE_DOMAIN,payload));
                peers.add(new Peer(peer.userId(),ip(state.subnet(),state.addresses().get(peer.userId())),
                        peer.identity(),peer.wireguard(),tokenValue));
            }
            var selected=relays.config(state.relayNode());
            Snapshot snapshot=new Snapshot(1,room.id(),room.networkId(),state.revision(),now,now+SNAPSHOT_SECONDS,
                    ip(state.subnet(),state.addresses().get(device.userId())),device.identity(),device.wireguard(),
                    selected.id(),selected.relayURL(),ice?selected.stunURL():"",ice,List.copyOf(peers),nonce,ctx.sessionDigest(),selected.region(),relays.candidates(now));
            byte[] payload=bytes(snapshot);
            return new SignedSnapshot(B64.encodeToString(payload),B64.encodeToString(sign(SNAP_DOMAIN,payload)));
        }
    }
    public void disconnect(String token){
        synchronized(platform){
            var ctx=platform.networkContext(token);
            Device prior=devices.get(ctx.user().id());
            if(prior!=null&&prior.session().equals(ctx.sessionDigest()))devices.remove(ctx.user().id());
        }
    }
    public boolean authorize(String secret, Lease requested){
        if(!enabled()||requested==null)return false;
        synchronized(platform){
            long now=Instant.now().getEpochSecond();
            if(!relays.authenticated(requested.node(),secret)||requested.expires()<=now)return false;
            prune(now);
            UUID network;
            try{network=UUID.fromString(requested.network());}catch(Exception e){return false;}
            var room=platform.roomByNetwork(network);
            if(room==null)return false;
            var active=activeDevices(room,now);
            if(active.size()<2)return false;
            RoomState state;
            try{state=state(room,active,now);}catch(NoRelay e){return false;}
            if(!state.relayNode().equals(requested.node()))return false;
            Device a=null,b=null;
            for(Device d:active){if(d.identity().equals(requested.a()))a=d;if(d.identity().equals(requested.b()))b=d;}
            return a!=null&&b!=null&&!a.userId().equals(b.userId())&&lease(network,state,a,b).equals(requested);
        }
    }
    public RelayDirectory.Limits heartbeat(String secret,RelayDirectory.Heartbeat heartbeat){
        synchronized(platform){return enabled()?relays.heartbeat(secret,heartbeat,Instant.now().getEpochSecond()):null;}
    }
    public List<RelayDirectory.PublicStatus> relayStatus(String token){
        synchronized(platform){platform.authenticate(token);return relays.status(Instant.now().getEpochSecond());}
    }
    public RelayDirectory.History relayHistory(String token,String node){
        synchronized(platform){
            platform.authenticate(token);
            var history=relays.history(node,Instant.now().getEpochSecond());
            return new RelayDirectory.History(history.node(),history.retentionMinutes(),history.minutes(),clustered);
        }
    }
    public static final class NoRelay extends RuntimeException {public NoRelay(){super("no healthy relay node available");}}
    public static final class NoRoom extends RuntimeException {public NoRoom(){super("not in a room");}}
    private List<Device> activeDevices(com.battleex.room.RoomDirectory.RoomView room,long now){
        return room.members().stream().map(m->devices.get(m.userId())).filter(Objects::nonNull)
                .filter(d->d.seen()+HEARTBEAT_SECONDS>now && platform.isSessionCurrent(d.userId(),d.session()))
                .sorted(Comparator.comparing(d->d.userId().toString())).toList();
    }
    private RoomState state(com.battleex.room.RoomDirectory.RoomView room,List<Device> active,long now){
        if(active.size()>65)throw new IllegalStateException("current engine supports 64 peers per device");
        var old=networks.get(room.networkId());
        String selected=relays.select(old==null?null:old.relayNode(),old==null?0:old.changedAt(),
                active.stream().map(Device::session).toList(),
                room.state()==com.battleex.room.RoomDirectory.State.WAITING,now);
        if(selected==null)throw new NoRelay();
        long changedAt=old==null||!selected.equals(old.relayNode())?now:old.changedAt();
        String fingerprint=selected+":"+relays.generation(selected)+":"+room.membershipRevision()+":"+String.join(",",active.stream().map(d->d.userId()+":"+d.session()+":"+d.identity()+":"+d.wireguard()).toList());
        if(old!=null&&old.fingerprint().equals(fingerprint)&&old.leaseExpiry()>now+Math.min(20,leaseSeconds/3))return old;
        int subnet=old==null?allocate(now):old.subnet();
        var addresses=new HashMap<UUID,Integer>();
        if(old!=null)for(var m:room.members()){
            Integer assigned=old.addresses().get(m.userId());if(assigned!=null)addresses.put(m.userId(),assigned);
        }
        for(var m:room.members())if(!addresses.containsKey(m.userId())){
            int host=1;while(addresses.containsValue(host)&&host<255)host++;
            if(host>=255)throw new IllegalStateException("room IPv4 addresses exhausted");
            addresses.put(m.userId(),host);
        }
        long revision=revisions.merge(room.networkId(),1L,Long::sum);
        var next=new RoomState(subnet,fingerprint,revision,now+leaseSeconds,Map.copyOf(addresses),selected,changedAt);
        networks.put(room.networkId(),next);
        return next;
    }
    private Lease lease(UUID network,RoomState state,Device a,Device b){
        String first=a.identity().compareTo(b.identity())<0?a.identity():b.identity();
        String second=first.equals(a.identity())?b.identity():a.identity();
        String flow=sha256(network+":"+state.revision()+":"+first+":"+second);
        return new Lease(state.relayNode(),network.toString(),flow,first,second,state.leaseExpiry());
    }
    private void prune(long now){
        platform.sweep();
        relays.prune(now);
        devices.values().removeIf(d->!platform.isSessionCurrent(d.userId(),d.session()));
        var it=networks.entrySet().iterator();
        while(it.hasNext()){
            var entry=it.next();
            if(platform.roomByNetwork(entry.getKey())==null){
                quarantine.put(entry.getValue().subnet(),now+MAX_LEASE_SECONDS);
                revisions.remove(entry.getKey());it.remove();
            }
        }
        quarantine.values().removeIf(end->end<=now);
    }
    private int allocate(long now){
        Set<Integer> used=new HashSet<>(quarantine.keySet());
        networks.values().forEach(s->used.add(s.subnet()));
        int start=random.nextInt(POOL);
        for(int n=0;n<POOL;n++){int slot=(start+n)%POOL;if(!used.contains(slot))return slot;}
        throw new IllegalStateException("IPv4 room address pool exhausted");
    }
    private static String ip(int slot,int host){return "10."+(160+(slot>>>8))+"."+(slot&255)+"."+host;}
    private static boolean key(String value){return value!=null&&value.matches("[a-f0-9]{64}")&&!value.equals("0".repeat(64));}
    static String sha256(String s){
        try{return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("digest failed");}
    }
    private byte[] bytes(Object v){try{return json.writeValueAsBytes(v);}catch(Exception e){throw new IllegalStateException("snapshot encoding failed");}}
    private byte[] sign(byte[] domain,byte[] payload){
        try{Signature s=Signature.getInstance("Ed25519");s.initSign(signer);s.update(domain);s.update(payload);return s.sign();}
        catch(Exception e){throw new IllegalStateException("network signature failed");}
    }
    private static byte[] decodeSignature(String v){
        try{if(v.length()>128)return new byte[0];return Base64.getUrlDecoder().decode(v);}catch(Exception e){return new byte[0];}
    }
    private static boolean verify(String pub,byte[] payload,byte[] signature){
        try{
            byte[] prefix=HEX.parseHex("302a300506032b6570032100");
            byte[] encoded=Arrays.copyOf(prefix,prefix.length+32);
            System.arraycopy(HEX.parseHex(pub),0,encoded,prefix.length,32);
            var key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            Signature s=Signature.getInstance("Ed25519");s.initVerify(key);s.update(payload);return s.verify(signature);
        }catch(Exception e){return false;}
    }
    static String readSecret(String file)throws Exception{
        Path p=Path.of(file);
        if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>4096)
            throw new IllegalArgumentException("invalid secret file");
        try{var perms=Files.getPosixFilePermissions(p);for(var permission:perms)
            if(permission.name().startsWith("GROUP_")||permission.name().startsWith("OTHERS_"))
                throw new IllegalArgumentException("network secret file must be private");
        }catch(UnsupportedOperationException ignored){/* Windows installer ACL gate. */}
        return Files.readString(p,StandardCharsets.US_ASCII);
    }
}
