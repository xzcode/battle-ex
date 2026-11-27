package com.battleex.web;

import com.battleex.community.Teams;
import com.battleex.community.Tournaments;
import com.battleex.operations.Operations;
import com.battleex.platform.Platform;
import com.battleex.platform.ReactiveWork;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.*;

@RestController
@RequestMapping("/api/v1/community")
public final class CommunityApi {
    private final Platform p;private final ReactiveWork w;
    public CommunityApi(Platform p,ReactiveWork w){this.p=p;this.w=w;}
    public record MyTeam(Teams.Team team){}
    public record TeamInput(String name,String tag,String description){}
    public record ApplicationInput(String message){}
    public record Review(Boolean accept){}
    public record MemberRole(Teams.Role role){}
    public record Transfer(UUID targetId){}
    public record EventInput(String name,String gameId,String description,int capacity){}
    public record CheckIn(Boolean ready){}
    public record Ruling(UUID winner,Tournaments.Source source,String reason){}
    public record Reason(String reason){}
    @GetMapping("/teams") Mono<Teams.Page<Teams.Team>> teams(@RequestHeader(value="Authorization",required=false)String a,@RequestParam(defaultValue="")String search,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="50")int limit){return w.state(()->p.teams(Api.bearer(a),search,offset,limit));}
    @GetMapping("/teams/mine") Mono<MyTeam> mine(@RequestHeader(value="Authorization",required=false)String a){return w.state(()->new MyTeam(p.myTeam(Api.bearer(a))));}
    @PostMapping("/teams") @ResponseStatus(HttpStatus.CREATED) Mono<Teams.Team> create(@RequestHeader(value="Authorization",required=false)String a,@RequestBody TeamInput b){return w.state(()->p.createTeam(Api.bearer(a),b.name(),b.tag(),b.description()));}
    @GetMapping("/teams/{id}") Mono<Teams.Detail> detail(@RequestHeader(value="Authorization",required=false)String a,@PathVariable UUID id){return w.state(()->p.teamDetail(Api.bearer(a),id));}
    @PostMapping("/teams/{id}/edit") Mono<Teams.Team> edit(@RequestHeader(value="Authorization",required=false)String a,@PathVariable UUID id,@RequestBody TeamInput b){return w.state(()->p.editTeam(Api.bearer(a),id,b.name(),b.tag(),b.description()));}
    @PostMapping("/teams/{id}/applications") Mono<Teams.Application> apply(@RequestHeader(value="Authorization",required=false)String a,@PathVariable UUID id,@RequestBody ApplicationInput b){return w.state(()->p.applyTeam(Api.bearer(a),id,b.message()));}
    @DeleteMapping("/teams/{id}/applications/mine") @ResponseStatus(HttpStatus.NO_CONTENT) Mono<Void> withdraw(@RequestHeader(value="Authorization",required=false)String a,@PathVariable UUID id){return w.stateRun(()->p.withdrawTeam(Api.bearer(a),id));}
    @PostMapping("/teams/{id}/applications/{user}/review") Mono<Teams.Team> review(@RequestHeader(value="Authorization",required=false)String a,@PathVariable UUID id,@PathVariable UUID user,@RequestBody Review b){return w.state(()->{if(b.accept()==null)throw new IllegalArgumentException("accept required");return p.reviewTeam(Api.bearer(a),id,user,b.accept());});}
    @PostMapping("/teams/{id}/members/{user}/role") Mono<Teams.Team> role(@RequestHeader(value="Authorization",required=false)String a,@PathVariable UUID id,@PathVariable UUID user,@RequestBody MemberRole b){return w.state(()->p.teamRole(Api.bearer(a),id,user,b.role()));}
    @PostMapping("/teams/{id}/transfer") Mono<Teams.Team> transfer(@RequestHeader(value="Authorization",required=false)String a,@PathVariable UUID id,@RequestBody Transfer b){return w.state(()->p.transferTeam(Api.bearer(a),id,b.targetId()));}
    @DeleteMapping("/teams/{id}/members/{user}") @ResponseStatus(HttpStatus.NO_CONTENT) Mono<Void> remove(@RequestHeader(value="Authorization",required=false)String a,@PathVariable UUID id,@PathVariable UUID user){return w.stateRun(()->p.removeTeamMember(Api.bearer(a),id,user));}
    @DeleteMapping("/teams/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) Mono<Void> disband(@RequestHeader(value="Authorization",required=false)String a,@PathVariable UUID id){return w.stateRun(()->p.disbandTeam(Api.bearer(a),id));}

    @GetMapping("/events") Mono<Teams.Page<Tournaments.Event>> events(@RequestHeader(value="Authorization",required=false)String a,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="30")int limit){return w.state(()->p.events(Api.bearer(a),offset,limit));}
    @GetMapping("/events/{id}") Mono<Tournaments.Event> event(@RequestHeader(value="Authorization",required=false)String a,@PathVariable UUID id){return w.state(()->p.event(Api.bearer(a),id));}
    @PostMapping("/events/{id}/register") Mono<Tournaments.Event> register(@RequestHeader(value="Authorization",required=false)String a,@PathVariable UUID id){return w.state(()->p.registerEvent(Api.bearer(a),id));}
    @DeleteMapping("/events/{id}/register") Mono<Tournaments.Event> leave(@RequestHeader(value="Authorization",required=false)String a,@PathVariable UUID id){return w.state(()->p.withdrawEvent(Api.bearer(a),id));}
    @PostMapping("/events/{id}/slots/{slot}/check-in") Mono<Tournaments.Event> check(@RequestHeader(value="Authorization",required=false)String a,@PathVariable UUID id,@PathVariable int slot,@RequestBody CheckIn b){return w.state(()->{if(b.ready()==null)throw new IllegalArgumentException("ready required");return p.checkInEvent(Api.bearer(a),id,slot,b.ready());});}
    @PostMapping("/admin/events") @ResponseStatus(HttpStatus.CREATED) Mono<Tournaments.Event> createEvent(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="X-Battle-Moderator",required=false)String k,@RequestBody EventInput b){return w.state(()->p.createEvent(Api.bearer(a),k,b.name(),b.gameId(),b.description(),b.capacity()));}
    @PostMapping("/admin/events/{id}/start") Mono<Tournaments.Event> start(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="X-Battle-Moderator",required=false)String k,@PathVariable UUID id){return w.state(()->p.startEvent(Api.bearer(a),k,id));}
    @PostMapping("/admin/events/{id}/slots/{slot}/retry") Mono<Tournaments.Event> retry(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="X-Battle-Moderator",required=false)String k,@PathVariable UUID id,@PathVariable int slot,@RequestBody Reason b){return w.state(()->p.retryEvent(Api.bearer(a),k,id,slot,b.reason()));}
    @PostMapping("/admin/events/{id}/slots/{slot}/decide") Mono<Tournaments.Event> decide(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="X-Battle-Moderator",required=false)String k,@PathVariable UUID id,@PathVariable int slot,@RequestBody Ruling b){return w.state(()->p.decideEvent(Api.bearer(a),k,id,slot,b.winner(),b.source(),b.reason()));}
    @PostMapping("/admin/events/{id}/cancel") Mono<Tournaments.Event> cancel(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="X-Battle-Moderator",required=false)String k,@PathVariable UUID id,@RequestBody Reason b){return w.state(()->p.cancelEvent(Api.bearer(a),k,id,b.reason()));}
}
