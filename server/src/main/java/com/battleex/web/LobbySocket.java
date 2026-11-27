package com.battleex.web;

import com.battleex.platform.Platform;
import com.battleex.platform.ReactiveWork;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
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

    private static final class InvalidFrame extends RuntimeException {}

    public LobbySocket(Platform platform, ObjectMapper json, ReactiveWork work) {
        this.platform = platform;
        this.json = json;
        this.work = work;
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
                    return work.state(() -> authenticate(first.get()))
                            .flatMap(token -> {
                                Flux<WebSocketMessage> outbound = Flux.interval(Duration.ZERO, Duration.ofSeconds(1))
                                        .onBackpressureLatest()
                                        .concatMap(tick -> work.state(() -> platform.lobby(token)), 1)
                                        .distinctUntilChanged()
                                        .concatMap(lobby -> Mono.fromCallable(() -> json.writeValueAsString(
                                                Map.of("type","lobby","data",lobby))), 1)
                                        .map(session::textMessage);

                                // The replayed first frame is skipped. Receiving any further
                                // command is a protocol violation; disconnect cancels sending.
                                Mono<Void> inboundDone = inbound.skip(1).next()
                                        .flatMap(ignored -> Mono.<Void>error(new InvalidFrame()));
                                return Mono.firstWithSignal(session.send(outbound), inboundDone);
                            });
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
            platform.authenticate(token);
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
    public Mono<Void> sweep() { return work.stateRun(platform::sweep); }
}
