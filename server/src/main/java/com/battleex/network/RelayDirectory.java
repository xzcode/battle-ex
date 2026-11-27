package com.battleex.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Administrator-owned endpoints and credentials; never accept endpoint URLs from a heartbeat. */
public final class RelayDirectory {
    static final long TTL = 10, PROBE_TTL = 25, SWITCH_COOLDOWN = 30;
    record Config(String id, String region, String relayURL, String stunURL, String secretFile,
                  int priority, int maxConnections, long bytesPerSecond) {}
    public record Stats(int connections, long ingress_bytes, long egress_bytes, long dropped_packets) {}
    public record Heartbeat(String node, String instance, boolean draining, Stats stats) {}
    public record Minute(long minute, int peakConnections, long ingressBytes, long egressBytes,
                         long droppedPackets, int samples, int discontinuities) {}
    public record History(String node, int retentionMinutes, List<Minute> minutes) {}
    public record Limits(int maxConnections, long bytesPerSecond) {}
    public record Candidate(String id, String region, String relayURL) {}
    public record PublicStatus(String id, String region, String state, long seenAt, Stats stats,
                        int maxConnections, long bytesPerSecond) {}
    record Node(Config config, String secret, boolean legacy) {}
    private record Live(String instance, long seen, boolean draining, Stats stats) {}
    private record Sample(long seen, Map<String,Integer> measurements) {}
    private final Map<String,Node> nodes = new TreeMap<>();
    private final Map<String,Live> live = new HashMap<>();
    private final Map<String,Sample> samples = new HashMap<>();
    private final Map<String,Long> generations = new HashMap<>();
    private final Map<String,NavigableMap<Long,Minute>> history = new HashMap<>();
    private long nextGeneration;
    private long lastHistoryPrune = Long.MIN_VALUE;
    private final int retentionMinutes;
    private final boolean dynamic;
    private final boolean ice;

    RelayDirectory(ObjectMapper mapper, String directory, boolean ice, String legacyId,
                   String legacyURL, String legacyStun, String legacySecretFile) throws Exception {
        this(mapper,directory,ice,legacyId,legacyURL,legacyStun,legacySecretFile,1440);
    }
    RelayDirectory(ObjectMapper mapper, String directory, boolean ice, String legacyId,
                   String legacyURL, String legacyStun, String legacySecretFile,int retentionMinutes) throws Exception {
        if(retentionMinutes<1)throw new IllegalArgumentException("metrics retention must be positive");
        this.retentionMinutes=retentionMinutes;
        this.ice=ice;
        dynamic=!directory.isBlank();
        if(dynamic){
            if(!legacyURL.isBlank()||!legacySecretFile.isBlank())throw new IllegalArgumentException("choose directory or legacy relay configuration");
            Path path=Path.of(directory);
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>65536)
                throw new IllegalArgumentException("invalid relay directory file");
            Config[] values=mapper.readValue(Files.readAllBytes(path),Config[].class);
            if(values==null||values.length==0)throw new IllegalArgumentException("empty relay directory");
            for(Config c:values)add(c,false);
        }else if(!legacyURL.isBlank()||!legacySecretFile.isBlank()){
            add(new Config(legacyId,"default",legacyURL,legacyStun,legacySecretFile,0,256,1L<<30),true);
        }
    }

    private void add(Config c,boolean legacy)throws Exception{
        if(c==null||c.id()==null||!c.id().matches("[A-Za-z0-9_-]{1,64}")||c.region()==null||
                !c.region().matches("[A-Za-z0-9 _-]{1,48}")||!validURL(c.relayURL())||
                (ice&&!validStun(c.stunURL()))||c.secretFile()==null||c.secretFile().isBlank()||
                c.maxConnections()<2||c.maxConnections()>256||c.bytesPerSecond()<65536||c.bytesPerSecond()>(1L<<30))
            throw new IllegalArgumentException("invalid relay node configuration");
        String secret=NetworkAuthority.readSecret(c.secretFile()).strip();
        if(!secret.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("invalid relay service secret");
        if(nodes.containsKey(c.id())||nodes.values().stream().anyMatch(n->n.config().relayURL().equals(c.relayURL())||n.secret().equals(secret)))
            throw new IllegalArgumentException("duplicate relay ID, endpoint or credential");
        nodes.put(c.id(),new Node(c,secret,legacy));
        generations.put(c.id(),++nextGeneration);
    }
    // Validate a complete replacement directory outside the room-authority lock,
    // then apply it atomically. Preserve live state for unchanged endpoints/keys.
    boolean reload(RelayDirectory replacement){
        if(!dynamic||!replacement.dynamic)throw new IllegalArgumentException("not a dynamic directory");
        if(nodes.equals(replacement.nodes))return false;
        for(String id:new ArrayList<>(nodes.keySet())){
            if(!replacement.nodes.containsKey(id)){
                live.remove(id);generations.remove(id);
            }
        }
        for(var entry:replacement.nodes.entrySet()){
            String id=entry.getKey();Node next=entry.getValue(),old=nodes.get(id);
            if(old==null||!sameRoute(old,next)){
                live.remove(id);
                generations.put(id,++nextGeneration);
            }
        }
        nodes.clear();nodes.putAll(replacement.nodes);
        samples.clear();
        return true;
    }
    private boolean sameRoute(Node a,Node b){
        return a.secret().equals(b.secret())&&a.config().relayURL().equals(b.config().relayURL())
                &&Objects.equals(a.config().stunURL(),b.config().stunURL());
    }
    long generation(String id){return generations.getOrDefault(id,0L);}
    boolean empty(){return nodes.isEmpty();}
    boolean authenticated(String id,String secret){
        Node n=id==null?null:nodes.get(id);
        return n!=null&&secret!=null&&MessageDigest.isEqual(n.secret().getBytes(StandardCharsets.US_ASCII),secret.getBytes(StandardCharsets.US_ASCII));
    }
    Limits heartbeat(String secret,Heartbeat h,long now){
        if(!dynamic||h==null||!authenticated(h.node(),secret)||h.instance()==null||
                !h.instance().matches("[a-f0-9]{32}")||h.stats()==null||h.stats().connections()<0||
                h.stats().connections()>256||h.stats().ingress_bytes()<0||h.stats().egress_bytes()<0||h.stats().dropped_packets()<0)return null;
        Live previous=live.put(h.node(),new Live(h.instance(),now,h.draining(),h.stats()));
        recordHistory(h,previous,now);
        Config c=nodes.get(h.node()).config();
        return new Limits(c.maxConnections(),c.bytesPerSecond());
    }
    private void recordHistory(Heartbeat h,Live previous,long now){
        long minute=Math.floorDiv(now,60)*60;
        Stats stats=h.stats();
        boolean continuous=previous!=null&&previous.instance().equals(h.instance())&&previous.seen()+TTL>now
                &&stats.ingress_bytes()>=previous.stats().ingress_bytes()
                &&stats.egress_bytes()>=previous.stats().egress_bytes()
                &&stats.dropped_packets()>=previous.stats().dropped_packets();
        long in=continuous?stats.ingress_bytes()-previous.stats().ingress_bytes():0;
        long out=continuous?stats.egress_bytes()-previous.stats().egress_bytes():0;
        long dropped=continuous?stats.dropped_packets()-previous.stats().dropped_packets():0;
        var series=history.computeIfAbsent(h.node(),ignored->new TreeMap<>());
        var old=series.getOrDefault(minute,new Minute(minute,0,0,0,0,0,0));
        series.put(minute,new Minute(minute,Math.max(old.peakConnections(),stats.connections()),
                saturatedAdd(old.ingressBytes(),in),saturatedAdd(old.egressBytes(),out),saturatedAdd(old.droppedPackets(),dropped),
                old.samples()+1,old.discontinuities()+(continuous?0:1)));
        pruneHistory(now);
    }
    private static long saturatedAdd(long a,long b){return Long.MAX_VALUE-a<b?Long.MAX_VALUE:a+b;}
    private void pruneHistory(long now){
        long minute=Math.floorDiv(now,60)*60;
        if(minute==lastHistoryPrune)return;
        lastHistoryPrune=minute;
        long oldest=minute-(retentionMinutes-1L)*60;
        var iterator=history.entrySet().iterator();
        while(iterator.hasNext()){
            var entry=iterator.next();
            entry.getValue().headMap(oldest,false).clear();
            if(entry.getValue().isEmpty()&&!nodes.containsKey(entry.getKey()))iterator.remove();
        }
    }
    History history(String node,long now){
        if(!nodes.containsKey(node))throw new IllegalArgumentException("unknown relay node");
        pruneHistory(now);
        var values=history.get(node);
        return new History(node,retentionMinutes,values==null?List.of():List.copyOf(values.values()));
    }

    private boolean healthy(Node n,long now){
        Live l=live.get(n.config().id());
        return n.legacy()||l!=null&&l.seen()+TTL>now&&!l.draining();
    }
    private int connections(Node n){
        Live l=live.get(n.config().id());
        return l==null?0:l.stats().connections();
    }
    Config config(String id){Node n=nodes.get(id);return n==null?null:n.config();}
    List<Candidate> candidates(long now){
        if(!dynamic)return List.of();
        return nodes.values().stream().filter(n->healthy(n,now))
                .map(n->new Candidate(n.config().id(),n.config().region(),n.config().relayURL())).toList();
    }
    List<PublicStatus> status(long now){
        return nodes.values().stream().map(n->{
            Live l=live.get(n.config().id());
            String state=n.legacy()?"static":l==null||l.seen()+TTL<=now?"offline":l.draining()?"draining":"online";
            return new PublicStatus(n.config().id(),n.config().region(),state,l==null?0:l.seen(),l==null?null:l.stats(),
                    n.config().maxConnections(),n.config().bytesPerSecond());
        }).toList();
    }
    void report(String session,Map<String,Integer> metrics,long now){
        if(!dynamic||metrics==null||metrics.isEmpty())return;
        Map<String,Integer> copy=new HashMap<>();
        for(var entry:metrics.entrySet()){
            if(entry.getKey()==null||entry.getValue()==null||entry.getValue() < -1||entry.getValue()>5000)
                throw new IllegalArgumentException("invalid relay probe measurement");
            if(nodes.containsKey(entry.getKey()))copy.put(entry.getKey(),entry.getValue());
        }
        samples.put(session,new Sample(now,Map.copyOf(copy)));
    }
    void prune(long now){samples.values().removeIf(s->s.seen()+PROBE_TTL<=now);pruneHistory(now);}
    private Double score(Node n,List<String> sessions,long now){
        if(sessions.isEmpty())return null;
        double total=0,worst=0;
        for(String session:sessions){
            Sample s=samples.get(session);
            Integer r=s==null||s.seen()+PROBE_TTL<=now?null:s.measurements().get(n.config().id());
            if(r==null)return null;
            double value=r<0?5000:r;
            total+=value;worst=Math.max(worst,value);
        }
        // Minimize the slowest player's path first, then mean latency and node load.
        return worst+total/sessions.size()+50.0*connections(n)/n.config().maxConnections();
    }
    String select(String current,long changedAt,List<String> sessions,boolean waiting,long now){
        Node previous=current==null?null:nodes.get(current);
        boolean keep=previous!=null&&healthy(previous,now);
        List<Node> eligible=nodes.values().stream().filter(n->healthy(n,now))
                .filter(n->n==previous||connections(n)<n.config().maxConnections()).toList();
        if(eligible.isEmpty())return null;
        Comparator<Node> fallback=Comparator.comparingInt((Node n)->n.config().priority())
                .thenComparingDouble(n->(double)connections(n)/n.config().maxConnections()).thenComparing(n->n.config().id());
        Node best=eligible.stream().min(fallback).orElseThrow();
        // Only compare measurements when all healthy candidates have fresh reports
        // from all active room devices. Missing samples are not zero latency.
        boolean measured=dynamic&&!sessions.isEmpty()&&eligible.stream().allMatch(n->score(n,sessions,now)!=null);
        if(measured)best=eligible.stream().min(Comparator.comparingDouble((Node n)->score(n,sessions,now)).thenComparing(fallback)).orElseThrow();
        if(!keep)return best.config().id();
        if(best==previous||!measured)return current;
        double before=score(previous,sessions,now),after=score(best,sessions,now);
        // A reported unreachable selected node can fail over even during a match;
        // routine quality-based migrations occur only while waiting and after cooldown.
        if(before>=5000&&after<5000&&now-changedAt>=5)return best.config().id();
        if(waiting&&now-changedAt>=SWITCH_COOLDOWN&&before-after>=Math.max(25,before*0.20))return best.config().id();
        return current;
    }

    static boolean validURL(String value){
        try{URI u=URI.create(value);return "wss".equals(u.getScheme())&&u.getHost()!=null&&"/relay/v1".equals(u.getPath())
                &&u.getRawUserInfo()==null&&u.getQuery()==null&&u.getFragment()==null&&u.getPort()!=0;}catch(Exception e){return false;}
    }
    static boolean validStun(String value){
        if(value==null||!value.matches("stun:[A-Za-z0-9.\\-]+:[0-9]{1,5}"))return false;
        int p=Integer.parseInt(value.substring(value.lastIndexOf(':')+1));return p>0&&p<=65535;
    }
}
