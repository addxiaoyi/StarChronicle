package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.config.ConfigLoader;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Immutable read-only directory of actual accepted/pinned definition variants. Retired entries
 * are evidence for exact past references, never automatically enabled stories or executable jobs. */
public final class DefinitionCatalog {
    private static final Pattern ID=Pattern.compile("[A-Za-z0-9_.-]{1,80}");
    public record Key(String storyId,String version) {
        public Key {id(storyId);id(version);}
        @Override public String toString(){return storyId+"/"+version;}
    }
    public record Entry(Key key,String fingerprint,StoryDefinition definition,String provenance,String firstSeen) {}
    private final Map<Key,List<Entry>> entries;
    private DefinitionCatalog(Map<Key,List<Entry>> source) {
        Map<Key,List<Entry>> copy=new LinkedHashMap<>();source.forEach((key,value)->copy.put(key,List.copyOf(value)));
        entries=Collections.unmodifiableMap(copy);
    }
    public static DefinitionCatalog empty(){return new DefinitionCatalog(Map.of());}
    public Map<Key,List<Entry>> entries(){return entries;}
    public Set<Key> keys(){return entries.keySet();}

    public static void createSchema(Connection connection)throws SQLException {
        inTransaction(connection);
        try(var s=connection.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS story_definitions("+
                "story TEXT NOT NULL,version TEXT NOT NULL,fingerprint TEXT NOT NULL "+
                "CHECK(length(fingerprint)=64 AND fingerprint NOT GLOB '*[^0-9a-f]*'),"+
                "json TEXT NOT NULL,provenance TEXT NOT NULL,"+
                "first_seen TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),"+
                "PRIMARY KEY(story,version,fingerprint))");
        }
    }

    public static DefinitionCatalog read(Connection connection)throws SQLException {
        inTransaction(connection);Map<Key,List<Entry>> result=new LinkedHashMap<>();
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT story,version,fingerprint,json,provenance,first_seen FROM story_definitions ORDER BY story,version,fingerprint")) {
            while(r.next()) {
                Entry entry=entry(r);result.computeIfAbsent(entry.key(),ignored->new ArrayList<>()).add(entry);
            }
        }
        return new DefinitionCatalog(result);
    }

    /** Preserve the first readable source and archive time for each exact canonical variant.
     * This method deliberately accepts real conflicting variants; callers validateCurrent
     * before accepting new executable configuration, whereas migrations must preserve evidence. */
    public static int archive(Connection connection,Collection<StoryDefinition> definitions,String provenance)throws SQLException {
        inTransaction(connection);Objects.requireNonNull(definitions);provenance(provenance);int added=0;
        for(StoryDefinition definition:definitions) {
            String canonical=canonical(definition),fingerprint=digest(canonical);Key key=new Key(definition.id(),definition.version());
            try(var s=connection.prepareStatement("INSERT OR IGNORE INTO story_definitions(story,version,fingerprint,json,provenance) VALUES(?,?,?,?,?)")) {
                s.setString(1,key.storyId());s.setString(2,key.version());s.setString(3,fingerprint);s.setString(4,canonical);s.setString(5,provenance);added+=s.executeUpdate();
            }
            try(var s=connection.prepareStatement("SELECT story,version,fingerprint,json,provenance,first_seen FROM story_definitions WHERE story=? AND version=? AND fingerprint=?")) {
                s.setString(1,key.storyId());s.setString(2,key.version());s.setString(3,fingerprint);
                try(var r=s.executeQuery()) {
                    if(!r.next())throw new IllegalStateException("Definition archive row disappeared: "+key);
                    Entry stored=entry(r);
                    if(!canonical.equals(canonical(stored.definition())))throw new IllegalStateException("Definition fingerprint payload collision: "+key);
                }
            }
        }
        return added;
    }

    /** Import only definitions actually retained in old instance/pending snapshots. There is no
     * reconstruction from choice IDs, event facts, file names or invented placeholder nodes. */
    public static int migrateSnapshots(Connection connection)throws SQLException {
        inTransaction(connection);List<Snapshot> snapshots=new ArrayList<>();
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT id,story,scope,completed,json FROM instances ORDER BY id")) {
            while(r.next()) {
                StoryInstance instance=SqliteStore.decode(r.getString("json"),StoryInstance.class);
                if(!instance.id().equals(r.getString("id"))||!instance.definition().id().equals(r.getString("story"))||!instance.scopeKey().equals(r.getString("scope"))||instance.completed()!=(r.getInt("completed")!=0))
                    throw new IllegalStateException("Definition source instance metadata is inconsistent: "+r.getString("id"));
                snapshots.add(new Snapshot(instance.definition(),"LEGACY_INSTANCE "+instance.id()));
            }
        }
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT id,story_json FROM story_triggers ORDER BY id")) {
            while(r.next())snapshots.add(new Snapshot(SqliteStore.decode(r.getString("story_json"),StoryDefinition.class),"PENDING_TRIGGER "+r.getString("id")));
        }
        int added=0;for(Snapshot snapshot:snapshots)added+=archive(connection,List.of(snapshot.definition()),snapshot.provenance());return added;
    }

    public StoryDefinition require(String storyId,String version) {
        Key key=new Key(storyId,version);List<Entry> variants=entries.get(key);
        if(variants==null)throw new IllegalArgumentException("Missing archived story definition: "+key);
        if(variants.size()!=1)throw conflict(key,variants);
        return variants.getFirst().definition();
    }

    /** Active selection remains one version per story ID. No current-file precedence, latest
     * version fallback or native-binding field stripping can resolve an archived conflict. */
    public void validateCurrent(Collection<StoryDefinition> current) {
        Objects.requireNonNull(current);Set<String> enabled=new HashSet<>();
        for(StoryDefinition definition:current) {
            String canonical=canonical(definition);Key key=new Key(definition.id(),definition.version());
            if(!enabled.add(key.storyId()))throw new IllegalArgumentException("Duplicate current story ID: "+key.storyId());
            List<Entry> variants=entries.get(key);if(variants==null)continue;
            if(variants.size()!=1)throw conflict(key,variants);
            Entry old=variants.getFirst();String fingerprint=digest(canonical);
            if(!old.fingerprint().equals(fingerprint))throw new IllegalArgumentException("Current definition conflicts with archived "+key+": "+fingerprint+" differs from "+old.fingerprint()+" ["+old.provenance()+"]; increment the story version");
        }
    }

    private record Snapshot(StoryDefinition definition,String provenance) {}
    private static Entry entry(ResultSet row)throws SQLException {
        Key key=new Key(row.getString("story"),row.getString("version"));StoryDefinition definition=SqliteStore.decode(row.getString("json"),StoryDefinition.class);
        String canonical=canonical(definition),fingerprint=row.getString("fingerprint"),source=row.getString("provenance"),firstSeen=row.getString("first_seen");
        provenance(source);
        if(!key.equals(new Key(definition.id(),definition.version()))||!digest(canonical).equals(fingerprint)||firstSeen==null||firstSeen.isBlank())
            throw new IllegalStateException("Corrupt archived definition metadata or fingerprint: "+key);
        return new Entry(key,fingerprint,definition,source,firstSeen);
    }
    private static String canonical(StoryDefinition definition) {
        Objects.requireNonNull(definition,"definition");
        // A retired definition may reference unavailable quests/scripts or other retired stories.
        // Validate its own bounded shape, not today's executable dependency directory.
        ConfigLoader.validate(definition,ignored->true,ignored->true);return SqliteStore.json(definition);
    }
    private static String digest(String canonical) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static IllegalArgumentException conflict(Key key,List<Entry> variants) {
        List<String> examples=variants.stream().limit(5).map(entry->entry.fingerprint()+" ["+entry.provenance()+"]").toList();
        return new IllegalArgumentException("Conflicting archived story definition "+key+" has "+variants.size()+" variants: "+String.join(", ",examples)+(variants.size()>5?"; more variants retained":""));
    }
    private static void id(String value){if(value==null||!ID.matcher(value).matches())throw new IllegalArgumentException("Invalid definition version identity: "+value);}
    private static void provenance(String value) {
        if(value==null||value.isBlank()||value.length()>512||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Definition provenance must be readable text of 1..512 characters");
    }
    private static void inTransaction(Connection connection)throws SQLException {
        if(connection==null||connection.getAutoCommit())throw new IllegalStateException("Definition catalog requires the enclosing Chronicle transaction");
    }
}
