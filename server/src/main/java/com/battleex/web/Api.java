package com.battleex.web;

import com.battleex.platform.Platform;
import com.battleex.platform.ClusterAuthority;
import com.battleex.platform.ReactiveWork;
import com.battleex.room.RoomDirectory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequestMapping("/api/v1")
public final class Api {
    public record Credentials(String username, String password) {}
    public record CreateRoom(String name, String gameId, int capacity, RoomDirectory.Visibility visibility) {}
    public record JoinRoom(String invite) {}
    public record Ready(Boolean ready) {}

    private final Platform platform;
    private final ReactiveWork work;
    public Api(Platform platform, ReactiveWork work) { this.platform = platform; this.work = work; }

    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    Mono<Platform.Login> register(@RequestBody Credentials request) {
        return platform.register(request.username(), request.password());
    }
    @PostMapping("/login")
    Mono<Platform.Login> login(@RequestBody Credentials request) {
        return platform.login(request.username(), request.password());
    }
    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> logout(@RequestHeader(value="Authorization", required=false) String auth) {
        return work.stateRun(() -> platform.logout(bearer(auth)));
    }
    @GetMapping("/lobby")
    Mono<Platform.Lobby> lobby(@RequestHeader(value="Authorization", required=false) String auth) {
        return work.state(() -> platform.lobby(bearer(auth)));
    }
    @PostMapping("/rooms") @ResponseStatus(HttpStatus.CREATED)
    Mono<RoomDirectory.RoomView> create(@RequestHeader(value="Authorization", required=false) String auth,
                                       @RequestBody CreateRoom room) {
        return work.state(() -> {
            if (room.visibility() == null) throw new IllegalArgumentException("room visibility required");
            return platform.create(bearer(auth),room.name(),room.gameId(),room.capacity(),room.visibility());
        });
    }
    @PostMapping("/rooms/{id}/join")
    Mono<RoomDirectory.RoomView> join(@RequestHeader(value="Authorization", required=false) String auth,
                    @PathVariable UUID id, @RequestBody(required=false) JoinRoom request) {
        return work.state(() -> platform.join(bearer(auth), id, request == null ? null : request.invite()));
    }
    @PostMapping("/rooms/{id}/leave") @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> leave(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) {
        return work.stateRun(() -> platform.leave(bearer(auth), id));
    }
    public record GameBuildReport(RoomDirectory.GameBuild build) {}
    @PostMapping("/rooms/{id}/game-build")
    Mono<RoomDirectory.RoomView> gameBuild(@RequestHeader(value="Authorization",required=false) String auth,
                                           @PathVariable UUID id,@RequestBody GameBuildReport report){
        return work.state(()->platform.reportGameBuild(bearer(auth),id,report.build()));
    }
    @PostMapping("/rooms/{id}/ready")
    Mono<RoomDirectory.RoomView> ready(@RequestHeader(value="Authorization", required=false) String auth,
                                      @PathVariable UUID id, @RequestBody Ready request) {
        return work.state(() -> {
            if (request.ready() == null) throw new IllegalArgumentException("ready required");
            return platform.ready(bearer(auth), id, request.ready());
        });
    }
    @PostMapping("/rooms/{id}/start")
    Mono<RoomDirectory.RoomView> start(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) {
        return work.state(() -> platform.start(bearer(auth),id));
    }
    @PostMapping("/rooms/{id}/finish")
    Mono<RoomDirectory.RoomView> finish(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) {
        return work.state(() -> platform.finish(bearer(auth),id));
    }
    @GetMapping("/rooms/{id}/invitation")
    Mono<Map<String,String>> invitation(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) {
        return work.state(() -> Map.of("invite", platform.invitation(bearer(auth),id,false)));
    }
    @PostMapping("/rooms/{id}/invitation")
    Mono<Map<String,String>> rotate(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) {
        return work.state(() -> Map.of("invite", platform.invitation(bearer(auth),id,true)));
    }
    static String bearer(String header) {
        if (header == null || !header.startsWith("Bearer ")) throw new Platform.Unauthorized();
        return header.substring(7);
    }

    @RestControllerAdvice
    public static final class Errors {
        @ExceptionHandler(Platform.Unauthorized.class)
        ResponseEntity<Map<String,String>> unauthorized(Platform.Unauthorized e) { return error(HttpStatus.UNAUTHORIZED,e); }
        @ExceptionHandler(SecurityException.class)
        ResponseEntity<Map<String,String>> forbidden(SecurityException e) { return error(HttpStatus.FORBIDDEN,e); }
        @ExceptionHandler(IllegalArgumentException.class)
        ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e) { return error(HttpStatus.BAD_REQUEST,e); }
        @ExceptionHandler(IllegalStateException.class)
        ResponseEntity<Map<String,String>> conflict(IllegalStateException e) { return error(HttpStatus.CONFLICT,e); }
        @ExceptionHandler(ClusterAuthority.Unavailable.class)
        ResponseEntity<Map<String,String>> authorityUnavailable(ClusterAuthority.Unavailable e){
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After","1")
                    .body(Map.of("error",e.getMessage()));
        }
        @ExceptionHandler(RejectedExecutionException.class)
        ResponseEntity<Map<String,String>> busy(RejectedExecutionException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After","1")
                    .body(Map.of("error","server busy; retry later"));
        }
        private ResponseEntity<Map<String,String>> error(HttpStatus code, RuntimeException e) {
            return ResponseEntity.status(code).body(Map.of("error", e.getMessage() == null ? code.getReasonPhrase() : e.getMessage()));
        }
    }
}
