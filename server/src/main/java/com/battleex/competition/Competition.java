package com.battleex.competition;

import com.battleex.platform.Platform.User;
import java.time.Instant;
import java.util.*;

/**
 * Server-owned 1v1 queue, immutable match roster and result/rating ledger.
 * Called only inside Platform's authority transaction. Never accepts a
 * participant ID as the authenticated actor.
 */
public final class Competition {
    public static final Set<String> GAMES=Set.of("warcraft3","redalert2","cs16","custom");
    public static final long INITIAL_RATING=1500;
    private static final long RESULT_WAIT_SECONDS=600;
    public enum Mode { CASUAL, RANKED, TOURNAMENT }
    public enum State { WAITING, RUNNING, AWAITING_RESULT, FINISHED, CANCELLED, ABANDONED, NO_RESULT, RATED }
    public enum Outcome { WIN, DRAW }
    public record Ticket(User user,String gameId,Mode mode,long queuedAt) {}
    public record Rating(String gameId,User user,long points,long played,long wins,long draws,long losses) {}
    public record Change(UUID userId,long before,long after) {}
    public record Match(UUID id,UUID roomId,String gameId,Mode mode,List<User> players,State state,
                        long createdAt,Long startedAt,Long endedAt,Long resultDeadline,
                        Outcome outcome,UUID winnerId,String resultDigest,List<Change> ratingChanges) {
        public Match {
            players=List.copyOf(players);
            ratingChanges=ratingChanges==null?List.of():List.copyOf(ratingChanges);
            if(players.size()!=2||players.get(0).id().equals(players.get(1).id()))throw new IllegalArgumentException("invalid match roster");
        }
        boolean contains(UUID id){return players.stream().anyMatch(u->u.id().equals(id));}
        boolean active(){return state==State.WAITING||state==State.RUNNING;}
        boolean unresolvedRanked(){return mode==Mode.RANKED&&(active()||state==State.AWAITING_RESULT);}
    }
    public record View(Ticket ticket,Match match,Match awaitingResult,List<String> rankedGames) {}
    public record Standing(long rank,Rating rating) {}
    public record Leaderboard(String gameId,int offset,int total,List<Standing> entries) {}
    public record History(int offset,int total,List<Match> entries) {}
    public record Dump(List<Ticket> tickets,List<Match> matches,List<Rating> ratings) {}
    public record Pair(Ticket first,Ticket second) {}
    private final Map<UUID,Ticket> tickets=new HashMap<>();
    private final Map<UUID,Match> matches=new HashMap<>();
    private final Map<UUID,UUID> byRoom=new HashMap<>();
    private final Map<UUID,UUID> active=new HashMap<>();
    private final Map<UUID,UUID> pending=new HashMap<>();
    private final Map<String,Rating> ratings=new HashMap<>();
    private final ResultAuthority authority;

    public Competition(ResultAuthority authority){this.authority=Objects.requireNonNull(authority);}
    public ResultAuthority authority(){return authority;}
    public static void game(String id){
        if(!GAMES.contains(id==null?"":id))throw new IllegalArgumentException("unsupported matchmaking game");
    }
    public boolean queued(UUID user){return tickets.containsKey(user);}
    public Match forRoom(UUID room){UUID id=byRoom.get(room);return id==null?null:matches.get(id);}
    public Match active(UUID user){UUID id=active.get(user);return id==null?null:matches.get(id);}
    private Match awaiting(UUID user){UUID id=pending.get(user);return id==null?null:matches.get(id);}
    public View view(UUID user){return new View(tickets.get(user),active(user),awaiting(user),authority.games());}
    public void enqueue(User user,String gameId,Mode mode,long now){
        game(gameId);
        if(mode==null||mode==Mode.TOURNAMENT)throw new IllegalArgumentException("invalid matchmaking mode");
        if(active(user.id())!=null)throw new IllegalStateException("already in an assigned match");
        if(mode==Mode.RANKED){
            if(!authority.enabled(gameId))throw new IllegalStateException("ranked results are not configured for this game");
            if(awaiting(user.id())!=null)throw new IllegalStateException("previous ranked result is pending");
        }
        Ticket previous=tickets.get(user.id());
        if(previous!=null){
            if(previous.gameId().equals(gameId)&&previous.mode()==mode)return;
            throw new IllegalStateException("cancel the existing queue first");
        }
        tickets.put(user.id(),new Ticket(user,gameId,mode,now));
    }
    public boolean cancel(UUID user){return tickets.remove(user)!=null;}
    private static final Comparator<Ticket> ORDER=Comparator.comparingLong(Ticket::queuedAt).thenComparing(t->t.user().id());
    private static long window(long now,Ticket t){return 100+50*Math.max(0,(now-t.queuedAt())/30);}
    public Pair nextPair(long now){
        var ordered=tickets.values().stream().sorted(ORDER).toList();
        for(int i=0;i<ordered.size();i++){
            Ticket a=ordered.get(i);
            for(int j=i+1;j<ordered.size();j++){
                Ticket b=ordered.get(j);
                if(a.mode()!=b.mode()||!a.gameId().equals(b.gameId()))continue;
                if(a.mode()==Mode.RANKED){
                    long gap=Math.abs(points(a.gameId(),a.user().id())-points(b.gameId(),b.user().id()));
                    if(gap>Math.min(window(now,a),window(now,b)))continue;
                }
                return new Pair(a,b);
            }
        }
        return null;
    }
    public Match assign(Pair pair,UUID room,long now){
        Ticket a=pair.first(),b=pair.second();
        if(!Objects.equals(tickets.get(a.user().id()),a)||!Objects.equals(tickets.get(b.user().id()),b)||byRoom.containsKey(room))
            throw new IllegalStateException("matchmaking ticket changed");
        Match m=new Match(UUID.randomUUID(),room,a.gameId(),a.mode(),List.of(a.user(),b.user()),State.WAITING,
                now,null,null,null,null,null,null,List.of());
        tickets.remove(a.user().id());tickets.remove(b.user().id());
        put(m);
        return m;
    }
    public Match assignTournament(User first,User second,String gameId,UUID room,long now){
        game(gameId);
        if(first.id().equals(second.id())||active(first.id())!=null||active(second.id())!=null||queued(first.id())||queued(second.id())||byRoom.containsKey(room))
            throw new IllegalStateException("tournament entrant unavailable");
        Match m=new Match(UUID.randomUUID(),room,gameId,Mode.TOURNAMENT,List.of(first,second),State.WAITING,now,null,null,null,null,null,null,List.of());
        put(m);return m;
    }
    public long runningCount(){return matches.values().stream().filter(m->m.state()==State.RUNNING).count();}
    public int queuedCount(){return tickets.size();}
    private void put(Match m){
        Match old=matches.put(m.id(),m);
        if(old!=null){
            if(old.active()){
                byRoom.remove(old.roomId(),old.id());
                for(User u:old.players())active.remove(u.id(),old.id());
            }
            if(old.state()==State.AWAITING_RESULT)for(User u:old.players())pending.remove(u.id(),old.id());
        }
        if(m.active()){
            byRoom.put(m.roomId(),m.id());
            for(User u:m.players())active.put(u.id(),m.id());
        }
        if(m.state()==State.AWAITING_RESULT)for(User u:m.players())pending.put(u.id(),m.id());
    }
    private Match transition(Match m,State state,Long started,Long ended,Long deadline){
        Match next=new Match(m.id(),m.roomId(),m.gameId(),m.mode(),m.players(),state,m.createdAt(),started,ended,deadline,null,null,null,List.of());
        put(next);return next;
    }
    public void start(UUID room,long now){
        Match m=forRoom(room);
        if(m!=null){
            if(m.state()!=State.WAITING)throw new IllegalStateException("assigned match is not waiting");
            transition(m,State.RUNNING,now,null,null);
        }
    }
    public void end(UUID room,boolean departed,long now){
        Match m=forRoom(room);
        if(m==null)return;
        if(m.state()==State.WAITING){transition(m,State.CANCELLED,null,now,null);return;}
        if(m.state()!=State.RUNNING)throw new IllegalStateException("assigned match is not running");
        if(m.mode()==Mode.RANKED){
            transition(m,State.AWAITING_RESULT,m.startedAt(),now,now+RESULT_WAIT_SECONDS);
        }else transition(m,departed?State.ABANDONED:State.FINISHED,m.startedAt(),now,null);
    }
    public boolean expire(long now){
        boolean changed=false;
        for(Match m:new ArrayList<>(matches.values()))if(m.state()==State.AWAITING_RESULT&&m.resultDeadline()<=now){
            transition(m,State.NO_RESULT,m.startedAt(),m.endedAt(),null);changed=true;
        }
        return changed;
    }
    private static String key(String gameId,UUID user){return gameId+":"+user;}
    private long points(String gameId,UUID user){
        Rating r=ratings.get(key(gameId,user));return r==null?INITIAL_RATING:r.points();
    }
    private Rating rating(String gameId,User user){
        return ratings.getOrDefault(key(gameId,user.id()),new Rating(gameId,user,INITIAL_RATING,0,0,0,0));
    }
    private static Rating apply(Rating old,User user,long delta,Outcome outcome,UUID winner){
        boolean draw=outcome==Outcome.DRAW,win=!draw&&user.id().equals(winner);
        return new Rating(old.gameId(),user,old.points()+delta,old.played()+1,old.wins()+(win?1:0),
                old.draws()+(draw?1:0),old.losses()+(!draw&&!win?1:0));
    }

    // Signature verification is done before this method. Match IDs are
    // immutable, the game and winner are bound to the roster, and a replay
    // of the identical signed payload cannot apply Elo twice.
    public Match settle(ResultAuthority.Verified verified,long now){
        ResultAuthority.Report report=verified.report();
        Match m=matches.get(report.matchId());
        if(m==null||m.mode()!=Mode.RANKED||!m.gameId().equals(report.gameId())||!authority.enabled(m.gameId()))
            throw new SecurityException("result does not name an authorized ranked match");
        if(m.state()==State.RATED){
            if(Objects.equals(m.resultDigest(),verified.digest()))return m;
            throw new IllegalStateException("match already settled");
        }
        if(m.state()!=State.RUNNING&&m.state()!=State.AWAITING_RESULT)
            throw new IllegalStateException("match is not accepting results");
        if(m.state()==State.AWAITING_RESULT&&m.resultDeadline()<=now)throw new IllegalStateException("result deadline passed");
        if(report.issuedAt()<m.startedAt()||report.issuedAt()>now+5||report.issuedAt()<now-RESULT_WAIT_SECONDS)
            throw new IllegalArgumentException("result timestamp is outside the match window");
        if(report.outcome()==null||(report.outcome()==Outcome.DRAW&&report.winnerId()!=null)||
                (report.outcome()==Outcome.WIN&&(report.winnerId()==null||!m.contains(report.winnerId()))))
            throw new IllegalArgumentException("result winner does not match roster");
        User a=m.players().get(0),b=m.players().get(1);
        Rating x=rating(m.gameId(),a),y=rating(m.gameId(),b);
        double expected=1.0/(1.0+Math.pow(10.0,(y.points()-x.points())/400.0));
        double score=report.outcome()==Outcome.DRAW?0.5:a.id().equals(report.winnerId())?1:0;
        long delta=Math.round(32*(score-expected));
        Rating nx=apply(x,a,delta,report.outcome(),report.winnerId()),ny=apply(y,b,-delta,report.outcome(),report.winnerId());
        ratings.put(key(m.gameId(),a.id()),nx);ratings.put(key(m.gameId(),b.id()),ny);
        Match result=new Match(m.id(),m.roomId(),m.gameId(),m.mode(),m.players(),State.RATED,m.createdAt(),m.startedAt(),
                m.endedAt()==null?now:m.endedAt(),null,report.outcome(),report.winnerId(),verified.digest(),
                List.of(new Change(a.id(),x.points(),nx.points()),new Change(b.id(),y.points(),ny.points())));
        put(result);return result;
    }
    private static void page(int offset,int limit){
        if(offset<0||limit<1||limit>200)throw new IllegalArgumentException("invalid pagination (limit 1–200)");
    }
    public Leaderboard leaderboard(String gameId,int offset,int limit){
        game(gameId);page(offset,limit);
        var rows=ratings.values().stream().filter(r->r.gameId().equals(gameId))
                .sorted(Comparator.comparingLong(Rating::points).reversed().thenComparing(Comparator.comparingLong(Rating::wins).reversed())
                        .thenComparing(r->r.user().id())).toList();
        var result=new ArrayList<Standing>();int from=Math.min(offset,rows.size()),to=(int)Math.min((long)from+limit,rows.size());
        for(int i=from;i<to;i++)result.add(new Standing(i+1L,rows.get(i)));
        return new Leaderboard(gameId,offset,rows.size(),List.copyOf(result));
    }
    public Match participantMatch(UUID user,UUID matchId){
        Match m=matches.get(matchId);
        if(m==null||!m.contains(user))throw new SecurityException("match participant required");
        return m;
    }
    public List<Match> runningMatches(){return matches.values().stream().filter(m->m.state()==State.RUNNING).toList();}
    public History history(UUID user,int offset,int limit){
        page(offset,limit);
        var rows=matches.values().stream().filter(m->m.contains(user)).sorted(Comparator.comparingLong(Match::createdAt).reversed().thenComparing(Match::id)).toList();
        int from=Math.min(offset,rows.size()),to=(int)Math.min((long)from+limit,rows.size());
        return new History(offset,rows.size(),List.copyOf(rows.subList(from,to)));
    }
    public Dump exportState(){
        return new Dump(tickets.values().stream().sorted(ORDER).toList(),matches.values().stream().sorted(Comparator.comparing(Match::id)).toList(),
                ratings.values().stream().sorted(Comparator.comparing(Rating::gameId).thenComparing(r->r.user().id())).toList());
    }
    public void importState(Dump dump){
        tickets.clear();matches.clear();byRoom.clear();active.clear();pending.clear();ratings.clear();
        if(dump==null)return; // Existing v1 authority documents predate competition.
        for(Ticket t:dump.tickets())if(tickets.put(t.user().id(),t)!=null)throw new IllegalStateException("duplicate persisted ticket");
        for(Match m:dump.matches()){
            if(matches.containsKey(m.id())||(m.active()&&(byRoom.containsKey(m.roomId())||m.players().stream().anyMatch(u->active.containsKey(u.id())||tickets.containsKey(u.id()))))||
               (m.state()==State.AWAITING_RESULT&&m.players().stream().anyMatch(u->pending.containsKey(u.id()))))
                throw new IllegalStateException("invalid persisted competition membership");
            put(m);
        }
        for(Rating r:dump.ratings())if(ratings.put(key(r.gameId(),r.user().id()),r)!=null)throw new IllegalStateException("duplicate persisted rating");
        for(Match m:matches.values())if(m.unresolvedRanked()&&m.state()!=State.AWAITING_RESULT&&m.players().stream().anyMatch(u->pending.containsKey(u.id())))
            throw new IllegalStateException("multiple unresolved ranked matches");
    }
}
