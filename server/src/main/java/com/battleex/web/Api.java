package com.battleex.web;

import com.battleex.platform.Platform;
import com.battleex.room.RoomDirectory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public final class Api {
    public record Credentials(String username, String password) {}
    public record CreateRoom(String name, String gameId, int capacity, RoomDirectory.Visibility visibility) {}
    public record JoinRoom(String invite) {}
    public record Ready(Boolean ready) {}

    private final Platform platform;
    public Api(Platform platform) { this.platform = platform; }

    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    Platform.Login register(@RequestBody Credentials request) {
        return platform.register(request.username(), request.password());
    }
    @PostMapping("/login")
    Platform.Login login(@RequestBody Credentials request) {
        return platform.login(request.username(), request.password());
    }
    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestHeader(value="Authorization", required=false) String auth) { platform.logout(bearer(auth)); }
    @GetMapping("/lobby")
    Platform.Lobby lobby(@RequestHeader(value="Authorization", required=false) String auth) {
        return platform.lobby(bearer(auth));
    }
    @PostMapping("/rooms") @ResponseStatus(HttpStatus.CREATED)
    RoomDirectory.RoomView create(@RequestHeader(value="Authorization", required=false) String auth,
                                  @RequestBody CreateRoom room) {
        if (room.visibility() == null) throw new IllegalArgumentException("room visibility required");
        return platform.create(bearer(auth),room.name(),room.gameId(),room.capacity(),room.visibility());
    }
    @PostMapping("/rooms/{id}/join")
    RoomDirectory.RoomView join(@RequestHeader(value="Authorization", required=false) String auth,
                    @PathVariable UUID id, @RequestBody(required=false) JoinRoom request) {
        return platform.join(bearer(auth), id, request == null ? null : request.invite());
    }
    @PostMapping("/rooms/{id}/leave") @ResponseStatus(HttpStatus.NO_CONTENT)
    void leave(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) {
        platform.leave(bearer(auth), id);
    }
    @PostMapping("/rooms/{id}/ready")
    RoomDirectory.RoomView ready(@RequestHeader(value="Authorization", required=false) String auth,
                                  @PathVariable UUID id, @RequestBody Ready request) {
        if (request.ready() == null) throw new IllegalArgumentException("ready required");
        return platform.ready(bearer(auth), id, request.ready());
    }
    @PostMapping("/rooms/{id}/start")
    RoomDirectory.RoomView start(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) {
        return platform.start(bearer(auth),id);
    }
    @PostMapping("/rooms/{id}/finish")
    RoomDirectory.RoomView finish(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) {
        return platform.finish(bearer(auth),id);
    }
    @GetMapping("/rooms/{id}/invitation")
    Map<String,String> invitation(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) {
        return Map.of("invite", platform.invitation(bearer(auth),id,false));
    }
    @PostMapping("/rooms/{id}/invitation")
    Map<String,String> rotate(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) {
        return Map.of("invite", platform.invitation(bearer(auth),id,true));
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
        private ResponseEntity<Map<String,String>> error(HttpStatus code, RuntimeException e) {
            return ResponseEntity.status(code).body(Map.of("error", e.getMessage() == null ? code.getReasonPhrase() : e.getMessage()));
        }
    }
}
