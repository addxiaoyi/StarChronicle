package io.github.addxiaoyi.starchronicle.memory;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.*;

/** Uses the caller's history transaction. Never invokes Bukkit or opens a nested storage transaction. */
public final class MemoryRecipeLedger {
    private MemoryRecipeLedger(){}
    private static final java.time.format.DateTimeFormatter SORTABLE_TIME=new java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    public record Block(String eventId,String recipeId,String version,String reason) {}
    public static void createSchema(Connection c)throws SQLException {
        enclosing(c);try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS memory_recipe_definitions(recipe_id TEXT NOT NULL,version TEXT NOT NULL,fingerprint TEXT NOT NULL CHECK(length(fingerprint)=64 AND fingerprint NOT GLOB '*[^0-9a-f]*'),json TEXT NOT NULL,PRIMARY KEY(recipe_id,version),UNIQUE(recipe_id,version,fingerprint))");
            s.execute("CREATE TABLE IF NOT EXISTS memory_entries(id TEXT PRIMARY KEY,source_event_id TEXT NOT NULL REFERENCES events(id),recipe_id TEXT NOT NULL,version TEXT NOT NULL,definition_hash TEXT NOT NULL,region_id TEXT NOT NULL,recorded_at TEXT NOT NULL,signal_event_id TEXT NOT NULL UNIQUE REFERENCES events(id) DEFERRABLE INITIALLY DEFERRED,json TEXT NOT NULL,UNIQUE(source_event_id,recipe_id,version),FOREIGN KEY(recipe_id,version,definition_hash) REFERENCES memory_recipe_definitions(recipe_id,version,fingerprint))");
            s.execute("CREATE INDEX IF NOT EXISTS memory_entries_region ON memory_entries(region_id,recorded_at DESC,id)");
            s.execute("CREATE TABLE IF NOT EXISTS memory_recipe_blocks(id TEXT PRIMARY KEY,event_id TEXT NOT NULL REFERENCES events(id),recipe_id TEXT NOT NULL,version TEXT NOT NULL,reason TEXT NOT NULL)");
        }
    }
    public static String fingerprint(MemoryRecipeCatalog.Recipe recipe){return RuleEngine.stable("memory-recipe-v1",recipe.canonical());}
    public static void validateDefinitions(Connection c,MemoryRecipeCatalog catalog)throws SQLException {
        enclosing(c);for(var recipe:catalog.recipes())try(var s=c.prepareStatement("SELECT fingerprint,json FROM memory_recipe_definitions WHERE recipe_id=? AND version=?")) {
            s.setString(1,recipe.id());s.setString(2,recipe.version());try(var rows=s.executeQuery()) {
                if(rows.next()&&(!fingerprint(recipe).equals(rows.getString(1))||!recipe.canonical().equals(rows.getString(2))))throw new IllegalArgumentException("Memory recipe content changed without a new version: "+recipe.id()+"/"+recipe.version());
            }
        }
    }
    public static void archiveDefinitions(Connection c,MemoryRecipeCatalog catalog)throws SQLException {
        validateDefinitions(c,catalog);for(var recipe:catalog.recipes())try(var s=c.prepareStatement("INSERT OR IGNORE INTO memory_recipe_definitions VALUES(?,?,?,?)")) {
            s.setString(1,recipe.id());s.setString(2,recipe.version());s.setString(3,fingerprint(recipe));s.setString(4,recipe.canonical());s.executeUpdate();
        }
    }
    public static MemoryEntry register(Connection c,MemoryRecipeCatalog.Recipe recipe,WorldEvent source,Instant recordedAt)throws SQLException {
        enclosing(c);
        try(var query=c.prepareStatement("SELECT json FROM events WHERE id=?")) {
            query.setString(1,source.id());try(var rows=query.executeQuery()) {
                if(!rows.next()||!source.equals(decode(rows.getString(1),WorldEvent.class)))throw new IllegalArgumentException("Memory registration requires the stored canonical source");
            }
        }
        String id=RuleEngine.stable("memory-entry-v1",source.id(),recipe.id(),recipe.version());
        MemoryEntry entry=new MemoryEntry(id,source.id(),recipe.id(),recipe.version(),fingerprint(recipe),recipe.regionId(),recipe.title(),recipe.text(),source.occurredAt(),recordedAt,"memory:"+id);
        try(var s=c.prepareStatement("INSERT INTO memory_entries VALUES(?,?,?,?,?,?,?,?,?)")) {
            s.setString(1,id);s.setString(2,source.id());s.setString(3,recipe.id());s.setString(4,recipe.version());s.setString(5,entry.definitionHash());s.setString(6,recipe.regionId());s.setString(7,SORTABLE_TIME.format(recordedAt));s.setString(8,entry.signalEventId());s.setString(9,json(entry));s.executeUpdate();
        }
        String prefix="region."+recipe.regionId()+".memory."+recipe.id()+".";
        Map<String,String> facts=Map.of("entry",id,"version",recipe.version(),"source",source.id(),"source_occurred_at",source.occurredAt().toString(),"recorded_at",recordedAt.toString(),"title",recipe.title(),"text",recipe.text());
        try(var s=c.prepareStatement("INSERT INTO facts(owner,key,value) VALUES('WORLD',?,?) ON CONFLICT(owner,key) DO UPDATE SET value=excluded.value")) {
            for(var fact:facts.entrySet()){s.setString(1,prefix+fact.getKey());s.setString(2,fact.getValue());s.addBatch();}s.executeBatch();
        }
        return entry;
    }
    public static WorldEvent signal(MemoryEntry entry,WorldEvent source) {
        Map<String,String> facts=new TreeMap<>();facts.put("region.id",entry.regionId());facts.put("memory.entry",entry.id());facts.put("memory.recipe",entry.recipeId());facts.put("memory.version",entry.recipeVersion());facts.put("memory.definition_hash",entry.definitionHash());facts.put("memory.title",entry.title());facts.put("memory.text",entry.text());facts.put("source.id",source.id());facts.put("source.type",source.type());facts.put("source.source",source.source());facts.put("source.occurred_at",source.occurredAt().toString());facts.put("time_basis","RECORDED");
        if(source.facts().containsKey("city.id"))facts.put("city.id",source.facts().get("city.id"));
        if(source.globalAudience())facts.put("audience","GLOBAL");
        return new WorldEvent(entry.signalEventId(),"WORLD_MEMORY_RECORDED",entry.recordedAt(),source.participants(),source.nations(),facts,"StarChronicle.MemoryRecipes","Memory registration="+entry.id()+"; recipe="+entry.recipeId()+"/"+entry.recipeVersion()+"; canonical source="+source.id()+"; source occurredAt="+source.occurredAt()+"; recordedAt="+entry.recordedAt()+"; "+entry.text());
    }
    public static void block(Connection c,WorldEvent source,MemoryRecipeCatalog.Recipe recipe,String reason)throws SQLException {
        enclosing(c);try(var s=c.prepareStatement("INSERT OR IGNORE INTO memory_recipe_blocks VALUES(?,?,?,?,?)")) {
            s.setString(1,RuleEngine.stable(source.id(),recipe.id(),recipe.version(),reason));s.setString(2,source.id());s.setString(3,recipe.id());s.setString(4,recipe.version());s.setString(5,reason);s.executeUpdate();
        }
    }
    public static List<MemoryEntry> entries(Connection c,String region,int page,int size)throws SQLException {
        enclosing(c);if(region!=null)MemoryRecipeCatalog.localId(region);if(page<0||size<1||size>100||page>1_000_000)throw new IllegalArgumentException("Invalid memory page");
        List<MemoryEntry> result=new ArrayList<>();String sql="SELECT json FROM memory_entries"+(region==null?"":" WHERE region_id=?")+" ORDER BY recorded_at DESC,id LIMIT ? OFFSET ?";
        try(var s=c.prepareStatement(sql)){int p=1;if(region!=null)s.setString(p++,region);s.setInt(p++,size);s.setLong(p,(long)page*size);try(var rows=s.executeQuery()){while(rows.next())result.add(decode(rows.getString(1),MemoryEntry.class));}}return List.copyOf(result);
    }
    public static List<Block> blocks(Connection c,int limit)throws SQLException {
        enclosing(c);if(limit<1||limit>1000)throw new IllegalArgumentException("Invalid memory block limit");List<Block> result=new ArrayList<>();
        try(var s=c.prepareStatement("SELECT event_id,recipe_id,version,reason FROM memory_recipe_blocks ORDER BY rowid DESC LIMIT ?")){s.setInt(1,limit);try(var rows=s.executeQuery()){while(rows.next())result.add(new Block(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4)));}}return List.copyOf(result);
    }
    private static void enclosing(Connection c)throws SQLException{if(c==null||c.getAutoCommit())throw new IllegalStateException("Memory recipes require an enclosing history transaction");}
}
