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

    private RelayDirectory directory() throws Exception {
        ObjectMapper json=new ObjectMapper();
        Path sa=dir.resolve("a.secret"),sb=dir.resolve("b.secret");
        Files.createFile(sa,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.createFile(sb,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
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
}
