package com.battleex.competition;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Operator-configured external match-result issuer. Disabled by default. */
public final class ResultAuthority {
    public record SignedResult(String payload,String signature) {}
    public record Report(UUID matchId,String gameId,Competition.Outcome outcome,UUID winnerId,long issuedAt) {}
    public record Verified(Report report,String digest) {}
    private static final byte[] DOMAIN="battle-ex/competition-result/v1\0".getBytes(StandardCharsets.UTF_8);
    private final PublicKey key;
    private final List<String> games;
    private final String issuer;
    private final ObjectMapper json;
    public ResultAuthority(String hex,String gameList,ObjectMapper json){
        this.json=json;
        this.issuer=hex==null?"":hex.strip();
        var enabled=new TreeSet<String>();
        if(gameList!=null&&!gameList.isBlank())for(String game:gameList.split(",")){
            String id=game.strip();Competition.game(id);enabled.add(id);
        }
        games=List.copyOf(enabled);
        if(issuer.isEmpty()&&games.isEmpty()){key=null;return;}
        if(!issuer.matches("[0-9a-f]{64}")||issuer.equals("0".repeat(64))||games.isEmpty())
            throw new IllegalArgumentException("ranked result issuer and explicit game list required");
        try{
            byte[] prefix=HexFormat.of().parseHex("302a300506032b6570032100"),raw=HexFormat.of().parseHex(issuer);
            byte[] encoded=Arrays.copyOf(prefix,prefix.length+raw.length);
            System.arraycopy(raw,0,encoded,prefix.length,raw.length);
            key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        }catch(GeneralSecurityException e){throw new IllegalArgumentException("invalid result issuer",e);}
    }
    public static ResultAuthority disabled(){return new ResultAuthority("","",new ObjectMapper());}
    public List<String> games(){return games;}
    public boolean enabled(String game){return key!=null&&games.contains(game);}
    public String configurationFingerprint(){return key==null?"":":competition-v1:"+issuer+":"+String.join(",",games);}
    public Verified verify(SignedResult request){
        if(key==null)throw new SecurityException("trusted results are disabled");
        if(request==null||request.payload()==null||request.signature()==null||request.payload().length()>4096||request.signature().length()>128)
            throw new IllegalArgumentException("invalid signed result");
        try{
            byte[] raw=Base64.getUrlDecoder().decode(request.payload()),sig=Base64.getUrlDecoder().decode(request.signature());
            Signature verifier=Signature.getInstance("Ed25519");verifier.initVerify(key);verifier.update(DOMAIN);verifier.update(raw);
            if(sig.length!=64||!verifier.verify(sig))throw new SecurityException("result signature rejected");
            Report report=json.readerFor(Report.class).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(raw);
            if(report.matchId()==null||!enabled(report.gameId())||report.issuedAt()<=0)throw new IllegalArgumentException("invalid result binding");
            return new Verified(report,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        }catch(SecurityException|IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalArgumentException("malformed signed result");}
    }
}
