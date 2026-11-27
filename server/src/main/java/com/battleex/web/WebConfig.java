package com.battleex.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Arrays;

@Configuration
@EnableWebSocket
public class WebConfig implements WebSocketConfigurer, WebMvcConfigurer {
    private final LobbySocket socket;
    private final String[] origins;
    public WebConfig(LobbySocket socket, @Value("${battle.allowed-origins:}") String allowed) {
        this.socket = socket;
        this.origins = Arrays.stream(allowed.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/ws/lobby").setAllowedOrigins(origins);
    }
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/**").allowedOrigins(origins)
                .allowedMethods("GET","POST","OPTIONS").allowedHeaders("Authorization","Content-Type")
                .allowCredentials(false);
    }
    @Component
    public static class NoStore extends OncePerRequestFilter {
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                           FilterChain chain) throws ServletException, IOException {
            if (request.getRequestURI().startsWith("/api/")) response.setHeader("Cache-Control","no-store");
            chain.doFilter(request,response);
        }
    }
}
