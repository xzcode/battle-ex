package com.battleex.web;

import com.battleex.competition.Competition;
import com.battleex.competition.ResultAuthority;
import com.battleex.platform.Platform;
import com.battleex.platform.ReactiveWork;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/competition")
public final class CompetitionApi {
    public record Enqueue(String gameId,Competition.Mode mode,String region) {}
    private final Platform platform;
    private final ReactiveWork work;
    public CompetitionApi(Platform platform,ReactiveWork work){this.platform=platform;this.work=work;}

    @PostMapping("/queue")
    Mono<Competition.View> enqueue(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody Enqueue request){
        return work.state(()->platform.enqueue(Api.bearer(auth),request.gameId(),request.mode(),request.region()));
    }
    @DeleteMapping("/queue")
    Mono<Competition.View> cancel(@RequestHeader(value="Authorization",required=false) String auth){
        return work.state(()->platform.cancelQueue(Api.bearer(auth)));
    }
    @GetMapping("/leaderboard/{game}")
    Mono<Competition.Leaderboard> leaderboard(@RequestHeader(value="Authorization",required=false) String auth,
            @PathVariable String game,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit){
        return work.leaderboard(platform,Api.bearer(auth),game,offset,limit);
    }
    @GetMapping("/history")
    Mono<Competition.History> history(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit){
        return work.history(platform,Api.bearer(auth),offset,limit);
    }
    // No client-authenticated winner submission endpoint. The operator's
    // configured game-result issuer must sign this immutable match result.
    @PostMapping("/trusted-result")
    Mono<Competition.Match> trusted(@RequestBody ResultAuthority.SignedResult request){
        return work.state(()->platform.trustedResult(request));
    }
}
