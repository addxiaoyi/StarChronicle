package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Exact-version directory. Original payloads are never rewritten by a protocol upgrade.
 * Logical variants use V2 semantics; retired entries never enable stories or replay work. */
public final class DefinitionCatalog {
    private static final Pattern ID=Pattern.compile("[A-Za-z0-9_.-]{1,80}");
    public record Key(String storyId,String version) {
        public Key {id(storyId);id(version);}
        @Override public String toString(){return storyId+"/"+version;}
    }
    /** fingerprint identifies exact original UTF-8 bytes; semanticFingerprint is the current
     * protocol interpretation. The source provenance/time remain those of the raw row. */
    public record Entry(Key key,String fingerprint,StoryDefinition definition,String provenance,String firstSeen,int canonicalVersion) {
        public Entry(Key key,String fingerprint,StoryDefinition definition,String provenance,String firstSeen){this(key,fingerprint,definition,provenance,firstSeen,DefinitionCanonicalCodec.CURRENT);}
        public String semanticFingerprint(){return DefinitionCanonicalCodec.fingerprintV2(definition);}
    }
    private final Map<Key,List<Entry>> entries;
    private final List<Entry> evidenceEntries;
    private DefinitionCatalog(Map<Key,List<Entry>> source,List<Entry> evidence) {
        Map<Key,List<Entry>> copy=new LinkedHashMap<>();source.forEach((key,value)->copy.put(key,List.copyOf(value)));
        entries=Collections.unmodifiableMap(copy);evidenceEntries=List.copyOf(evidence);
    }
    public static DefinitionCatalog empty(){return new DefinitionCatalog(Map.of(),List.of());}
    /** One entry per effective variant; equivalent V1/V2 representations do not conflict. */
    public Map<Key,List<Entry>> entries(){return entries;}
    public List<Entry> evidenceEntries(){return evidenceEntries;}
    public Set<Key> keys(){return entries.keySet();}

    public static void createSchema(Connection connection)throws SQLException {
        inTransaction(connection);
        try(var s=connection.createStatement()) {
            // Released schema-4 table and all existing rows/columns stay untouched.
            s.execute("CREATE TABLE IF NOT EXISTS story_definitions("+
                "story TEXT NOT NULL,version TEXT NOT NULL,fingerprint TEXT NOT NULL "+
                "CHECK(length(fingerprint)=64 AND fingerprint NOT GLOB '*[^0-9a-f]*'),"+
                "json TEXT NOT NULL,provenance TEXT NOT NULL,"+
                "first_seen TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),"+
                "PRIMARY KEY(story,version,fingerprint))");
            s.execute("CREATE TABLE IF NOT EXISTS story_definition_formats("+
                "story TEXT NOT NULL,version TEXT NOT NULL,fingerprint TEXT NOT NULL,"+
                "canonical_version INTEGER NOT NULL CHECK(canonical_version IN (1,2)),"+
                "PRIMARY KEY(story,version,fingerprint),"+
                "FOREIGN KEY(story,version,fingerprint) REFERENCES story_definitions(story,version,fingerprint))");
            s.execute("CREATE TABLE IF NOT EXISTS story_definition_upgrades("+
                "story TEXT NOT NULL,version TEXT NOT NULL,from_fingerprint TEXT NOT NULL,"+
                "from_canonical_version INTEGER NOT NULL CHECK(from_canonical_version=1),"+
                "to_canonical_version INTEGER NOT NULL CHECK(to_canonical_version=2),"+
                "to_fingerprint TEXT NOT NULL CHECK(length(to_fingerprint)=64 AND to_fingerprint NOT GLOB '*[^0-9a-f]*'),"+
                "json TEXT NOT NULL,algorithm TEXT NOT NULL CHECK(algorithm='V1_TO_V2_ADD_PLAYER_VOTE_UNIT'),"+
                "PRIMARY KEY(story,version,from_fingerprint),"+
                "FOREIGN KEY(story,version,from_fingerprint) REFERENCES story_definitions(story,version,fingerprint))");
        }
    }

    /** Schema 5 adds V1 evidence -> V2 semantics links without updating original JSON,
     * fingerprint, provenance or time. A failed raw hash aborts before decoding. */
    public static int migrateCanonicalV2(Connection connection)throws SQLException {
        inTransaction(connection);List<Raw> originals=rawRows(connection);int added=0;
        for(Raw raw:originals) {
            int format=raw.format()==null?DefinitionCanonicalCodec.V1:raw.format();
            Entry entry=verified(raw,format);putFormat(connection,entry.key(),entry.fingerprint(),format);
            if(format==DefinitionCanonicalCodec.V1) {
                String canonical=DefinitionCanonicalCodec.encodeV2(entry.definition()),fingerprint=DefinitionCanonicalCodec.sha256(canonical);
                try(var s=connection.prepareStatement("INSERT OR IGNORE INTO story_definition_upgrades(story,version,from_fingerprint,from_canonical_version,to_canonical_version,to_fingerprint,json,algorithm) VALUES(?,?,?,1,2,?,?,?)")) {
                    s.setString(1,entry.key().storyId());s.setString(2,entry.key().version());s.setString(3,entry.fingerprint());s.setString(4,fingerprint);s.setString(5,canonical);s.setString(6,DefinitionCanonicalCodec.V1_TO_V2);added+=s.executeUpdate();
                }
                verifyUpgrade(connection,entry);
            }else noUpgrade(connection,entry);
        }
        return added;
    }

    public static DefinitionCatalog read(Connection connection)throws SQLException {
        inTransaction(connection);Map<Key,Map<String,Entry>> variants=new TreeMap<>(Comparator.comparing(Key::storyId).thenComparing(Key::version));List<Entry> evidence=new ArrayList<>();
        for(Raw raw:rawRows(connection)) {
            if(raw.format()==null)throw new IllegalStateException("Missing definition canonical format: "+raw.story()+"/"+raw.version());
            Entry entry=verified(raw,raw.format());if(entry.canonicalVersion()==DefinitionCanonicalCodec.V1)verifyUpgrade(connection,entry);else noUpgrade(connection,entry);
            evidence.add(entry);Map<String,Entry> byFingerprint=variants.computeIfAbsent(entry.key(),ignored->new TreeMap<>());
            byFingerprint.merge(entry.semanticFingerprint(),entry,(a,b)->Comparator.comparing(Entry::firstSeen).thenComparing(Entry::fingerprint).compare(a,b)<=0?a:b);
        }
        Map<Key,List<Entry>> result=new LinkedHashMap<>();variants.forEach((key,value)->result.put(key,List.copyOf(value.values())));return new DefinitionCatalog(result,evidence);
    }

    /** Archive explicit V2 payloads. An equal verified V1 variant is reused through its
     * migration link, preserving the first provenance/time and original row count. */
    public static int archive(Connection connection,Collection<StoryDefinition> definitions,String provenance)throws SQLException {
        inTransaction(connection);Objects.requireNonNull(definitions);provenance(provenance);int added=0;
        DefinitionCatalog existing=read(connection);Map<Key,Set<String>> known=new HashMap<>();
        existing.entries.forEach((key,variants)->{Set<String> fingerprints=new HashSet<>();variants.forEach(entry->fingerprints.add(entry.semanticFingerprint()));known.put(key,fingerprints);});
        for(StoryDefinition definition:definitions) {
            String canonical=DefinitionCanonicalCodec.encodeV2(definition),fingerprint=DefinitionCanonicalCodec.sha256(canonical);Key key=new Key(definition.id(),definition.version());
            Set<String> fingerprints=known.computeIfAbsent(key,ignored->new HashSet<>());if(fingerprints.contains(fingerprint))continue;
            added+=insertRaw(connection,key,fingerprint,canonical,provenance);putFormat(connection,key,fingerprint,DefinitionCanonicalCodec.V2);
            Raw raw=raw(connection,key,fingerprint);Entry entry=verified(raw,DefinitionCanonicalCodec.V2);
            if(!canonical.equals(raw.payload())||!fingerprint.equals(entry.semanticFingerprint()))throw new IllegalStateException("Definition fingerprint payload collision: "+key);
            noUpgrade(connection,entry);fingerprints.add(fingerprint);
        }
        return added;
    }

    /** Only actual old snapshots are imported with the frozen V1 codec. Thus 2/3 -> 5
     * produces the same original archive rows as 2/3 -> 4 -> 5. No snapshot is edited.
     * Run this before migrateCanonicalV2 in the enclosing schema transaction. */
    public static int migrateSnapshots(Connection connection)throws SQLException {
        inTransaction(connection);List<Snapshot> snapshots=new ArrayList<>();
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT id,story,scope,completed,json FROM instances ORDER BY id")) {
            while(r.next()) {
                String payload=r.getString("json");StoryInstance instance=SqliteStore.decode(payload,StoryInstance.class);
                StoryDefinition definition=DefinitionCanonicalCodec.decode(DefinitionCanonicalCodec.V1,DefinitionCanonicalCodec.legacyDefinitionFromInstance(payload));
                if(!instance.id().equals(r.getString("id"))||!definition.equals(instance.definition())||!definition.id().equals(r.getString("story"))||!instance.scopeKey().equals(r.getString("scope"))||instance.completed()!=(r.getInt("completed")!=0))
                    throw new IllegalStateException("Definition source instance metadata is inconsistent: "+r.getString("id"));
                snapshots.add(new Snapshot(definition,"LEGACY_INSTANCE "+instance.id()));
            }
        }
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT id,story_json FROM story_triggers ORDER BY id")) {
            while(r.next())snapshots.add(new Snapshot(DefinitionCanonicalCodec.decode(DefinitionCanonicalCodec.V1,r.getString("story_json")),"PENDING_TRIGGER "+r.getString("id")));
        }
        int added=0;for(Snapshot snapshot:snapshots) {
            String payload=DefinitionCanonicalCodec.encodeV1(snapshot.definition()),fingerprint=DefinitionCanonicalCodec.sha256(payload);Key key=new Key(snapshot.definition().id(),snapshot.definition().version());
            added+=insertRaw(connection,key,fingerprint,payload,snapshot.provenance());Raw actual=raw(connection,key,fingerprint);
            if(!payload.equals(actual.payload()))throw new IllegalStateException("Legacy definition payload collision: "+key);
        }return added;
    }

    public StoryDefinition require(String storyId,String version) {
        Key key=new Key(storyId,version);List<Entry> variants=entries.get(key);
        if(variants==null)throw new IllegalArgumentException("Missing archived story definition: "+key);
        if(variants.size()!=1)throw conflict(key,variants);return variants.getFirst().definition();
    }
    public void validateCurrent(Collection<StoryDefinition> current) {
        Objects.requireNonNull(current);Set<String> enabled=new HashSet<>();
        for(StoryDefinition definition:current) {
            String fingerprint=DefinitionCanonicalCodec.fingerprintV2(definition);Key key=new Key(definition.id(),definition.version());
            if(!enabled.add(key.storyId()))throw new IllegalArgumentException("Duplicate current story ID: "+key.storyId());
            List<Entry> variants=entries.get(key);if(variants==null)continue;if(variants.size()!=1)throw conflict(key,variants);
            Entry old=variants.getFirst();if(!old.semanticFingerprint().equals(fingerprint))throw new IllegalArgumentException("Current definition conflicts with archived "+key+": "+fingerprint+" differs from "+old.semanticFingerprint()+" ["+old.provenance()+"]; increment the story version");
        }
    }

    private record Snapshot(StoryDefinition definition,String provenance){}
    private record Raw(String story,String version,String fingerprint,String payload,String provenance,String firstSeen,Integer format){}
    private static List<Raw> rawRows(Connection connection)throws SQLException {
        List<Raw> rows=new ArrayList<>();try(var s=connection.createStatement();var r=s.executeQuery("SELECT d.story,d.version,d.fingerprint,d.json,d.provenance,d.first_seen,f.canonical_version FROM story_definitions d LEFT JOIN story_definition_formats f ON f.story=d.story AND f.version=d.version AND f.fingerprint=d.fingerprint ORDER BY d.story,d.version,d.fingerprint")){while(r.next())rows.add(raw(r));}return rows;
    }
    private static Raw raw(Connection connection,Key key,String fingerprint)throws SQLException {
        try(var s=connection.prepareStatement("SELECT d.story,d.version,d.fingerprint,d.json,d.provenance,d.first_seen,f.canonical_version FROM story_definitions d LEFT JOIN story_definition_formats f ON f.story=d.story AND f.version=d.version AND f.fingerprint=d.fingerprint WHERE d.story=? AND d.version=? AND d.fingerprint=?")) {
            s.setString(1,key.storyId());s.setString(2,key.version());s.setString(3,fingerprint);try(var r=s.executeQuery()){if(!r.next())throw new IllegalStateException("Definition archive row disappeared: "+key);return raw(r);}
        }
    }
    private static Raw raw(ResultSet row)throws SQLException {
        int format=row.getInt("canonical_version");Integer nullable=row.wasNull()?null:format;
        return new Raw(row.getString("story"),row.getString("version"),row.getString("fingerprint"),row.getString("json"),row.getString("provenance"),row.getString("first_seen"),nullable);
    }
    private static Entry verified(Raw row,int format) {
        Key key=new Key(row.story(),row.version());StoryDefinition definition=DefinitionCanonicalCodec.verifyAndDecode(format,row.payload(),row.fingerprint());provenance(row.provenance());
        if(!key.equals(new Key(definition.id(),definition.version()))||row.firstSeen()==null||row.firstSeen().isBlank())throw new IllegalStateException("Corrupt archived definition metadata: "+key);
        return new Entry(key,row.fingerprint(),definition,row.provenance(),row.firstSeen(),format);
    }
    private static int insertRaw(Connection connection,Key key,String fingerprint,String payload,String source)throws SQLException {
        provenance(source);try(var s=connection.prepareStatement("INSERT OR IGNORE INTO story_definitions(story,version,fingerprint,json,provenance) VALUES(?,?,?,?,?)")){s.setString(1,key.storyId());s.setString(2,key.version());s.setString(3,fingerprint);s.setString(4,payload);s.setString(5,source);return s.executeUpdate();}
    }
    private static void putFormat(Connection connection,Key key,String fingerprint,int format)throws SQLException {
        try(var s=connection.prepareStatement("INSERT OR IGNORE INTO story_definition_formats VALUES(?,?,?,?)")){s.setString(1,key.storyId());s.setString(2,key.version());s.setString(3,fingerprint);s.setInt(4,format);s.executeUpdate();}
        try(var s=connection.prepareStatement("SELECT canonical_version FROM story_definition_formats WHERE story=? AND version=? AND fingerprint=?")){s.setString(1,key.storyId());s.setString(2,key.version());s.setString(3,fingerprint);try(var r=s.executeQuery()){if(!r.next()||r.getInt(1)!=format)throw new IllegalStateException("Definition canonical format collision: "+key);}}
    }
    private static void verifyUpgrade(Connection connection,Entry entry)throws SQLException {
        try(var s=connection.prepareStatement("SELECT from_canonical_version,to_canonical_version,to_fingerprint,json,algorithm FROM story_definition_upgrades WHERE story=? AND version=? AND from_fingerprint=?")) {
            s.setString(1,entry.key().storyId());s.setString(2,entry.key().version());s.setString(3,entry.fingerprint());try(var r=s.executeQuery()) {
                if(!r.next())throw new IllegalStateException("Missing V1 definition migration association: "+entry.key());
                if(r.getInt(1)!=DefinitionCanonicalCodec.V1||r.getInt(2)!=DefinitionCanonicalCodec.V2||!DefinitionCanonicalCodec.V1_TO_V2.equals(r.getString(5)))throw new IllegalStateException("Invalid definition migration protocol: "+entry.key());
                String payload=r.getString(4),fingerprint=r.getString(3);StoryDefinition upgraded=DefinitionCanonicalCodec.verifyAndDecode(DefinitionCanonicalCodec.V2,payload,fingerprint);
                if(!entry.definition().equals(upgraded)||!DefinitionCanonicalCodec.encodeV2(entry.definition()).equals(payload))throw new IllegalStateException("Invalid definition migration semantics: "+entry.key());
            }
        }
    }
    private static void noUpgrade(Connection connection,Entry entry)throws SQLException {
        try(var s=connection.prepareStatement("SELECT 1 FROM story_definition_upgrades WHERE story=? AND version=? AND from_fingerprint=?")){s.setString(1,entry.key().storyId());s.setString(2,entry.key().version());s.setString(3,entry.fingerprint());try(var r=s.executeQuery()){if(r.next())throw new IllegalStateException("V2 definition cannot be a V1 migration source: "+entry.key());}}
    }
    private static IllegalArgumentException conflict(Key key,List<Entry> variants) {
        List<String> examples=variants.stream().limit(5).map(entry->entry.fingerprint()+" ["+entry.provenance()+"]").toList();
        return new IllegalArgumentException("Conflicting archived story definition "+key+" has "+variants.size()+" variants: "+String.join(", ",examples)+(variants.size()>5?"; more variants retained":""));
    }
    private static void id(String value){if(value==null||!ID.matcher(value).matches())throw new IllegalArgumentException("Invalid definition version identity: "+value);}
    private static void provenance(String value){if(value==null||value.isBlank()||value.length()>512||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Definition provenance must be readable text of 1..512 characters");}
    private static void inTransaction(Connection connection)throws SQLException{if(connection==null||connection.getAutoCommit())throw new IllegalStateException("Definition catalog requires the enclosing Chronicle transaction");}
}
