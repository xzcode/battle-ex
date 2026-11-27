package com.battleex.community;

import com.battleex.competition.Competition;
import com.battleex.platform.Platform.User;
import java.util.*;

/** Server-generated, single elimination individual tournaments. Never awards Elo. */
public final class Tournaments {
    public enum State { REGISTRATION, RUNNING, FINISHED, CANCELLED }
    public enum SlotState { BLOCKED, READY, ASSIGNED, BYE, DECIDED }
    public enum Source { PLAYED, FORFEIT }
    public record Entry(User user,long joinedAt) {}
    public record Ruling(UUID winner,Source source,User operator,String reason,long at) {}
    public record Slot(int id,int round,UUID first,UUID second,SlotState state,List<UUID> checkedIn,
                       UUID matchId,List<UUID> attempts,UUID winner,Ruling ruling) {
        public Slot {checkedIn=List.copyOf(checkedIn);attempts=List.copyOf(attempts);}
        boolean includes(UUID user){return user!=null&&(user.equals(first)||user.equals(second));}
        boolean resolved(){return state==SlotState.BYE||state==SlotState.DECIDED;}
    }
    public record Event(UUID id,String name,String gameId,String description,int capacity,User organizer,long createdAt,
                        State state,List<Entry> entrants,List<Slot> slots,UUID champion,String cancellationReason) {
        public Event {entrants=List.copyOf(entrants);slots=List.copyOf(slots);}
    }
    public record Dump(List<Event> events) {}
    private final Map<UUID,Event> events=new HashMap<>();

    private static String text(String s,int max,boolean required){
        s=s==null?"":s.strip();
        if((required&&s.isEmpty())||s.length()>max)throw new IllegalArgumentException("invalid event text");
        return s;
    }
    public Event create(User actor,String name,String game,String description,int capacity,long now){
        Competition.game(game);
        // Capacity is the organizer's chosen entrant count, not a fixed team
        // or account quota. 2^30 protects heap index calculations;
        // deployment's aggregate storage remains the actual practical limit.
        if(capacity<2||capacity>1_073_741_824)throw new IllegalArgumentException("invalid tournament capacity");
        Event e=new Event(UUID.randomUUID(),text(name,100,true),game,text(description,4000,false),capacity,actor,now,State.REGISTRATION,List.of(),List.of(),null,null);
        events.put(e.id(),e);return e;
    }
    public Event get(UUID id){Event e=events.get(id);if(e==null)throw new IllegalArgumentException("tournament not found");return e;}
    private Event replace(Event e,State state,List<Entry> entrants,List<Slot> slots,UUID champion,String cancellation){
        Event result=new Event(e.id(),e.name(),e.gameId(),e.description(),e.capacity(),e.organizer(),e.createdAt(),state,entrants,slots,champion,cancellation);
        events.put(e.id(),result);return result;
    }
    public Event register(UUID id,User user,long now){
        Event e=get(id);if(e.state()!=State.REGISTRATION)throw new IllegalStateException("registration closed");
        if(e.entrants().stream().anyMatch(v->v.user().id().equals(user.id())))return e;
        if(e.entrants().size()>=e.capacity())throw new IllegalStateException("event is full");
        List<Entry> next=new ArrayList<>(e.entrants());next.add(new Entry(user,now));
        return replace(e,e.state(),next,e.slots(),null,null);
    }
    public Event withdraw(UUID id,UUID user){
        Event e=get(id);if(e.state()!=State.REGISTRATION)throw new IllegalStateException("registration closed");
        return replace(e,e.state(),e.entrants().stream().filter(v->!v.user().id().equals(user)).toList(),e.slots(),null,null);
    }
    private static List<Integer> seeds(int size){
        List<Integer> values=List.of(1,2);
        for(int n=4;n<=size;n*=2){
            List<Integer> next=new ArrayList<>();for(int seed:values){next.add(seed);next.add(n+1-seed);}values=next;if(n==size)break;
        }
        return values;
    }
    private static Slot empty(int id,int size){
        int round=Integer.numberOfTrailingZeros(size)-(31-Integer.numberOfLeadingZeros(id));
        return new Slot(id,round,null,null,SlotState.BLOCKED,List.of(),null,List.of(),null,null);
    }
    private static Slot fill(Slot s,UUID a,UUID b){
        if(a==null||b==null)return new Slot(s.id(),s.round(),a,b,SlotState.BYE,List.of(),null,s.attempts(),a==null?b:a,null);
        return new Slot(s.id(),s.round(),a,b,SlotState.READY,List.of(),null,s.attempts(),null,null);
    }
    private Event advance(Event e,List<Slot> slots){
        // Heap layout; children are completed before their parent is seeded.
        int bottom=(slots.size()+1)/2;
        for(int i=bottom-1;i>=1;i--){
            Slot s=slots.get(i-1),left=slots.get(2*i-1),right=slots.get(2*i);
            if(s.state()==SlotState.BLOCKED&&left.resolved()&&right.resolved())slots.set(i-1,fill(s,left.winner(),right.winner()));
        }
        Slot finalSlot=slots.getFirst();
        boolean finished=finalSlot.resolved();
        return replace(e,finished?State.FINISHED:State.RUNNING,e.entrants(),slots,finished?finalSlot.winner():null,null);
    }
    public Event start(UUID id){
        Event e=get(id);if(e.state()!=State.REGISTRATION||e.entrants().size()<2)throw new IllegalStateException("at least two registered entrants required");
        int size=2;while(size<e.entrants().size())size*=2;
        List<Integer> order=seeds(size);List<Slot> slots=new ArrayList<>(size-1);
        for(int i=1;i<size;i++)slots.add(empty(i,size));
        for(int i=0;i<size/2;i++){
            int a=order.get(2*i),b=order.get(2*i+1),index=size/2+i;
            slots.set(index-1,fill(slots.get(index-1),a<=e.entrants().size()?e.entrants().get(a-1).user().id():null,
                b<=e.entrants().size()?e.entrants().get(b-1).user().id():null));
        }
        return advance(e,slots);
    }
    private Slot slot(Event e,int id){
        if(id<1||id>e.slots().size())throw new IllegalArgumentException("bracket slot not found");return e.slots().get(id-1);
    }
    public Slot slot(UUID event,int id){return slot(get(event),id);}
    private void running(Event e){if(e.state()!=State.RUNNING)throw new IllegalStateException("event not running");}
    private Event replaceSlot(Event e,Slot s){
        List<Slot> slots=new ArrayList<>(e.slots());slots.set(s.id()-1,s);
        return replace(e,e.state(),e.entrants(),slots,e.champion(),e.cancellationReason());
    }
    public boolean clearCheckIns(UUID user){
        boolean changed=false;
        for(Event original:new ArrayList<>(events.values())){
            Event e=original;
            for(Slot s:e.slots())if(s.checkedIn().contains(user)){
                e=replaceSlot(e,new Slot(s.id(),s.round(),s.first(),s.second(),s.state(),
                        s.checkedIn().stream().filter(id->!id.equals(user)).toList(),s.matchId(),s.attempts(),s.winner(),s.ruling()));changed=true;
            }
        }
        return changed;
    }
    public Event checkIn(UUID id,int index,UUID actor,boolean ready){
        Event e=get(id);running(e);Slot s=slot(e,index);
        if(s.state()!=SlotState.READY)throw new IllegalStateException("bracket slot not ready");
        if(!s.includes(actor))throw new SecurityException("bracket participant required");
        if(ready)clearCheckIns(actor);
        e=get(id);s=slot(e,index);
        List<UUID> next=new ArrayList<>(s.checkedIn());next.remove(actor);if(ready)next.add(actor);
        next.sort(Comparator.naturalOrder());
        return replaceSlot(e,new Slot(s.id(),s.round(),s.first(),s.second(),s.state(),next,null,s.attempts(),null,null));
    }
    public User entrant(Event e,UUID id){
        return e.entrants().stream().filter(v->v.user().id().equals(id)).map(Entry::user).findFirst().orElseThrow(()->new IllegalStateException("entrant not found"));
    }
    public Event assign(UUID id,int index,UUID match){
        Event e=get(id);running(e);Slot s=slot(e,index);
        if(s.state()!=SlotState.READY||s.checkedIn().size()!=2)throw new IllegalStateException("both entrants must check in");
        List<UUID> attempts=new ArrayList<>(s.attempts());attempts.add(match);
        return replaceSlot(e,new Slot(s.id(),s.round(),s.first(),s.second(),SlotState.ASSIGNED,List.of(),match,attempts,null,null));
    }
    private boolean active(Competition.Match m){return m.state()==Competition.State.WAITING||m.state()==Competition.State.RUNNING;}
    private void noActive(Slot s,Competition c){
        if(s.matchId()!=null&&active(c.participantMatch(s.first(),s.matchId())))throw new IllegalStateException("finish or leave the assigned room first");
    }
    public Event retry(UUID id,int index,Competition c){
        Event e=get(id);running(e);Slot s=slot(e,index);
        if(s.state()!=SlotState.ASSIGNED)throw new IllegalStateException("no assigned attempt");
        noActive(s,c);
        return replaceSlot(e,new Slot(s.id(),s.round(),s.first(),s.second(),SlotState.READY,List.of(),null,s.attempts(),null,null));
    }
    public Event decide(UUID id,int index,User actor,UUID winner,Source source,String reason,long now,Competition c){
        Event e=get(id);running(e);Slot s=slot(e,index);
        if(s.state()!=SlotState.READY&&s.state()!=SlotState.ASSIGNED)throw new IllegalStateException("slot cannot be decided");
        if(s.includes(actor.id()))throw new SecurityException("operator cannot adjudicate their own match");
        if(!s.includes(winner)||source==null)throw new IllegalArgumentException("winner must be a bracket participant");
        noActive(s,c);
        if(source==Source.PLAYED){
            if(s.matchId()==null)throw new IllegalStateException("no completed match attempt");
            Competition.Match m=c.participantMatch(s.first(),s.matchId());
            if(m.startedAt()==null||m.endedAt()==null)throw new IllegalStateException("played decision requires a started, concluded match");
        }
        Ruling ruling=new Ruling(winner,source,actor,text(reason,4000,true),now);
        List<Slot> slots=new ArrayList<>(e.slots());
        slots.set(index-1,new Slot(s.id(),s.round(),s.first(),s.second(),SlotState.DECIDED,List.of(),s.matchId(),s.attempts(),winner,ruling));
        return advance(e,slots);
    }
    public Event cancel(UUID id,String reason,Competition c){
        Event e=get(id);if(e.state()!=State.REGISTRATION&&e.state()!=State.RUNNING)throw new IllegalStateException("event already ended");
        for(Slot s:e.slots())noActive(s,c);
        List<Slot> slots=e.slots().stream().map(s->new Slot(s.id(),s.round(),s.first(),s.second(),s.state(),List.of(),s.matchId(),s.attempts(),s.winner(),s.ruling())).toList();
        return replace(e,State.CANCELLED,e.entrants(),slots,null,text(reason,4000,true));
    }
    public Teams.Page<Event> list(int offset,int limit){return Teams.page(events.values().stream().sorted(Comparator.comparingLong(Event::createdAt).reversed().thenComparing(Event::id)).toList(),offset,limit);}
    public int count(){return events.size();}
    public Dump exportState(){return new Dump(events.values().stream().sorted(Comparator.comparing(Event::id)).toList());}
    public void importState(Dump d,Competition c){
        events.clear();if(d==null)return;
        Set<UUID> attempts=new HashSet<>();
        for(Event e:d.events()){
            if(events.put(e.id(),e)!=null||e.entrants().stream().map(v->v.user().id()).distinct().count()!=e.entrants().size())throw new IllegalStateException("invalid persisted event");
            Set<UUID> entrantIds=new HashSet<>(e.entrants().stream().map(v->v.user().id()).toList());
            for(int i=0;i<e.slots().size();i++){
                Slot s=e.slots().get(i);
                if(s.id()!=i+1||(s.first()!=null&&!entrantIds.contains(s.first()))||(s.second()!=null&&!entrantIds.contains(s.second()))||
                        (s.winner()!=null&&!s.includes(s.winner()))||s.checkedIn().stream().anyMatch(u->!s.includes(u))||
                        (s.matchId()!=null&&!s.attempts().contains(s.matchId())))
                    throw new IllegalStateException("invalid persisted bracket");
                for(UUID id:s.attempts()){
                    Competition.Match m=c.participantMatch(s.first(),id);
                    if(!attempts.add(id)||m.mode()!=Competition.Mode.TOURNAMENT||!m.gameId().equals(e.gameId())||
                        !new HashSet<>(m.players().stream().map(User::id).toList()).equals(Set.of(s.first(),s.second())))
                        throw new IllegalStateException("invalid persisted tournament attempt");
                }
            }
        }
    }
}
