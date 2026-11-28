package com.battleex.web;

import com.battleex.network.NetworkAuthority;
import com.battleex.network.RelayDirectory;
import com.battleex.platform.Platform;
import com.battleex.platform.ReactiveWork;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/v1/network")
public final class NetworkApi {
    private final NetworkAuthority authority;
    private final Platform platform;
    private final ReactiveWork work;
    public NetworkApi(NetworkAuthority authority, Platform platform, ReactiveWork work) {
        this.authority = authority;
        this.platform = platform;
        this.work = work;
    }

    @GetMapping("/info")
    Mono<Map<String,Object>> info(@RequestHeader(value="Authorization",required=false)String auth) {
        return work.state(() -> {
            platform.authenticate(Api.bearer(auth));
            return Map.of("enabled",authority.enabled(),"issuer",authority.issuer());
        });
    }
    @PostMapping("/device")
    Mono<NetworkAuthority.Enrollment> enroll(@RequestHeader(value="Authorization",required=false)String auth,
            @RequestBody NetworkAuthority.DeviceRequest req) {
        return work.state(() -> authority.enroll(Api.bearer(auth),req));
    }
    public record SnapshotRequest(String nonce, Map<String,Integer> relayMeasurements) {}
    @PostMapping("/snapshot")
    Mono<ResponseEntity<NetworkAuthority.SignedSnapshot>> snapshot(@RequestHeader(value="Authorization",required=false)String auth,
                                                                    @RequestBody SnapshotRequest req) {
        String token=Api.bearer(auth);
        return work.fastSnapshot(token,req.nonce(),req.relayMeasurements()).flatMap(cached->{
            if(cached.isPresent())return Mono.just(ResponseEntity.ok(cached.get()));
            return work.state(() -> {
                try { return ResponseEntity.ok(authority.snapshot(token,req.nonce(),req.relayMeasurements())); }
                catch (NetworkAuthority.NoRoom e) { return ResponseEntity.noContent().build(); }
                catch (NetworkAuthority.NoRelay e) { return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build(); }
            });
        });
    }
    @PostMapping("/disconnect") @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> disconnect(@RequestHeader(value="Authorization",required=false)String auth) {
        return work.stateRun(() -> authority.disconnect(Api.bearer(auth)));
    }
    @PostMapping("/relay/heartbeat")
    Mono<ResponseEntity<RelayDirectory.Limits>> heartbeat(@RequestHeader(value="X-Relay-Secret",required=false)String secret,
                                          @RequestBody RelayDirectory.Heartbeat body) {
        return work.relayHeartbeat(authority,secret,body).map(limits->limits.isPresent()?ResponseEntity.ok(limits.get()):ResponseEntity.status(HttpStatus.FORBIDDEN).build());
    }
    @GetMapping("/relays")
    Mono<List<RelayDirectory.PublicStatus>> relays(@RequestHeader(value="Authorization",required=false)String auth) {
        return work.state(() -> authority.relayStatus(Api.bearer(auth)));
    }
    @GetMapping("/relays/{node}/history")
    Mono<RelayDirectory.History> relayHistory(@RequestHeader(value="Authorization",required=false)String auth,
                                              @PathVariable String node) {
        return work.relayHistory(authority,Api.bearer(auth),node);
    }
    @PostMapping("/relay/authorize")
    Mono<ResponseEntity<Void>> authorize(@RequestHeader(value="X-Relay-Secret",required=false)String secret,
             @RequestHeader(value="X-Relay-Instance",required=false)String instance,
             @RequestBody NetworkAuthority.Lease lease) {
        return work.relayAuthorize(authority,secret,instance,lease).map(allowed->ResponseEntity.status(allowed
                ? HttpStatus.NO_CONTENT : HttpStatus.FORBIDDEN).build());
    }
}
