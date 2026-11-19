package io.github.addxiaoyi.starchronicle.npc;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.core.ChoiceLedger;
import io.github.addxiaoyi.starchronicle.config.Condition;
import io.github.addxiaoyi.starchronicle.history.HistoricalFacts;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durable NPC relationships and global growth. All writes require the caller's source transaction. */
public final class NpcLedger {
    public static final int RELATIONSHIP_MIN=-1000,RELATIONSHIP_MAX=1000,HOSTILITY_MIN=0,HOSTILITY_MAX=1000,GROWTH_MAX=1_000_000;
    private static final Pattern PLACEHOLDER=Pattern.compile("\\{([^{}\\r\\n]{1,4096})}");
    private final SqliteStore store;
    private final NpcCatalog catalog;
    public NpcLedger(SqliteStore store,NpcCatalog catalog) {this.store=Objects.requireNonNull(store);this.catalog=Objects.requireNonNull(catalog);}
    public NpcCatalog catalog(){return catalog;}
    public static boolean isGlobalEffect(String type){return Set.of("growth","position").contains(type);}

    /** Call after inserting the source event and before committing the same SQLite transaction.
     * Reads the stored canonical payload so a mismatched argument cannot rewrite participants. */
    public void applyEvent(Connection connection,WorldEvent supplied) throws SQLException {
        inTransaction(connection);Objects.requireNonNull(supplied);
        WorldEvent event;
        try(var query=connection.prepareStatement("SELECT json FROM events WHERE id=?")) {
            query.setString(1,supplied.id());
            try(var result=query.executeQuery()) {
                if(!result.next())throw new IllegalStateException("NPC reactions require a durably recorded source event in this transaction");
                event=SqliteStore.decode(result.getString(1),WorldEvent.class);
            }
        }
        for(var entry:catalog.profiles().entrySet()) {
            String npcId=entry.getKey();
            Set<String> requested=references(npcId);
            for(var reaction:entry.getValue().reactions()) {
                if(!reaction.events().contains(event.type()))continue;
                String receipt="npc-reaction:"+RuleEngine.stable(event.id(),npcId,reaction.id());
                if(!receipt(connection,receipt,event.id(),npcId,reaction.id()))continue;
                Map<String,String> global=eventFacts(connection,event,npcId,null,requested);
                boolean globalMatch=reaction.condition()==null||!needsPlayer(reaction.condition())&&reaction.condition().test(global);
                List<UUID> matching=new ArrayList<>();
                for(UUID player:event.participants()) {
                    Map<String,String> personal=eventFacts(connection,event,npcId,player,requested);
                    if(reaction.condition()==null||reaction.condition().test(personal))matching.add(player);
                }
                // A player-specific condition may drive a single global promotion when any evidenced participant matches.
                if(globalMatch||!matching.isEmpty()) {
                    if(reaction.growth()!=0)applyEffect(connection,null,"growth",npcId,Integer.toString(reaction.growth()));
                    if(reaction.position()!=null)applyEffect(connection,null,"position",npcId,reaction.position());
                }
                for(UUID player:matching) {
                    if(reaction.reputation()!=0)applyEffect(connection,player,"reputation",npcId,Integer.toString(reaction.reputation()));
                    if(reaction.trust()!=0)applyEffect(connection,player,"trust",npcId,Integer.toString(reaction.trust()));
                    if(reaction.hostility()!=0)applyEffect(connection,player,"hostility",npcId,Integer.toString(reaction.hostility()));
                }
            }
        }
    }

    /** Trusted effect executor hook; caller owns its stable outcome receipt and authorization. */
    public void applyEffect(Connection connection,UUID player,String type,String npcId,String value) throws SQLException {
        inTransaction(connection);catalog.validateEffect(type,npcId,value);
        String key="npc."+npcId+"."+type;
        if(type.equals("position")) {set(connection,"WORLD",key,value);return;}
        if(type.equals("growth")) {
            int growth=bounded(connection,"WORLD",key,Long.parseLong(value),0,GROWTH_MAX);
            set(connection,"WORLD","npc."+npcId+".position",catalog.positionAt(npcId,growth));return;
        }
        Objects.requireNonNull(player,"Relationship effects require a player");
        bounded(connection,player.toString(),key,Long.parseLong(value),type.equals("hostility")?HOSTILITY_MIN:RELATIONSHIP_MIN,RELATIONSHIP_MAX);
    }

    /** All persisted relationship keys plus NPC global growth and position, with immutable defaults. */
    public Map<String,String> facts(UUID player) {Objects.requireNonNull(player);return store.transaction(connection->Map.copyOf(npcFacts(connection,player)));}
    /** Read within an already open engine transaction without nesting SqliteStore.transaction. */
    public Map<String,String> facts(Connection connection,UUID player) throws SQLException {
        Objects.requireNonNull(connection);Objects.requireNonNull(player);return Map.copyOf(npcFacts(connection,player));
    }

    /** Fast database projection; call on the persistence worker, then render on the player's entity thread. */
    public NpcView view(UUID player,String npcId,PlayerContext context,Map<String,String> worldFacts) {
        if(!player.equals(context.playerId()))throw new SecurityException("NPC view context belongs to a different player");
        catalog.require(npcId);Objects.requireNonNull(worldFacts);
        return store.transaction(connection->{
            Map<String,String> facts=dialogueFacts(connection,player,npcId,context,worldFacts);
            var selected=catalog.require(npcId).dialogues().stream()
                    .sorted(Comparator.comparingInt(NpcCatalog.Dialogue::priority).reversed().thenComparing(NpcCatalog.Dialogue::id))
                    .filter(dialogue->dialogue.condition()==null||dialogue.condition().test(facts)).findFirst();
            var profile=catalog.require(npcId);
            String dialogue=selected.map(NpcCatalog.Dialogue::text).orElse(profile.name()+"暂时没有更多要说的。");
            return new NpcView(npcId,profile.name(),profile.background(),profile.faction(),facts.get("npc.position"),
                    Integer.parseInt(facts.get("npc.growth")),Integer.parseInt(facts.get("npc.reputation")),Integer.parseInt(facts.get("npc.trust")),
                    Integer.parseInt(facts.get("npc.hostility")),selected.map(NpcCatalog.Dialogue::id).orElse("fallback"),render(dialogue,facts),facts);
        });
    }

    private Map<String,String> dialogueFacts(Connection connection,UUID player,String npcId,PlayerContext context,Map<String,String> worldFacts) throws SQLException {
        Map<String,String> facts=new HashMap<>(context.facts());
        worldFacts.forEach((key,value)->{if(key.startsWith("region.")||key.startsWith("choice.")||key.startsWith("story.choice."))facts.put(key,value);});
        facts.putAll(read(connection,"WORLD"));facts.putAll(npcFacts(connection,player));
        history(connection,facts,player,context.nationId(),references(npcId));
        facts.put("player.id",player.toString());
        if(context.nationId().isBlank())facts.remove("nation.id");else facts.put("nation.id",context.nationId());
        metadata(facts,npcId);return facts;
    }
    private Map<String,String> eventFacts(Connection connection,WorldEvent event,String npcId,UUID player,Collection<String> requested) throws SQLException {
        Map<String,String> facts=new HashMap<>(read(connection,"WORLD"));
        for(var entry:event.facts().entrySet())facts.put("event."+entry.getKey(),entry.getValue());
        facts.put("event.type",event.type());facts.put("event.source",event.source());
        if(player!=null){facts.putAll(npcFacts(connection,player));facts.put("player.id",player.toString());}
        else defaults(connection,facts,npcId,null);
        // Event actors provide UUID evidence, not their current authoritative nationality.
        // Never choose an arbitrary event nation to manufacture a current national subject.
        history(connection,facts,player,null,requested);metadata(facts,npcId);return facts;
    }
    private Map<String,String> npcFacts(Connection connection,UUID player) throws SQLException {
        Map<String,String> facts=new HashMap<>();
        read(connection,"WORLD").forEach((key,value)->{if(key.startsWith("npc."))facts.put(key,value);});
        read(connection,player.toString()).forEach((key,value)->{if(key.startsWith("npc."))facts.put(key,value);});
        for(String npcId:catalog.profiles().keySet())defaults(connection,facts,npcId,player);
        return facts;
    }
    private void defaults(Connection connection,Map<String,String> facts,String npcId,UUID player) {
        String prefix="npc."+npcId+".";
        facts.put(prefix+"growth",Integer.toString(safeInteger(facts.get(prefix+"growth"),0,GROWTH_MAX)));
        facts.putIfAbsent(prefix+"position",catalog.positionAt(npcId,safeInteger(facts.get(prefix+"growth"),0,GROWTH_MAX)));
        if(player!=null) {
            facts.put(prefix+"reputation",Integer.toString(safeInteger(facts.get(prefix+"reputation"),RELATIONSHIP_MIN,RELATIONSHIP_MAX)));
            facts.put(prefix+"trust",Integer.toString(safeInteger(facts.get(prefix+"trust"),RELATIONSHIP_MIN,RELATIONSHIP_MAX)));
            facts.put(prefix+"hostility",Integer.toString(safeInteger(facts.get(prefix+"hostility"),HOSTILITY_MIN,HOSTILITY_MAX)));
        }
    }
    private void metadata(Map<String,String> facts,String npcId) {
        var profile=catalog.require(npcId);String prefix="npc."+npcId+".";
        facts.put("npc.id",npcId);facts.put("npc.name",profile.name());facts.put("npc.faction",profile.faction());facts.put("npc.background",profile.background());
        facts.put("npc.position",facts.getOrDefault(prefix+"position",profile.initialPosition()));
        for(String key:List.of("growth","reputation","trust","hostility"))facts.put("npc."+key,facts.getOrDefault(prefix+key,"0"));
    }
    private static void history(Connection connection,Map<String,String> facts,UUID player,String nation,Collection<String> requested) throws SQLException {
        // Caller-supplied contexts cannot fabricate historical counts for dialogue or reaction predicates.
        facts.keySet().removeIf(key->key.startsWith("history.")||key.startsWith("chronicle.history.")||key.startsWith("past."));
        facts.putAll(HistoricalFacts.read(connection,player,nation,requested));
        facts.putAll(ChoiceLedger.read(connection,player,nation,requested));
    }
    private Set<String> references(String npcId) {
        var profile=catalog.require(npcId);
        Condition[] conditions=java.util.stream.Stream.concat(profile.dialogues().stream().map(NpcCatalog.Dialogue::condition),profile.reactions().stream().map(NpcCatalog.Reaction::condition)).toArray(Condition[]::new);
        Set<String> requested=new HashSet<>(HistoricalFacts.requested(conditions));requested.addAll(ChoiceLedger.requested(conditions));return Set.copyOf(requested);
    }
    private static boolean needsPlayer(io.github.addxiaoyi.starchronicle.config.Condition condition) {
        if(condition.fact()!=null) {
            String fact=condition.fact();
            return fact.startsWith("player.")||fact.startsWith("chronicle.history.player.")||fact.startsWith("past.player.")||Set.of("npc.reputation","npc.trust","npc.hostility").contains(fact)
                    ||fact.startsWith("npc.")&&(fact.endsWith(".reputation")||fact.endsWith(".trust")||fact.endsWith(".hostility"));
        }
        if(condition.not()!=null)return needsPlayer(condition.not());
        var children=condition.all()!=null?condition.all():condition.any();
        return children!=null&&children.stream().anyMatch(NpcLedger::needsPlayer);
    }
    private static Map<String,String> read(Connection connection,String owner) throws SQLException {
        Map<String,String> facts=new HashMap<>();
        try(var query=connection.prepareStatement("SELECT key,value FROM facts WHERE owner=?")) {
            query.setString(1,owner);try(var result=query.executeQuery()){while(result.next())facts.put(result.getString(1),result.getString(2));}
        }
        return facts;
    }
    private static int bounded(Connection connection,String owner,String key,long delta,int min,int max) throws SQLException {
        long current=0;
        try(var query=connection.prepareStatement("SELECT value FROM facts WHERE owner=? AND key=?")) {
            query.setString(1,owner);query.setString(2,key);
            try(var result=query.executeQuery()){if(result.next())current=Long.parseLong(result.getString(1));}
        }
        long normalized=Math.max(min,Math.min(max,current));int next=(int)Math.max(min,Math.min(max,normalized+delta));
        set(connection,owner,key,Integer.toString(next));return next;
    }
    private static int safeInteger(String value,int min,int max) {
        if(value==null)return 0;
        try{return (int)Math.max(min,Math.min(max,Long.parseLong(value)));}
        catch(NumberFormatException exception){throw new IllegalStateException("Invalid persisted NPC integer",exception);}
    }
    private static void set(Connection connection,String owner,String key,String value) throws SQLException {
        try(var update=connection.prepareStatement("INSERT INTO facts(owner,key,value) VALUES(?,?,?) ON CONFLICT(owner,key) DO UPDATE SET value=excluded.value")) {
            update.setString(1,owner);update.setString(2,key);update.setString(3,value);update.executeUpdate();
        }
    }
    private static boolean receipt(Connection connection,String id,String source,String npc,String reaction) throws SQLException {
        try(var insert=connection.prepareStatement("INSERT OR IGNORE INTO outcomes(id,status,json) VALUES(?,'DONE',?)")) {
            insert.setString(1,id);insert.setString(2,SqliteStore.json(Map.of("sourceEvent",source,"npc",npc,"reaction",reaction)));
            return insert.executeUpdate()==1;
        }
    }
    private static void inTransaction(Connection connection) throws SQLException {
        if(connection==null||connection.getAutoCommit())throw new IllegalStateException("NPC writes require the enclosing Chronicle transaction");
    }
    private static String render(String template,Map<String,String> facts) {
        Matcher matcher=PLACEHOLDER.matcher(template);StringBuilder rendered=new StringBuilder();
        while(matcher.find())matcher.appendReplacement(rendered,Matcher.quoteReplacement(facts.getOrDefault(matcher.group(1),matcher.group())));
        matcher.appendTail(rendered);
        return rendered.length()>16384?rendered.substring(0,16384):rendered.toString();
    }
}
