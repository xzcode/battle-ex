package com.battleex.platform;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Callable;

/**
 * Keep CPU-heavy password hashing and the existing synchronized room/signing
 * authority off Reactor Netty event loops. MongoDB I/O stays reactive.
 * Bounded queues provide operational backpressure, not account/room quotas.
 */
@Component
public final class ReactiveWork implements DisposableBean {
    private final ClusterAuthority cluster;
    public ReactiveWork(ClusterAuthority cluster){this.cluster=cluster;}
    private final Scheduler state = Schedulers.newBoundedElastic(4, 2048, "battle-state");
    private final Scheduler password = Schedulers.newBoundedElastic(
            Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())), 256, "battle-password");

    public ClusterAuthority.StorageMetrics storageMetrics(){return cluster.storageMetrics();}
    public Mono<Platform.Lobby> lobby(Platform p,String token){return cluster.lobby(p,token,state);}
    public Mono<com.battleex.competition.Competition.History> history(Platform p,String token,int offset,int limit){return cluster.history(p,token,offset,limit,state);}
    public Mono<com.battleex.competition.Competition.Leaderboard> leaderboard(Platform p,String token,String game,int offset,int limit){return cluster.leaderboard(p,token,game,offset,limit,state);}
    public Mono<com.battleex.room.RoomDirectory.RoomView> roomReady(Platform p,String token,java.util.UUID id,boolean value){return cluster.roomReady(p,token,id,value,state);}
    public Mono<com.battleex.room.RoomDirectory.RoomView> roomBuild(Platform p,String token,java.util.UUID id,com.battleex.room.RoomDirectory.GameBuild build){return cluster.roomBuild(p,token,id,build,state);}
    public Mono<java.util.Optional<com.battleex.network.RelayDirectory.Limits>> relayHeartbeat(com.battleex.network.NetworkAuthority n,String secret,com.battleex.network.RelayDirectory.Heartbeat h){return cluster.relayHeartbeat(n,secret,h,state);}
    public Mono<Boolean> relayAuthorize(com.battleex.network.NetworkAuthority n,String secret,String instance,com.battleex.network.NetworkAuthority.Lease lease){return cluster.relayAuthorize(n,secret,instance,lease,state);}
    public Mono<com.battleex.network.RelayDirectory.History> relayHistory(com.battleex.network.NetworkAuthority n,String token,String node){return cluster.relayHistory(n,token,node,state);}
    public Mono<Platform.User> authenticatedUser(Platform p,String token){return cluster.authenticatedUser(p,token,state);}
    public Mono<java.util.Optional<com.battleex.network.NetworkAuthority.SignedSnapshot>> fastSnapshot(String token,String nonce,java.util.Map<String,Integer> measurements){return cluster.fastSnapshot(token,nonce,measurements,state);}
    public boolean persistent(){return cluster.enabled();}
    public Flux<Long> lobbyTicks(java.util.UUID user){return cluster.splitMode()?Flux.merge(Flux.just(0L),cluster.notifications(user),Flux.interval(java.time.Duration.ofSeconds(5))):Flux.interval(java.time.Duration.ZERO,java.time.Duration.ofSeconds(1));}

    public <T> Mono<T> state(Callable<T> task) {
        return cluster.execute(task,state);
    }

    public Mono<Void> stateRun(Runnable task) {
        return state(()->{task.run();return Boolean.TRUE;}).then();
    }

    public <T> Mono<T> password(Callable<T> task) {
        return Mono.fromCallable(task).subscribeOn(password);
    }

    @Override public void destroy() {
        state.dispose();
        password.dispose();
    }
}
