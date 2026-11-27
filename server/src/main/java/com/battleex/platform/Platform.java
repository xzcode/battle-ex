package com.battleex.platform;

import com.battleex.room.RoomDirectory;
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
                        RoomDirectory.RoomView activeRoom, Map<UUID,String> memberNames) {}
    private record Session(User user, Instant expiresAt, String digest) {}
    public static final class Unauthorized extends RuntimeException {
        public Unauthorized() { super("authentication required or invalid credentials"); }
    }

    private final AccountStore accounts;
    private final ReactiveWork work;
    private final RoomDirectory rooms = new RoomDirectory();
    private final Map<String,Session> sessions = new HashMap<>();
    private final Map<UUID,Session> byUser = new HashMap<>();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Duration lifetime;
    private final String dummyHash;
    private long revision;

    public Platform(AccountStore accounts, ReactiveWork work) {
        this(accounts, work, Clock.systemUTC(), Duration.ofHours(12));
    }
    Platform(AccountStore accounts, ReactiveWork work, Clock clock, Duration lifetime) {
        this.accounts = accounts; this.work = work; this.clock = clock; this.lifetime = lifetime;
        dummyHash = passwords.encode(UUID.randomUUID().toString());
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
            if (priorRoom != null) rooms.leave(user.id(), priorRoom.id());
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
        return new Lobby(s.user(), revision, byUser.size(), rooms.listPublic(), room, Map.copyOf(names));
    }
    public synchronized RoomDirectory.RoomView create(String token, String name, String gameId,
                int capacity, RoomDirectory.Visibility visibility) {
        if (capacity > 65) throw new IllegalArgumentException("current network engine supports at most 65 players per room");
        if (name == null || name.length() > 128 || gameId == null || gameId.length() > 128)
            throw new IllegalArgumentException("invalid room name or game ID");
        var result = rooms.create(require(token).user().id(), name, gameId, capacity, visibility);
        revision++;
        return result;
    }
    public synchronized RoomDirectory.RoomView join(String token, UUID id, String invite) {
        var result = rooms.join(require(token).user().id(), id, invite);
        revision++;
        return result;
    }
    public synchronized void leave(String token, UUID id) {
        rooms.leave(require(token).user().id(), id);
        revision++;
    }
    public synchronized RoomDirectory.RoomView ready(String token, UUID id, boolean ready) {
        var result = rooms.setReady(require(token).user().id(), id, ready);
        revision++;
        return result;
    }
    public synchronized RoomDirectory.RoomView start(String token, UUID id) {
        var result = rooms.start(require(token).user().id(), id);
        revision++;
        return result;
    }
    public synchronized RoomDirectory.RoomView finish(String token, UUID id) {
        var result = rooms.finish(require(token).user().id(), id);
        revision++;
        return result;
    }
    public synchronized String invitation(String token, UUID id, boolean rotate) {
        var actor = require(token).user().id();
        if (!rotate) return rooms.invitation(actor,id);
        String result = rooms.rotateInvitation(actor,id);
        revision++;
        return result;
    }
    public synchronized void sweep() {
        Instant now = clock.instant();
        for (Session s : new ArrayList<>(sessions.values())) if (!s.expiresAt().isAfter(now)) remove(s);
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
            if (room != null) rooms.leave(s.user().id(), room.id());
        }
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
