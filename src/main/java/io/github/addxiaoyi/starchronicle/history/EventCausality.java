package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.sql.*;
import java.util.*;

/** Transactional provenance for Chronicle-derived history. Event facts cannot supply these
 * fields. A bounded causal path protects old pinned stories across later configuration edits. */
public final class EventCausality {
    public static final int MAX_DEPTH=32;
    private EventCausality() {}
    public record Trace(String parentId,String rootId,int depth,List<String> stories) {
        public Trace {
            Objects.requireNonNull(rootId);stories=List.copyOf(stories);
            if(rootId.isBlank()||depth<0||depth!=stories.size()||(depth==0)!=(parentId==null))throw new IllegalArgumentException("Invalid causal trace");
            if(stories.stream().anyMatch(id->id==null||!id.matches("[A-Za-z0-9_.-]{1,80}")))throw new IllegalArgumentException("Invalid causal story ID");
        }
    }
    public record Block(String eventId,String storyId,String version,String reason) {}

    public static void createSchema(Connection c)throws SQLException {
        inTransaction(c);
        try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS event_causes(event_id TEXT PRIMARY KEY REFERENCES events(id),parent_id TEXT REFERENCES events(id),root_id TEXT NOT NULL REFERENCES events(id),depth INTEGER NOT NULL CHECK(depth>=0),story_path TEXT NOT NULL,CHECK((depth=0 AND parent_id IS NULL) OR (depth>0 AND parent_id IS NOT NULL)))");
            s.execute("CREATE INDEX IF NOT EXISTS event_causal_root ON event_causes(root_id,depth,event_id)");
            s.execute("CREATE TABLE IF NOT EXISTS story_trigger_blocks(id TEXT PRIMARY KEY,event_id TEXT NOT NULL REFERENCES events(id),story TEXT NOT NULL,version TEXT NOT NULL,reason TEXT NOT NULL)");
        }
    }
    public static Trace root(String eventId){return new Trace(null,eventId,0,List.of());}

    /** A V1/V2 source without a trace remains a legacy root; no unseen ancestry is invented. */
    public static Trace read(Connection c,String eventId)throws SQLException {
        inTransaction(c);
        try(var s=c.prepareStatement("SELECT parent_id,root_id,depth,story_path FROM event_causes WHERE event_id=?")) {
            s.setString(1,eventId);
            try(var r=s.executeQuery()){if(r.next())return new Trace(r.getString(1),r.getString(2),r.getInt(3),Arrays.asList(SqliteStore.decode(r.getString(4),String[].class)));}
        }
        try(var s=c.prepareStatement("SELECT 1 FROM events WHERE id=?")) {
            s.setString(1,eventId);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Missing causal source event: "+eventId);}
        }
        return root(eventId);
    }
    public static Trace derive(Connection c,String parentEvent,String storyId)throws SQLException {
        Trace parent=read(c,parentEvent);List<String> path=new ArrayList<>(parent.stories());path.add(storyId);
        return new Trace(parentEvent,parent.rootId(),Math.addExact(parent.depth(),1),path);
    }
    public static void record(Connection c,String eventId,Trace trace)throws SQLException {
        inTransaction(c);Objects.requireNonNull(trace);
        try(var s=c.prepareStatement("INSERT OR IGNORE INTO event_causes VALUES(?,?,?,?,?)")) {
            s.setString(1,eventId);s.setString(2,trace.parentId());s.setString(3,trace.rootId());s.setInt(4,trace.depth());s.setString(5,SqliteStore.json(trace.stories()));s.executeUpdate();
        }
        if(!trace.equals(read(c,eventId)))throw new IllegalArgumentException("Causal event ID collision: "+eventId);
    }
    public static String blockedReason(Trace trace,String nextStory) {
        if(trace.depth()>=MAX_DEPTH)return "MAX_CAUSAL_DEPTH";
        return trace.stories().contains(nextStory)?"REPEATED_STORY_IN_CAUSAL_CHAIN":null;
    }
    /** Keep the true history event even when a follow-up trigger is quarantined. */
    public static void block(Connection c,String eventId,StoryDefinition story,String reason)throws SQLException {
        inTransaction(c);
        try(var s=c.prepareStatement("INSERT OR IGNORE INTO story_trigger_blocks VALUES(?,?,?,?,?)")) {
            s.setString(1,RuleEngine.stable(eventId,story.id(),story.version(),reason));s.setString(2,eventId);s.setString(3,story.id());s.setString(4,story.version());s.setString(5,reason);s.executeUpdate();
        }
    }
    public static List<Block> blocks(Connection c,int limit)throws SQLException {
        inTransaction(c);if(limit<1||limit>1000)throw new IllegalArgumentException("Invalid causal block limit");
        List<Block> result=new ArrayList<>();
        try(var s=c.prepareStatement("SELECT event_id,story,version,reason FROM story_trigger_blocks ORDER BY rowid DESC LIMIT ?")) {
            s.setInt(1,limit);try(var r=s.executeQuery()){while(r.next())result.add(new Block(r.getString(1),r.getString(2),r.getString(3),r.getString(4)));}
        }
        return List.copyOf(result);
    }
    private static void inTransaction(Connection c)throws SQLException {if(c==null||c.getAutoCommit())throw new IllegalStateException("Causal history requires an enclosing transaction");}
}
