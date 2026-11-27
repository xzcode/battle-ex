package com.battleex.platform;

import com.battleex.room.RoomDirectory;
import com.battleex.competition.Competition;
import com.battleex.competition.ResultAuthority;
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
                        RoomDirectory.RoomView activeRoom, Map<UUID,String> memberNames, Competition.View competition) {}
    public record Session(User user, Instant expiresAt, String digest) {}
    public static final class Unauthorized extends RuntimeException {
        public Unauthorized() { super("authentication required or invalid credentials"); }
    }

    private final AccountStore accounts;
    private final ReactiveWork work;
    private final RoomDirectory rooms = new RoomDirectory();
    private final Competition competition;
    private final Map<String,Session> sessions = new HashMap<>();
    private final Map<UUID,Session> byUser = new HashMap<>();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Duration lifetime;
    private final String dummyHash;
    private long revision;

    public Platform(AccountStore accounts, ReactiveWork work) {
        this(accounts, work, Clock.systemUTC(), Duration.ofHours(12),ResultAuthority.disabled());
    }
    public Platform(AccountStore accounts,ReactiveWork work,ResultAuthority authority){
        this(accounts,work,Clock.systemUTC(),Duration.ofHours(12),authority);
    }
    Platform(AccountStore accounts, ReactiveWork work, Clock clock, Duration lifetime) {
        this(accounts,work,clock,lifetime,ResultAuthority.disabled());
    }
    Platform(AccountStore accounts, ReactiveWork work, Clock clock, Duration lifetime,ResultAuthority authority) {
        this.accounts = accounts; this.work = work; this.clock = clock; this.lifetime = lifetime;
        this.competition=new Competition(authority);
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public record StateDump(long revision,List<Session> sessions,List<RoomDirectory.StoredRoom> rooms,Competition.Dump competition) {}
    public synchronized StateDump exportState(){
        return new StateDump(revision,sessions.values().stream()
                .sorted(Comparator.comparing(Session::digest)).toList(),rooms.exportState(),competition.exportState());
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
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        User user = new User(account.id(), account.username());
        Session session = new Session(user, clock.instant().plus(lifetime), digest(token));
        Session prior = byUser.put(user.id(), session);
        if (prior != null) {
            sessions.remove(prior.digest());
            var priorRoom = rooms.activeRoom(user.id());
            if (priorRoom != null) depart(user.id(),priorRoom.id());
            competition.cancel(user.id());
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
        return s != null && s.digest().equals(digest) && s.expiresAt().isAfter(clock.instant());
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
        return new Lobby(s.user(), revision, byUser.size(), rooms.listPublic(), room, Map.copyOf(names),competition.view(s.user().id()));
    }
    public synchronized RoomDirectory.RoomView create(String token, String name, String gameId,
                int capacity, RoomDirectory.Visibility visibility) {
        if (capacity > 65) throw new IllegalArgumentException("current network engine supports at most 65 players per room");
        if (name == null || name.length() > 128 || gameId == null || gameId.length() > 128)
            throw new IllegalArgumentException("invalid room name or game ID");
        var actor=require(token).user().id();
        if(competition.queued(actor))throw new IllegalStateException("cancel matchmaking before creating a room");
        var result = rooms.create(actor, name, gameId, capacity, visibility);
        revision++;
        return result;
    }
    public synchronized RoomDirectory.RoomView join(String token, UUID id, String invite) {
        var actor=require(token).user().id();
        if(competition.queued(actor))throw new IllegalStateException("cancel matchmaking before joining a room");
        if(competition.forRoom(id)!=null)throw new SecurityException("matchmaking rooms are assigned by the server");
        var result = rooms.join(actor, id, invite);
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
        for (Session s : new ArrayList<>(sessions.values())) if (!s.expiresAt().isAfter(now)) remove(s);
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
            competition.cancel(s.user().id());
        }
    }
    private void depart(UUID actor,UUID roomId){
        var managed=competition.forRoom(roomId);
        if(managed==null){rooms.leave(actor,roomId);return;}
        rooms.view(actor,roomId); // enforce authenticated membership before teardown
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
    public String competitionFingerprint(){return competition.authority().configurationFingerprint();}
    public synchronized Competition.View enqueue(String token,String gameId,Competition.Mode mode){
        var user=require(token).user();
        if(rooms.activeRoom(user.id())!=null)throw new IllegalStateException("leave your active room before matchmaking");
        long now=clock.instant().getEpochSecond();
        competition.enqueue(user,gameId,mode,now);
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
        rooms.closeManaged(result.roomId());
        revision++;
        return result;
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
