package com.battleex.moderation;

import com.battleex.competition.Competition;
import com.battleex.competition.ResultAuthority;
import com.battleex.platform.Platform.User;
import org.junit.jupiter.api.Test;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModerationTest {
    private User user(String name){return new User(UUID.randomUUID(),name);}
    @Test void manualExpiryAndAuditAreIndependentOfResults()throws Exception{
        var a=user("a");var b=user("b");var operator=user("operator");
        var c=new Competition(ResultAuthority.disabled());
        c.enqueue(a,"cs16",Competition.Mode.CASUAL,1);c.enqueue(b,"cs16",Competition.Mode.CASUAL,1);
        var m=c.assign(c.nextPair(1),UUID.randomUUID(),1);c.start(m.roomId(),2);
        m=c.participantMatch(a.id(),m.id());
        byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);
        String key=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.US_ASCII)));
        Moderation moderation=new Moderation(digest);
        assertThrows(SecurityException.class,()->moderation.authorize(null));
        moderation.authorize(key);
        var report=moderation.file(m,a,new Moderation.ReportInput(m.id(),b.id(),Moderation.Reason.CHEATING,"Fixture",null),3);
        assertNull(moderation.activeSuspension(b.id(),3));
        var request=new Moderation.ReviewInput(Moderation.Decision.SUSPEND,"Human fixture review",100L);
        assertThrows(SecurityException.class,()->moderation.review(report.id(),a,request,4));
        var decision=moderation.review(report.id(),operator,request,4);
        assertNotNull(moderation.activeSuspension(b.id(),99));
        assertNull(moderation.activeSuspension(b.id(),100));
        assertEquals(decision,moderation.review(report.id(),operator,request,5));
        assertThrows(IllegalStateException.class,()->moderation.review(report.id(),operator,new Moderation.ReviewInput(Moderation.Decision.DISMISS,"different decision",null),6));
        var restored=new Moderation(digest);restored.importState(moderation.exportState(),c);
        assertNotNull(restored.activeSuspension(b.id(),5));
        var revoked=restored.revoke(decision.suspensionId(),operator,"Reversed",6);
        assertEquals(6L,revoked.revokedAt());assertNull(restored.activeSuspension(b.id(),7));
        assertEquals(0,c.leaderboard("cs16",0,10).total());
    }
}
