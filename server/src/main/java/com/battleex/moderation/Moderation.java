package com.battleex.moderation;

import com.battleex.competition.Competition;
import com.battleex.platform.Platform.User;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Manual, audited moderation. Reports alone never alter rating or access. */
public final class Moderation {
    public enum Reason { CHEATING, ABUSIVE_BEHAVIOR, DISCONNECTION_ABUSE, OTHER }
    public enum Decision { DISMISS, WARN, SUSPEND }
    public enum Status { OPEN, DISMISSED, WARNED, SUSPENDED }
    public record ReportInput(UUID matchId,UUID targetId,Reason reason,String details,String replaySha256) {}
    public record Report(UUID id,UUID matchId,User reporter,User target,Reason reason,String details,String replaySha256,
                         long createdAt,Status status,User reviewer,Long reviewedAt,String resolution,UUID suspensionId) {}
    public record ReviewInput(Decision decision,String resolution,Long until) {}
    public record Suspension(UUID id,User target,User reviewer,UUID reportId,String reason,long createdAt,Long until,Long revokedAt,User revokedBy,String revokeReason) {
        boolean active(long now){return revokedAt==null&&(until==null||until>now);}
    }
    public record Warning(UUID id,UUID matchId,String message,long at) {}
    public record Page<T>(int offset,int total,List<T> entries) {}
    public record Dump(List<Report> reports,List<Suspension> suspensions) {}
    private final Map<UUID,Report> reports=new HashMap<>();
    private final Map<UUID,Suspension> suspensions=new HashMap<>();
    private final byte[] keyDigest;
    public Moderation(String digest){
        if(digest==null||digest.isBlank()){keyDigest=null;return;}
        if(!digest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("moderator key digest must be 64 lowercase hex characters");
        keyDigest=HexFormat.of().parseHex(digest);
    }
    public boolean configured(){return keyDigest!=null;}
    public String fingerprint(){return keyDigest==null?"":":moderation-v1:"+HexFormat.of().formatHex(keyDigest);}
    public void authorize(String supplied){
        if(keyDigest==null||supplied==null||!supplied.matches("[A-Za-z0-9_-]{43,128}"))throw new SecurityException("moderator credential required");
        try{
            byte[] actual=MessageDigest.getInstance("SHA-256").digest(supplied.getBytes(StandardCharsets.US_ASCII));
            if(!MessageDigest.isEqual(keyDigest,actual))throw new SecurityException("moderator credential rejected");
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static String text(String value,String field){
        if(value==null||value.isBlank()||value.length()>4000||value.chars().anyMatch(c->c<32&&c!='\n'&&c!='\r'&&c!='\t'))
            throw new IllegalArgumentException(field+" required (up to 4000 characters)");
        return value.strip();
    }
    public Report file(Competition.Match match,User actor,ReportInput input,long now){
        if(match.startedAt()==null||input.reason()==null||input.targetId()==null||input.targetId().equals(actor.id()))
            throw new IllegalArgumentException("a played match and another participant are required");
        User target=match.players().stream().filter(p->p.id().equals(input.targetId())).findFirst()
                .orElseThrow(()->new SecurityException("target is not in the match roster"));
        String details=text(input.details(),"report details"),hash=input.replaySha256();
        if(hash!=null&&!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("invalid replay fingerprint");
        for(Report r:reports.values())if(r.matchId().equals(match.id())&&r.reporter().id().equals(actor.id())&&r.target().id().equals(target.id())){
            if(r.reason()==input.reason()&&r.details().equals(details)&&Objects.equals(r.replaySha256(),hash))return r;
            throw new IllegalStateException("a report for this participant and match already exists");
        }
        Report r=new Report(UUID.randomUUID(),match.id(),actor,target,input.reason(),details,hash,now,Status.OPEN,null,null,null,null);
        reports.put(r.id(),r);return r;
    }
    public Report review(UUID id,User actor,ReviewInput input,long now){
        Report r=reports.get(id);
        if(r==null)throw new IllegalArgumentException("report not found");
        if(r.reporter().id().equals(actor.id())||r.target().id().equals(actor.id()))throw new SecurityException("a reviewer cannot review their own match report");
        if(input==null||input.decision()==null)throw new IllegalArgumentException("decision required");
        String resolution=text(input.resolution(),"resolution");
        if(input.decision()!=Decision.SUSPEND&&input.until()!=null)throw new IllegalArgumentException("expiry is only for suspension");
        if(input.until()!=null&&input.until()<=now)throw new IllegalArgumentException("suspension expiry must be in the future");
        Status status=switch(input.decision()){case DISMISS->Status.DISMISSED;case WARN->Status.WARNED;case SUSPEND->Status.SUSPENDED;};
        if(r.status()!=Status.OPEN){
            if(r.status()==status&&Objects.equals(r.resolution(),resolution)&&r.reviewer().id().equals(actor.id())){
                if(status!=Status.SUSPENDED||Objects.equals(suspensions.get(r.suspensionId()).until(),input.until()))return r;
            }
            throw new IllegalStateException("report already reviewed");
        }
        UUID suspensionId=null;
        if(status==Status.SUSPENDED){
            Suspension existing=activeSuspension(r.target().id(),now);
            if(existing!=null)throw new IllegalStateException("target already suspended; review or revoke existing suspension first");
            suspensionId=UUID.randomUUID();
            suspensions.put(suspensionId,new Suspension(suspensionId,r.target(),actor,r.id(),resolution,now,input.until(),null,null,null));
        }
        Report result=new Report(r.id(),r.matchId(),r.reporter(),r.target(),r.reason(),r.details(),r.replaySha256(),r.createdAt(),status,actor,now,resolution,suspensionId);
        reports.put(r.id(),result);return result;
    }
    public Suspension revoke(UUID id,User actor,String reason,long now){
        Suspension s=suspensions.get(id);if(s==null)throw new IllegalArgumentException("suspension not found");
        String why=text(reason,"revocation reason");
        if(s.revokedAt()!=null)return s;
        Suspension next=new Suspension(s.id(),s.target(),s.reviewer(),s.reportId(),s.reason(),s.createdAt(),s.until(),now,actor,why);
        suspensions.put(id,next);return next;
    }
    public Suspension activeSuspension(UUID user,long now){
        return suspensions.values().stream().filter(s->s.target().id().equals(user)&&s.active(now))
                .max(Comparator.comparingLong(Suspension::createdAt).thenComparing(Suspension::id)).orElse(null);
    }
    public List<Warning> warnings(UUID actor){
        return reports.values().stream().filter(r->r.status()==Status.WARNED&&r.target().id().equals(actor))
                .map(r->new Warning(r.id(),r.matchId(),r.resolution(),r.reviewedAt())).sorted(Comparator.comparingLong(Warning::at).reversed().thenComparing(Warning::id)).toList();
    }
    private static <T> Page<T> page(List<T> rows,int offset,int limit){
        if(offset<0||limit<1||limit>200)throw new IllegalArgumentException("invalid pagination (limit 1–200)");
        int from=Math.min(offset,rows.size()),to=(int)Math.min((long)from+limit,rows.size());
        return new Page<>(offset,rows.size(),List.copyOf(rows.subList(from,to)));
    }
    public Page<Report> own(UUID user,int offset,int limit){return page(reportRows().stream().filter(r->r.reporter().id().equals(user)).toList(),offset,limit);}
    private List<Report> reportRows(){return reports.values().stream().sorted(Comparator.comparingLong(Report::createdAt).reversed().thenComparing(Report::id)).toList();}
    public Page<Report> reports(Status status,int offset,int limit){return page(reportRows().stream().filter(r->status==null||r.status()==status).toList(),offset,limit);}
    public Page<Suspension> suspensions(int offset,int limit){
        return page(suspensions.values().stream().sorted(Comparator.comparingLong(Suspension::createdAt).reversed().thenComparing(Suspension::id)).toList(),offset,limit);
    }
    public Dump exportState(){
        return new Dump(reports.values().stream().sorted(Comparator.comparing(Report::id)).toList(),suspensions.values().stream().sorted(Comparator.comparing(Suspension::id)).toList());
    }
    public void importState(Dump dump,Competition competition){
        reports.clear();suspensions.clear();if(dump==null)return;
        for(Report r:dump.reports()){
            competition.participantMatch(r.reporter().id(),r.matchId());competition.participantMatch(r.target().id(),r.matchId());
            if(reports.put(r.id(),r)!=null)throw new IllegalStateException("duplicate persisted report");
        }
        for(Suspension s:dump.suspensions()){
            Report r=reports.get(s.reportId());
            if(r==null||!Objects.equals(r.suspensionId(),s.id())||!r.target().id().equals(s.target().id())||
               suspensions.put(s.id(),s)!=null)throw new IllegalStateException("invalid persisted suspension");
        }
        for(Report r:reports.values())if(r.status()==Status.SUSPENDED&&!suspensions.containsKey(r.suspensionId()))throw new IllegalStateException("missing persisted suspension");
    }
}
