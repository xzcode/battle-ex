package com.battleex.platform;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
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

    public boolean persistent(){return cluster.enabled();}

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
