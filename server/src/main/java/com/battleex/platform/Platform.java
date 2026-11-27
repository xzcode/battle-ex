package com.battleex.platform;

import com.battleex.room.RoomDirectory;
import com.battleex.competition.Competition;
import com.battleex.competition.ResultAuthority;
import com.battleex.competition.MatchArchive;
import com.battleex.moderation.Moderation;
import com.battleex.community.Teams;
import com.battleex.community.Tournaments;
import com.battleex.operations.Operations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import reactor.core.publisher.Mono;

/** Single-instance authoritative account sessions and room mutations. */
public final class Platform {
    public record User(UUID id, String username) {}
    public record Login(String token, Instant expiresAt, User user) {}
    public record Lobby(User user, long revision, int signedInUsers, List<RoomDirectory.RoomSummary> rooms,
                        RoomDirectory.RoomView activeRoom, Map<UUID,String> memberNames, Competition.View competition,boolean moderationConfigured,boolean operationsConfigured) {}
    public record Session(User user, Instant expiresAt, String digest) {}
    public static final class Unauthorized extends RuntimeException {
        public Unauthorized() { super("authentication required or invalid credentials"); }
    }

    private final AccountStore accounts;
    private final ReactiveWork work;
    private final RoomDirectory rooms = new RoomDirectory();
    private final Competition competition;
    private final MatchArchive archive=new MatchArchive();
    private final Moderation moderation;
    private final Teams teams=new Teams();
    private final Tournaments tournaments=new Tournaments();
    private final Operations operations;
    private final Map<String,Session> sessions = new HashMap<>();
    private final Map<UUID,Session> byUser = new HashMap<>();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Duration lifetime;
    private final String dummyHash;
    private long revision;

    public Platform(AccountStore accounts, ReactiveWork work) {
        this(accounts, work, Clock.systemUTC(), Duration.ofHours(12),ResultAuthority.disabled(),new Moderation(""),new Operations(""));
    }
    public Platform(AccountStore accounts,ReactiveWork work,ResultAuthority authority){
        this(accounts,work,Clock.systemUTC(),Duration.ofHours(12),authority,new Moderation(""),new Operations(""));
    }
    public Platform(AccountStore accounts,ReactiveWork work,ResultAuthority authority,Moderation moderation){
        this(accounts,work,Clock.systemUTC(),Duration.ofHours(12),authority,moderation,new Operations(""));
    }
    public Platform(AccountStore accounts,ReactiveWork work,ResultAuthority authority,Moderation moderation,Operations operations){
        this(accounts,work,Clock.systemUTC(),Duration.ofHours(12),authority,moderation,operations);
    }
    Platform(AccountStore accounts, ReactiveWork work, Clock clock, Duration lifetime) {
        this(accounts,work,clock,lifetime,ResultAuthority.disabled(),new Moderation(""),new Operations(""));
    }
    Platform(AccountStore accounts, ReactiveWork work, Clock clock, Duration lifetime,ResultAuthority authority,Moderation moderation,Operations operations) {
        this.accounts = accounts; this.work = work; this.clock = clock; this.lifetime = lifetime;
        this.competition=new Competition(authority);this.moderation=moderation;this.operations=operations;
        if(operations.configured()&&!moderation.configured())throw new IllegalArgumentException("root operators require a configured moderator credential");
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public record StateDump(long revision,List<Session> sessions,List<RoomDirectory.StoredRoom> rooms,Competition.Dump competition,MatchArchive.Dump archive,Moderation.Dump moderation,Teams.Dump teams,Tournaments.Dump tournaments,Operations.Dump operations) {}
    public synchronized StateDump exportState(){
        return new StateDump(revision,sessions.values().stream()
                .sorted(Comparator.comparing(Session::digest)).toList(),rooms.exportState(),competition.exportState(),archive.exportState(),moderation.exportState(),teams.exportState(),tournaments.exportState(),operations.exportState());
    }
    public synchronized void importState(StateDump state){
        Map<String,Session> next=new HashMap<>();
        Map<UUID,Session> users=new HashMap<>();
        for(Session session:state.sessions()){
            if(next.put(session.digest(),session)!=null||users.put(session.user().id(),session)!=null)
                throw new IllegalStateException("invalid persisted session");
        }
        for(var room:state.rooms())for(var member:room.view().members())
            if(!users.containsKey(member.userId()))throw new IllegalStateException("room member has no persisted session");
        rooms.importState(state.rooms());
        competition.importState(state.competition());
        archive.importState(state.archive(),competition);moderation.importState(state.moderation(),competition);
        teams.importState(state.teams());tournaments.importState(state.tournaments(),competition);operations.importState(state.operations());
        var dump=competition.exportState();
        for(var ticket:dump.tickets())if(!users.containsKey(ticket.user().id())||rooms.activeRoom(ticket.user().id())!=null)
            throw new IllegalStateException("invalid persisted queued account");
        for(var m:dump.matches())if(m.state()==Competition.State.WAITING||m.state()==Competition.State.RUNNING){
            var room=rooms.activeRoom(m.players().getFirst().id());
            if(room==null||!room.id().equals(m.roomId())||!room.gameId().equals(m.gameId())||
               room.members().size()!=2||!room.members().stream().map(RoomDirectory.Member::userId).collect(java.util.stream.Collectors.toSet())
                    .equals(m.players().stream().map(User::id).collect(java.util.stream.Collectors.toSet()))||
               (m.state()==Competition.State.WAITING)!=(room.state()==RoomDirectory.State.WAITING))
                throw new IllegalStateException("invalid persisted managed match");
        }
        sessions.clear();sessions.putAll(next);byUser.clear();byUser.putAll(users);
        revision=state.revision();
    }

    public Mono<Login> register(String rawName, String password) {
        return work.password(() -> {
            String username = username(rawName);
            validatePassword(password);
            return new AccountStore.Account(UUID.randomUUID(), username, passwords.encode(password));
        }).flatMap(account -> accounts.insert(account)
                .flatMap(inserted -> inserted
                        ? work.state(() -> issue(account))
                        : Mono.error(new IllegalStateException("username already exists"))));
    }

    public Mono<Login> login(String rawName, String password) {
        return Mono.defer(() -> {
            final String username;
            try { username = username(rawName); validatePassword(password); }
            catch (IllegalArgumentException e) { return Mono.error(new Unauthorized()); }
            return accounts.find(username).map(Optional::of).defaultIfEmpty(Optional.empty())
                    .flatMap(found -> work.password(() -> {
                        boolean ok = passwords.matches(password,
                                found.map(AccountStore.Account::passwordHash).orElse(dummyHash));
                        if (found.isEmpty() || !ok) throw new Unauthorized();
                        return found.get();
                    }))
                    .flatMap(account -> work.state(() -> issue(account)));
        });
    }
    private synchronized Login issue(AccountStore.Account account) {
        sweep();
        if(moderation.activeSuspension(account.id(),clock.instant().getEpochSecond())!=null)throw new SecurityException("account suspended");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        User user = new User(account.id(), account.username());
        Session session = new Session(user, clock.instant().plus(lifetime), digest(token));
        Session prior = byUser.put(user.id(), session);
        if (prior != null) {
            sessions.remove(prior.digest());
            var priorRoom = rooms.activeRoom(user.id());
            if (priorRoom != null) depart(user.id(),priorRoom.id());
            competition.cancel(user.id());tournaments.clearCheckIns(user.id());
        }
        sessions.put(session.digest(), session);
        revision++;
        return new Login(token, session.expiresAt(), user);
    }
    public synchronized void logout(String token) {
        var s = require(token);
        remove(s);
    }
    public synchronized User authenticate(String token) { return require(token).user(); }

    public record NetworkContext(User user, String sessionDigest, RoomDirectory.RoomView room) {}
    public synchronized NetworkContext networkContext(String token) {
        var s = require(token);
        return new NetworkContext(s.user(), s.digest(), rooms.activeRoom(s.user().id()));
    }
    public synchronized boolean isSessionCurrent(UUID user, String digest) {
        var s = byUser.get(user);
        return s != null && s.digest().equals(digest) && s.expiresAt().isAfter(clock.instant())&&moderation.activeSuspension(user,clock.instant().getEpochSecond())==null;
    }
    public synchronized RoomDirectory.RoomView roomByNetwork(UUID network) {
        return rooms.byNetwork(network);
    }

    public synchronized Lobby lobby(String token) {
        var s = require(token);
        var room = rooms.activeRoom(s.user().id());
        var names = new LinkedHashMap<UUID,String>();
        if (room != null) for (var member : room.members()) {
            var memberSession = byUser.get(member.userId());
            if (memberSession != null) names.put(member.userId(), memberSession.user().username());
        }
        return new Lobby(s.user(), revision, byUser.size(), rooms.listPublic(), room, Map.copyOf(names),competition.view(s.user().id()),moderation.configured(),operations.configured());
    }
    public synchronized RoomDirectory.RoomView create(String token, String name, String gameId,
                int capacity, RoomDirectory.Visibility visibility) {
        if (capacity > 65) throw new IllegalArgumentException("current network engine supports at most 65 players per room");
        if (name == null || name.length() > 128 || gameId == null || gameId.length() > 128)
            throw new IllegalArgumentException("invalid room name or game ID");
        var actor=require(token).user().id();
        if(competition.queued(actor))throw new IllegalStateException("cancel matchmaking before creating a room");
        var result = rooms.create(actor, name, gameId, capacity, visibility);
        tournaments.clearCheckIns(actor);
        revision++;
        return result;
    }
    public synchronized RoomDirectory.RoomView join(String token, UUID id, String invite) {
        var actor=require(token).user().id();
        if(competition.queued(actor))throw new IllegalStateException("cancel matchmaking before joining a room");
        if(competition.forRoom(id)!=null)throw new SecurityException("matchmaking rooms are assigned by the server");
        var result = rooms.join(actor, id, invite);
        tournaments.clearCheckIns(actor);
        revision++;
        return result;
    }
    public synchronized void leave(String token, UUID id) {
        depart(require(token).user().id(),id);
        revision++;
    }
    public synchronized RoomDirectory.RoomView reportGameBuild(String token,UUID roomId,RoomDirectory.GameBuild build){
        var result=rooms.reportGameBuild(require(token).user().id(),roomId,build);
        revision++;
        return result;
    }
    public synchronized RoomDirectory.RoomView ready(String token, UUID id, boolean ready) {
        var result = rooms.setReady(require(token).user().id(), id, ready);
        revision++;
        return result;
    }
    public synchronized RoomDirectory.RoomView start(String token, UUID id) {
        var result = rooms.start(require(token).user().id(), id);
        competition.start(id,clock.instant().getEpochSecond());
        revision++;
        return result;
    }
    public synchronized RoomDirectory.RoomView finish(String token, UUID id) {
        var result = rooms.finish(require(token).user().id(), id);
        if(competition.forRoom(id)!=null){
            archive.close(competition.forRoom(id).id());
            competition.end(id,false,clock.instant().getEpochSecond());
            rooms.closeManaged(id);
        }
        revision++;
        return result;
    }
    public synchronized String invitation(String token, UUID id, boolean rotate) {
        var actor = require(token).user().id();
        if(competition.forRoom(id)!=null)throw new SecurityException("managed match invitations are disabled");
        if (!rotate) return rooms.invitation(actor,id);
        String result = rooms.rotateInvitation(actor,id);
        revision++;
        return result;
    }
    public synchronized void sweep() {
        Instant now = clock.instant();
        for (Session s : new ArrayList<>(sessions.values())) if (!s.expiresAt().isAfter(now)||moderation.activeSuspension(s.user().id(),now.getEpochSecond())!=null) remove(s);
        if(competition.expire(now.getEpochSecond()))revision++;
        matchWaiting(now.getEpochSecond());
    }
    private Session require(String token) {
        sweep();
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new Unauthorized();
        var s = sessions.get(digest(token));
        if (s == null) throw new Unauthorized();
        return s;
    }
    private void remove(Session s) {
        sessions.remove(s.digest());
        if (byUser.remove(s.user().id(), s)) {
            revision++;
            var room = rooms.activeRoom(s.user().id());
            if (room != null) depart(s.user().id(),room.id());
            competition.cancel(s.user().id());tournaments.clearCheckIns(s.user().id());
        }
    }
    private void depart(UUID actor,UUID roomId){
        var managed=competition.forRoom(roomId);
        if(managed==null){rooms.leave(actor,roomId);return;}
        rooms.view(actor,roomId); // enforce authenticated membership before teardown
        archive.close(managed.id());
        competition.end(roomId,true,clock.instant().getEpochSecond());
        rooms.closeManaged(roomId);
    }
    private void matchWaiting(long now){
        Competition.Pair pair;
        while((pair=competition.nextPair(now))!=null){
            var a=pair.first();var b=pair.second();
            var room=rooms.create(a.user().id(),a.mode()==Competition.Mode.RANKED?"Ranked 1v1":"Casual 1v1",a.gameId(),2,RoomDirectory.Visibility.PRIVATE);
            rooms.join(b.user().id(),room.id(),rooms.invitation(a.user().id(),room.id()));
            competition.assign(pair,room.id(),now);
            revision++;
        }
    }
    public String competitionFingerprint(){return competition.authority().configurationFingerprint()+moderation.fingerprint()+operations.fingerprint();}
    public synchronized Competition.View enqueue(String token,String gameId,Competition.Mode mode){
        var user=require(token).user();
        if(rooms.activeRoom(user.id())!=null)throw new IllegalStateException("leave your active room before matchmaking");
        long now=clock.instant().getEpochSecond();
        competition.enqueue(user,gameId,mode,now);tournaments.clearCheckIns(user.id());
        revision++;matchWaiting(now);
        return competition.view(user.id());
    }
    public synchronized Competition.View cancelQueue(String token){
        var user=require(token).user();
        if(competition.cancel(user.id()))revision++;
        return competition.view(user.id());
    }
    public synchronized Competition.Leaderboard leaderboard(String token,String game,int offset,int limit){
        require(token);return competition.leaderboard(game,offset,limit);
    }
    public synchronized Competition.History matchHistory(String token,int offset,int limit){
        return competition.history(require(token).user().id(),offset,limit);
    }
    public synchronized Competition.Match trustedResult(ResultAuthority.SignedResult request){
        var verified=competition.authority().verify(request);
        sweep();
        var result=competition.settle(verified,clock.instant().getEpochSecond());
        archive.close(result.id());
        rooms.closeManaged(result.roomId());
        revision++;
        return result;
    }

    public synchronized MatchArchive.Detail matchDetail(String token,UUID id){
        var actor=require(token).user();return archive.detail(competition.participantMatch(actor.id(),id),actor.id());
    }
    public synchronized MatchArchive.Replay addReplay(String token,UUID match,MatchArchive.ReplayInput input){
        var actor=require(token).user();
        var result=archive.add(competition.participantMatch(actor.id(),match),actor,input,clock.instant().getEpochSecond());
        revision++;return result;
    }
    public synchronized void removeReplay(String token,UUID match,UUID replay){
        var actor=require(token).user();archive.remove(competition.participantMatch(actor.id(),match),actor.id(),replay);revision++;
    }
    public synchronized MatchArchive.Detail shareStatus(String token,UUID match,boolean share){
        var actor=require(token).user();var result=archive.consent(competition.participantMatch(actor.id(),match),actor.id(),share);
        revision++;return result;
    }
    public synchronized List<MatchArchive.Live> liveMatches(String token){
        require(token);return archive.live(competition.runningMatches());
    }
    public synchronized Moderation.Report fileReport(String token,Moderation.ReportInput input){
        var actor=require(token).user();
        if(input==null||input.matchId()==null)throw new IllegalArgumentException("match ID required");
        var match=competition.participantMatch(actor.id(),input.matchId());
        if(input.replaySha256()!=null&&!archive.detail(match,actor.id()).replays().stream().anyMatch(r->r.sha256().equals(input.replaySha256())&&r.uploader().id().equals(actor.id())))
            throw new IllegalArgumentException("replay fingerprint must be your registered match replay");
        var result=moderation.file(match,actor,input,clock.instant().getEpochSecond());revision++;return result;
    }
    public synchronized Moderation.Page<Moderation.Report> ownReports(String token,int offset,int limit){
        return moderation.own(require(token).user().id(),offset,limit);
    }
    public synchronized List<Moderation.Warning> warnings(String token){return moderation.warnings(require(token).user().id());}
    private User moderator(String token,String credential){
        User actor=operator(token,credential);operations.requireModeration(actor.id());return actor;
    }
    public synchronized Moderation.Page<Moderation.Report> reviewReports(String token,String key,Moderation.Status status,int offset,int limit){
        moderator(token,key);return moderation.reports(status,offset,limit);
    }
    public synchronized Moderation.Report reviewReport(String token,String key,UUID id,Moderation.ReviewInput input){
        User actor=moderator(token,key);
        var result=moderation.review(id,actor,input,clock.instant().getEpochSecond());
        operations.audit(actor,"REPORT_REVIEW",id.toString(),input.resolution(),now());
        // In the same authority commit, invalidate the banned account's
        // token, queue and assigned room. Network authorization consults
        // current session/membership; old relay grants are rejected.
        if(result.status()==Moderation.Status.SUSPENDED&&moderation.activeSuspension(result.target().id(),clock.instant().getEpochSecond())!=null){
            Session session=byUser.get(result.target().id());if(session!=null)remove(session);
        }
        revision++;return result;
    }
    public synchronized Moderation.Page<Moderation.Suspension> suspensions(String token,String key,int offset,int limit){
        moderator(token,key);return moderation.suspensions(offset,limit);
    }
    public synchronized Moderation.Suspension revokeSuspension(String token,String key,UUID id,String reason){
        var actor=moderator(token,key);
        var result=moderation.revoke(id,actor,reason,clock.instant().getEpochSecond());
        operations.audit(actor,"SUSPENSION_REVOKED",id.toString(),reason,now());
        revision++;return result;
    }

    private long now(){return clock.instant().getEpochSecond();}
    public synchronized Teams.Page<Teams.Team> teams(String token,String search,int offset,int limit){
        require(token);return teams.list(search,offset,limit);
    }
    public synchronized Teams.Team myTeam(String token){return teams.myTeam(require(token).user().id());}
    public synchronized Teams.Detail teamDetail(String token,UUID id){return teams.detail(require(token).user().id(),id);}
    public synchronized Teams.Team createTeam(String token,String name,String tag,String desc){var t=teams.create(require(token).user(),name,tag,desc,now());revision++;return t;}
    public synchronized Teams.Team editTeam(String token,UUID id,String name,String tag,String desc){var t=teams.update(require(token).user().id(),id,name,tag,desc);revision++;return t;}
    public synchronized Teams.Application applyTeam(String token,UUID id,String message){var a=teams.apply(require(token).user(),id,message,now());revision++;return a;}
    public synchronized void withdrawTeam(String token,UUID id){teams.withdraw(require(token).user().id(),id);revision++;}
    public synchronized Teams.Team reviewTeam(String token,UUID id,UUID user,boolean accept){var t=teams.review(require(token).user().id(),id,user,accept,now(),moderation.activeSuspension(user,now())==null);revision++;return t;}
    public synchronized Teams.Team teamRole(String token,UUID id,UUID user,Teams.Role role){var t=teams.role(require(token).user().id(),id,user,role);revision++;return t;}
    public synchronized Teams.Team transferTeam(String token,UUID id,UUID user){
        var actor=require(token).user();
        if(user==null||moderation.activeSuspension(user,now())!=null)throw new IllegalArgumentException("eligible owner required");
        var t=teams.transfer(actor.id(),id,user);revision++;return t;
    }
    public synchronized void removeTeamMember(String token,UUID id,UUID user){teams.remove(require(token).user().id(),id,user);revision++;}
    public synchronized void disbandTeam(String token,UUID id){teams.disband(require(token).user().id(),id);revision++;}

    private User operator(String token,String key){
        User actor=require(token).user();moderation.authorize(key);return actor;
    }
    private User eventOperator(String token,String key){
        User actor=operator(token,key);operations.require(actor.id(),Operations.Role.EVENT_MANAGER);return actor;
    }
    public synchronized Operations.Access operatorAccess(String token,String key){
        var actor=operator(token,key);return operations.access(actor.id());
    }
    public synchronized Operations.Overview overview(String token,String key){
        var actor=operator(token,key);operations.requireOverview(actor.id());
        return new Operations.Overview(byUser.size(),rooms.count(),competition.runningCount(),competition.queuedCount(),teams.count(),tournaments.count(),
                moderation.pendingCount(),moderation.activeCount(now()),work.persistent());
    }
    public synchronized List<Operations.Grant> operatorRoles(String token,String key){return operations.grants(operator(token,key).id());}
    public Mono<Operations.Grant> grantOperator(String token,String key,String username,Operations.Role role,String reason){
        return work.state(()->{operations.requireRoot(operator(token,key).id());return username(username);})
            .flatMap(name->accounts.find(name).switchIfEmpty(Mono.error(new IllegalArgumentException("account not found"))))
            .flatMap(account->work.state(()->{
                var actor=operator(token,key);
                var g=operations.grant(actor,new User(account.id(),account.username()),role,reason,now());revision++;return g;
            }));
    }
    public synchronized void revokeOperator(String token,String key,UUID target,Operations.Role role,String reason){operations.revoke(operator(token,key),target,role,reason,now());revision++;}
    public synchronized Teams.Page<Operations.Event> operatorAudit(String token,String key,int offset,int limit){return operations.events(operator(token,key).id(),offset,limit);}
    public synchronized Teams.Page<Operations.SessionRow> operatorSessions(String token,String key,int offset,int limit){
        operations.requireRoot(operator(token,key).id());
        var rows=byUser.values().stream().sorted(Comparator.comparing(s->s.user().username()))
                .map(s->{
                    var room=rooms.activeRoom(s.user().id());
                    return new Operations.SessionRow(s.user(),s.expiresAt().getEpochSecond(),
                            room==null?null:room.id(),competition.queued(s.user().id()));
                }).toList();
        return Teams.page(rows,offset,limit);
    }
    public synchronized void disconnectSession(String token,String key,UUID target,String reason){
        var actor=operator(token,key);operations.requireRoot(actor.id());Operations.validateReason(reason);
        if(target==null||target.equals(actor.id()))throw new IllegalArgumentException("another account is required");
        var session=byUser.get(target);
        if(session==null)throw new IllegalArgumentException("active session not found");
        remove(session);
        operations.audit(actor,"SESSION_DISCONNECTED",target.toString(),reason,now());
    }
    public synchronized Teams.Page<Operations.RoomRow> operatorRooms(String token,String key,int offset,int limit){
        operations.requireRoot(operator(token,key).id());
        return Teams.page(rooms.exportState().stream().map(stored->stored.view())
                .map(r->new Operations.RoomRow(r.id(),r.name(),r.gameId(),r.visibility().name(),r.state().name(),r.host(),r.members().size(),r.capacity()))
                .sorted(Comparator.comparing(Operations.RoomRow::id)).toList(),offset,limit);
    }
    public synchronized void closeRoom(String token,String key,UUID id,String reason){
        var actor=operator(token,key);operations.requireRoot(actor.id());Operations.validateReason(reason);
        var room=rooms.exportState().stream().map(r->r.view()).filter(r->r.id().equals(id)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("room not found"));
        var match=competition.forRoom(room.id());
        if(match!=null){archive.close(match.id());competition.end(room.id(),true,now());}
        rooms.closeManaged(room.id());operations.audit(actor,"ROOM_CLOSED",id.toString(),reason,now());revision++;
    }
    public synchronized Teams.Page<Tournaments.Event> events(String token,int offset,int limit){require(token);return tournaments.list(offset,limit);}
    public synchronized Tournaments.Event event(String token,UUID id){require(token);return tournaments.get(id);}
    public synchronized Tournaments.Event createEvent(String token,String key,String name,String game,String desc,int capacity){
        var actor=eventOperator(token,key);var e=tournaments.create(actor,name,game,desc,capacity,now());operations.audit(actor,"EVENT_CREATED",e.id().toString(),null,now());revision++;return e;
    }
    public synchronized Tournaments.Event startEvent(String token,String key,UUID id){
        var actor=eventOperator(token,key);var e=tournaments.start(id);operations.audit(actor,"EVENT_STARTED",id.toString(),null,now());revision++;return e;
    }
    public synchronized Tournaments.Event registerEvent(String token,UUID id){var e=tournaments.register(id,require(token).user(),now());revision++;return e;}
    public synchronized Tournaments.Event withdrawEvent(String token,UUID id){var e=tournaments.withdraw(id,require(token).user().id());revision++;return e;}
    private boolean freeForEvent(UUID user){
        return byUser.containsKey(user)&&rooms.activeRoom(user)==null&&!competition.queued(user)&&moderation.activeSuspension(user,now())==null;
    }
    public synchronized Tournaments.Event checkInEvent(String token,UUID id,int slot,boolean ready){
        var actor=require(token).user();
        if(ready&&!freeForEvent(actor.id()))throw new IllegalStateException("leave your room or queue before checking in");
        var e=tournaments.checkIn(id,slot,actor.id(),ready);
        var s=tournaments.slot(id,slot);
        if(s.checkedIn().size()==2){
            if(!freeForEvent(s.first()))tournaments.clearCheckIns(s.first());
            if(!freeForEvent(s.second()))tournaments.clearCheckIns(s.second());
            s=tournaments.slot(id,slot);
            if(s.checkedIn().size()==2){
                var first=tournaments.entrant(e,s.first());var second=tournaments.entrant(e,s.second());
                var room=rooms.create(first.id(),"Tournament 1v1",e.gameId(),2,RoomDirectory.Visibility.PRIVATE);
                rooms.join(second.id(),room.id(),rooms.invitation(first.id(),room.id()));
                var match=competition.assignTournament(first,second,e.gameId(),room.id(),now());
                e=tournaments.assign(id,slot,match.id());
            }else e=tournaments.get(id);
        }
        revision++;return e;
    }
    public synchronized Tournaments.Event retryEvent(String token,String key,UUID id,int slot,String reason){
        var actor=eventOperator(token,key);Operations.validateReason(reason);
        var e=tournaments.retry(id,slot,competition);operations.audit(actor,"EVENT_RETRY",id+":"+slot,reason,now());revision++;return e;
    }
    public synchronized Tournaments.Event decideEvent(String token,String key,UUID id,int slot,UUID winner,Tournaments.Source source,String reason){
        var actor=eventOperator(token,key);
        var e=tournaments.decide(id,slot,actor,winner,source,reason,now(),competition);operations.audit(actor,"EVENT_DECIDED",id+":"+slot,reason,now());revision++;return e;
    }
    public synchronized Tournaments.Event cancelEvent(String token,String key,UUID id,String reason){
        var actor=eventOperator(token,key);var e=tournaments.cancel(id,reason,competition);operations.audit(actor,"EVENT_CANCELLED",id.toString(),reason,now());revision++;return e;
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String username(String value) {
        if (value == null) throw new IllegalArgumentException("username required");
        var normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_][a-z0-9_.-]{2,31}"))
            throw new IllegalArgumentException("username: 3–32 ASCII letters, numbers, underscore, dot or dash");
        return normalized;
    }
    private static void validatePassword(String password) {
        if (password == null || password.codePointCount(0,password.length()) < 8 ||
                password.getBytes(StandardCharsets.UTF_8).length > 72 || password.indexOf(0) >= 0)
            throw new IllegalArgumentException("password: at least 8 characters and at most 72 UTF-8 bytes");
    }
}
