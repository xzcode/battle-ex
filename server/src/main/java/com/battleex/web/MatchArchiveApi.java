package com.battleex.web;
import com.battleex.competition.MatchArchive;
import com.battleex.platform.Platform;
import com.battleex.platform.ReactiveWork;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.*;

@RestController
@RequestMapping("/api/v1/competition")
public final class MatchArchiveApi {
    private final Platform platform;private final ReactiveWork work;
    public MatchArchiveApi(Platform platform,ReactiveWork work){this.platform=platform;this.work=work;}
    public record Share(Boolean share) {}
    @GetMapping("/matches/{id}")
    Mono<MatchArchive.Detail> detail(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id){
        return work.state(()->platform.matchDetail(Api.bearer(auth),id));
    }
    @PostMapping("/matches/{id}/replays")
    Mono<MatchArchive.Replay> add(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@RequestBody MatchArchive.ReplayInput input){
        return work.state(()->platform.addReplay(Api.bearer(auth),id,input));
    }
    @DeleteMapping("/matches/{id}/replays/{replay}") @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> remove(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@PathVariable UUID replay){
        return work.stateRun(()->platform.removeReplay(Api.bearer(auth),id,replay));
    }
    @PostMapping("/matches/{id}/share-status")
    Mono<MatchArchive.Detail> share(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id,@RequestBody Share body){
        return work.state(()->{if(body.share()==null)throw new IllegalArgumentException("share required");return platform.shareStatus(Api.bearer(auth),id,body.share());});
    }
    @GetMapping("/live")
    Mono<List<MatchArchive.Live>> live(@RequestHeader(value="Authorization",required=false)String auth){
        return work.state(()->platform.liveMatches(Api.bearer(auth)));
    }
}
