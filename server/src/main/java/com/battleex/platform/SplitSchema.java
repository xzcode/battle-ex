package com.battleex.platform;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.bson.Document;
import java.util.*;

/**
 * Explicit, stable BSON layout for the v2 authority. Each logical row has
 * an independent document; never persist Java class/type discriminator keys.
 * This codec deliberately enumerates the storage contract, rather than
 * deriving collections from implementation class names.
 */
final class SplitSchema {
    record Key(String collection,String id) implements Comparable<Key>{
        public int compareTo(Key other){int c=collection.compareTo(other.collection);return c==0?id.compareTo(other.id):c;}
    }
    record State(long revision,long nextGeneration,long lastHistoryPrune,Map<Key,Document> rows){}
    private record Shape(String collection,String path,String... identity){}
    // Empty identity = a keyed map; otherwise one row per array element.
    private static final List<Shape> SHAPES=List.of(
        new Shape("sessions","platform.sessions","user.id"),
        new Shape("rooms","platform.rooms","view.id"),
        new Shape("matchmaking_tickets","platform.competition.tickets","user.id"),
        new Shape("matches","platform.competition.matches","id"),
        new Shape("ratings","platform.competition.ratings","gameId","user.id"),
        new Shape("replays","platform.archive.replays","id"),
        new Shape("match_consents","platform.archive.consents","matchId","userId"),
        new Shape("reports","platform.moderation.reports","id"),
        new Shape("suspensions","platform.moderation.suspensions","id"),
        new Shape("teams","platform.teams.teams","id"),
        new Shape("team_applications","platform.teams.applications","teamId","user.id"),
        new Shape("tournaments","platform.tournaments.events","id"),
        new Shape("operator_roles","platform.operations.grants","user.id","role"),
        new Shape("audit_logs","platform.operations.events","id"),
        new Shape("network_devices","network.devices"),
        new Shape("network_allocations","network.networks"),
        new Shape("network_quarantine","network.quarantine"),
        new Shape("network_revisions","network.revisions"),
        new Shape("relay_nodes","network.relays.live"),
        new Shape("relay_samples","network.relays.samples"),
        new Shape("relay_generations","network.relays.generations")
    );
    static final List<String> COLLECTIONS;
    static {
        var names=new ArrayList<>(SHAPES.stream().map(Shape::collection).toList());
        names.addAll(List.of("relay_metrics","team_members","tournament_entries","tournament_matches"));COLLECTIONS=List.copyOf(names);
    }
    private final ObjectMapper json;
    SplitSchema(ObjectMapper json){this.json=json;}
    private static JsonNode at(JsonNode n,String path){for(String p:path.split("\\."))n=n.path(p);return n;}
    private static ObjectNode parent(ObjectNode root,String path){
        ObjectNode n=root;String[] parts=path.split("\\.");
        for(int i=0;i<parts.length-1;i++)n=(ObjectNode)n.withObjectProperty(parts[i]);return n;
    }
    private static String leaf(String path){return path.substring(path.lastIndexOf('.')+1);}
    private static String rowId(JsonNode n,String... paths){
        var values=new ArrayList<String>();
        for(String p:paths){
            var id=at(n,p);
            if(!id.isTextual()||id.asText().isBlank()||id.asText().contains(":"))throw new IllegalArgumentException("invalid persisted entity ID: "+p);
            values.add(id.asText());
        }
        return String.join(":",values);
    }
    static void checkKeys(JsonNode n){
        if(n.isObject()){
            if(n.has("_class"))throw new IllegalArgumentException("forbidden MongoDB type metadata");
            n.elements().forEachRemaining(SplitSchema::checkKeys);
        }else if(n.isArray())n.elements().forEachRemaining(SplitSchema::checkKeys);
    }
    private Object bson(JsonNode n){
        // BSON values use explicit business names, with no default typing.
        if(n.isObject()){
            var d=new Document();n.fields().forEachRemaining(e->d.put(e.getKey(),bson(e.getValue())));return d;
        }
        if(n.isArray()){
            var a=new ArrayList<>();n.forEach(v->a.add(bson(v)));return a;
        }
        if(n.isNull())return null;
        if(n.isTextual())return n.textValue();
        if(n.isBoolean())return n.booleanValue();
        if(n.isIntegralNumber())return n.longValue();
        if(n.isFloatingPointNumber())return n.doubleValue();
        throw new IllegalArgumentException("unsupported BSON value");
    }
    private static void add(Map<Key,Document> rows,String collection,String id,Document d){
        if(rows.put(new Key(collection,id),d)!=null)throw new IllegalArgumentException("duplicate entity "+collection);
    }
    Document roomRow(com.battleex.room.RoomDirectory.StoredRoom room){
        JsonNode node=json.valueToTree(room);checkKeys(node);
        return new Document("_id",room.view().id().toString()).append("data",bson(node));
    }
    State encode(ClusterAuthority.Snapshot snapshot){
        ObjectNode tree=json.valueToTree(snapshot);checkKeys(tree);
        Map<Key,Document> rows=new TreeMap<>();
        for(Shape s:SHAPES){
            JsonNode part=at(tree,s.path());
            if(s.identity().length==0){
                if(!part.isObject())throw new IllegalArgumentException("invalid map "+s.path());
                part.fields().forEachRemaining(e->add(rows,s.collection(),e.getKey(),new Document("_id",e.getKey()).append("data",bson(e.getValue()))));
            }else{
                if(!part.isArray())throw new IllegalArgumentException("invalid list "+s.path());
                long ordinal=0;
                for(JsonNode item:part){
                    String id=rowId(item,s.identity());
                    ObjectNode record=(ObjectNode)item.deepCopy();
                    if(s.collection().equals("teams"))splitChildren(rows,id,record,"members","team_members","user.id");
                    if(s.collection().equals("tournaments")){
                        splitChildren(rows,id,record,"entrants","tournament_entries","user.id");
                        splitChildren(rows,id,record,"slots","tournament_matches","id");
                    }
                    Document d=new Document("_id",id).append("data",bson(record));
                    // The audit is append-only. Preserve append order across
                    // reloads; other lists have domain-defined order or IDs.
                    if(s.collection().equals("audit_logs"))d.append("ordinal",ordinal++);
                    if(s.collection().equals("sessions"))d.append("expiresEpoch",json.convertValue(item,Platform.Session.class).expiresAt().toEpochMilli());
                    add(rows,s.collection(),id,d);
                }
            }
        }
        JsonNode history=at(tree,"network.relays.history");
        history.fields().forEachRemaining(e->{
            for(JsonNode minute:e.getValue()){
                long epoch=minute.path("minute").longValue();
                String id=e.getKey()+":"+epoch;
                add(rows,"relay_metrics",id,new Document("_id",id).append("node",e.getKey()).append("minute",epoch).append("data",bson(minute)));
            }
        });
        return new State(at(tree,"platform.revision").longValue(),at(tree,"network.relays.nextGeneration").longValue(),
                at(tree,"network.relays.lastHistoryPrune").longValue(),Collections.unmodifiableMap(rows));
    }
    private void splitChildren(Map<Key,Document> rows,String parent,ObjectNode record,String field,String collection,String idPath){
        JsonNode children=record.remove(field);
        if(children==null||!children.isArray())throw new IllegalArgumentException("invalid nested list "+field);
        long ordinal=0;
        for(JsonNode item:children){
            JsonNode childId=at(item,idPath);
            String id=childId.isIntegralNumber()?childId.asText():rowId(item,idPath);
            String key=parent+":"+id;
            add(rows,collection,key,new Document("_id",key).append("parent",parent).append("ordinal",ordinal++).append("data",bson(item)));
        }
    }
    private void restoreChildren(ObjectNode record,String parent,String field,Map<String,List<Document>> byParent){
        ArrayNode array=record.putArray(field);
        for(Document child:byParent.getOrDefault(parent,List.of()).stream().sorted(Comparator.comparingLong(d->((Number)d.get("ordinal")).longValue())).toList())
            array.add(json.valueToTree(child.get("data")));
    }
    private static Map<String,List<Document>> children(Map<String,List<Document>> groups,String collection){
        Map<String,List<Document>> result=new HashMap<>();
        for(Document d:groups.getOrDefault(collection,List.of()))result.computeIfAbsent(d.getString("parent"),v->new ArrayList<>()).add(d);
        return result;
    }
    ClusterAuthority.Snapshot decode(State state){
        ObjectNode tree=json.createObjectNode();
        parent(tree,"platform.revision").put("revision",state.revision());
        parent(tree,"network.relays.nextGeneration").put("nextGeneration",state.nextGeneration());
        parent(tree,"network.relays.lastHistoryPrune").put("lastHistoryPrune",state.lastHistoryPrune());
        Map<String,List<Document>> grouped=new HashMap<>();
        state.rows().forEach((k,v)->grouped.computeIfAbsent(k.collection(),x->new ArrayList<>()).add(v));
        var members=children(grouped,"team_members");var entrants=children(grouped,"tournament_entries");var slots=children(grouped,"tournament_matches");
        for(Shape s:SHAPES){
            ObjectNode p=parent(tree,s.path());List<Document> rows=grouped.getOrDefault(s.collection(),List.of());
            if(s.identity().length==0){
                ObjectNode obj=p.putObject(leaf(s.path()));
                for(Document d:rows)obj.set(d.getString("_id"),json.valueToTree(d.get("data")));
            }else{
                ArrayNode arr=p.putArray(leaf(s.path()));
                if(s.collection().equals("audit_logs"))rows=rows.stream().sorted(Comparator.comparingLong(d->((Number)d.get("ordinal")).longValue())).toList();
                else rows=rows.stream().sorted(Comparator.comparing(d->d.getString("_id"))).toList();
                for(Document d:rows){
                    ObjectNode record=json.valueToTree(d.get("data"));String id=d.getString("_id");
                    if(s.collection().equals("teams"))restoreChildren(record,id,"members",members);
                    if(s.collection().equals("tournaments")){
                        restoreChildren(record,id,"entrants",entrants);restoreChildren(record,id,"slots",slots);
                    }
                    arr.add(record);
                }
            }
        }
        ObjectNode history=parent(tree,"network.relays.history").putObject("history");
        for(Document d:grouped.getOrDefault("relay_metrics",List.of()).stream()
                .sorted(Comparator.comparingLong(d->((Number)d.get("minute")).longValue())).toList()){
            history.withArrayProperty(d.getString("node")).add(json.valueToTree(d.get("data")));
        }
        checkKeys(tree);
        try{return json.treeToValue(tree,ClusterAuthority.Snapshot.class);}
        catch(Exception e){throw new IllegalStateException("invalid explicit MongoDB state schema",e);}
    }
}
