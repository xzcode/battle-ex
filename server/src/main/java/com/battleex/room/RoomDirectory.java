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
import java.util.Set;

/**
 * Single-process room domain. The actor MUST come from the authenticated
 * application session, not from a client request body.
 * No networking, persistence or trusted match-result claims are made here.
 */
public final class RoomDirectory {
    public enum Visibility { PUBLIC, PRIVATE }
    public enum State { WAITING, IN_GAME }
    /** Optional, self-reported executable fingerprint. Not a trusted result or anti-cheat proof. */
    public record GameBuild(String gameId, String sha256, String fileVersion, String architecture) {
        public GameBuild {
            if(gameId==null || gameId.isBlank() || gameId.length()>128 ||
                    sha256==null || !sha256.matches("[a-f0-9]{64}") ||
                    architecture==null || !Set.of("x86","x64","ARM64").contains(architecture) ||
                    (fileVersion!=null && !fileVersion.matches("[0-9]{1,5}(\\.[0-9]{1,5}){0,3}")))
                throw new IllegalArgumentException("invalid game build report");
        }
    }
    public record Member(UUID userId, boolean ready, GameBuild gameBuild) {
        public Member(UUID userId, boolean ready){this(userId,ready,null);}
    }
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
        final UUID id;
        final UUID networkId;
        final String name;
        final String gameId;
        final int capacity;
        final Visibility visibility;
        final Map<UUID, Boolean> members = new LinkedHashMap<>();
        final Map<UUID,GameBuild> builds=new HashMap<>();
        UUID host;
        State state = State.WAITING;
        long revision = 1;
        long membershipRevision = 1;
        String invite;

        Room(UUID actor, String name, String gameId, int capacity, Visibility visibility) {
            this.id = UUID.randomUUID();
            this.networkId = UUID.randomUUID();
            this.name = name;
            this.gameId = gameId;
            this.capacity = capacity;
            this.visibility = visibility;
            this.host = actor;
            this.members.put(actor, false);
            if (visibility == Visibility.PRIVATE) rotateInvite();
        }

        Room(StoredRoom stored) {
            RoomView v=stored.view();
            id=v.id();networkId=v.networkId();name=v.name();gameId=v.gameId();
            capacity=v.capacity();visibility=v.visibility();host=v.host();state=v.state();
            revision=v.revision();membershipRevision=v.membershipRevision();invite=stored.invite();
            for(Member member:v.members()){
                members.put(member.userId(),member.ready());
                if(member.gameBuild()!=null){
                    if(!gameId.equals(member.gameBuild().gameId()))throw new IllegalStateException("invalid persisted game build");
                    builds.put(member.userId(),member.gameBuild());
                }
            }
        }

        void rotateInvite() {
            invite = UUID.randomUUID().toString() + UUID.randomUUID();
        }

        RoomView view() {
            List<Member> result = new ArrayList<>();
            members.forEach((id, ready) -> result.add(new Member(id, ready,builds.get(id))));
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

    // Internal persistence DTO; invitations are never exposed by the lobby.
    public record StoredRoom(RoomView view,String invite) {}
    public synchronized List<StoredRoom> exportState(){
        return rooms.values().stream().map(r->new StoredRoom(r.view(),r.invite)).toList();
    }
    public synchronized void importState(List<StoredRoom> stored){
        Map<UUID,Room> next=new LinkedHashMap<>();
        Map<UUID,UUID> membership=new HashMap<>();
        Set<UUID> networks=new java.util.HashSet<>();
        for(StoredRoom value:stored){
            Room r=new Room(value);
            if(r.members.isEmpty()||!r.members.containsKey(r.host)||r.members.size()>r.capacity||
                    !networks.add(r.networkId)||next.put(r.id,r)!=null)
                throw new IllegalStateException("invalid persisted room");
            for(UUID user:r.members.keySet())if(membership.put(user,r.id)!=null)
                throw new IllegalStateException("duplicate persisted membership");
        }
        rooms.clear();rooms.putAll(next);active.clear();active.putAll(membership);
    }

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
        r.builds.remove(actor);
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

    // Report only the authenticated member's own local build. Reports stay
    // private to the room and never change the network membership revision.
    public synchronized RoomView reportGameBuild(UUID actor,UUID roomId,GameBuild build){
        Room r=memberRoom(actor,roomId);
        requireWaiting(r);
        if(build!=null&&!r.gameId.equals(build.gameId()))
            throw new IllegalArgumentException("game build does not match the room game");
        if(!Objects.equals(r.builds.get(actor),build)){
            if(build==null)r.builds.remove(actor);else r.builds.put(actor,build);
            r.resetReady();
            r.revision++;
        }
        return r.view();
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

    // Internal managed-match teardown. The platform owns authorization.
    public synchronized void closeManaged(UUID roomId){
        Room r=rooms.remove(roomId);
        if(r!=null)for(UUID user:r.members.keySet())active.remove(user,roomId);
    }

    public synchronized RoomView byNetwork(UUID network) {
        for (Room r : rooms.values()) if (r.networkId.equals(network)) return r.view();
        return null;
    }

    public synchronized RoomView activeRoom(UUID actor) {
        Objects.requireNonNull(actor, "actor");
        UUID id = active.get(actor);
        return id == null ? null : memberRoom(actor, id).view();
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
