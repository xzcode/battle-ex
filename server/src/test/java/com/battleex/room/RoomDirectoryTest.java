package com.battleex.room;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.battleex.room.RoomDirectory.State.*;
import static com.battleex.room.RoomDirectory.Visibility.*;

/** Dependency-free executable domain test; invoked by scripts/test-room-domain.sh. */
public final class RoomDirectoryTest {
    private static void check(boolean result, String description) {
        if (!result) throw new AssertionError(description);
    }
    private static void rejects(Class<? extends RuntimeException> expected, Runnable action) {
        try { action.run(); }
        catch (RuntimeException ex) {
            if (expected.isInstance(ex)) return;
            throw new AssertionError("wrong failure type", ex);
        }
        throw new AssertionError("expected " + expected.getSimpleName());
    }
    public static void main(String[] args) throws Exception {
        lifecycle();
        privateRooms();
        concurrentJoin();
        System.out.println("PASS: room lifecycle, membership revisions, invitation permissions, isolation and concurrent capacity");
    }
    private static void lifecycle() {
        var rooms = new RoomDirectory();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        var created = rooms.create(a, "LAN match", "custom", 2, PUBLIC);
        UUID id = created.id();
        check(rooms.listPublic().size() == 1, "public lobby");
        rejects(SecurityException.class, () -> rooms.view(b, id));
        rejects(IllegalStateException.class, () -> rooms.create(a, "other", "custom", 2, PUBLIC));
        rooms.setReady(a, id, true);
        var joined = rooms.join(b, id, null);
        check(joined.membershipRevision() == 2 && joined.members().size() == 2, "membership snapshot");
        check(joined.members().stream().noneMatch(RoomDirectory.Member::ready), "joining resets readiness");
        rejects(IllegalStateException.class, () -> rooms.join(c, id, null));
        rejects(SecurityException.class, () -> rooms.start(b, id));
        rejects(IllegalStateException.class, () -> rooms.start(a, id));
        rooms.setReady(a, id, true);
        rooms.setReady(b, id, true);
        check(rooms.start(a, id).state() == IN_GAME, "match started");
        rejects(IllegalStateException.class, () -> rooms.setReady(b, id, false));
        rejects(SecurityException.class, () -> rooms.finish(b, id));
        check(rooms.finish(a, id).state() == WAITING, "match ended");
        rooms.leave(a, id);
        var survivor = rooms.view(b, id);
        check(survivor.host().equals(b) && survivor.membershipRevision() == 3, "host transfer and revocation");
        rejects(SecurityException.class, () -> rooms.view(a, id));
        check(rooms.authorizedMembership(id).userIds().equals(List.of(b)), "trusted membership excludes departed member");
        var separate = rooms.create(a, "second", "custom", 2, PUBLIC);
        check(!separate.networkId().equals(created.networkId()), "distinct network identities");
        rejects(SecurityException.class, () -> rooms.view(b, separate.id()));
        rooms.leave(b, id);
        rejects(IllegalArgumentException.class, () -> rooms.authorizedMembership(id));
        check(rooms.listPublic().size() == 1, "empty room removed");
    }
    private static void privateRooms() {
        var rooms = new RoomDirectory();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        var r = rooms.create(a, "private", "custom", 3, PRIVATE);
        UUID id = r.id();
        check(rooms.listPublic().isEmpty(), "private lobby hidden");
        rejects(SecurityException.class, () -> rooms.join(b, id, null));
        String old = rooms.invitation(a, id);
        rejects(SecurityException.class, () -> rooms.invitation(b, id));
        rooms.join(b, id, old);
        rejects(SecurityException.class, () -> rooms.rotateInvitation(b, id));
        rooms.leave(a, id);
        String next = rooms.invitation(b, id);
        check(!next.equals(old), "invite rotated on host transfer");
        rejects(SecurityException.class, () -> rooms.join(c, id, old));
        rooms.join(c, id, next);
        check(rooms.view(c, id).members().size() == 2, "new invitation valid");
    }
    private static void concurrentJoin() throws Exception {
        var rooms = new RoomDirectory();
        UUID host = UUID.randomUUID();
        UUID id = rooms.create(host, "race", "custom", 3, PUBLIC).id();
        var gate = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                UUID player = UUID.randomUUID();
                results.add(pool.submit(() -> {
                    gate.await();
                    try { rooms.join(player, id, null); return true; }
                    catch (IllegalStateException full) { return false; }
                }));
            }
            gate.countDown();
            int accepted = 0;
            for (Future<Boolean> f : results) if (f.get()) accepted++;
            check(accepted == 2, "atomic capacity enforcement");
            check(rooms.view(host, id).members().size() == 3, "room not overfilled");
        }
    }
}
