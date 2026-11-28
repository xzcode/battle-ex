package com.battleex.web;

import com.battleex.platform.Platform;
import com.battleex.platform.ReactiveWork;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import com.battleex.competition.Competition;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Reactive WebSocket: one receive stream and one send stream per connection.
 * First frame authenticates; subsequent inbound commands are forbidden.
 * A slow client coalesces periodic ticks instead of accumulating an unbounded queue.
 */
@Component
public final class LobbySocket implements WebSocketHandler {
    private final Platform platform;
    private final ObjectMapper json;
    private final ReactiveWork work;
    private final boolean workerEnabled;
    private final Set<String> partitions;

    private static final class InvalidFrame extends RuntimeException {}

    public LobbySocket(Platform platform, ObjectMapper json, ReactiveWork work,
            @Value("${battle.match.worker-enabled:true}") boolean workerEnabled,
            @Value("${battle.match.partitions:*}") String partitionConfig) {
        this.platform = platform;
        this.json = json;
        this.work = work;this.workerEnabled=workerEnabled;
        var parsed=new TreeSet<String>();
        if(!partitionConfig.strip().equals("*"))for(String raw:partitionConfig.split(",")){
            String[] p=raw.strip().split(":");
            if(p.length!=3)throw new IllegalArgumentException("match partition must be game:mode:region");
            Competition.game(p[0]);
            var mode=Competition.Mode.valueOf(p[1]);
            if(mode==Competition.Mode.TOURNAMENT)throw new IllegalArgumentException("not a queue mode");
            parsed.add(p[0]+":"+mode+":"+Competition.normalizeRegion(p[2]));
        }
        this.partitions=Set.copyOf(parsed);
    }

    @Override public Mono<Void> handle(WebSocketSession session) {
        return session.receive()
                .map(message -> {
                    // Copy to immutable text synchronously while Netty owns the frame.
                    if (message.getType() != WebSocketMessage.Type.TEXT ||
                            message.getPayload().readableByteCount() > 2048) throw new InvalidFrame();
                    return message.getPayloadAsText();
                })
                .timeout(Mono.delay(Duration.ofSeconds(5)), ignored -> Mono.never())
                .switchOnFirst((first, inbound) -> {
                    if (first.isOnError()) return inbound.then();
                    if (!first.hasValue()) return Mono.<Void>empty();
                    return Mono.fromCallable(()->authenticate(first.get()))
                            .flatMap(token -> work.authenticatedUser(platform,token).flatMap(user->{
                                Flux<WebSocketMessage> outbound = work.lobbyTicks(user.id())
                                        .onBackpressureLatest()
                                        .concatMap(tick -> work.lobby(platform,token), 1)
                                        .distinctUntilChanged()
                                        .concatMap(lobby -> Mono.fromCallable(() -> json.writeValueAsString(
                                                Map.of("type","lobby","data",lobby))), 1)
                                        .map(session::textMessage);

                                // The replayed first frame is skipped. Receiving any further
                                // command is a protocol violation; disconnect cancels sending.
                                Mono<Void> inboundDone = inbound.skip(1).next()
                                        .flatMap(ignored -> Mono.<Void>error(new InvalidFrame()));
                                return Mono.firstWithSignal(session.send(outbound), inboundDone);
                            }));
                })
                .then()
                .onErrorResume(Platform.Unauthorized.class, e -> session.close(CloseStatus.POLICY_VIOLATION))
                .onErrorResume(InvalidFrame.class, e -> session.close(CloseStatus.POLICY_VIOLATION))
                .onErrorResume(TimeoutException.class, e -> session.close(CloseStatus.POLICY_VIOLATION))
                .onErrorResume(e -> session.close(CloseStatus.SERVER_ERROR));
    }

    private String authenticate(String text) {
        try {
            var node = json.readTree(text);
            if (node == null || !"authenticate".equals(node.path("type").asText()) ||
                    !node.path("token").isTextual()) throw new InvalidFrame();
            String token = node.get("token").asText();
            return token;
        } catch (Platform.Unauthorized e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidFrame();
        }
    }

    // Keep session expiration active even when there are no live WebSockets.
    // Spring's scheduling thread is separate from Reactor Netty.
    @Scheduled(fixedDelay=1000)
    public Mono<Void> sweep() { return workerEnabled?work.stateRun(()->platform.workerTick(partitions)):Mono.empty(); }
}
