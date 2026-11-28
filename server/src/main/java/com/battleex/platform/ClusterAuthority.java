package com.battleex.platform;

import com.battleex.network.NetworkAuthority;
import com.battleex.competition.Competition;
import com.battleex.room.RoomDirectory;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mongodb.*;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.reactivestreams.client.*;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/**
 * Development active/active authority: a versioned MongoDB aggregate with
 * optimistic compare-and-set. No process-local state is trusted between calls.
 * This is a consistency baseline, not a sharded or high-throughput design.
 */
@Component
public final class ClusterAuthority implements SmartInitializingSingleton, DisposableBean {
    private static final Logger LOG=LoggerFactory.getLogger(ClusterAuthority.class);
    private static final String ID="battle-authority-v1";
    private static final int SCHEMA=1;
    private static final Duration IO_TIMEOUT=Duration.ofSeconds(4);
    public static final class Unavailable extends RuntimeException {
        public Unavailable(){super("shared authority unavailable; retry later");}
    }
    private static final class Conflict extends RuntimeException {}
    public record StorageMetrics(String mode,long cachedVersion,long transactionAttempts,long rowCommits,long retries,long cacheHits,long fullLoads,long deltaLoads,long changedRows,long projectedReads,long scopedRoomWrites) {}
    public StorageMetrics storageMetrics(){return split==null?new StorageMetrics(enabled?"mongo":"memory",0,0,0,0,0,0,0,0,0,0):split.metrics();}
    public Mono<Platform.Lobby> lobby(Platform p,String token,Scheduler worker){return splitMode&&ready?split.lobby(token):execute(()->p.lobby(token),worker);}
    public Mono<Competition.History> history(Platform p,String token,int offset,int limit,Scheduler worker){return splitMode&&ready?split.history(token,offset,limit):execute(()->p.matchHistory(token,offset,limit),worker);}
    public Mono<Competition.Leaderboard> leaderboard(Platform p,String token,String game,int offset,int limit,Scheduler worker){return splitMode&&ready?split.leaderboard(token,game,offset,limit):execute(()->p.leaderboard(token,game,offset,limit),worker);}
    public Mono<RoomDirectory.RoomView> roomReady(Platform p,String token,UUID id,boolean ready,Scheduler worker){
        return splitMode&&this.ready?split.roomMutation(token,id,ready,null,false):execute(()->p.ready(token,id,ready),worker);
    }
    public Mono<RoomDirectory.RoomView> roomBuild(Platform p,String token,UUID id,RoomDirectory.GameBuild build,Scheduler worker){
        return splitMode&&ready?split.roomMutation(token,id,null,build,true):execute(()->p.reportGameBuild(token,id,build),worker);
    }
    public record Snapshot(Platform.StateDump platform, NetworkAuthority.StateDump network) {}
    private record Attempt<T>(long version,String oldPayload,String payload,T result) {}

    private final ObjectProvider<Platform> platforms;
    private final ObjectProvider<NetworkAuthority> networks;
    private final ObjectMapper json;
    private final boolean enabled;
    private final boolean splitMode,migrate;
    private final MongoDatabase database;
    private SplitAuthority split;
    private final MongoClient client;
    private final MongoCollection<Document> collection;
    private String fingerprint;
    private volatile boolean ready;

    public ClusterAuthority(ObjectProvider<Platform> platforms,ObjectProvider<NetworkAuthority> networks,
            ObjectMapper mapper,@Value("${battle.state-mode:memory}") String mode,
            @Value("${battle.mongo-uri:}") String uri,@Value("${battle.mongo-db:battle_ex}") String database,
            @Value("${battle.mongo-migrate-legacy:false}") boolean migrate){
        this.platforms=platforms;this.networks=networks;
        json=mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        if(!Set.of("memory","mongo","mongo-split").contains(mode))throw new IllegalArgumentException("state mode must be memory, mongo or mongo-split");
        splitMode=mode.equals("mongo-split");enabled=!mode.equals("memory");this.migrate=migrate;
        if(migrate&&!splitMode)throw new IllegalArgumentException("legacy migration requires mongo-split");
        if(!enabled){client=null;collection=null;this.database=null;return;}
        if(uri.isBlank()||!database.matches("[a-zA-Z0-9_-]+"))
            throw new IllegalArgumentException("MongoDB account storage is required for shared authority");
        client=MongoClients.create(new ConnectionString(uri));
        this.database=client.getDatabase(database);
        collection=this.database.getCollection("authority_state")
                .withReadPreference(ReadPreference.primary()).withReadConcern(ReadConcern.MAJORITY)
                .withWriteConcern(WriteConcern.MAJORITY);
    }
    public boolean enabled(){return enabled;}
    public boolean splitMode(){return splitMode;}
    public Flux<Long> notifications(){return split==null?Flux.never():split.notifications();}

    @Override public void afterSingletonsInstantiated(){
        if(!enabled)return;
        Platform platform=platforms.getObject();NetworkAuthority network=networks.getObject();
        fingerprint=network.configurationFingerprint()+platform.competitionFingerprint();
        if(splitMode){split=new SplitAuthority(client,database,platform,network,json,fingerprint,migrate);split.initialize();ready=true;LOG.info("Split MongoDB authority enabled (schema 2)");return;}
        String payload;
        synchronized(platform){payload=encode(platform,network);}
        // Startup only. An existing authority must match the exact schema and
        // network configuration; never silently initialize/overwrite it.
        Mono.from(collection.updateOne(eq("_id",ID),combine(setOnInsert("schema",SCHEMA),
                setOnInsert("configuration",fingerprint),setOnInsert("version",0L),
                setOnInsert("payload",payload)),new UpdateOptions().upsert(true)))
                .onErrorResume(MongoWriteException.class,e->e.getError().getCategory()==ErrorCategory.DUPLICATE_KEY
                        ?Mono.empty():Mono.error(e)).block(Duration.ofSeconds(30));
        var current=read().block(Duration.ofSeconds(30));
        if(current==null)throw new IllegalStateException("shared authority missing");
        try{validate(current);}
        catch(Unavailable e){throw new IllegalStateException("shared authority schema or network configuration mismatch");}
        synchronized(platform){restore(current.getString("payload"),platform,network);}
        ready=true;
        LOG.info("Shared MongoDB authority enabled (schema {})",SCHEMA);
    }

    public <T> Mono<T> execute(Callable<T> task,Scheduler worker){
        if(!enabled)return Mono.fromCallable(task).subscribeOn(worker);
        if(splitMode)return Mono.defer(()->ready?split.execute(task,worker):Mono.error(new Unavailable()));
        return Mono.defer(()->{
            if(!ready)return Mono.error(new Unavailable());
            return read().flatMap(doc->Mono.fromCallable(()->apply(doc,task)).subscribeOn(worker))
                    .flatMap(attempt->{
                        if(attempt.oldPayload().equals(attempt.payload()))
                            return Mono.justOrEmpty(attempt.result());
                        // Return the result only once the exact version has been
                        // committed. No replay on unknown write outcomes.
                        return Mono.from(collection.updateOne(and(eq("_id",ID),eq("schema",SCHEMA),
                                        eq("configuration",fingerprint),eq("version",attempt.version())),
                                combine(set("payload",attempt.payload()),inc("version",1L))))
                                .timeout(IO_TIMEOUT)
                                .flatMap(write->write.getMatchedCount()==1
                                        ?Mono.justOrEmpty(attempt.result()):Mono.error(new Conflict()));
                    });
        }).retryWhen(Retry.backoff(16,Duration.ofMillis(4)).maxBackoff(Duration.ofMillis(80))
                .filter(Conflict.class::isInstance).onRetryExhaustedThrow((spec,signal)->new Unavailable()))
                .onErrorMap(MongoException.class,e->new Unavailable())
                .onErrorMap(TimeoutException.class,e->new Unavailable());
    }

    private Mono<Document> read(){
        return Mono.from(collection.find(eq("_id",ID)).first()).timeout(IO_TIMEOUT)
                .switchIfEmpty(Mono.error(new Unavailable()));
    }
    private void validate(Document doc){
        if(!Objects.equals(doc.getInteger("schema"),SCHEMA)||
                !Objects.equals(doc.getString("configuration"),fingerprint)||
                !(doc.get("version") instanceof Long)||doc.getString("payload")==null)
            throw new Unavailable();
    }
    private <T> Attempt<T> apply(Document doc,Callable<T> task)throws Exception{
        validate(doc);
        Platform platform=platforms.getObject();NetworkAuthority network=networks.getObject();
        synchronized(platform){
            // The lock only protects import/execute/export within this JVM.
            // Other instances are coordinated by the database version CAS.
            String before=doc.getString("payload");
            restore(before,platform,network);
            T result=task.call();
            String after=encode(platform,network);
            return new Attempt<>(doc.getLong("version"),before,after,result);
        }
    }
    private String encode(Platform p,NetworkAuthority n){
        try{return json.writeValueAsString(new Snapshot(p.exportState(),n.exportState()));}
        catch(Exception e){throw new Unavailable();}
    }
    private void restore(String payload,Platform p,NetworkAuthority n){
        try{
            Snapshot s=json.readValue(payload,Snapshot.class);
            p.importState(s.platform());
            n.importState(s.network());
        }catch(Exception e){throw new Unavailable();}
    }
    public Mono<Boolean> readiness(){
        if(!enabled)return Mono.just(true);
        return Mono.defer(()->{
            if(!ready)return Mono.just(false);
            if(splitMode)return split.readiness();
            return read().map(doc->{validate(doc);return true;}).onErrorReturn(false);
        });
    }
    @Override public void destroy(){ready=false;if(split!=null)split.close();if(client!=null)client.close();}
}
