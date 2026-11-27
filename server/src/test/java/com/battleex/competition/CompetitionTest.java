package com.battleex.competition;

import com.battleex.platform.Platform.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompetitionTest {
    static User user(String name){return new User(UUID.randomUUID(),name);}
    @Test void rankedClosedByDefaultAndGameIsolation(){
        Competition c=new Competition(ResultAuthority.disabled());
        var a=user("a");var b=user("b");var d=user("d");
        assertThrows(IllegalStateException.class,()->c.enqueue(a,"cs16",Competition.Mode.RANKED,0));
        c.enqueue(a,"cs16",Competition.Mode.CASUAL,1);c.enqueue(b,"warcraft3",Competition.Mode.CASUAL,2);
        assertNull(c.nextPair(3));c.enqueue(d,"cs16",Competition.Mode.CASUAL,4);
        var p=c.nextPair(4);assertEquals(a,p.first().user());assertEquals(d,p.second().user());
        var m=c.assign(p,UUID.randomUUID(),4);assertNotNull(c.active(a.id()));
        c.end(m.roomId(),true,5);assertNull(c.active(d.id()));
        assertEquals(Competition.State.CANCELLED,c.history(a.id(),0,10).entries().getFirst().state());
        assertEquals(0,c.leaderboard("cs16",0,10).total());
        assertNotNull(c.view(b.id()).ticket());
    }
    @Test void rankedWindowAndPendingExpiration(){
        // Test configuration and synthetic settlement objects only: HTTP
        // signature verification is independently covered by integration.
        var authority=new ResultAuthority("01".repeat(32),"cs16",new ObjectMapper());
        var c=new Competition(authority);var a=user("a");var b=user("b");
        var ratings=List.of(new Competition.Rating("cs16",a,1700,0,0,0,0),new Competition.Rating("cs16",b,1500,0,0,0,0));
        c.importState(new Competition.Dump(List.of(),List.of(),ratings));
        c.enqueue(a,"cs16",Competition.Mode.RANKED,0);c.enqueue(b,"cs16",Competition.Mode.RANKED,60);
        assertNull(c.nextPair(119));var pair=c.nextPair(120);assertNotNull(pair);
        var m=c.assign(pair,UUID.randomUUID(),120);c.start(m.roomId(),121);c.end(m.roomId(),false,122);
        assertEquals(Competition.State.AWAITING_RESULT,c.view(a.id()).awaitingResult().state());
        assertThrows(IllegalStateException.class,()->c.enqueue(a,"cs16",Competition.Mode.RANKED,123));
        assertFalse(c.expire(721));assertTrue(c.expire(722));assertNull(c.view(a.id()).awaitingResult());
        assertEquals(Competition.State.NO_RESULT,c.history(a.id(),0,10).entries().getFirst().state());
        assertEquals(1700,c.leaderboard("cs16",0,10).entries().getFirst().rating().points());
    }
}
