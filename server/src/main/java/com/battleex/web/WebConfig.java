package com.battleex.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import org.springframework.web.server.WebFilter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Configuration
public class WebConfig implements WebFluxConfigurer {
    private final LobbySocket socket;
    private final String[] origins;
    public WebConfig(LobbySocket socket, @Value("${battle.allowed-origins:}") String allowed) {
        this.socket = socket;
        this.origins = Arrays.stream(allowed.split(",")).map(String::strip)
                .filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    @Bean SimpleUrlHandlerMapping lobbyHandlerMapping() {
        var mapping = new SimpleUrlHandlerMapping(Map.of("/ws/lobby", socket), -1);
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(origins));
        cors.setAllowedMethods(List.of("GET"));
        cors.setAllowCredentials(false);
        mapping.setCorsConfigurations(Map.of("/ws/lobby", cors));
        return mapping;
    }

    @Bean WebSocketHandlerAdapter webSocketHandlerAdapter() {
        var strategy = new ReactorNettyRequestUpgradeStrategy(
                () -> reactor.netty.http.server.WebsocketServerSpec.builder().maxFramePayloadLength(2048));
        return new WebSocketHandlerAdapter(new HandshakeWebSocketService(strategy));
    }

    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/**").allowedOrigins(origins)
                .allowedMethods("GET","POST","DELETE","OPTIONS").allowedHeaders("Authorization","Content-Type","X-Battle-Moderator")
                .allowCredentials(false);
    }

    @Bean @Order(Ordered.HIGHEST_PRECEDENCE)
    WebFilter noStore() {
        return (exchange, chain) -> {
            if (exchange.getRequest().getPath().pathWithinApplication().value().startsWith("/api/"))
                exchange.getResponse().getHeaders().setCacheControl("no-store");
            return chain.filter(exchange);
        };
    }
}
