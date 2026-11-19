package io.github.addxiaoyi.starchronicle.persistence;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.history.EventCausality;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.addxiaoyi.starchronicle.persistence.DefinitionSchemaUpgradeTest.*;

/** Literal released V2/V3/V4 schemas and actual old payloads, never a current schema relabeled
 * to simulate an upgrade. Tests preserve all old data and the schema-4 raw archive evidence. */
class CanonicalDefinitionSchemaUpgradeTest {
    @TempDir Path directory;
    private static final String V4_CATALOG="CREATE TABLE story_definitions(story TEXT NOT NULL,version TEXT NOT NULL,fingerprint TEXT NOT NULL CHECK(length(fingerprint)=64 AND fingerprint NOT GLOB '*[^0-9a-f]*'),json TEXT NOT NULL,provenance TEXT NOT NULL,first_seen TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),PRIMARY KEY(story,version,fingerprint))";
    private static final String V4_INDEX="CREATE INDEX instance_origin ON instances(story,scope,json_extract(json,'$.definition.version'),json_extract(json,'$.sourceEventId'))";
    private static final String FIRST_SEEN="2026-11-19T12:00:00.001Z";
    private DefinitionSchemaUpgradeTest old(){var old=new DefinitionSchemaUpgradeTest();old.directory=directory;return old;}
    private static Connection open(Path path)throws Exception {Class.forName("org.sqlite.JDBC");Connection c=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());try(var s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");}return c;}
    private static StoryDefinition national(StoryDefinition d,String version){return new StoryDefinition(d.id(),version,d.title(),d.scope(),d.triggers(),d.condition(),d.start(),d.cooldownSeconds(),d.nodes(),VoteUnit.NATION);}

    private void createLiteralSource(Path path,int version)throws Exception {
        var old=old();try(var c=open(path);var s=c.createStatement()) {
            c.setAutoCommit(false);for(String ddl:version==2?ChoiceSchemaUpgradeTest.V2_DDL:V3_DDL)s.execute(ddl);
            WorldEvent event=new WorldEvent("original:root","ROOT",TIME,Set.of(PLAYER),Set.of("river"),Map.of("original","真实旧证据"),"OldAuthority","Original operation=55; retain actors and source timestamp");
            try(var p=c.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?)")){p.setString(1,event.id());p.setString(2,event.type());p.setString(3,event.occurredAt().toString());p.setString(4,old.fingerprint(event));p.setString(5,old.pretty(event));p.executeUpdate();}
            s.execute("INSERT INTO event_nations VALUES('original:root','river')");s.execute("INSERT INTO event_players VALUES('original:root','"+PLAYER+"')");
            old.putInstance(c,old.active());old.putInstance(c,old.completed());
            try(var p=c.prepareStatement("INSERT INTO story_triggers VALUES(?,?,?,?,?,?)")){p.setString(1,"old:pending");p.setString(2,RuleEngine.stable(event.id(),old.pending().id(),old.pending().version()));p.setString(3,event.id());p.setString(4,"world");p.setString(5,old.pretty(old.pending()));p.setInt(6,17);p.executeUpdate();}
            s.execute("INSERT INTO story_trigger_receipts VALUES('original:receipt','world')");s.execute("INSERT INTO facts VALUES('WORLD','region.legacy-city','original memory')");
            for(String status:List.of("DONE","PENDING","UNKNOWN","RUNNING"))try(var p=c.prepareStatement("INSERT INTO outcomes VALUES(?,?,?)")){String id="original:"+status;Delivery d=new Delivery(id,old.completed().id(),"authority","diplomacy.invite","hill",PLAYER,"river","starcore.nation.ally.add");p.setString(1,id);p.setString(2,status);p.setString(3,old.pretty(d));p.executeUpdate();}
            if(version==3)ChoiceLedger.migrateSnapshots(c);
            s.execute("PRAGMA user_version="+version);c.commit();
        }
    }
    /** The released schema-4 transition, expressed with literal DDL and frozen V1 payloads.
     * Deliberately does not call the current store or create its V5 sidecar tables. */
    private void stageReleasedV4(Path path,int originalVersion)throws Exception {
        var old=old();try(var c=open(path);var s=c.createStatement()) {
            c.setAutoCommit(false);
            if(originalVersion==2){ChoiceLedger.createSchema(c);EventCausality.createSchema(c);ChoiceLedger.migrateSnapshots(c);}
            s.execute(V4_CATALOG);s.execute(V4_INDEX);
            putV1(c,old.bound(),"LEGACY_INSTANCE old:active");putV1(c,old.retired(),"LEGACY_INSTANCE old:completed");putV1(c,old.pending(),"PENDING_TRIGGER old:pending");
            s.execute("PRAGMA user_version=4");c.commit();
        }
    }
    private static void putV1(Connection c,StoryDefinition definition,String provenance)throws Exception {
        String payload=DefinitionCanonicalCodec.encodeV1(definition);putRaw(c,definition.id(),definition.version(),payload,DefinitionCanonicalCodec.sha256(payload),provenance,FIRST_SEEN);
    }
    private static void putRaw(Connection c,String story,String version,String payload,String fingerprint,String provenance,String seen)throws SQLException {
        try(var p=c.prepareStatement("INSERT INTO story_definitions VALUES(?,?,?,?,?,?)")){p.setString(1,story);p.setString(2,version);p.setString(3,fingerprint);p.setString(4,payload);p.setString(5,provenance);p.setString(6,seen);p.executeUpdate();}
    }
    private Path v4(String name)throws Exception {Path path=directory.resolve(name);createLiteralSource(path,3);stageReleasedV4(path,3);return path;}
    private static List<List<String>> archiveRows(Connection c)throws SQLException{return rows(c,"SELECT * FROM story_definitions ORDER BY story,version,fingerprint");}
    private static Map<String,List<List<String>>> canonicalState(Connection c)throws SQLException {
        Map<String,List<List<String>>> result=new TreeMap<>();result.put("original_identity",rows(c,"SELECT story,version,fingerprint,json,provenance FROM story_definitions ORDER BY story,version,fingerprint"));
        result.put("formats",rows(c,"SELECT * FROM story_definition_formats ORDER BY story,version,fingerprint"));result.put("upgrades",rows(c,"SELECT * FROM story_definition_upgrades ORDER BY story,version,from_fingerprint"));result.put("choices",rows(c,"SELECT * FROM story_choice_log ORDER BY seq"));return result;
    }
    private static boolean causeContains(Throwable error,String text){for(Throwable at=error;at!=null;at=at.getCause())if(Objects.toString(at.getMessage(),"").contains(text))return true;return false;}

    @Test void schema4UpgradePreservesEveryOriginalRowAndByteWhileRecordingExplicitCanonicalLinks()throws Exception {
        Path path=v4("literal-v4.db");List<List<String>> originalArchive;Map<String,List<List<String>>> before;Map<String,String> originalSchema;
        try(var c=open(path)){originalArchive=archiveRows(c);before=snapshot(c);originalSchema=schema(c);assertEquals("4",scalar(c,"PRAGMA user_version"));}
        Map<String,List<List<String>>> first;
        try(var store=new SqliteStore(path)) {
            first=store.transaction(c->{assertEquals("5",scalar(c,"PRAGMA user_version"));assertEquals(originalArchive,archiveRows(c));assertEquals(recovered(before),snapshot(c));assertPayloadBytes(before,snapshot(c));for(var entry:originalSchema.entrySet())assertEquals(entry.getValue(),schema(c).get(entry.getKey()));
                assertEquals("3",scalar(c,"SELECT COUNT(*) FROM story_definition_formats WHERE canonical_version=1"));assertEquals("3",scalar(c,"SELECT COUNT(*) FROM story_definition_upgrades WHERE from_canonical_version=1 AND to_canonical_version=2"));
                for(var entry:DefinitionCatalog.read(c).evidenceEntries()){assertEquals(1,entry.canonicalVersion());assertEquals(DefinitionCanonicalCodec.fingerprintV2(entry.definition()),entry.semanticFingerprint());assertEquals(FIRST_SEEN,entry.firstSeen());assertNotEquals(entry.fingerprint(),entry.semanticFingerprint());}
                var catalog=DefinitionCatalog.read(c);assertEquals(old().pending(),catalog.require("retired-pending","7"));assertThrows(IllegalArgumentException.class,()->catalog.require("retired-a","1"));assertTrue(rows(c,"PRAGMA foreign_key_check").isEmpty());assertEquals("ok",scalar(c,"PRAGMA integrity_check"));return canonicalState(c);});
        }
        try(var store=new SqliteStore(path)){store.transaction(c->{assertEquals(first,canonicalState(c));assertEquals(originalArchive,archiveRows(c));assertEquals(0,DefinitionCatalog.migrateCanonicalV2(c));assertEquals(first,canonicalState(c));return null;});}
    }

    @ParameterizedTest @ValueSource(ints={2,3}) void directLegacyUpgradeHasTheSameIdentityAndLinksAsTheReleasedSchema4Step(int sourceVersion)throws Exception {
        Path direct=directory.resolve("direct-"+sourceVersion+".db"),staged=directory.resolve("staged-"+sourceVersion+".db");createLiteralSource(direct,sourceVersion);createLiteralSource(staged,sourceVersion);stageReleasedV4(staged,sourceVersion);
        Map<String,List<List<String>>> first;
        try(var store=new SqliteStore(direct)){first=store.transaction(c->{assertEquals("5",scalar(c,"PRAGMA user_version"));return canonicalState(c);});}
        try(var store=new SqliteStore(staged)){store.transaction(c->{assertEquals(first,canonicalState(c),"Only original first_seen differs because the two real archive operations occur at different times");return null;});}
    }

    @Test void unchangedPlayerConfigurationReusesOriginalEvidenceButChangingVoteUnitRequiresANewStoryVersion()throws Exception {
        Path path=v4("reuse-v4.db");List<List<String>> rawBefore;try(var c=open(path)){rawBefore=archiveRows(c);}
        try(var store=new SqliteStore(path)){store.transaction(c->{var pending=old().pending();var catalog=DefinitionCatalog.read(c);catalog.validateCurrent(List.of(pending));assertEquals(0,DefinitionCatalog.archive(c,List.of(pending),"CURRENT_PLAYER"));assertEquals(rawBefore,archiveRows(c));assertThrows(IllegalArgumentException.class,()->catalog.validateCurrent(List.of(national(pending,"7"))));var v8=national(pending,"8");catalog.validateCurrent(List.of(v8));assertEquals(1,DefinitionCatalog.archive(c,List.of(v8),"NEW_NATIONAL_VERSION"));assertEquals(v8,DefinitionCatalog.read(c).require(pending.id(),"8"));assertEquals("2",scalar(c,"SELECT canonical_version FROM story_definition_formats WHERE story='retired-pending' AND version='8'"));assertEquals("3",scalar(c,"SELECT COUNT(*) FROM story_definition_upgrades"));return null;});}
    }

    @Test void rawHashFailureBeforeDecodeRollsBackAllNewObjectsLinksAndExistingRunningIsolation()throws Exception {
        Path path=v4("bad-raw-v4.db");Map<String,String> oldSchema;List<List<String>> damaged;
        try(var c=open(path);var s=c.createStatement()){s.execute("UPDATE story_definitions SET json='{broken' WHERE story='retired-pending'");oldSchema=schema(c);damaged=archiveRows(c);}
        var error=assertThrows(IllegalStateException.class,()->{try(var ignored=new SqliteStore(path)){fail("A changed raw payload must fail before decoding");}});assertTrue(causeContains(error,"raw payload fingerprint"));
        try(var c=open(path)){assertEquals("4",scalar(c,"PRAGMA user_version"));assertEquals(oldSchema,schema(c));assertEquals(damaged,archiveRows(c));assertEquals("RUNNING",scalar(c,"SELECT status FROM outcomes WHERE id='original:RUNNING'"));assertEquals("0",scalar(c,"SELECT COUNT(*) FROM sqlite_master WHERE name IN ('story_definition_formats','story_definition_upgrades')"));}
    }

    @Test void aHashValidButUnknownV1FieldIsNotDefaultedAway()throws Exception {
        Path path=v4("unknown-v1-v4.db");var d=old().definition("z.future","1",null);String future=DefinitionCanonicalCodec.encodeV2(d);
        try(var c=open(path)){putRaw(c,d.id(),d.version(),future,DefinitionCanonicalCodec.sha256(future),"ACTUAL_V4_PAYLOAD",FIRST_SEEN);}
        var error=assertThrows(IllegalStateException.class,()->{try(var ignored=new SqliteStore(path)){fail("V1 must not interpret V2 fields");}});assertTrue(causeContains(error,"Unknown definition field: voteUnit"));
        try(var c=open(path)){assertEquals("4",scalar(c,"PRAGMA user_version"));assertEquals("4",scalar(c,"SELECT COUNT(*) FROM story_definitions"));assertEquals("0",scalar(c,"SELECT COUNT(*) FROM sqlite_master WHERE name='story_definition_formats'"));}
    }

    @Test void selfConsistentButWrongUpgradePayloadCannotReplaceOriginalPlayerSemantics()throws Exception {
        Path path=v4("bad-association.db");try(var store=new SqliteStore(path)){store.transaction(c->{var changed=national(old().pending(),"7");String payload=DefinitionCanonicalCodec.encodeV2(changed);try(var s=c.prepareStatement("UPDATE story_definition_upgrades SET json=?,to_fingerprint=? WHERE story='retired-pending'")){s.setString(1,payload);s.setString(2,DefinitionCanonicalCodec.sha256(payload));s.executeUpdate();}assertThrows(IllegalStateException.class,()->DefinitionCatalog.read(c));return null;});}
    }
    @Test void validRawBytesDoNotExcuseForgedArchiveIdentityMetadata()throws Exception {
        Path path=v4("forged-identity.db");try(var c=open(path);var s=c.createStatement()){s.execute("UPDATE story_definitions SET story='forged-pending' WHERE story='retired-pending'");}
        var error=assertThrows(IllegalStateException.class,()->{try(var ignored=new SqliteStore(path)){fail("Metadata must match the verified original definition");}});assertTrue(causeContains(error,"Corrupt archived definition metadata"));
        try(var c=open(path)){assertEquals("4",scalar(c,"PRAGMA user_version"));assertEquals("1",scalar(c,"SELECT COUNT(*) FROM story_definitions WHERE story='forged-pending'"));assertEquals("0",scalar(c,"SELECT COUNT(*) FROM sqlite_master WHERE name='story_definition_formats'"));}
    }

    @Test void missingProtocolMetadataOrAssociationFailsClosedInsteadOfGuessingFromCurrentDto()throws Exception {
        Path first=v4("missing-format.db");try(var store=new SqliteStore(first)){store.transaction(c->{try(var s=c.createStatement()){s.execute("DELETE FROM story_definition_formats WHERE story='retired-pending'");}assertThrows(IllegalStateException.class,()->DefinitionCatalog.read(c));return null;});}
        Path second=v4("missing-link.db");try(var store=new SqliteStore(second)){store.transaction(c->{try(var s=c.createStatement()){s.execute("DELETE FROM story_definition_upgrades WHERE story='retired-pending'");}assertThrows(IllegalStateException.class,()->DefinitionCatalog.read(c));return null;});}
    }

    @Test void equivalentProtocolRepresentationsRemainTwoEvidenceRowsAndOneLogicalVariant()throws Exception {
        Path path=v4("equivalent-protocols.db");try(var store=new SqliteStore(path)){store.transaction(c->{var d=old().pending();String payload=DefinitionCanonicalCodec.encodeV2(d),fingerprint=DefinitionCanonicalCodec.sha256(payload);putRaw(c,d.id(),d.version(),payload,fingerprint,"REDUNDANT_REAL_V2_PAYLOAD","2026-11-20T00:00:00Z");try(var s=c.prepareStatement("INSERT INTO story_definition_formats VALUES(?,?,?,2)")){s.setString(1,d.id());s.setString(2,d.version());s.setString(3,fingerprint);s.executeUpdate();}var catalog=DefinitionCatalog.read(c);assertEquals(4,catalog.evidenceEntries().size());assertEquals(1,catalog.entries().get(new DefinitionCatalog.Key(d.id(),d.version())).size());assertEquals(d,catalog.require(d.id(),d.version()));assertEquals(0,DefinitionCatalog.archive(c,List.of(d),"DUPLICATE"));assertThrows(UnsupportedOperationException.class,()->catalog.evidenceEntries().clear());assertThrows(IllegalArgumentException.class,()->catalog.require("retired-a","1"));return null;});}
    }

    @Test void sourceArchiveUtf8BytesAndMetadataAreNotReplacedByNormalizedMigrationJson()throws Exception {
        Path path=v4("utf8-original.db");List<List<String>> before;try(var c=open(path)){before=archiveRows(c);}
        try(var store=new SqliteStore(path)){store.transaction(c->{List<List<String>> after=archiveRows(c);assertEquals(before,after);for(int row=0;row<before.size();row++)for(int col=0;col<before.get(row).size();col++)assertArrayEquals(before.get(row).get(col).getBytes(StandardCharsets.UTF_8),after.get(row).get(col).getBytes(StandardCharsets.UTF_8));assertTrue(rows(c,"SELECT json FROM story_definition_upgrades").stream().allMatch(r->r.getFirst().contains("\"voteUnit\":\"PLAYER\"")));assertTrue(after.stream().noneMatch(r->r.get(3).contains("voteUnit")));return null;});}
    }
}
