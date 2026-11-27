package com.battleex.operations;

import com.battleex.community.Teams;
import com.battleex.platform.Platform.User;
import java.util.*;

/** Per-account RBAC, backed by the configured operator credential and immutable root identities. */
public final class Operations {
    public enum Role { MODERATOR, EVENT_MANAGER, ANALYST }
    public record Grant(User user,Role role,User grantedBy,long at) {}
    public record Event(UUID id,User actor,String action,String subject,String reason,long at) {}
    public record Access(boolean root,List<Role> roles,boolean legacyModeration) {}
    public record Dump(List<Grant> grants,List<Event> events) {}
    public record SessionRow(User user,long expiresAt,UUID roomId,boolean queued) {}
    public record RoomRow(UUID id,String name,String gameId,String visibility,String state,UUID host,int members,int capacity) {}
    public record Overview(long signedIn,long rooms,long runningMatches,long queued,long teams,long tournaments,long pendingReports,long activeSuspensions,boolean persistent) {}
    private final Set<UUID> roots;
    private final Map<String,Grant> grants=new HashMap<>();
    private final List<Event> events=new ArrayList<>();
    public Operations(String value){
        Set<UUID> ids=new TreeSet<>();
        if(value!=null&&!value.isBlank())for(String id:value.split(","))ids.add(UUID.fromString(id.strip()));
        roots=Set.copyOf(ids);
    }
    public String fingerprint(){return roots.isEmpty()?"":":operator-rbac-v1:"+roots.stream().sorted().map(UUID::toString).collect(java.util.stream.Collectors.joining(","));}
    public boolean configured(){return !roots.isEmpty();}
    private boolean root(UUID id){return roots.contains(id);}
    private String key(UUID id,Role role){return id+":"+role;}
    public Access access(UUID user){
        return new Access(root(user),Arrays.stream(Role.values()).filter(r->root(user)||grants.containsKey(key(user,r))).toList(),roots.isEmpty());
    }
    public void require(UUID user,Role role){
        if(!root(user)&&!grants.containsKey(key(user,role)))throw new SecurityException("operator role required: "+role);
    }
    public void requireModeration(UUID user){if(configured())require(user,Role.MODERATOR);}
    public void requireOverview(UUID user){
        if(!root(user)&&access(user).roles().isEmpty())throw new SecurityException("operator role required");
    }
    public void requireRoot(UUID user){if(!root(user))throw new SecurityException("root operator required");}
    public Grant grant(User actor,User target,Role role,String reason,long now){
        requireRoot(actor.id());validateReason(reason);
        if(role==null||root(target.id()))throw new IllegalArgumentException("invalid delegated role");
        String k=key(target.id(),role);Grant old=grants.get(k);if(old!=null)return old;
        Grant g=new Grant(target,role,actor,now);grants.put(k,g);audit(actor,"ROLE_GRANTED",k,reason,now);return g;
    }
    public void revoke(User actor,UUID target,Role role,String reason,long now){
        requireRoot(actor.id());validateReason(reason);
        if(target==null||role==null||root(target))throw new IllegalArgumentException("invalid delegated role");
        if(grants.remove(key(target,role))!=null)audit(actor,"ROLE_REVOKED",key(target,role),reason,now);
    }
    public List<Grant> grants(UUID actor){
        requireRoot(actor);return grants.values().stream().sorted(Comparator.comparing((Grant g)->g.user().id()).thenComparing(Grant::role)).toList();
    }
    public static void validateReason(String s){
        if(s==null||s.isBlank()||s.length()>4000)throw new IllegalArgumentException("written operator reason required");
    }
    public void audit(User actor,String action,String subject,String reason,long now){
        events.add(new Event(UUID.randomUUID(),actor,action,subject,reason,now));
    }
    public Teams.Page<Event> events(UUID actor,int offset,int limit){
        requireRoot(actor);return Teams.page(events.reversed(),offset,limit);
    }
    public Dump exportState(){return new Dump(grants.values().stream().sorted(Comparator.comparing((Grant g)->g.user().id()).thenComparing(Grant::role)).toList(),List.copyOf(events));}
    public void importState(Dump d){
        grants.clear();events.clear();if(d==null)return;
        for(Grant g:d.grants())if(grants.put(key(g.user().id(),g.role()),g)!=null)throw new IllegalStateException("duplicate persisted role");
        events.addAll(d.events());
    }
}
