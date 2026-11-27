package com.battleex.competition;

import com.battleex.platform.Platform.User;
import java.util.*;

/** Participant-scoped replay catalogue and mutually consented match status. */
public final class MatchArchive {
    public record ReplayInput(String filename,String sha256,long bytes) {}
    public record Replay(UUID id,UUID matchId,User uploader,String gameId,String filename,String sha256,long bytes,long createdAt) {}
    public record Consent(UUID matchId,UUID userId) {}
    public record Live(UUID matchId,String gameId,Competition.Mode mode,List<User> players,long startedAt) {}
    public record Detail(Competition.Match match,List<Replay> replays,boolean sharingStatus,boolean publicStatus) {}
    public record Dump(List<Replay> replays,List<Consent> consents) {}
    private final Map<UUID,Replay> replays=new HashMap<>();
    private final Map<UUID,Set<UUID>> consents=new HashMap<>();

    public Replay add(Competition.Match match,User actor,ReplayInput input,long now){
        if(match.startedAt()==null||match.endedAt()==null)throw new IllegalStateException("only concluded played matches accept replay records");
        if(input==null||input.filename()==null||input.filename().isBlank()||input.filename().length()>255||
           input.filename().equals(".")||input.filename().equals("..")||input.filename().chars().anyMatch(c->c<32||c==127||c=='/'||c=='\\'||c==':')||
           input.sha256()==null||!input.sha256().matches("[a-f0-9]{64}")||input.bytes()<=0)
            throw new IllegalArgumentException("invalid replay metadata");
        String name=input.filename().toLowerCase(Locale.ROOT);
        if(!(match.gameId().equals("warcraft3")&&name.endsWith(".w3g"))&&
           !(match.gameId().equals("cs16")&&name.endsWith(".dem")))
            throw new IllegalArgumentException("no supported replay format for this game");
        for(Replay r:replays.values())if(r.matchId().equals(match.id())&&r.uploader().id().equals(actor.id())&&r.sha256().equals(input.sha256())){
            if(r.bytes()!=input.bytes()||!r.filename().equals(input.filename()))throw new IllegalStateException("replay digest already registered with different metadata");
            return r;
        }
        Replay r=new Replay(UUID.randomUUID(),match.id(),actor,match.gameId(),input.filename(),input.sha256(),input.bytes(),now);
        replays.put(r.id(),r);return r;
    }
    public void remove(Competition.Match match,UUID actor,UUID replay){
        Replay r=replays.get(replay);
        if(r==null||!r.matchId().equals(match.id())||!r.uploader().id().equals(actor))
            throw new SecurityException("only the replay uploader may remove this record");
        replays.remove(replay);
    }
    public Detail detail(Competition.Match m,UUID actor){
        Set<UUID> approved=consents.getOrDefault(m.id(),Set.of());
        return new Detail(m,replays.values().stream().filter(r->r.matchId().equals(m.id()))
                .sorted(Comparator.comparingLong(Replay::createdAt).thenComparing(Replay::id)).toList(),
                approved.contains(actor),isPublic(m));
    }
    private boolean isPublic(Competition.Match m){
        return m.state()==Competition.State.RUNNING&&m.players().stream()
                .allMatch(u->consents.getOrDefault(m.id(),Set.of()).contains(u.id()));
    }
    public Detail consent(Competition.Match m,UUID actor,boolean share){
        if(m.state()!=Competition.State.RUNNING)throw new IllegalStateException("only running matches can share live status");
        if(share)consents.computeIfAbsent(m.id(),id->new HashSet<>()).add(actor);
        else{
            var current=consents.get(m.id());
            if(current!=null){current.remove(actor);if(current.isEmpty())consents.remove(m.id());}
        }
        return detail(m,actor);
    }
    public List<Live> live(Collection<Competition.Match> running){
        return running.stream().filter(this::isPublic)
                .map(m->new Live(m.id(),m.gameId(),m.mode(),m.players(),m.startedAt()))
                .sorted(Comparator.comparingLong(Live::startedAt).thenComparing(Live::matchId)).toList();
    }
    public void close(UUID match){consents.remove(match);}
    public Dump exportState(){
        var approvals=new ArrayList<Consent>();
        consents.forEach((match,users)->users.forEach(u->approvals.add(new Consent(match,u))));
        approvals.sort(Comparator.comparing(Consent::matchId).thenComparing(Consent::userId));
        return new Dump(replays.values().stream().sorted(Comparator.comparing(Replay::id)).toList(),List.copyOf(approvals));
    }
    public void importState(Dump dump,Competition competition){
        replays.clear();consents.clear();if(dump==null)return;
        for(Replay r:dump.replays()){
            Competition.Match m=competition.participantMatch(r.uploader().id(),r.matchId());
            if(!m.gameId().equals(r.gameId())||replays.put(r.id(),r)!=null)throw new IllegalStateException("invalid persisted replay");
        }
        for(Consent c:dump.consents()){
            Competition.Match m=competition.participantMatch(c.userId(),c.matchId());
            if(m.state()!=Competition.State.RUNNING)throw new IllegalStateException("invalid persisted audience consent");
            if(!consents.computeIfAbsent(c.matchId(),id->new HashSet<>()).add(c.userId()))throw new IllegalStateException("duplicate persisted consent");
        }
    }
}
