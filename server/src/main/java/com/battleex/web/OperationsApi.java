package com.battleex.web;

import com.battleex.community.Teams;
import com.battleex.operations.Operations;
import com.battleex.platform.Platform;
import com.battleex.platform.ReactiveWork;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.*;

@RestController
@RequestMapping("/api/v1/operations")
public final class OperationsApi {
    private final Platform p;private final ReactiveWork w;
    public OperationsApi(Platform p,ReactiveWork w){this.p=p;this.w=w;}
    public record Grant(String username,Operations.Role role,String reason){}
    public record Revoke(Operations.Role role,String reason){}
    @GetMapping("/access") Mono<Operations.Access> access(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="X-Battle-Moderator",required=false)String k){return w.state(()->p.operatorAccess(Api.bearer(a),k));}
    @GetMapping("/overview") Mono<Operations.Overview> overview(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="X-Battle-Moderator",required=false)String k){return w.state(()->p.overview(Api.bearer(a),k));}
    @GetMapping("/roles") Mono<List<Operations.Grant>> roles(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="X-Battle-Moderator",required=false)String k){return w.state(()->p.operatorRoles(Api.bearer(a),k));}
    @PostMapping("/roles") Mono<Operations.Grant> grant(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="X-Battle-Moderator",required=false)String k,@RequestBody Grant b){return p.grantOperator(Api.bearer(a),k,b.username(),b.role(),b.reason());}
    @PostMapping("/roles/{id}/revoke") @ResponseStatus(HttpStatus.NO_CONTENT) Mono<Void> revoke(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="X-Battle-Moderator",required=false)String k,@PathVariable UUID id,@RequestBody Revoke b){return w.stateRun(()->p.revokeOperator(Api.bearer(a),k,id,b.role(),b.reason()));}
    public record Reason(String reason){}
    @GetMapping("/sessions") Mono<Teams.Page<Operations.SessionRow>> sessions(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="X-Battle-Moderator",required=false)String k,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="50")int limit){return w.state(()->p.operatorSessions(Api.bearer(a),k,offset,limit));}
    @PostMapping("/sessions/{id}/disconnect") @ResponseStatus(HttpStatus.NO_CONTENT) Mono<Void> disconnect(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="X-Battle-Moderator",required=false)String k,@PathVariable UUID id,@RequestBody Reason b){return w.stateRun(()->p.disconnectSession(Api.bearer(a),k,id,b.reason()));}
    @GetMapping("/rooms") Mono<Teams.Page<Operations.RoomRow>> rooms(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="X-Battle-Moderator",required=false)String k,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="50")int limit){return w.state(()->p.operatorRooms(Api.bearer(a),k,offset,limit));}
    @PostMapping("/rooms/{id}/close") @ResponseStatus(HttpStatus.NO_CONTENT) Mono<Void> close(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="X-Battle-Moderator",required=false)String k,@PathVariable UUID id,@RequestBody Reason b){return w.stateRun(()->p.closeRoom(Api.bearer(a),k,id,b.reason()));}
    @GetMapping("/audit") Mono<Teams.Page<Operations.Event>> audit(@RequestHeader(value="Authorization",required=false)String a,@RequestHeader(value="X-Battle-Moderator",required=false)String k,@RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="50")int limit){return w.state(()->p.operatorAudit(Api.bearer(a),k,offset,limit));}
}
