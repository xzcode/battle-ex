package com.battleex.web;

import com.battleex.platform.ClusterAuthority;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/** Internal load-balancer probes, intentionally no credentials or topology. */
@RestController
@RequestMapping("/health")
public final class HealthApi {
    private final ClusterAuthority authority;
    public HealthApi(ClusterAuthority authority){this.authority=authority;}
    @GetMapping("/live") ResponseEntity<Void> live(){return ResponseEntity.noContent().build();}
    @GetMapping("/ready") Mono<ResponseEntity<Void>> ready(){
        return authority.readiness().map(ok->ResponseEntity.status(ok?204:503).build());
    }
}
