package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import io.github.addxiaoyi.starchronicle.npc.NpcLedger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.*;

/** Pure Java orchestration extension. StarCore retains script execution, quests and authority.
 * Call on a bounded worker, never a region thread. SQLite serializes cross-player transitions. */
public final class RuleEngine implements AutoCloseable {
    private final SqliteStore store;
    private volatile List<StoryDefinition> definitions;
    private final Clock clock;
    private final NpcLedger npcs;
    private static final DateTimeFormatter SORTABLE_TIME=new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    public RuleEngine(SqliteStore store,List<StoryDefinition> definitions,Clock clock) {
        this(store,definitions,clock,null);
    }
    public RuleEngine(SqliteStore store,List<StoryDefinition> definitions,Clock clock,NpcLedger npcs) {
        this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);this.npcs=npcs;
        reload(definitions);
    }
    public void reload(List<StoryDefinition> validatedDefinitions){
        validate(validatedDefinitions);
        definitions=List.copyOf(validatedDefinitions);
    }
    public void validate(List<StoryDefinition> validatedDefinitions){
        Set<String> ids=new HashSet<>();
        for(var d:validatedDefinitions){ConfigLoader.validate(d,id->true,id->true);if(!ids.add(d.id()))throw new IllegalArgumentException("Duplicate story ID");}
        if(npcs!=null)for(var d:validatedDefinitions)for(var n:d.nodes().values()) {
            validateNpcEffects(n.effects());for(var choice:n.choices().values())validateNpcEffects(choice.effects());
        }
    }
    private void validateNpcEffects(List<StoryDefinition.Effect> effects){for(var e:effects)if(Set.of("reputation","trust","hostility","growth","position").contains(e.type()))npcs.catalog().validateEffect(e.type(),e.target(),e.value());}

    public ProcessReport accept(WorldEvent event,Collection<PlayerContext> contexts) {
        Objects.requireNonNull(event);Objects.requireNonNull(contexts);
        // An audience is distinct from historical actors: online citizens may witness a national
        // event without being listed as its direct participants in the permanent historical record.
        Map<UUID,PlayerContext> unique=new LinkedHashMap<>();
        for(var ctx:contexts) {
            if(!audience(event,ctx))
                throw new IllegalArgumentException("Context is neither a source participant nor a citizen of a participating nation");
            if(unique.put(ctx.playerId(),ctx)!=null) throw new IllegalArgumentException("Duplicate player context");
        }
        if(unique.size()>10000)throw new IllegalArgumentException("Too many trigger recipients");
        List<StoryDefinition> rules=definitions;
        return store.transaction(c->{
            boolean fresh=record(c,event);
            if(fresh)for(var rule:rules)if(rule.triggers().contains(event.type()))queueTrigger(c,rule,event,unique.values());
            Set<String> changed=drainTriggers(c,List.copyOf(unique.values()),event.id());
            return new ProcessReport(!fresh,List.copyOf(changed),fresh?"已记录事件与剧情变更":changed.isEmpty()?"事件已处理":"历史未重复，已恢复尚未交付的剧情触发");
        });
    }

    private static boolean audience(WorldEvent event,PlayerContext context){return event.globalAudience()||event.participants().contains(context.playerId())||!context.nationId().isBlank()&&event.nations().contains(context.nationId());}
    private static String scope(StoryDefinition rule,PlayerContext context){return switch(rule.scope()){case PLAYER->"player:"+context.playerId();case NATION->context.nationId().isBlank()?null:"nation:"+context.nationId();case WORLD->"world";};}
    private static void queueTrigger(Connection c,StoryDefinition rule,WorldEvent event,Collection<PlayerContext> contexts)throws Exception {
        Set<String> seeds=new TreeSet<>();
        switch(rule.scope()) {
            case WORLD->seeds.add("world");
            case PLAYER->{event.participants().forEach(id->seeds.add("player:"+id));contexts.forEach(ctx->seeds.add("player:"+ctx.playerId()));event.nations().forEach(id->seeds.add("nation-players:"+id));if(event.globalAudience())seeds.add("players:*");}
            case NATION->{event.nations().forEach(id->seeds.add("nation:"+id));if(event.nations().isEmpty())event.participants().forEach(id->seeds.add("actor:"+id));contexts.stream().filter(ctx->!ctx.nationId().isBlank()&&(event.nations().isEmpty()||event.nations().contains(ctx.nationId()))).forEach(ctx->seeds.add("nation:"+ctx.nationId()));if(event.globalAudience()&&event.nations().isEmpty())seeds.add("nations:*");}
        }
        String trigger=stable(event.id(),rule.id(),rule.version());
        for(String seed:seeds)try(var s=c.prepareStatement("INSERT OR IGNORE INTO story_triggers(id,trigger_key,event_id,scope,story_json) VALUES(?,?,?,?,?)")){
            s.setString(1,stable(trigger,seed));s.setString(2,trigger);s.setString(3,event.id());s.setString(4,seed);s.setString(5,json(rule));s.executeUpdate();
        }
    }
    private record PendingTrigger(String id,String triggerKey,String eventId,String seed,StoryDefinition definition){}
    private static Set<String> audienceSeeds(Collection<PlayerContext> contexts){Set<String> seeds=new HashSet<>(Set.of("world","players:*","nations:*"));for(var ctx:contexts){seeds.add("player:"+ctx.playerId());seeds.add("actor:"+ctx.playerId());if(!ctx.nationId().isBlank()){seeds.add("nation:"+ctx.nationId());seeds.add("nation-players:"+ctx.nationId());}}return seeds;}
    /** Event acceptance and this durable inbox commit together. A lack of online audience is
     * not a consumed plot result. Call with fresh server snapshots on join and worker polling. */
    public ProcessReport resumeTriggers(Collection<PlayerContext> contexts){
        Map<UUID,PlayerContext> unique=new LinkedHashMap<>();for(var ctx:contexts)if(unique.put(ctx.playerId(),ctx)!=null)throw new IllegalArgumentException("Duplicate player context");
        if(unique.isEmpty())return new ProcessReport(false,List.of(),"暂无在线剧情受众");
        if(unique.size()>10000)throw new IllegalArgumentException("Too many trigger recipients");
        return store.transaction(c->{
            List<PlayerContext> current=List.copyOf(unique.values());Set<String> changed=drainTriggers(c,current,null);
            Set<String> scopes=new HashSet<>(Set.of("world"));for(var ctx:current){scopes.add("player:"+ctx.playerId());if(!ctx.nationId().isBlank())scopes.add("nation:"+ctx.nationId());}
            String marks=String.join(",",Collections.nCopies(scopes.size(),"?"));List<StoryInstance> active=new ArrayList<>();
            try(var s=c.prepareStatement("SELECT json FROM instances WHERE completed=0 AND scope IN ("+marks+") ORDER BY id")){int p=1;for(String key:scopes)s.setString(p++,key);try(var r=s.executeQuery()){while(r.next())active.add(decode(r.getString(1),StoryInstance.class));}}
            for(StoryInstance instance:active) {
                WorldEvent origin=event(c,instance.sourceEventId());
                for(var ctx:current)if(!instance.contains(ctx.playerId())&&audience(origin,ctx)&&Objects.equals(instance.scopeKey(),scope(instance.definition(),ctx))&&eligible(c,instance,ctx,origin)){
                    instance=addMember(c,instance,ctx,origin);changed.add(instance.id());
                }
            }
            return new ProcessReport(false,List.copyOf(changed),"已恢复持久化剧情触发与相关参与者");
        });
    }
    public List<StoryDefinition> pendingDefinitions(){return store.transaction(c->{List<StoryDefinition> result=new ArrayList<>();try(var s=c.createStatement();var r=s.executeQuery("SELECT DISTINCT story_json FROM story_triggers")){while(r.next())result.add(decode(r.getString(1),StoryDefinition.class));}return List.copyOf(result);});}
    public long pendingTriggerCount(){return store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM story_triggers")){r.next();return r.getLong(1);}});}
    private boolean eligible(Connection c,StoryInstance instance,PlayerContext context,WorldEvent origin)throws Exception {return test(instance.definition().condition(),facts(c,context,origin,instance))&&permitted(instance.definition().nodes().get(instance.node()).effects(),context);}
    private Set<String> drainTriggers(Connection c,List<PlayerContext> contexts,String onlyEvent)throws Exception {
        Set<String> changed=new LinkedHashSet<>();if(contexts.isEmpty())return changed;
        Set<String> seeds=audienceSeeds(contexts);List<PendingTrigger> pending=new ArrayList<>();
        try(var s=c.prepareStatement("SELECT id,trigger_key,event_id,scope,story_json FROM story_triggers WHERE scope IN (SELECT value FROM json_each(?))"+(onlyEvent==null?"":" AND event_id=?")+" ORDER BY checked,id LIMIT 100")){
            s.setString(1,json(seeds));if(onlyEvent!=null)s.setString(2,onlyEvent);
            try(var r=s.executeQuery()){while(r.next())pending.add(new PendingTrigger(r.getString(1),r.getString(2),r.getString(3),r.getString(4),decode(r.getString(5),StoryDefinition.class)));}
        }
        for(var trigger:pending) {
            try(var s=c.prepareStatement("UPDATE story_triggers SET checked=checked+1 WHERE id=?")){s.setString(1,trigger.id());s.executeUpdate();}
            WorldEvent source=event(c,trigger.eventId());Map<String,List<PlayerContext>> groups=new LinkedHashMap<>();
            for(var ctx:contexts)if(audience(source,ctx)) {
                String actual=scope(trigger.definition(),ctx);if(actual==null)continue;
                boolean matches=trigger.seed().equals(actual)||trigger.seed().equals("actor:"+ctx.playerId())||trigger.seed().equals("nation-players:"+ctx.nationId())||trigger.seed().equals("players:*")||trigger.seed().equals("nations:*");
                if(matches)groups.computeIfAbsent(actual,k->new ArrayList<>()).add(ctx);
            }
            boolean consume=false;
            for(var group:groups.entrySet())if(activate(c,trigger,source,group.getKey(),group.getValue(),changed))consume=true;
            if(consume&&!trigger.seed().equals("players:*")&&!trigger.seed().equals("nations:*")&&!trigger.seed().startsWith("nation-players:"))try(var s=c.prepareStatement("DELETE FROM story_triggers WHERE id=?")){s.setString(1,trigger.id());s.executeUpdate();}
        }
        return changed;
    }
    private boolean activate(Connection c,PendingTrigger trigger,WorldEvent source,String scope,List<PlayerContext> contexts,Set<String> changed)throws Exception {
        StoryDefinition rule=trigger.definition();boolean consumed;
        try(var s=c.prepareStatement("SELECT 1 FROM story_trigger_receipts WHERE trigger_key=? AND scope=?")){s.setString(1,trigger.triggerKey());s.setString(2,scope);try(var r=s.executeQuery()){consumed=r.next();}}
        StoryInstance active=findActive(c,rule.id(),scope);
        if(active!=null) {
            WorldEvent origin=event(c,active.sourceEventId());boolean matched=false;
            for(var ctx:contexts)if(eligible(c,active,ctx,origin)){
                matched=true;if(!active.contains(ctx.playerId())){active=addMember(c,active,ctx,origin);changed.add(active.id());}
            }
            if(!matched&&!consumed)return false;
        }else if(!consumed) {
            if(cooling(c,rule,scope))return false;
            var node=rule.nodes().get(rule.start());List<PlayerContext> eligible=new ArrayList<>();
            for(var ctx:contexts)if(test(rule.condition(),facts(c,ctx,source,null))&&permitted(node.effects(),ctx))eligible.add(ctx);
            if(eligible.isEmpty())return false;
            String id=stable(rule.id(),rule.version(),scope,source.id());Map<UUID,String> members=new LinkedHashMap<>();for(var ctx:eligible)members.put(ctx.playerId(),ctx.nationId());
            active=new StoryInstance(id,rule,scope,source.id(),rule.start(),members,Map.of(),Map.of(),clock.instant(),clock.instant(),node.terminal());
            save(c,active);enter(c,active,eligible.getFirst());changed.add(id);
        }
        try(var s=c.prepareStatement("INSERT OR IGNORE INTO story_trigger_receipts VALUES(?,?)")){s.setString(1,trigger.triggerKey());s.setString(2,scope);s.executeUpdate();}
        return true;
    }

    /** Explicit opt-in to an already active shared story. Eligibility uses its original source evidence. */
    public ProcessReport join(String instanceId,PlayerContext context) {
        return store.transaction(c->{
            StoryInstance instance=require(c,instanceId);
            if(instance.completed()) throw new IllegalStateException("剧情已经结束");
            if(instance.definition().scope()==StoryDefinition.Scope.PLAYER&&!instance.contains(context.playerId())) throw new SecurityException("个人剧情不能加入");
            if(instance.definition().scope()==StoryDefinition.Scope.NATION&&!instance.scopeKey().equals("nation:"+context.nationId())) throw new SecurityException("不属于该国家");
            WorldEvent origin=event(c,instance.sourceEventId());
            if(!test(instance.definition().condition(),facts(c,context,origin,instance))) throw new SecurityException("不满足剧情前置条件");
            if(instance.contains(context.playerId())) return new ProcessReport(true,List.of(instanceId),"已经加入");
            addMember(c,instance,context,origin);
            return new ProcessReport(false,List.of(instanceId),"已加入共同剧情");
        });
    }

    public ProcessReport choose(String instanceId,UUID player,String choiceId,PlayerContext context) {
        return choose(instanceId,player,null,choiceId,context);
    }
    /** Expected-node token prevents a delayed click from selecting an identically named choice in a later stage. */
    public ProcessReport choose(String instanceId,UUID player,String expectedNode,String choiceId,PlayerContext context) {
        if(!player.equals(context.playerId())) throw new SecurityException("Player context mismatch");
        return store.transaction(c->{
            StoryInstance i=require(c,instanceId);
            if(i.completed()) throw new IllegalStateException("剧情已经结束");
            if(expectedNode!=null&&!expectedNode.equals(i.node()))throw new IllegalStateException("剧情阶段已改变，请刷新界面");
            if(!i.contains(player)) throw new SecurityException("请先加入剧情");
            if(!i.members().get(player).equals(context.nationId())) throw new SecurityException("国籍已变化，请由管理员审核成员资格");
            var node=i.definition().nodes().get(i.node());var choice=node.choices().get(choiceId);
            if(choice==null) throw new IllegalArgumentException("不存在的剧情选项");
            if(!context.permitted(choice.permission())) throw new SecurityException("缺少选择权限");
            if(!test(choice.condition(),facts(c,context,event(c,i.sourceEventId()),i))) throw new SecurityException("不满足分支条件");
            if(choiceId.equals(i.votes().get(player))) return new ProcessReport(true,List.of(i.id()),"本阶段已经投票");
            requirePermissions(choice.effects(),context);
            var next=i.definition().nodes().get(choice.next());requirePermissions(next.effects(),context);
            Map<UUID,String> votes=new LinkedHashMap<>(i.votes());votes.put(player,choiceId);
            Map<String,String> choices=new LinkedHashMap<>(i.choices());choices.put(i.node()+"/"+player,choiceId);
            long count=votes.values().stream().filter(choiceId::equals).count();
            if(count<node.quorum()) {
                save(c,new StoryInstance(i.id(),i.definition(),i.scopeKey(),i.sourceEventId(),i.node(),i.members(),votes,choices,i.startedAt(),clock.instant(),false));
                return new ProcessReport(false,List.of(i.id()),"已投票 "+count+"/"+node.quorum());
            }
            choices.put(i.node(),choiceId);
            applyEffects(c,i,"choice/"+i.node()+"/"+choiceId,choice.effects(),context);
            StoryInstance moved=new StoryInstance(i.id(),i.definition(),i.scopeKey(),i.sourceEventId(),choice.next(),i.members(),Map.of(),choices,i.startedAt(),clock.instant(),next.terminal());
            save(c,moved);enter(c,moved,context);
            return new ProcessReport(false,List.of(i.id()),next.terminal()?"剧情已完成，历史已经保存":"已进入下一阶段");
        });
    }

    private StoryInstance addMember(Connection c,StoryInstance i,PlayerContext ctx,WorldEvent origin)throws Exception {
        if(i.contains(ctx.playerId())) return i;
        if(i.completed()) return i;
        requirePermissions(i.definition().nodes().get(i.node()).effects(),ctx);
        Map<UUID,String> members=new LinkedHashMap<>(i.members());members.put(ctx.playerId(),ctx.nationId());
        StoryInstance joined=new StoryInstance(i.id(),i.definition(),i.scopeKey(),i.sourceEventId(),i.node(),members,i.votes(),i.choices(),i.startedAt(),clock.instant(),false);
        save(c,joined);enter(c,joined,ctx);return joined;
    }
    private void enter(Connection c,StoryInstance i,PlayerContext actor)throws Exception {
        var node=i.definition().nodes().get(i.node());
        applyEffects(c,i,"node/"+i.node(),node.effects(),actor);
        for(var member:i.members().entrySet()) {
            // Chronicle node IDs belong to the campaign graph, not to the native scene graph.
            // Null native node starts the script at its own declared startNode.
            if(node.script()!=null&&!node.script().isBlank()) enqueue(c,new Delivery(stable(i.id(),i.node(),"script",member.getKey().toString()),i.id(),"narrative.start",node.script(),"",member.getKey(),member.getValue(),null));
            if(node.quest()!=null&&!node.quest().isBlank()) enqueue(c,new Delivery(stable(i.id(),i.node(),"quest",member.getKey().toString()),i.id(),"quest.accept",node.quest(),"",member.getKey(),member.getValue(),null));
        }
    }
    private void applyEffects(Connection c,StoryInstance i,String action,List<StoryDefinition.Effect> effects,PlayerContext actor)throws Exception {
        for(var e:effects) {
            if(e.type().equals("authority")) {
                if(actor.nationId().isBlank())throw new SecurityException("国家权威效果要求当前国家身份");
                // One request per nation and outcome, carrying the actual committing actor.
                // Never impersonate every member or multiply a national mutation by its population.
                String outcome=stable(i.id(),action,e.id(),"authority",actor.nationId());
                enqueue(c,new Delivery(outcome,i.id(),e.type(),e.target(),e.value(),actor.playerId(),actor.nationId(),e.permission()));
                continue;
            }
            if(e.type().equals("memory")||e.type().equals("history")) {
                String outcome=stable(i.id(),action,e.id(),"WORLD");
                if(!receipt(c,outcome,"DONE","{}")) continue;
                if(e.type().equals("memory")) setFact(c,"WORLD","region."+e.target(),e.value());
                else record(c,new WorldEvent("chronicle:"+outcome,e.target(),clock.instant(),i.members().keySet(),new HashSet<>(i.members().values().stream().filter(n->!n.isBlank()).toList()),Map.of("story",i.definition().id(),"outcome",e.value()),"StarChronicle","instance="+i.id()+"; action="+action+"; effect="+e.id()+"; source="+i.sourceEventId()));
                continue;
            }
            if(NpcLedger.isGlobalEffect(e.type())) {
                if(npcs==null)throw new IllegalStateException("NPC catalog required for "+e.type());
                if(receipt(c,stable(i.id(),action,e.id(),"NPC_WORLD"),"DONE","{}"))npcs.applyEffect(c,null,e.type(),e.target(),e.value());
                continue;
            }
            for(var member:i.members().entrySet()) {
                UUID player=member.getKey();String outcome=stable(i.id(),action,e.id(),player.toString());
                if(Set.of("honor","title","reputation","trust","hostility").contains(e.type())) {
                    if(!receipt(c,outcome,"DONE","{}")) continue;
                    if(Set.of("reputation","trust","hostility").contains(e.type())&&npcs!=null){npcs.applyEffect(c,player,e.type(),e.target(),e.value());continue;}
                    if(e.type().equals("trust")||e.type().equals("hostility"))throw new IllegalStateException("NPC catalog required for "+e.type());
                    String key=switch(e.type()){case "honor"->"player.honor";case "title"->"title."+e.target();default->"npc."+e.target()+".reputation";};
                    if(e.type().equals("title")) setFact(c,player.toString(),key,e.value());
                    else {
                        long current=Long.parseLong(getFact(c,player.toString(),key,"0"));
                        setFact(c,player.toString(),key,Long.toString(Math.addExact(current,Long.parseLong(e.value()))));
                    }
                } else enqueue(c,new Delivery(outcome,i.id(),e.type(),e.target(),e.value(),player,member.getValue(),e.permission()));
            }
        }
    }
    private static void requirePermissions(List<StoryDefinition.Effect> effects,PlayerContext ctx) {
        for(var e:effects) if(!ctx.permitted(e.permission())) throw new SecurityException("缺少效果权限: "+e.permission());
    }
    private static boolean permitted(List<StoryDefinition.Effect> effects,PlayerContext ctx){return effects.stream().allMatch(e->ctx.permitted(e.permission()));}
    private static boolean test(Condition c,Map<String,String> facts){return c==null||c.test(facts);}
    private Map<String,String> facts(Connection c,PlayerContext context,WorldEvent event,StoryInstance instance)throws Exception {
        Map<String,String> facts=new HashMap<>(context.facts());facts.put("player.id",context.playerId().toString());
        if(!context.nationId().isBlank()) facts.put("nation.id",context.nationId()); else facts.remove("nation.id");
        for(var entry:event.facts().entrySet()) facts.put("event."+entry.getKey(),entry.getValue());
        facts.put("event.type",event.type());facts.putAll(readFacts(c,"WORLD"));facts.putAll(readFacts(c,context.playerId().toString()));
        if(npcs!=null)facts.putAll(npcs.facts(c,context.playerId()));
        facts.keySet().removeIf(k->k.startsWith("history."));
        try(var s=c.prepareStatement("SELECT type,COUNT(*) FROM events GROUP BY type");var r=s.executeQuery()) {while(r.next()) facts.put("history."+r.getString(1),r.getString(2));}
        if(instance!=null) {
            // Collective resolution and personal choices are distinct. Two passes make actor
            // precedence deterministic regardless of immutable-map iteration order after restart.
            for(var entry:instance.choices().entrySet())if(!entry.getKey().contains("/")) {
                facts.put("story.choice."+entry.getKey(),entry.getValue());
                facts.put("choice."+entry.getKey(),entry.getValue());
            }
            for(var entry:instance.choices().entrySet())if(entry.getKey().endsWith("/"+context.playerId()))
                facts.put("choice."+entry.getKey().split("/",2)[0],entry.getValue());
        }
        return facts;
    }

    public List<StoryInstance> instances(UUID player){return store.transaction(c->allInstances(c,false).stream().filter(i->i.contains(player)).toList());}
    public List<StoryInstance> activeInstances(){return store.transaction(c->allInstances(c,true));}
    public List<StoryInstance> availableInstances(PlayerContext context){return store.transaction(c->{
        List<StoryInstance> visible=new ArrayList<>();
        for(var instance:allInstances(c,true)) {
            if(instance.contains(context.playerId())) {visible.add(instance);continue;}
            if(instance.definition().scope()==StoryDefinition.Scope.PLAYER)continue;
            if(instance.definition().scope()==StoryDefinition.Scope.NATION&&!instance.scopeKey().equals("nation:"+context.nationId()))continue;
            if(test(instance.definition().condition(),facts(c,context,event(c,instance.sourceEventId()),instance)))visible.add(instance);
        }
        return List.copyOf(visible);
    });}
    public Map<String,StoryDefinition.Choice> availableChoices(String instanceId,PlayerContext context){return store.transaction(c->{
        StoryInstance instance=require(c,instanceId);
        if(instance.completed()||!instance.contains(context.playerId())||!instance.members().get(context.playerId()).equals(context.nationId()))return Map.of();
        Map<String,String> state=facts(c,context,event(c,instance.sourceEventId()),instance);
        Map<String,StoryDefinition.Choice> visible=new LinkedHashMap<>();
        for(var entry:instance.definition().nodes().get(instance.node()).choices().entrySet())
            if(context.permitted(entry.getValue().permission())&&test(entry.getValue().condition(),state))visible.put(entry.getKey(),entry.getValue());
        return Collections.unmodifiableMap(visible);
    });}
    public Map<String,String> facts(UUID player){return store.transaction(c->Map.copyOf(readFacts(c,player.toString())));}
    public Map<String,String> worldFacts(){return store.transaction(c->Map.copyOf(readFacts(c,"WORLD")));}
    /** Durable observation baseline for upstream sources without post-commit events.
     * First observation returns null; disappearance is meaningful only for complete authoritative scans. */
    public Map<String,String> observe(String namespace,Map<String,String> snapshot) {
        return store.transaction(c->{
            String owner="observation:"+namespace;
            Map<String,String> old=readFacts(c,owner);
            boolean existed=old.remove("__initialized__")!=null;
            try(var s=c.prepareStatement("DELETE FROM facts WHERE owner=?")){s.setString(1,owner);s.executeUpdate();}
            for(var entry:snapshot.entrySet()) {
                if(entry.getKey().equals("__initialized__"))throw new IllegalArgumentException("Reserved observation key");
                setFact(c,owner,entry.getKey(),entry.getValue());
            }
            setFact(c,owner,"__initialized__","true");
            return existed?Map.copyOf(old):null;
        });
    }
    public List<WorldEvent> history(String nation,UUID player,int page,int size) {
        if(page<0||size<1||size>100||page>1000000) throw new IllegalArgumentException("Invalid history page");
        return store.transaction(c->{
            String sql="SELECT e.json FROM events e WHERE 1=1"+(nation==null?"":" AND EXISTS(SELECT 1 FROM event_nations n WHERE n.event_id=e.id AND n.nation=?)")+(player==null?"":" AND EXISTS(SELECT 1 FROM event_players p WHERE p.event_id=e.id AND p.player=?)")+" ORDER BY e.time DESC,e.id DESC LIMIT ? OFFSET ?";
            List<WorldEvent> result=new ArrayList<>();
            try(var s=c.prepareStatement(sql)){int p=1;if(nation!=null)s.setString(p++,nation);if(player!=null)s.setString(p++,player.toString());s.setInt(p++,size);s.setInt(p,page*size);try(var r=s.executeQuery()){while(r.next())result.add(decode(r.getString(1),WorldEvent.class));}}return List.copyOf(result);
        });
    }
    public List<Delivery> pending(){return deliveries("PENDING");}
    /** Filter recipients before LIMIT, so offline recipients cannot starve online deliveries. */
    public List<Delivery> pending(Set<UUID> onlinePlayers){
        if(onlinePlayers.isEmpty())return List.of();
        if(onlinePlayers.size()>10000)throw new IllegalArgumentException("Too many online recipients");
        return store.transaction(c->{
            String marks=String.join(",",Collections.nCopies(onlinePlayers.size(),"?"));
            List<Delivery> result=new ArrayList<>();
            try(var s=c.prepareStatement("SELECT json FROM outcomes WHERE status='PENDING' AND json_extract(json,'$.playerId') IN ("+marks+") ORDER BY id LIMIT 100")){
                int p=1;for(UUID player:onlinePlayers)s.setString(p++,player.toString());
                try(var r=s.executeQuery()){while(r.next())result.add(decode(r.getString(1),Delivery.class));}
            }
            return List.copyOf(result);
        });
    }
    public List<Delivery> uncertain(){return deliveries("UNKNOWN");}
    private List<Delivery> deliveries(String status){return store.transaction(c->{List<Delivery> result=new ArrayList<>();try(var s=c.prepareStatement("SELECT json FROM outcomes WHERE status=? ORDER BY id LIMIT 100")){s.setString(1,status);try(var r=s.executeQuery()){while(r.next())result.add(decode(r.getString(1),Delivery.class));}}return List.copyOf(result);});}
    public boolean claimDelivery(String id){return store.transaction(c->{try(var s=c.prepareStatement("UPDATE outcomes SET status='RUNNING' WHERE id=? AND status='PENDING'")){s.setString(1,id);return s.executeUpdate()==1;}});}
    public void finishDelivery(String id,CoreGateway.DeliveryResult result){store.transaction(c->{try(var s=c.prepareStatement("UPDATE outcomes SET status=? WHERE id=? AND status='RUNNING'")){s.setString(1,switch(result){case APPLIED->"DONE";case REJECTED->"REJECTED";case UNKNOWN->"UNKNOWN";});s.setString(2,id);s.executeUpdate();}return null;});}
    /** Only the adapter that proves no native call was invoked may release an admitted attempt. */
    public boolean notInvoked(String id){return store.transaction(c->{try(var s=c.prepareStatement("UPDATE outcomes SET status='PENDING' WHERE id=? AND status='RUNNING'")){s.setString(1,id);return s.executeUpdate()==1;}});}
    /** Reconciliation may only acknowledge an UNKNOWN outcome after independently checking upstream. No resend API. */
    public boolean acknowledgeUnknown(String id){return store.transaction(c->{try(var s=c.prepareStatement("UPDATE outcomes SET status='DONE' WHERE id=? AND status='UNKNOWN'")){s.setString(1,id);return s.executeUpdate()==1;}});}

    private static List<StoryInstance> allInstances(Connection c,boolean active)throws Exception {List<StoryInstance> result=new ArrayList<>();try(var s=c.prepareStatement("SELECT json FROM instances"+(active?" WHERE completed=0":"")+" ORDER BY updated DESC,id");var r=s.executeQuery()){while(r.next())result.add(decode(r.getString(1),StoryInstance.class));}return List.copyOf(result);}
    private static StoryInstance require(Connection c,String id)throws Exception {try(var s=c.prepareStatement("SELECT json FROM instances WHERE id=?")){s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("不存在的剧情实例");return decode(r.getString(1),StoryInstance.class);}}}
    private static StoryInstance findActive(Connection c,String story,String scope)throws Exception {try(var s=c.prepareStatement("SELECT json FROM instances WHERE story=? AND scope=? AND completed=0 LIMIT 1")){s.setString(1,story);s.setString(2,scope);try(var r=s.executeQuery()){return r.next()?decode(r.getString(1),StoryInstance.class):null;}}}
    private boolean cooling(Connection c,StoryDefinition rule,String scope)throws Exception {try(var s=c.prepareStatement("SELECT updated FROM instances WHERE story=? AND scope=? ORDER BY updated DESC LIMIT 1")){s.setString(1,rule.id());s.setString(2,scope);try(var r=s.executeQuery()){return r.next()&&Instant.parse(r.getString(1)).plusSeconds(rule.cooldownSeconds()).isAfter(clock.instant());}}}
    private static void save(Connection c,StoryInstance i)throws Exception {try(var s=c.prepareStatement("INSERT INTO instances(id,story,scope,completed,updated,json) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET completed=excluded.completed,updated=excluded.updated,json=excluded.json")){s.setString(1,i.id());s.setString(2,i.definition().id());s.setString(3,i.scopeKey());s.setInt(4,i.completed()?1:0);s.setString(5,SORTABLE_TIME.format(i.updatedAt()));s.setString(6,json(i));s.executeUpdate();}}
    private static WorldEvent event(Connection c,String id)throws Exception {try(var s=c.prepareStatement("SELECT json FROM events WHERE id=?")){s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())throw new IllegalStateException("Missing source evidence");return decode(r.getString(1),WorldEvent.class);}}}
    private boolean record(Connection c,WorldEvent e)throws Exception {
        Map<String,Object> canonical=new TreeMap<>();canonical.put("id",e.id());canonical.put("type",e.type());canonical.put("time","OBSERVED".equals(e.facts().get("time_basis"))?"OBSERVED":e.occurredAt().toString());canonical.put("players",e.participants().stream().map(UUID::toString).sorted().toList());canonical.put("nations",new TreeSet<>(e.nations()));canonical.put("facts",new TreeMap<>(e.facts()));canonical.put("source",e.source());canonical.put("evidence",e.evidence());
        String hash=stable(json(canonical));
        try(var s=c.prepareStatement("SELECT hash FROM events WHERE id=?")){s.setString(1,e.id());try(var r=s.executeQuery()){if(r.next()){if(!r.getString(1).equals(hash)) throw new IllegalArgumentException("Source event ID collision: "+e.id());return false;}}}
        try(var s=c.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?)")){s.setString(1,e.id());s.setString(2,e.type());s.setString(3,SORTABLE_TIME.format(e.occurredAt()));s.setString(4,hash);s.setString(5,json(e));s.executeUpdate();}
        try(var n=c.prepareStatement("INSERT INTO event_nations VALUES(?,?)");var p=c.prepareStatement("INSERT INTO event_players VALUES(?,?)")){for(String nation:e.nations()){n.setString(1,e.id());n.setString(2,nation);n.addBatch();}for(UUID player:e.participants()){p.setString(1,e.id());p.setString(2,player.toString());p.addBatch();}n.executeBatch();p.executeBatch();}
        if(npcs!=null)npcs.applyEvent(c,e);
        return true;
    }
    private static boolean receipt(Connection c,String id,String status,String payload)throws Exception {try(var s=c.prepareStatement("INSERT OR IGNORE INTO outcomes VALUES(?,?,?)")){s.setString(1,id);s.setString(2,status);s.setString(3,payload);return s.executeUpdate()==1;}}
    private static void enqueue(Connection c,Delivery d)throws Exception {receipt(c,d.outcomeId(),"PENDING",json(d));}
    private static Map<String,String> readFacts(Connection c,String owner)throws Exception {Map<String,String> result=new LinkedHashMap<>();try(var s=c.prepareStatement("SELECT key,value FROM facts WHERE owner=?")){s.setString(1,owner);try(var r=s.executeQuery()){while(r.next())result.put(r.getString(1),r.getString(2));}}return result;}
    private static String getFact(Connection c,String owner,String key,String fallback)throws Exception {try(var s=c.prepareStatement("SELECT value FROM facts WHERE owner=? AND key=?")){s.setString(1,owner);s.setString(2,key);try(var r=s.executeQuery()){return r.next()?r.getString(1):fallback;}}}
    private static void setFact(Connection c,String owner,String key,String value)throws Exception {try(var s=c.prepareStatement("INSERT INTO facts VALUES(?,?,?) ON CONFLICT(owner,key) DO UPDATE SET value=excluded.value")){s.setString(1,owner);s.setString(2,key);s.setString(3,value);s.executeUpdate();}}
    public static String stable(String... parts) {try{MessageDigest digest=MessageDigest.getInstance("SHA-256");for(String part:parts){byte[] bytes=part.getBytes(StandardCharsets.UTF_8);digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));digest.update((byte)':');digest.update(bytes);}return HexFormat.of().formatHex(digest.digest());}catch(Exception e){throw new IllegalStateException(e);}}
    @Override public void close(){store.close();}
}
