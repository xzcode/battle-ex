package com.battleex.room;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Single-process room domain. The actor MUST come from the authenticated
 * application session, not from a client request body.
 * No networking, persistence or trusted match-result claims are made here.
 */
public final class RoomDirectory {
    public enum Visibility { PUBLIC, PRIVATE }
    public enum State { WAITING, IN_GAME }
    public record Member(UUID userId, boolean ready) {}
    public record RoomView(UUID id, UUID networkId, String name, String gameId,
                           int capacity, Visibility visibility, UUID host,
                           State state, long revision, long membershipRevision,
                           List<Member> members) {
        public RoomView { members = List.copyOf(members); }
    }
    public record RoomSummary(UUID id, String name, String gameId, int capacity,
                              int occupancy, State state) {}
    public record Membership(UUID roomId, UUID networkId, long revision,
                             List<UUID> userIds) {
        public Membership { userIds = List.copyOf(userIds); }
    }

    private static final class Room {
        final UUID id = UUID.randomUUID();
        final UUID networkId = UUID.randomUUID();
        final String name;
        final String gameId;
        final int capacity;
        final Visibility visibility;
        final Map<UUID, Boolean> members = new LinkedHashMap<>();
        UUID host;
        State state = State.WAITING;
        long revision = 1;
        long membershipRevision = 1;
        String invite;

        Room(UUID actor, String name, String gameId, int capacity, Visibility visibility) {
            this.name = name;
            this.gameId = gameId;
            this.capacity = capacity;
            this.visibility = visibility;
            this.host = actor;
            this.members.put(actor, false);
            if (visibility == Visibility.PRIVATE) rotateInvite();
        }

        void rotateInvite() {
            invite = UUID.randomUUID().toString() + UUID.randomUUID();
        }

        RoomView view() {
            List<Member> result = new ArrayList<>();
            members.forEach((id, ready) -> result.add(new Member(id, ready)));
            return new RoomView(id, networkId, name, gameId, capacity, visibility,
                    host, state, revision, membershipRevision, result);
        }
        void resetReady() {
            members.replaceAll((id, ready) -> false);
        }
        void membershipChanged() {
            revision++;
            membershipRevision++;
        }
    }

    // One active room per authenticated account for the initial client model.
    // No imposed maximum number of rooms.
    private final Map<UUID, Room> rooms = new LinkedHashMap<>();
    private final Map<UUID, UUID> active = new HashMap<>();

    public synchronized RoomView create(UUID actor, String name, String gameId,
                                        int capacity, Visibility visibility) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(visibility, "visibility");
        if (capacity < 2) throw new IllegalArgumentException("capacity must be at least two");
        String cleanName = requiredText(name, "room name");
        String cleanGame = requiredText(gameId, "game ID");
        if (active.containsKey(actor)) throw new IllegalStateException("already in an active room");
        Room r = new Room(actor, cleanName, cleanGame, capacity, visibility);
        rooms.put(r.id, r);
        active.put(actor, r.id);
        return r.view();
    }

    public synchronized RoomView join(UUID actor, UUID roomId, String invite) {
        Objects.requireNonNull(actor, "actor");
        Room r = room(roomId);
        if (r.members.containsKey(actor)) return r.view();
        if (r.visibility == Visibility.PRIVATE && !matches(r.invite, invite)) {
            throw new SecurityException("invalid room invitation");
        }
        if (active.containsKey(actor)) throw new IllegalStateException("already in an active room");
        if (r.state != State.WAITING) throw new IllegalStateException("match is in progress");
        if (r.members.size() >= r.capacity) throw new IllegalStateException("room is full");
        r.members.put(actor, false);
        active.put(actor, r.id);
        r.resetReady();
        r.membershipChanged();
        return r.view();
    }

    /** Empty room is removed. Remaining members receive a new revision. */
    public synchronized void leave(UUID actor, UUID roomId) {
        Room r = memberRoom(actor, roomId);
        r.members.remove(actor);
        active.remove(actor);
        r.membershipChanged();
        if (r.members.isEmpty()) {
            rooms.remove(roomId);
            return;
        }
        if (r.host.equals(actor)) {
            r.host = r.members.keySet().iterator().next();
            if (r.visibility == Visibility.PRIVATE) r.rotateInvite();
        }
        if (r.state == State.IN_GAME && r.members.size() < 2) {
            r.state = State.WAITING;
        }
        if (r.state == State.WAITING) r.resetReady();
    }

    public synchronized RoomView setReady(UUID actor, UUID roomId, boolean ready) {
        Room r = memberRoom(actor, roomId);
        requireWaiting(r);
        if (r.members.get(actor) != ready) {
            r.members.put(actor, ready);
            r.revision++;
        }
        return r.view();
    }

    public synchronized RoomView start(UUID actor, UUID roomId) {
        Room r = hostRoom(actor, roomId);
        requireWaiting(r);
        if (r.members.size() < 2 || r.members.containsValue(false)) {
            throw new IllegalStateException("all players must be ready");
        }
        r.state = State.IN_GAME;
        r.revision++;
        return r.view();
    }

    /** A host may end the session; this does NOT assert a winner or grant rating. */
    public synchronized RoomView finish(UUID actor, UUID roomId) {
        Room r = hostRoom(actor, roomId);
        if (r.state != State.IN_GAME) throw new IllegalStateException("no active match");
        r.state = State.WAITING;
        r.resetReady();
        r.revision++;
        return r.view();
    }

    public synchronized RoomView view(UUID actor, UUID roomId) {
        return memberRoom(actor, roomId).view();
    }

    public synchronized String invitation(UUID actor, UUID roomId) {
        Room r = hostRoom(actor, roomId);
        if (r.visibility != Visibility.PRIVATE) throw new IllegalStateException("not a private room");
        return r.invite;
    }

    public synchronized String rotateInvitation(UUID actor, UUID roomId) {
        Room r = hostRoom(actor, roomId);
        if (r.visibility != Visibility.PRIVATE) throw new IllegalStateException("not a private room");
        r.rotateInvite();
        r.revision++;
        return r.invite;
    }

    public synchronized List<RoomSummary> listPublic() {
        return rooms.values().stream().filter(r -> r.visibility == Visibility.PUBLIC)
                .map(r -> new RoomSummary(r.id, r.name, r.gameId, r.capacity,
                        r.members.size(), r.state)).toList();
    }

    /** Internal control-plane boundary; only trusted server components may call this. */
    public synchronized Membership authorizedMembership(UUID roomId) {
        Room r = room(roomId);
        return new Membership(r.id, r.networkId, r.membershipRevision,
                List.copyOf(r.members.keySet()));
    }

    private Room room(UUID id) {
        Room r = rooms.get(id);
        if (r == null) throw new IllegalArgumentException("room not found");
        return r;
    }
    private Room memberRoom(UUID actor, UUID id) {
        Objects.requireNonNull(actor, "actor");
        Room r = room(id);
        if (!r.members.containsKey(actor)) throw new SecurityException("not a room member");
        return r;
    }
    private Room hostRoom(UUID actor, UUID id) {
        Room r = memberRoom(actor, id);
        if (!r.host.equals(actor)) throw new SecurityException("room host required");
        return r;
    }
    private static void requireWaiting(Room r) {
        if (r.state != State.WAITING) throw new IllegalStateException("match is in progress");
    }
    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
        return value.strip();
    }
    private static boolean matches(String expected, String supplied) {
        return expected != null && supplied != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
