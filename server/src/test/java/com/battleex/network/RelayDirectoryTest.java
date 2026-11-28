package com.battleex.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class RelayDirectoryTest {
    @TempDir Path dir;
    private final String a=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
    private final String b=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
    private final String instance=UUID.randomUUID().toString().replace("-","");

    // Test fixtures must use permissions supported by the host filesystem.
    // POSIX hosts still create private files atomically; Windows uses its
    // normal temporary-directory ACL. These are random, disposable test values.
    private static void createSecretFixture(Path path)throws Exception{
        if(Files.getFileStore(path.getParent()).supportsFileAttributeView("posix"))
            Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        else Files.createFile(path);
    }

    private RelayDirectory directory() throws Exception {
        ObjectMapper json=new ObjectMapper();
        Path sa=dir.resolve("a.secret"),sb=dir.resolve("b.secret");
        createSecretFixture(sa);createSecretFixture(sb);
        Files.writeString(sa,a);Files.writeString(sb,b);
        Path config=dir.resolve("nodes.json");
        json.writeValue(config.toFile(),List.of(
                new RelayDirectory.Config("a","East","wss://a.example/relay/v1","stun:a.example:3478",sa.toString(),0,256,1_000_000),
                new RelayDirectory.Config("b","West","wss://b.example/relay/v1","stun:b.example:3478",sb.toString(),1,256,1_000_000)));
        return new RelayDirectory(json,config.toString(),true,"unused","","","");
    }
    private RelayDirectory.Heartbeat heartbeat(String node,boolean drain,int connections){
        return new RelayDirectory.Heartbeat(node,instance,drain,new RelayDirectory.Stats(connections,100,200,0));
    }
    @Test void heartbeatExpiryAndDrain()throws Exception{
        RelayDirectory d=directory();
        assertNull(d.select(null,0,List.of("one"),true,100));
        assertNotNull(d.heartbeat(a,heartbeat("a",false,0),100));
        assertEquals("a",d.select(null,0,List.of("one"),true,100));
        assertNotNull(d.heartbeat(b,heartbeat("b",false,0),105));
        assertEquals("b",d.select("a",100,List.of("one"),false,110));
        assertNotNull(d.heartbeat(b,heartbeat("b",true,0),111));
        assertNull(d.select("b",110,List.of("one"),false,111));
        assertEquals("draining",d.status(111).stream().filter(s->s.id().equals("b")).findFirst().orElseThrow().state());
    }
    @Test void measuredSelectionAndHysteresis()throws Exception{
        RelayDirectory d=directory();
        d.heartbeat(a,heartbeat("a",false,0),100);d.heartbeat(b,heartbeat("b",false,0),100);
        d.report("one",Map.of("a",180,"b",20),100);
        assertEquals("a",d.select(null,0,List.of("one","two"),true,100),"unknown is not zero RTT");
        d.report("two",Map.of("a",150,"b",30),100);
        assertEquals("b",d.select(null,0,List.of("one","two"),true,100));
        assertEquals("a",d.select("a",95,List.of("one","two"),true,100),"cooldown");
        assertEquals("a",d.select("a",60,List.of("one","two"),false,100),"no routine switch mid-match");
        assertEquals("b",d.select("a",60,List.of("one","two"),true,100));
        d.report("one",Map.of("a",-1,"b",20),101);
        assertEquals("b",d.select("a",90,List.of("one","two"),false,101),"unreachable failover");
        d.heartbeat(a,heartbeat("a",false,256),102);d.heartbeat(b,heartbeat("b",false,0),102);
        assertEquals("b",d.select(null,0,List.of("unmeasured"),true,102),"new room avoids full node");
    }

    @Test void historyTracksObservedDeltasAndDiscontinuities()throws Exception{
        RelayDirectory d=directory();
        d.heartbeat(a,new RelayDirectory.Heartbeat("a",instance,false,new RelayDirectory.Stats(1,100,200,1)),120);
        d.heartbeat(a,new RelayDirectory.Heartbeat("a",instance,false,new RelayDirectory.Stats(5,140,260,3)),122);
        var row=d.history("a",123).minutes().getFirst();
        assertEquals(5,row.peakConnections());
        assertEquals(40,row.ingressBytes());
        assertEquals(60,row.egressBytes());
        assertEquals(2,row.droppedPackets());
        assertEquals(2,row.samples());
        assertEquals(1,row.discontinuities());
        String nextInstance=UUID.randomUUID().toString().replace("-","");
        d.heartbeat(a,new RelayDirectory.Heartbeat("a",nextInstance,false,new RelayDirectory.Stats(2,8,10,0)),124);
        row=d.history("a",125).minutes().getFirst();
        assertEquals(40,row.ingressBytes(),"restart counters must not double-count");
        assertEquals(2,row.discontinuities());
        assertTrue(d.history("a",120+1440L*60).minutes().isEmpty());
    }
    @Test void reservationsAndRelayGenerationFencing()throws Exception{
        var d=directory();d.configureFence(true);
        d.heartbeat(a,heartbeat("a",false,0),100);d.heartbeat(b,heartbeat("b",false,0),100);
        assertEquals("b",d.select(null,0,List.of("one","two"),true,100,Map.of("a",256),2,0,true));
        assertNull(d.select(null,0,List.of("one","two"),true,100,Map.of("a",256,"b",256),2,0,true));
        assertEquals("a",d.select("a",100,List.of("one","two"),true,100,Map.of("a",254),2,2,true));
        assertTrue(d.instanceAllowed("a",instance,101));
        String other=UUID.randomUUID().toString().replace("-","");
        long generation=d.generation("a");
        var hb=new RelayDirectory.Heartbeat("a",other,false,new RelayDirectory.Stats(0,0,0,0));
        assertNull(d.heartbeat(a,hb,102));assertFalse(d.instanceAllowed("a",other,102));
        assertNotNull(d.heartbeat(a,hb,110));assertTrue(d.generation("a")>generation);
        assertFalse(d.instanceAllowed("a",instance,111));assertTrue(d.instanceAllowed("a",other,111));
        assertEquals("10.160.0.1",NetworkAuthority.ip(0,1));
        assertEquals("10.175.255.254",NetworkAuthority.ip(4095,254));
        assertEquals("10.176.0.1",NetworkAuthority.ip(4096,1));
        assertEquals("10.128.0.1",NetworkAuthority.ip(8192,1));
        assertEquals("10.159.255.254",NetworkAuthority.ip(16383,254));
    }
    @Test void stableSnapshotReuseIsConservative()throws Exception{
        var d=directory();
        var ok=new RelayDirectory.Live(instance,100,false,new RelayDirectory.Stats(0,0,0,0));
        var drain=new RelayDirectory.Live(instance,100,true,new RelayDirectory.Stats(0,0,0,0));
        Map<String,RelayDirectory.Live> live=Map.of("a",ok,"b",ok);
        List<String> sessions=List.of("one","two");
        var better=new RelayDirectory.Sample(100,Map.of("a",180,"b",20));
        var probes=Map.of("one",better,"two",better);
        assertTrue(d.mayReuse("a",95,sessions,true,100,live,probes,true,2),"cooldown");
        assertTrue(d.mayReuse("a",60,sessions,false,100,live,probes,true,2),"routine migration does not interrupt a running match");
        assertFalse(d.mayReuse("a",60,sessions,true,100,live,probes,true,2),"possible better path returns to capacity transaction");
        assertFalse(d.mayReuse("a",60,sessions,true,110,live,probes,true,2),"stale heartbeat");
        assertFalse(d.mayReuse("a",95,sessions,true,100,Map.of("a",drain,"b",ok),probes,true,2),"drain");
        assertFalse(d.mayReuse("a",95,sessions,true,100,live,probes,true,257),"reservation exceeds node capacity");
        var unreachable=new RelayDirectory.Sample(100,Map.of("a",-1,"b",20));
        assertFalse(d.mayReuse("a",90,sessions,false,100,live,Map.of("one",unreachable,"two",better),true,2),"unreachable path falls back mid-match");
        assertTrue(d.mayReuse("a",60,sessions,true,100,live,Map.of(),true,2),"missing probes are not assumed faster");
        assertThrows(IllegalArgumentException.class,()->d.validatedSample(Map.of("a",-2),100));
    }
}
