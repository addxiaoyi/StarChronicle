package io.github.addxiaoyi.starchronicle.config;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Directory-level references and mandatory completion dependencies. Every past reference is
 * validated, but an optional route or an absence bootstrap is not a prerequisite edge. History
 * production edges remain conservative; runtime guards still protect pinned causal feedback. */
public final class StoryDependencies {
    private static final Pattern ID=Pattern.compile("[A-Za-z0-9_.-]{1,80}");
    private static final String PLAYER="past.player.choice.",WORLD="past.resolution.world.",NATION="past.resolution.nation.";
    private StoryDependencies() {}

    public enum Kind { PLAYER_CHOICE, WORLD_RESOLUTION, NATION_RESOLUTION }
    public record Reference(Kind kind,String storyId,String version,String nodeId) {
        public Reference {
            Objects.requireNonNull(kind,"kind");
            requireId(storyId);requireId(version);requireId(nodeId);
        }
    }

    /** Non-past facts are not references. Reserved past.* facts must use one exact namespace
     * and three slash-separated stable IDs; dots remain part of an ID, never separators. */
    public static Optional<Reference> parse(String fact) {
        if(fact==null||!fact.startsWith("past."))return Optional.empty();
        Kind kind;String suffix;
        if(fact.startsWith(PLAYER)){kind=Kind.PLAYER_CHOICE;suffix=fact.substring(PLAYER.length());}
        else if(fact.startsWith(WORLD)){kind=Kind.WORLD_RESOLUTION;suffix=fact.substring(WORLD.length());}
        else if(fact.startsWith(NATION)){kind=Kind.NATION_RESOLUTION;suffix=fact.substring(NATION.length());}
        else throw new IllegalArgumentException("Unknown past fact namespace: "+fact);
        String[] parts=suffix.split("/",-1);
        if(parts.length!=3)throw new IllegalArgumentException("Past fact requires story/version/node: "+fact);
        return Optional.of(new Reference(kind,parts[0],parts[1],parts[2]));
    }

    /** Syntax and operator checks require no current-directory lookup, so a persisted pinned
     * definition can still be validated after its referenced configuration file is removed. */
    static void validateComparison(Condition condition) {
        if(parse(condition.fact()).isPresent()&&!Set.of("eq","ne","exists").contains(condition.op()==null?"":condition.op()))
            throw new IllegalArgumentException("Past choice facts support only eq, ne or exists: "+condition.fact());
    }

    public static void validate(Collection<StoryDefinition> definitions) {
        if(definitions==null)throw new IllegalArgumentException("Null story definitions");
        Map<String,StoryDefinition> stories=new TreeMap<>();
        for(StoryDefinition story:definitions) {
            if(story==null)throw new IllegalArgumentException("Null story");
            requireId(story.id());requireId(story.version());
            if(stories.putIfAbsent(story.id(),story)!=null)throw new IllegalArgumentException("Duplicate story ID: "+story.id());
        }
        if(stories.isEmpty())throw new IllegalArgumentException("No enabled stories");
        Map<String,Set<String>> graph=new TreeMap<>(),consumers=new HashMap<>();
        for(StoryDefinition story:stories.values()) {
            graph.put(story.id(),new TreeSet<>());
            if(story.triggers()==null||story.nodes()==null)throw new IllegalArgumentException("Missing triggers/nodes: "+story.id());
            for(String trigger:story.triggers())consumers.computeIfAbsent(trigger,ignored->new TreeSet<>()).add(story.id());
        }
        for(StoryDefinition owner:stories.values()) {
            Consumer<Condition> check=condition->validateReference(owner,condition,stories);
            walk(owner.condition(),0,check);
            for(StoryDefinition.Node node:owner.nodes().values()) {
                if(node==null)throw new IllegalArgumentException("Null node: "+owner.id());
                historyEdges(owner.id(),node.effects(),consumers,graph);
                for(StoryDefinition.Choice choice:node.choices().values()) {
                    if(choice==null)throw new IllegalArgumentException("Null choice: "+owner.id());
                    walk(choice.condition(),0,check);
                    historyEdges(owner.id(),choice.effects(),consumers,graph);
                }
            }
            Set<String> mandatory=new HashSet<>(required(owner.condition(),true,0));
            mandatory.addAll(requiredNode(owner.start(),owner,new HashMap<>(),new HashSet<>()));
            for(String dependency:mandatory)graph.get(dependency).add(owner.id());
        }
        rejectCycles(graph);
    }

    private static void validateReference(StoryDefinition owner,Condition condition,Map<String,StoryDefinition> stories) {
        var parsed=parse(condition.fact());if(parsed.isEmpty())return;
        validateComparison(condition);Reference reference=parsed.get();
        StoryDefinition target=stories.get(reference.storyId());
        if(target==null)throw new IllegalArgumentException("Missing referenced story in "+owner.id()+": "+reference.storyId());
        if(!target.version().equals(reference.version()))throw new IllegalArgumentException("Referenced story version mismatch in "+owner.id()+": "+condition.fact());
        if(reference.kind()==Kind.WORLD_RESOLUTION&&target.scope()!=StoryDefinition.Scope.WORLD
                ||reference.kind()==Kind.NATION_RESOLUTION&&target.scope()!=StoryDefinition.Scope.NATION)
            throw new IllegalArgumentException("Referenced resolution scope mismatch in "+owner.id()+": "+condition.fact());
        StoryDefinition.Node targetNode=target.nodes().get(reference.nodeId());
        if(targetNode==null)throw new IllegalArgumentException("Missing referenced node in "+owner.id()+": "+condition.fact());
        if(!"exists".equals(condition.op())&&!targetNode.choices().containsKey(condition.value()))
            throw new IllegalArgumentException("Missing referenced choice in "+owner.id()+": "+condition.fact()+" = "+condition.value());
    }

    /** What completed stories are unavoidable for this expression to have the desired truth?
     * Missing ordinary comparisons are UNKNOWN in either polarity. In contrast, exists=false
     * deliberately admits an absent past record, so NOT exists can bootstrap a first run. */
    private static Set<String> required(Condition condition,boolean desired,int depth) {
        if(condition==null)return Set.of();
        if(depth>16)throw new IllegalArgumentException("Condition too deep");
        if(condition.all()!=null)return requiredGroup(condition.all(),desired,desired,depth);
        if(condition.any()!=null)return requiredGroup(condition.any(),desired,!desired,depth);
        if(condition.not()!=null)return required(condition.not(),!desired,depth+1);
        var reference=parse(condition.fact());
        if(reference.isEmpty()||"exists".equals(condition.op())&&!desired)return Set.of();
        return Set.of(reference.get().storyId());
    }

    /** ALL=true and ANY=false need every child; the opposite cases may use any one child. */
    private static Set<String> requiredGroup(List<Condition> children,boolean desired,boolean union,int depth) {
        Set<String> result=null;
        for(Condition child:children) {
            Set<String> next=required(child,desired,depth+1);
            if(result==null)result=new HashSet<>(next);
            else if(union)result.addAll(next);else result.retainAll(next);
        }
        return result==null?Set.of():Set.copyOf(result);
    }

    /** Every valid choice is a possible completion route. A story is a hard prerequisite only
     * when all routes to a terminal need it, including requirements at later DAG nodes. */
    private static Set<String> requiredNode(String id,StoryDefinition story,Map<String,Set<String>> memo,Set<String> visiting) {
        Set<String> cached=memo.get(id);if(cached!=null)return cached;
        StoryDefinition.Node node=story.nodes().get(id);
        if(node==null)throw new IllegalArgumentException("Missing story node in "+story.id()+": "+id);
        if(!visiting.add(id))throw new IllegalArgumentException("Cyclic node dependency: "+id);
        Set<String> result=null;
        if(!node.terminal()) {
            if(node.choices().isEmpty())throw new IllegalArgumentException("No completion route in "+story.id()+": "+id);
            for(StoryDefinition.Choice choice:node.choices().values()) {
                Set<String> path=new HashSet<>(required(choice.condition(),true,0));
                path.addAll(requiredNode(choice.next(),story,memo,visiting));
                if(result==null)result=path;else result.retainAll(path);
            }
        }
        visiting.remove(id);Set<String> answer=result==null?Set.of():Set.copyOf(result);memo.put(id,answer);return answer;
    }

    private static void historyEdges(String producer,List<StoryDefinition.Effect> effects,Map<String,Set<String>> consumers,Map<String,Set<String>> graph) {
        for(StoryDefinition.Effect effect:effects) {
            if(effect==null)throw new IllegalArgumentException("Null effect: "+producer);
            if("history".equals(effect.type()))graph.get(producer).addAll(consumers.getOrDefault(effect.target(),Set.of()));
        }
    }

    private static void walk(Condition condition,int depth,Consumer<Condition> visitor) {
        if(condition==null)return;
        if(depth>16)throw new IllegalArgumentException("Condition too deep");
        if(condition.fact()!=null)visitor.accept(condition);
        if(condition.all()!=null)for(Condition nested:condition.all())walk(nested,depth+1,visitor);
        if(condition.any()!=null)for(Condition nested:condition.any())walk(nested,depth+1,visitor);
        if(condition.not()!=null)walk(condition.not(),depth+1,visitor);
    }

    private record Cursor(String story,Iterator<String> destinations) {}
    /** Iterative DFS also handles long valid story chains without using the Java call stack. */
    private static void rejectCycles(Map<String,Set<String>> graph) {
        Set<String> active=new HashSet<>(),done=new HashSet<>();List<String> path=new ArrayList<>();
        Deque<Cursor> pending=new ArrayDeque<>();
        for(String start:graph.keySet()) {
            if(done.contains(start))continue;
            active.add(start);path.add(start);pending.push(new Cursor(start,graph.get(start).iterator()));
            while(!pending.isEmpty()) {
                Cursor current=pending.peek();
                if(!current.destinations().hasNext()) {
                    pending.pop();active.remove(current.story());done.add(current.story());path.removeLast();continue;
                }
                String next=current.destinations().next();
                if(active.contains(next)) {
                    List<String> cycle=new ArrayList<>(path.subList(path.indexOf(next),path.size()));cycle.add(next);
                    throw new IllegalArgumentException("Cyclic story dependency: "+String.join(" -> ",cycle));
                }
                if(!done.contains(next)){active.add(next);path.add(next);pending.push(new Cursor(next,graph.get(next).iterator()));}
            }
        }
    }
    private static void requireId(String value){if(value==null||!ID.matcher(value).matches())throw new IllegalArgumentException("Invalid past reference stable ID: "+value);}
}
