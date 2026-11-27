package com.battleex.web;

import com.battleex.network.NetworkAuthority;
import com.battleex.platform.Platform;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/network")
public final class NetworkApi {
    private final NetworkAuthority authority;
    private final Platform platform;
    public NetworkApi(NetworkAuthority authority,Platform platform){this.authority=authority;this.platform=platform;}
    @GetMapping("/info")
    Map<String,Object> info(@RequestHeader(value="Authorization",required=false)String auth){
        // Discovery key is public; still require a valid session through the platform endpoint.
        platform.authenticate(Api.bearer(auth));
        return Map.of("enabled",authority.enabled(),"issuer",authority.issuer());
    }
    @PostMapping("/device")
    NetworkAuthority.Enrollment enroll(@RequestHeader(value="Authorization",required=false)String auth,
            @RequestBody NetworkAuthority.DeviceRequest req){return authority.enroll(Api.bearer(auth),req);}
    public record SnapshotRequest(String nonce) {}
    @PostMapping("/snapshot")
    ResponseEntity<NetworkAuthority.SignedSnapshot> snapshot(@RequestHeader(value="Authorization",required=false)String auth, @RequestBody SnapshotRequest req){
        try{return ResponseEntity.ok(authority.snapshot(Api.bearer(auth),req.nonce()));}
        catch(NetworkAuthority.NoRoom e){return ResponseEntity.noContent().build();}
    }
    @PostMapping("/disconnect") @ResponseStatus(HttpStatus.NO_CONTENT)
    void disconnect(@RequestHeader(value="Authorization",required=false)String auth){authority.disconnect(Api.bearer(auth));}
    @PostMapping("/relay/authorize")
    ResponseEntity<Void> authorize(@RequestHeader(value="X-Relay-Secret",required=false)String secret,
             @RequestBody NetworkAuthority.Lease lease){
        return ResponseEntity.status(authority.authorize(secret,lease)?HttpStatus.NO_CONTENT:HttpStatus.FORBIDDEN).build();
    }
}
