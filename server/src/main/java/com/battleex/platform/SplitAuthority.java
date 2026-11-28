package com.battleex.platform;

import com.battleex.network.NetworkAuthority;
import com.battleex.competition.Competition;
import com.battleex.room.RoomDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.*;
import com.mongodb.client.model.*;
import com.mongodb.reactivestreams.client.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.*;
import reactor.core.scheduler.Scheduler;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.LongAdder;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.mongodb.client.model.Filters.*;

/**
 * Replica-set authority v2: explicit entity documents and atomic dirty-row
 * writes. A small commit fence still serializes domain decisions that read
 * the whole platform; this prevents write skew in the existing domain model.
 * Cached row snapshots use a bounded durable change journal to avoid full
 * collection scans on ordinary cross-node reads.
 */
final class SplitAuthority implements AutoCloseable {
    private static final String ID="battle-authority-v2",LEGACY="battle-authority-v1";
    private static final String META="authority_meta",JOURNAL="outbox_events";
    private static final int SCHEMA=2,MAX_DELTA=4096;
    private static final Duration TIMEOUT=Duration.ofSeconds(12);
    private static final TransactionOptions TX=TransactionOptions.builder()
        .readConcern(ReadConcern.SNAPSHOT).writeConcern(WriteConcern.MAJORITY).readPreference(ReadPreference.primary()).build();
    private static final class Conflict extends RuntimeException {}
    private record Cached(long version,SplitSchema.State state){}
    private record Read(Document meta,Cached cached){}
    private record Decision<T>(Cached next,T result,Map<SplitSchema.Key,Document> upserts,Set<SplitSchema.Key> deletes){}
    private final MongoClient client;
    private final MongoDatabase db;
    private final Platform platform;
    private final NetworkAuthority network;
    private final ObjectMapper json;
    private final SplitSchema schema;
    private final String fingerprint;
    private final boolean migrate;
    private final AtomicReference<Cached> cache=new AtomicReference<>();
    private final Sinks.Many<Long> notifications=Sinks.many().multicast().directBestEffort();
    private volatile Disposable watcher,roomWatcher;
    private final LongAdder attempts=new LongAdder(),commits=new LongAdder(),retriesCount=new LongAdder(),
            cacheHits=new LongAdder(),fullLoads=new LongAdder(),deltaLoads=new LongAdder(),changedRows=new LongAdder(),projectedReads=new LongAdder(),scopedRoomWrites=new LongAdder();
    ClusterAuthority.StorageMetrics metrics(){
        Cached c=cache.get();
        return new ClusterAuthority.StorageMetrics("mongo-split",c==null?0:c.version(),attempts.sum(),commits.sum(),
                retriesCount.sum(),cacheHits.sum(),fullLoads.sum(),deltaLoads.sum(),changedRows.sum(),projectedReads.sum(),scopedRoomWrites.sum());
    }
    SplitAuthority(MongoClient client,MongoDatabase db,Platform platform,NetworkAuthority network,ObjectMapper json,String fingerprint,boolean migrate){
        this.client=client;this.db=db.withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);this.platform=platform;this.network=network;this.json=json;
        this.schema=new SplitSchema(json);this.fingerprint=fingerprint;this.migrate=migrate;
    }
    private MongoCollection<Document> col(String name){return db.getCollection(name).withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);}
    private static Mono<Void> done(Publisher<?> p){return Mono.from(p).then();}
    private static boolean transientFailure(Throwable e){
        return e instanceof Conflict||e instanceof MongoException m&&!unknownCommit(e)&&m.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
    }
    private static boolean unknownCommit(Throwable e){return e instanceof MongoException m&&m.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);}
    private <T> Mono<T> transaction(Function<ClientSession,Mono<T>> work){
        return Mono.usingWhen(Mono.from(client.startSession()),s->Mono.defer(()->{
            attempts.increment();s.startTransaction(TX);
            return work.apply(s).flatMap(result->Mono.defer(()->done(s.commitTransaction()))
                .retryWhen(Retry.backoff(4,Duration.ofMillis(30)).filter(SplitAuthority::unknownCommit)
                    .onRetryExhaustedThrow((spec,signal)->signal.failure())).thenReturn(result));
        }),s->Mono.fromRunnable(s::close),(s,e)->abort(s),this::abort);
    }
    private Mono<Void> abort(ClientSession s){
        return Mono.defer(()->s.hasActiveTransaction()?done(s.abortTransaction()).onErrorResume(e->Mono.empty()):Mono.empty())
                .doFinally(sig->s.close());
    }
    private <T> Mono<T> retries(Mono<T> work){
        return work.retryWhen(Retry.backoff(16,Duration.ofMillis(8)).maxBackoff(Duration.ofMillis(150))
                .filter(SplitAuthority::transientFailure).doBeforeRetry(signal->retriesCount.increment()).onRetryExhaustedThrow((spec,signal)->new ClusterAuthority.Unavailable()));
    }
    void initialize(){
        // Fail at startup for standalone MongoDB; never silently fall back.
        Document hello=Mono.from(db.runCommand(new Document("hello",1))).block(Duration.ofSeconds(15));
        if(hello==null||hello.getString("setName")==null)throw new IllegalStateException("mongo-split requires a MongoDB replica set");
        // Materialize collections outside transactions and install indexes.
        List<String> names=new ArrayList<>(SplitSchema.COLLECTIONS);names.addAll(List.of(META,JOURNAL,"authority_state"));
        Flux.fromIterable(names).concatMap(n->done(db.createCollection(n))
                .onErrorResume(MongoCommandException.class,e->e.getErrorCode()==48?Mono.empty():Mono.error(e)))
                .then().block(Duration.ofSeconds(60));
        Flux.fromIterable(List.of(
            Map.entry("matchmaking_tickets",Indexes.ascending("data.gameId","data.mode","data.region","data.queuedAt")),
            Map.entry("matches",Indexes.ascending("data.players.id","data.createdAt")),
            Map.entry("ratings",Indexes.compoundIndex(Indexes.ascending("data.gameId"),Indexes.descending("data.points","data.wins"),Indexes.ascending("data.user.id"))),

            Map.entry("rooms",Indexes.ascending("data.view.members.userId")),
            Map.entry("rooms",Indexes.ascending("data.view.visibility")),
            Map.entry("sessions",Indexes.ascending("expiresEpoch")),

            Map.entry("suspensions",Indexes.ascending("data.target.id","data.revokedAt","data.until")),
            Map.entry("matches",Indexes.ascending("data.players.id","data.state")),

            Map.entry("reports",Indexes.ascending("data.status","data.createdAt")),
            Map.entry("team_members",Indexes.ascending("parent","ordinal")),
            Map.entry("tournament_entries",Indexes.ascending("parent","ordinal")),
            Map.entry("tournament_matches",Indexes.ascending("parent","ordinal")),
            Map.entry("audit_logs",Indexes.descending("ordinal")),
            Map.entry("relay_metrics",Indexes.ascending("node","minute"))
        )).concatMap(e->done(col(e.getKey()).createIndex(e.getValue()))).then().block(Duration.ofSeconds(60));
        Flux.fromIterable(List.of(
            Map.entry("sessions",Indexes.ascending("data.digest")),
            Map.entry("rooms",Indexes.ascending("data.view.networkId")),
            Map.entry("teams",Indexes.ascending("data.tag")),
            Map.entry("network_allocations",Indexes.ascending("data.subnet"))
        )).concatMap(e->done(col(e.getKey()).createIndex(e.getValue(),new IndexOptions().unique(true))))
            .then().block(Duration.ofSeconds(60));
        done(col(JOURNAL).createIndex(Indexes.ascending("createdAt"),new IndexOptions().expireAfter(24L,TimeUnit.HOURS))).block(Duration.ofSeconds(30));
        Cached initial=retries(transaction(s->Mono.from(col(META).find(s,eq("_id",ID)).first())
            .map(Optional::of).defaultIfEmpty(Optional.empty()).flatMap(found->{
                if(found.isPresent())return load(s,found.get());
                return bootstrap(s);
            })).onErrorResume(MongoWriteException.class,e->e.getError().getCategory()==ErrorCategory.DUPLICATE_KEY?Mono.error(new Conflict()):Mono.error(e)))
                .block(Duration.ofSeconds(120));
        if(initial==null)throw new IllegalStateException("split authority missing");
        synchronized(platform){restore(initial.state());}
        remember(initial);
        // Change stream is a notification, not the source of authorization.
        // On disconnect, reconnect; WebSockets also resync on bounded timer.
        watcher=Flux.defer(()->Flux.from(col(META).watch().fullDocument(com.mongodb.client.model.changestream.FullDocument.UPDATE_LOOKUP)))
            .handle((change,sink)->{Document d=change.getFullDocument();if(d!=null)sink.next(d);}).cast(Document.class)
            .filter(d->ID.equals(d.getString("_id"))).map(d->number(d,"revision")).distinctUntilChanged()
            .retryWhen(Retry.backoff(Long.MAX_VALUE,Duration.ofMillis(250)).maxBackoff(Duration.ofSeconds(5)))
            .subscribe(v->notifications.tryEmitNext(v),e->{});
        // Room-local updates do not advance the global commit fence. A
        // separate change stream plus periodic resync wakes lobby viewers.
        roomWatcher=Flux.defer(()->Flux.from(col("rooms").watch()))
            .retryWhen(Retry.backoff(Long.MAX_VALUE,Duration.ofMillis(250)).maxBackoff(Duration.ofSeconds(5)))
            .subscribe(v->notifications.tryEmitNext(-1L),e->{});

    }
    Flux<Long> notifications(){return notifications.asFlux();}
    private static long number(Document d,String name){Object v=d.get(name);if(!(v instanceof Number n))throw new IllegalStateException("missing numeric "+name);return n.longValue();}
    private Document meta(Cached c){return new Document("_id",ID).append("schema",SCHEMA).append("configuration",fingerprint)
        .append("version",c.version()).append("revision",c.state().revision()).append("relayNextGeneration",c.state().nextGeneration())
        .append("relayLastHistoryPrune",c.state().lastHistoryPrune());}
    private void validate(Document m){
        if(!Objects.equals(m.getInteger("schema"),SCHEMA)||!fingerprint.equals(m.getString("configuration"))||number(m,"version")<0)
            throw new IllegalStateException("split authority schema or configuration mismatch");
    }
    private SplitSchema.State state(Document m,Map<SplitSchema.Key,Document> rows){
        return new SplitSchema.State(number(m,"revision"),number(m,"relayNextGeneration"),number(m,"relayLastHistoryPrune"),Collections.unmodifiableMap(rows));
    }
    private ClusterAuthority.Snapshot current(){return new ClusterAuthority.Snapshot(platform.exportState(),network.exportState());}
    private void restore(SplitSchema.State s){
        var snapshot=schema.decode(s);platform.importState(snapshot.platform());network.importState(snapshot.network());
    }
    private Mono<Cached> bootstrap(ClientSession session){
        return Mono.from(col("authority_state").find(session,eq("_id",LEGACY)).first()).map(Optional::of).defaultIfEmpty(Optional.empty())
            .flatMap(old->{
                final Cached initial;
                synchronized(platform){
                    if(old.isPresent()){
                        Document d=old.get();
                        if(!migrate||!Objects.equals(d.getInteger("schema"),1)||!fingerprint.equals(d.getString("configuration")))
                            return Mono.error(new IllegalStateException("legacy authority present; offline explicit migration and matching policy required"));
                        try{
                            SplitSchema.checkKeys(json.valueToTree(d));
                            SplitSchema.checkKeys(json.readTree(d.getString("payload")));
                            var snap=json.readValue(d.getString("payload"),ClusterAuthority.Snapshot.class);
                            platform.importState(snap.platform());network.importState(snap.network());
                        }catch(Exception e){return Mono.error(new IllegalStateException("legacy migration snapshot invalid",e));}
                        initial=new Cached(number(d,"version"),schema.encode(current()));
                    }else initial=new Cached(0,schema.encode(current()));
                }
                // Refuse to adopt stray pre-existing rows. The legacy document
                // is retained as a sealed backup (schema 2 blocks old writers).
                return Flux.fromIterable(SplitSchema.COLLECTIONS).concatMap(name->Mono.from(col(name).countDocuments(session))
                    .flatMap(n->n==0?Mono.empty():Mono.error(new IllegalStateException("split collection not empty: "+name))))
                    .then(done(col(META).insertOne(session,meta(initial))))
                    .then(writeRows(session,initial.state().rows(),Set.of()))
                    .then(Mono.defer(()->{
                        if(old.isPresent()){
                            var d=old.get();
                            return Mono.from(col("authority_state").updateOne(session,and(eq("_id",LEGACY),eq("schema",1),eq("version",d.get("version"))),
                                    Updates.combine(Updates.set("schema",2),Updates.set("migratedTo",ID))))
                                    .flatMap(r->r.getMatchedCount()==1?Mono.empty():Mono.error(new Conflict()));
                        }
                        return done(col("authority_state").insertOne(session,new Document("_id",LEGACY).append("schema",2).append("configuration",fingerprint)
                                .append("version",0L).append("migratedTo",ID)));
                    })).thenReturn(initial);
            });
    }
    private Mono<Cached> full(ClientSession session,Document m){
        fullLoads.increment();
        return Flux.fromIterable(SplitSchema.COLLECTIONS).concatMap(name->Flux.from(col(name).find(session))
            .map(d->Map.entry(new SplitSchema.Key(name,d.getString("_id")),d)))
            .collectMap(Map.Entry::getKey,Map.Entry::getValue,TreeMap::new)
            .map(rows->new Cached(number(m,"version"),state(m,rows)));
    }
    private Mono<Cached> load(ClientSession session,Document m){
        return loadJournal(session,m).flatMap(c->{
            Map<SplitSchema.Key,Document> rows=new TreeMap<>(c.state().rows());
            rows.keySet().removeIf(k->k.collection().equals("rooms"));
            return Flux.from(col("rooms").find(session)).doOnNext(d->rows.put(new SplitSchema.Key("rooms",d.getString("_id")),d))
                    .then(Mono.fromSupplier(()->new Cached(c.version(),state(m,rows))));
        });
    }
    private Mono<Cached> loadJournal(ClientSession session,Document m){
        validate(m);long version=number(m,"version");Cached previous=cache.get();
        if(previous==null||previous.version()>version||version-previous.version()>MAX_DELTA)return full(session,m);
        if(previous.version()==version){cacheHits.increment();return Mono.just(previous);}
        return Flux.from(col(JOURNAL).find(session,and(gt("_id",previous.version()),lte("_id",version))).sort(Indexes.ascending("_id")))
            .collectList().flatMap(events->{
                if(events.size()!=version-previous.version())return full(session,m);
                long expected=previous.version();Map<String,Set<String>> changes=new TreeMap<>();
                for(Document e:events){
                    if(number(e,"_id")!=++expected||Boolean.TRUE.equals(e.getBoolean("fullReload")))return full(session,m);
                    for(Document change:e.getList("changes",Document.class)){
                        String name=change.getString("collection");
                        if(!SplitSchema.COLLECTIONS.contains(name))throw new IllegalStateException("invalid change journal collection");
                        changes.computeIfAbsent(name,x->new TreeSet<>()).add(change.getString("id"));
                    }
                }
                deltaLoads.increment();Map<SplitSchema.Key,Document> rows=new TreeMap<>(previous.state().rows());
                return Flux.fromIterable(changes.entrySet()).concatMap(e->{
                    for(String id:e.getValue())rows.remove(new SplitSchema.Key(e.getKey(),id));
                    // Chunk IDs so an accumulated journal cannot exceed BSON query size.
                    return Flux.fromIterable(e.getValue()).buffer(512).concatMap(ids->Flux.from(col(e.getKey()).find(session,in("_id",ids)))
                            .doOnNext(d->rows.put(new SplitSchema.Key(e.getKey(),d.getString("_id")),d)));
                }).then(Mono.fromSupplier(()->new Cached(version,state(m,rows))));
            });
    }
    private void remember(Cached c){cache.accumulateAndGet(c,(old,next)->old==null||next.version()>=old.version()?next:old);}
    private <T> Decision<T> decide(Cached before,Callable<T> task)throws Exception{
        synchronized(platform){
            restore(before.state());
            // Encode the canonical restored model, not its JSON/BSON numeric
            // representation from a prior application version.
            SplitSchema.State canonical=schema.encode(current());
            T result=task.call();
            SplitSchema.State after=schema.encode(current());
            Map<SplitSchema.Key,Document> upserts=new TreeMap<>();
            Set<SplitSchema.Key> deletes=new TreeSet<>(canonical.rows().keySet());deletes.removeAll(after.rows().keySet());
            after.rows().forEach((key,value)->{if(!Objects.equals(canonical.rows().get(key),value))upserts.put(key,value);});
            boolean changed=!upserts.isEmpty()||!deletes.isEmpty()||after.revision()!=canonical.revision()||
                    after.nextGeneration()!=canonical.nextGeneration()||after.lastHistoryPrune()!=canonical.lastHistoryPrune();
            return new Decision<>(new Cached(changed?before.version()+1:before.version(),changed?after:before.state()),result,upserts,deletes);
        }
    }
    private Mono<Void> writeRows(ClientSession s,Map<SplitSchema.Key,Document> rows,Set<SplitSchema.Key> deletes){
        Map<String,List<WriteModel<Document>>> operations=new TreeMap<>();
        rows.forEach((k,d)->operations.computeIfAbsent(k.collection(),x->new ArrayList<>())
            .add(new ReplaceOneModel<>(eq("_id",k.id()),d,new ReplaceOptions().upsert(true))));
        deletes.forEach(k->operations.computeIfAbsent(k.collection(),x->new ArrayList<>()).add(new DeleteOneModel<>(eq("_id",k.id()))));
        return Flux.fromIterable(operations.entrySet()).concatMap(e->Flux.fromIterable(e.getValue()).buffer(512)
            .concatMap(batch->done(col(e.getKey()).bulkWrite(s,batch,new BulkWriteOptions().ordered(true))))).then();
    }
    private <T> Mono<Decision<T>> save(ClientSession session,Cached before,Decision<T> next){
        if(next.next().version()==before.version())return Mono.just(next);
        var changes=new TreeSet<SplitSchema.Key>();changes.addAll(next.upserts().keySet());changes.addAll(next.deletes());
        boolean reload=changes.size()>MAX_DELTA;
        Document journal=new Document("_id",next.next().version()).append("createdAt",new Date())
            .append("revision",next.next().state().revision()).append("fullReload",reload)
            .append("changes",reload?List.of():changes.stream().map(k->new Document("collection",k.collection()).append("id",k.id())).toList());
        return Mono.from(col(META).replaceOne(session,and(eq("_id",ID),eq("schema",SCHEMA),eq("configuration",fingerprint),eq("version",before.version())),meta(next.next())))
            .flatMap(result->result.getMatchedCount()==1?Mono.empty():Mono.error(new Conflict()))
            .then(writeRows(session,next.upserts(),next.deletes())).then(done(col(JOURNAL).insertOne(session,journal))).thenReturn(next);
    }
    <T> Mono<T> execute(Callable<T> task,Scheduler worker){
        return retries(transaction(session->Mono.from(col(META).find(session,eq("_id",ID)).first())
            .switchIfEmpty(Mono.error(new ClusterAuthority.Unavailable()))
            .flatMap(m->load(session,m))
            .flatMap(before->Mono.fromCallable(()->decide(before,task)).subscribeOn(worker)
                .flatMap(next->save(session,before,next)))))
            .timeout(TIMEOUT)
            .flatMap(result->{remember(result.next());if(!result.upserts().isEmpty()||!result.deletes().isEmpty()){commits.increment();changedRows.add(result.upserts().size()+result.deletes().size());}return Mono.justOrEmpty(result.result());})
            .onErrorMap(MongoException.class,e->new ClusterAuthority.Unavailable())
            .onErrorMap(java.util.concurrent.TimeoutException.class,e->new ClusterAuthority.Unavailable());
    }
    // Indexed, read-only projections do not reconstruct the domain snapshot
    // or acquire the JVM's Platform lock. All reads share one Mongo snapshot.
    private <T> T value(Document row,Class<T> type){return json.convertValue(row.get("data"),type);}
    private Mono<Platform.Session> session(ClientSession s,String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))return Mono.error(new Platform.Unauthorized());
        String digest;
        try{digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));}
        catch(Exception e){return Mono.error(new IllegalStateException(e));}
        return Mono.from(col("sessions").find(s,eq("data.digest",digest)).first())
                .switchIfEmpty(Mono.error(new Platform.Unauthorized())).map(d->value(d,Platform.Session.class))
                .flatMap(user->{
                    if(!user.expiresAt().isAfter(Instant.now()))return Mono.error(new Platform.Unauthorized());
                    return Mono.from(col("suspensions").find(s,and(eq("data.target.id",user.user().id().toString()),eq("data.revokedAt",null),
                            or(eq("data.until",null),gt("data.until",Instant.now().getEpochSecond())))).first())
                            .hasElement().flatMap(banned->banned?Mono.error(new Platform.Unauthorized()):Mono.just(user));
                });
    }
    private <T> Mono<T> projection(String token,java.util.function.BiFunction<ClientSession,Platform.Session,Mono<T>> fn){
        return retries(transaction(s->Mono.from(col(META).find(s,eq("_id",ID)).first())
                .switchIfEmpty(Mono.error(new ClusterAuthority.Unavailable()))
                .flatMap(m->{validate(m);return session(s,token).flatMap(u->fn.apply(s,u));})))
                .timeout(TIMEOUT).doOnSuccess(v->projectedReads.increment())
                .onErrorMap(MongoException.class,e->new ClusterAuthority.Unavailable())
                .onErrorMap(java.util.concurrent.TimeoutException.class,e->new ClusterAuthority.Unavailable());
    }
    private record RoomDecision(RoomDirectory.RoomView view,boolean changed){}
    Mono<RoomDirectory.RoomView> roomMutation(String token,UUID id,Boolean ready,RoomDirectory.GameBuild build,boolean buildReport){
        return retries(transaction(s->Mono.from(col(META).find(s,eq("_id",ID)).first())
            .switchIfEmpty(Mono.error(new ClusterAuthority.Unavailable()))
            .flatMap(m->{validate(m);return session(s,token);})
            .flatMap(actor->Mono.from(col("rooms").find(s,eq("_id",id.toString())).first())
                .switchIfEmpty(Mono.error(new IllegalArgumentException("room not found")))
                .flatMap(d->{
                    var stored=value(d,RoomDirectory.StoredRoom.class);
                    var local=new RoomDirectory();local.importState(List.of(stored));
                    RoomDirectory.RoomView view=buildReport?local.reportGameBuild(actor.user().id(),id,build):
                            local.setReady(actor.user().id(),id,Objects.requireNonNull(ready));
                    if(view.equals(stored.view()))return Mono.just(new RoomDecision(view,false));
                    var replacement=schema.roomRow(local.exportState().getFirst());
                    return Mono.from(col("rooms").replaceOne(s,and(eq("_id",id.toString()),eq("data.view.revision",stored.view().revision())),replacement))
                            .flatMap(result->result.getMatchedCount()==1?Mono.just(new RoomDecision(view,true)):Mono.error(new Conflict()));
                }))))
            .timeout(TIMEOUT).map(result->{if(result.changed()){scopedRoomWrites.increment();notifications.tryEmitNext(-1L);}return result.view();})
            .onErrorMap(MongoException.class,e->new ClusterAuthority.Unavailable())
            .onErrorMap(java.util.concurrent.TimeoutException.class,e->new ClusterAuthority.Unavailable());
    }
    Mono<Competition.Leaderboard> leaderboard(String token,String game,int offset,int limit){
        Competition.game(game);page(offset,limit);
        return projection(token,(s,u)->{
            Bson filter=eq("data.gameId",game);
            return Mono.from(col("ratings").countDocuments(s,filter)).flatMap(total->
                Flux.from(col("ratings").find(s,filter)
                    .sort(Sorts.orderBy(Sorts.descending("data.points","data.wins"),Sorts.ascending("data.user.id"))).skip(offset).limit(limit))
                    .map(d->value(d,Competition.Rating.class)).collectList().map(rows->{
                        var entries=new ArrayList<Competition.Standing>();
                        for(int i=0;i<rows.size();i++)entries.add(new Competition.Standing((long)offset+i+1,rows.get(i)));
                        return new Competition.Leaderboard(game,offset,Math.toIntExact(total),List.copyOf(entries));
                    }));
        });
    }
    Mono<Competition.History> history(String token,int offset,int limit){
        page(offset,limit);
        return projection(token,(s,u)->{
            Bson filter=eq("data.players.id",u.user().id().toString());
            return Mono.from(col("matches").countDocuments(s,filter)).flatMap(total->
                Flux.from(col("matches").find(s,filter).sort(Sorts.orderBy(Sorts.descending("data.createdAt"),Sorts.ascending("_id"))).skip(offset).limit(limit))
                    .map(d->value(d,Competition.Match.class)).collectList().map(rows->new Competition.History(offset,Math.toIntExact(total),rows)));
        });
    }
    private static void page(int offset,int limit){
        if(offset<0||limit<1||limit>200)throw new IllegalArgumentException("invalid pagination (limit 1–200)");
    }
    Mono<Platform.Lobby> lobby(String token){
        return projection(token,(s,user)->{
            String id=user.user().id().toString();
            return Mono.from(col(META).find(s,eq("_id",ID)).first()).flatMap(m->
                Mono.from(col("rooms").find(s,eq("data.view.members.userId",id)).first())
                .map(d->Optional.of(value(d,RoomDirectory.StoredRoom.class).view())).defaultIfEmpty(Optional.empty())
                .flatMap(room->Flux.from(col("rooms").find(s,eq("data.view.visibility","PUBLIC")).sort(Indexes.ascending("_id")))
                    .map(d->value(d,RoomDirectory.StoredRoom.class).view())
                    .map(v->new RoomDirectory.RoomSummary(v.id(),v.name(),v.gameId(),v.capacity(),v.members().size(),v.state()))
                    .collectList().flatMap(publicRooms->
                        Mono.from(col("sessions").countDocuments(s,gt("expiresEpoch",Instant.now().toEpochMilli())))
                        .flatMap(count->{
                            List<String> ids=room.map(r->r.members().stream().map(v->v.userId().toString()).toList()).orElse(List.of());
                            return Flux.from(col("sessions").find(s,in("_id",ids))).map(d->value(d,Platform.Session.class))
                                .filter(v->v.expiresAt().isAfter(Instant.now())).collectMap(v->v.user().id(),v->v.user().username())
                                .flatMap(names->Mono.from(col("matchmaking_tickets").find(s,eq("_id",id)).first())
                                    .map(d->Optional.of(value(d,Competition.Ticket.class))).defaultIfEmpty(Optional.empty())
                                    .flatMap(ticket->Flux.from(col("matches").find(s,and(eq("data.players.id",id),in("data.state",List.of("WAITING","RUNNING","AWAITING_RESULT")))))
                                        .map(d->value(d,Competition.Match.class)).collectList().map(matches->{
                                            Competition.Match active=matches.stream().filter(v->v.state()==Competition.State.WAITING||v.state()==Competition.State.RUNNING).findFirst().orElse(null);
                                            Competition.Match pending=matches.stream().filter(v->v.state()==Competition.State.AWAITING_RESULT).findFirst().orElse(null);
                                            return new Platform.Lobby(user.user(),number(m,"revision"),Math.toIntExact(count),publicRooms,room.orElse(null),Map.copyOf(names),
                                                    new Competition.View(ticket.orElse(null),active,pending,platform.rankedGames()),
                                                    platform.moderationConfigured(),platform.operationsConfigured());
                                        })));
                        }))));
        });
    }

    Mono<Boolean> readiness(){
        return Mono.from(col(META).withReadConcern(ReadConcern.MAJORITY).find(eq("_id",ID)).first())
                .map(m->{validate(m);return true;}).defaultIfEmpty(false)
                .timeout(Duration.ofSeconds(3)).onErrorReturn(false);
    }
    @Override public void close(){if(watcher!=null)watcher.dispose();if(roomWatcher!=null)roomWatcher.dispose();notifications.tryEmitComplete();}
}
