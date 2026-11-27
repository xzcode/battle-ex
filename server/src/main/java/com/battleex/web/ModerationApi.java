package com.battleex.web;
import com.battleex.moderation.Moderation;
import com.battleex.platform.Platform;
import com.battleex.platform.ReactiveWork;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.*;

@RestController
@RequestMapping("/api/v1/moderation")
public final class ModerationApi {
    private final Platform platform;private final ReactiveWork work;
    public ModerationApi(Platform platform,ReactiveWork work){this.platform=platform;this.work=work;}
    public record Revoke(String reason) {}
    @PostMapping("/reports")
    Mono<Moderation.Report> report(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody Moderation.ReportInput input){
        return work.state(()->platform.fileReport(Api.bearer(auth),input));
    }
    @GetMapping("/reports/mine")
    Mono<Moderation.Page<Moderation.Report>> mine(@RequestHeader(value="Authorization",required=false)String auth,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="50")int limit){
        return work.state(()->platform.ownReports(Api.bearer(auth),offset,limit));
    }
    @GetMapping("/warnings")
    Mono<List<Moderation.Warning>> warnings(@RequestHeader(value="Authorization",required=false)String auth){return work.state(()->platform.warnings(Api.bearer(auth)));}
    @GetMapping("/admin/reports")
    Mono<Moderation.Page<Moderation.Report>> reports(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Battle-Moderator",required=false)String key,
            @RequestParam(required=false)Moderation.Status status,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="50")int limit){
        return work.state(()->platform.reviewReports(Api.bearer(auth),key,status,offset,limit));
    }
    @PostMapping("/admin/reports/{id}/review")
    Mono<Moderation.Report> review(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Battle-Moderator",required=false)String key,
            @PathVariable UUID id,@RequestBody Moderation.ReviewInput input){
        return work.state(()->platform.reviewReport(Api.bearer(auth),key,id,input));
    }
    @GetMapping("/admin/suspensions")
    Mono<Moderation.Page<Moderation.Suspension>> suspensions(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Battle-Moderator",required=false)String key,
            @RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="50")int limit){
        return work.state(()->platform.suspensions(Api.bearer(auth),key,offset,limit));
    }
    @PostMapping("/admin/suspensions/{id}/revoke")
    Mono<Moderation.Suspension> revoke(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="X-Battle-Moderator",required=false)String key,
            @PathVariable UUID id,@RequestBody Revoke input){
        return work.state(()->platform.revokeSuspension(Api.bearer(auth),key,id,input.reason()));
    }
}
