package com.battleex.community;

import com.battleex.platform.Platform.User;
import java.util.*;

/** Team membership and applications; all mutations run in the authority transaction. */
public final class Teams {
    public enum Role { OWNER, OFFICER, MEMBER }
    public record Member(User user,Role role,long joinedAt) {}
    public record Team(UUID id,String name,String tag,String description,long createdAt,List<Member> members) {
        public Team {members=List.copyOf(members);}
    }
    public record Application(UUID teamId,User user,long at,String message) {}
    public record Detail(Team team,List<Application> applications,boolean myApplication) {}
    public record Page<T>(int offset,int total,List<T> entries) {}
    public record Dump(List<Team> teams,List<Application> applications) {}
    private final Map<UUID,Team> teams=new HashMap<>();
    private final Map<UUID,UUID> membership=new HashMap<>();
    private final Map<String,Application> applications=new HashMap<>();

    private static String key(UUID team,UUID user){return team+":"+user;}
    private static String text(String v,int max,boolean required,String field){
        if(v==null)v="";
        v=v.strip();
        if((required&&v.isEmpty())||v.length()>max||v.chars().anyMatch(c->c<32&&c!='\n'&&c!='\t'))
            throw new IllegalArgumentException("invalid "+field);
        return v;
    }
    private Team team(UUID id){Team t=teams.get(id);if(t==null)throw new IllegalArgumentException("team not found");return t;}
    private Member member(Team t,UUID actor){return t.members().stream().filter(m->m.user().id().equals(actor)).findFirst().orElse(null);}
    private Member requireMember(Team t,UUID actor){
        Member m=member(t,actor);if(m==null)throw new SecurityException("team membership required");return m;
    }
    private void manager(Team t,UUID actor){if(requireMember(t,actor).role()==Role.MEMBER)throw new SecurityException("team manager required");}
    private void owner(Team t,UUID actor){if(requireMember(t,actor).role()!=Role.OWNER)throw new SecurityException("team owner required");}
    private String name(String value,UUID except){
        String v=text(value,48,true,"team name");
        if(teams.values().stream().anyMatch(t->!t.id().equals(except)&&t.name().equalsIgnoreCase(v)))throw new IllegalStateException("team name already exists");
        return v;
    }
    private String tag(String value,UUID except){
        String v=text(value,8,true,"team tag").toUpperCase(Locale.ROOT);
        if(!v.matches("[A-Z0-9]{2,8}"))throw new IllegalArgumentException("team tag must contain 2–8 ASCII letters or digits");
        if(teams.values().stream().anyMatch(t->!t.id().equals(except)&&t.tag().equals(v)))throw new IllegalStateException("team tag already exists");
        return v;
    }
    private void clearApplications(UUID user){applications.values().removeIf(a->a.user().id().equals(user));}
    public Team myTeam(UUID actor){UUID id=membership.get(actor);return id==null?null:team(id);}
    public Team create(User actor,String name,String tag,String description,long now){
        if(membership.containsKey(actor.id()))throw new IllegalStateException("already in a team");
        Team t=new Team(UUID.randomUUID(),name(name,null),tag(tag,null),text(description,2000,false,"team description"),now,List.of(new Member(actor,Role.OWNER,now)));
        teams.put(t.id(),t);membership.put(actor.id(),t.id());clearApplications(actor.id());return t;
    }
    public Team update(UUID actor,UUID id,String name,String tag,String description){
        Team t=team(id);owner(t,actor);
        Team next=new Team(id,name(name,id),tag(tag,id),text(description,2000,false,"team description"),t.createdAt(),t.members());
        teams.put(id,next);return next;
    }
    public Detail detail(UUID actor,UUID id){
        Team t=team(id);Member m=member(t,actor);
        var pending=m!=null&&m.role()!=Role.MEMBER?applications.values().stream().filter(a->a.teamId().equals(id))
                .sorted(Comparator.comparingLong(Application::at).thenComparing(a->a.user().id())).toList():List.<Application>of();
        return new Detail(t,pending,applications.containsKey(key(id,actor)));
    }
    public Application apply(User actor,UUID id,String message,long now){
        team(id);if(membership.containsKey(actor.id()))throw new IllegalStateException("leave your team first");
        String k=key(id,actor.id());Application old=applications.get(k);
        if(old!=null)return old;
        Application a=new Application(id,actor,now,text(message,1000,false,"application message"));
        applications.put(k,a);return a;
    }
    public void withdraw(UUID actor,UUID id){team(id);applications.remove(key(id,actor));}
    public Team review(UUID actor,UUID id,UUID user,boolean accept,long now,boolean eligible){
        Team t=team(id);manager(t,actor);
        Application a=applications.get(key(id,user));if(a==null)throw new IllegalArgumentException("application not found");
        if(!accept){applications.remove(key(id,user));return t;}
        if(!eligible)throw new IllegalStateException("applicant is suspended");
        if(membership.containsKey(user))throw new IllegalStateException("applicant already joined a team");
        var list=new ArrayList<>(t.members());list.add(new Member(a.user(),Role.MEMBER,now));
        Team next=new Team(t.id(),t.name(),t.tag(),t.description(),t.createdAt(),list);
        teams.put(id,next);membership.put(user,id);clearApplications(user);return next;
    }
    public Team role(UUID actor,UUID id,UUID target,Role role){
        Team t=team(id);owner(t,actor);Member m=requireMember(t,target);
        if(role==null||m.role()==Role.OWNER||role==Role.OWNER)throw new IllegalArgumentException("use ownership transfer for owner changes");
        return replace(t,t.members().stream().map(v->v.user().id().equals(target)?new Member(v.user(),role,v.joinedAt()):v).toList());
    }
    public Team transfer(UUID actor,UUID id,UUID target){
        Team t=team(id);owner(t,actor);requireMember(t,target);
        if(actor.equals(target))return t;
        return replace(t,t.members().stream().map(v->v.user().id().equals(actor)?new Member(v.user(),Role.OFFICER,v.joinedAt()):
                v.user().id().equals(target)?new Member(v.user(),Role.OWNER,v.joinedAt()):v).toList());
    }
    private Team replace(Team t,List<Member> members){
        Team next=new Team(t.id(),t.name(),t.tag(),t.description(),t.createdAt(),members);teams.put(t.id(),next);return next;
    }
    public void remove(UUID actor,UUID id,UUID target){
        Team t=team(id);Member m=requireMember(t,target);
        if(m.role()==Role.OWNER)throw new IllegalStateException("transfer ownership or disband first");
        if(!actor.equals(target)){
            Member manager=requireMember(t,actor);
            if(manager.role()==Role.MEMBER||(manager.role()==Role.OFFICER&&m.role()!=Role.MEMBER))throw new SecurityException("insufficient team role");
        }
        replace(t,t.members().stream().filter(v->!v.user().id().equals(target)).toList());membership.remove(target,id);
    }
    public void disband(UUID actor,UUID id){
        Team t=team(id);owner(t,actor);teams.remove(id);
        for(Member m:t.members())membership.remove(m.user().id(),id);
        applications.values().removeIf(a->a.teamId().equals(id));
    }
    public Page<Team> list(String search,int offset,int limit){
        String q=search==null?"":search.strip().toLowerCase(Locale.ROOT);
        return page(teams.values().stream().filter(t->t.name().toLowerCase(Locale.ROOT).contains(q)||t.tag().toLowerCase(Locale.ROOT).contains(q))
            .sorted(Comparator.comparing(Team::name,String.CASE_INSENSITIVE_ORDER).thenComparing(Team::id)).toList(),offset,limit);
    }
    public static <T> Page<T> page(List<T> rows,int offset,int limit){
        if(offset<0||limit<1||limit>200)throw new IllegalArgumentException("invalid pagination");
        int from=Math.min(offset,rows.size()),to=(int)Math.min((long)from+limit,rows.size());
        return new Page<>(offset,rows.size(),List.copyOf(rows.subList(from,to)));
    }
    public int count(){return teams.size();}
    public Dump exportState(){return new Dump(teams.values().stream().sorted(Comparator.comparing(Team::id)).toList(),
        applications.values().stream().sorted(Comparator.comparing(Application::teamId).thenComparing(a->a.user().id())).toList());}
    public void importState(Dump d){
        teams.clear();membership.clear();applications.clear();if(d==null)return;
        Set<String> names=new HashSet<>(),tags=new HashSet<>();
        for(Team t:d.teams()){
            if(t.members().stream().filter(m->m.role()==Role.OWNER).count()!=1||!names.add(t.name().toLowerCase(Locale.ROOT))||
               !tags.add(t.tag())||teams.put(t.id(),t)!=null)throw new IllegalStateException("invalid persisted team");
            for(Member m:t.members())if(membership.put(m.user().id(),t.id())!=null)throw new IllegalStateException("duplicate team membership");
        }
        for(Application a:d.applications())if(!teams.containsKey(a.teamId())||membership.containsKey(a.user().id())||
                applications.put(key(a.teamId(),a.user().id()),a)!=null)throw new IllegalStateException("invalid persisted team application");
    }
}
