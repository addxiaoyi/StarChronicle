package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DefinitionCatalogCapacityTest {
    @TempDir Path directory;
    static final Instant TIME=Instant.parse("2026-11-26T00:00:00Z");
    static final Clock CLOCK=Clock.fixed(TIME,ZoneOffset.UTC);
    static StoryDefinition story(String id,String version,Condition condition,Condition choiceCondition) {
        return new StoryDefinition(id,version,id,Scope.PLAYER,List.of(id.toUpperCase(Locale.ROOT)),condition,"vote",0,
                Map.of("vote",new Node("Vote",null,null,1,Map.of("yes",new Choice("Yes","end",choiceCondition,null,List.of())),List.of(),false),
                        "end",new Node("Done",null,null,1,Map.of(),List.of(),true)));
    }
    static Condition past(String id,String version){return new Condition(null,null,null,"past.player.choice."+id+"/"+version+"/vote","eq","yes");}
    static Condition not(Condition c){return new Condition(null,null,c,null,null,null);}
    static Condition any(Condition... c){return new Condition(null,List.of(c),null,null,null,null);}

    @Test void selectedDirectoryRetainsAllDirectBranchesAndCurrentIdentitiesButNotRetiredDependencies() {
        var retired=story("retired","1",past("long-gone","1"),null);
        var oldVersion=story("current","1",null,null);
        var optional=story("optional","3",null,null);
        var current=story("current","2",any(past("retired","1"),not(past("optional","3"))),past("current","1"));
        var unrelated=story("unrelated","1",null,null);
        try(var store=new SqliteStore(directory.resolve("selection.db"))) {
            store.transaction(c->{DefinitionCatalog.archive(c,List.of(retired,oldVersion,optional,unrelated,current),"ACCEPTED");return null;});
            var selected=store.transaction(c->DefinitionCatalog.readReferenced(c,List.of(current)));
            assertEquals(Set.of(new DefinitionCatalog.Key("retired","1"),new DefinitionCatalog.Key("optional","3"),
                    new DefinitionCatalog.Key("current","1"),new DefinitionCatalog.Key("current","2")),selected.keys());
            assertEquals(retired,selected.require("retired","1"));assertDoesNotThrow(()->ConfigLoader.validateAll(List.of(current),selected));
            assertThrows(IllegalArgumentException.class,()->selected.require("unrelated","1"));
            assertEquals(5,store.transaction(DefinitionCatalog::read).keys().size());
            assertThrows(UnsupportedOperationException.class,()->selected.entries().clear());
            store.transaction(c->{DefinitionCatalog.archive(c,List.of(story("later","1",null,null)),"LATER");return null;});
            assertEquals(4,selected.keys().size());
        }
    }

    @Test void unreferencedCorruptionStillRejectsValidationArchiveAndEmptyRuntimeReload() {
        for(String corruption:List.of("raw","format","unexpected-upgrade"))try(var store=new SqliteStore(directory.resolve(corruption+".db"))) {
            var enabled=story("enabled","1",null,null);var hidden=story("hidden","1",null,null);
            var engine=new RuleEngine(store,List.of(enabled),CLOCK);
            store.transaction(c->{DefinitionCatalog.archive(c,List.of(hidden),"RETIRED");try(var s=c.createStatement()) {
                switch(corruption) {
                    case "raw"->s.execute("UPDATE story_definitions SET json=json||char(10) WHERE story='hidden'");
                    case "format"->s.execute("DELETE FROM story_definition_formats WHERE story='hidden'");
                    case "unexpected-upgrade"->s.execute("INSERT INTO story_definition_upgrades SELECT story,version,fingerprint,1,2,fingerprint,json,'V1_TO_V2_ADD_PLAYER_VOTE_UNIT' FROM story_definitions WHERE story='hidden'");
                }
            }return null;});
            assertThrows(IllegalStateException.class,()->engine.validate(List.of(enabled)));
            assertThrows(IllegalStateException.class,()->engine.reload(List.of()));
            assertThrows(IllegalStateException.class,()->store.transaction(c->DefinitionCatalog.archive(c,List.of(story("other","1",null,null)),"NEW")));
            assertThrows(IllegalStateException.class,()->store.transaction(c->{DefinitionCatalog.verify(c);return null;}));
        }
    }

    @Test void selectedVersionKeepsAmbiguityWhileUnrelatedConflictsRemainArchived() {
        var first=story("legacy","1",null,null);var changed=new StoryDefinition(first.id(),first.version(),"Changed",first.scope(),first.triggers(),null,first.start(),0,first.nodes());
        var consumer=story("consumer","1",past("legacy","1"),null);
        try(var store=new SqliteStore(directory.resolve("conflict.db"))) {
            store.transaction(c->{DefinitionCatalog.archive(c,List.of(first,changed),"REAL_VARIANTS");return null;});
            var selected=store.transaction(c->DefinitionCatalog.readReferenced(c,List.of(consumer)));
            assertEquals(2,selected.entries().get(new DefinitionCatalog.Key("legacy","1")).size());
            assertThrows(IllegalArgumentException.class,()->ConfigLoader.validateAll(List.of(consumer),selected));
            var engine=new RuleEngine(store,List.of(story("unrelated","1",null,null)),CLOCK);
            assertThrows(IllegalArgumentException.class,()->engine.reload(List.of(consumer)));
            assertEquals(2,store.transaction(DefinitionCatalog::read).entries().get(new DefinitionCatalog.Key("legacy","1")).size());
        }
    }

    @Test void equivalentV1AndV2EvidenceIsReusedAndTamperedUnreferencedAssociationStillFails() {
        var old=story("legacy","1",null,null);var consumer=story("consumer","1",past("legacy","1"),null);
        try(var store=new SqliteStore(directory.resolve("protocol.db"))) {
            store.transaction(c->{insertV1(c,old);DefinitionCatalog.migrateCanonicalV2(c);return null;});
            var selected=store.transaction(c->DefinitionCatalog.readReferenced(c,List.of(consumer)));
            assertEquals(1,selected.evidenceEntries().size());assertEquals(1,selected.evidenceEntries().getFirst().canonicalVersion());
            assertEquals(old,selected.require("legacy","1"));
            assertEquals(0,store.transaction(c->DefinitionCatalog.archive(c,List.of(old),"UNCHANGED")).intValue());
            store.transaction(c->{try(var s=c.createStatement()){s.execute("UPDATE story_definition_upgrades SET to_fingerprint='"+"0".repeat(64)+"'");}return null;});
            assertThrows(IllegalStateException.class,()->store.transaction(c->DefinitionCatalog.readReferenced(c,List.of())));
        }
    }

    @Test void archiveSideEffectCorruptionRollsBackBeforePublishingANewRuntime() {
        var enabled=story("enabled","1",null,null);var candidate=story("candidate","1",null,null);
        try(var store=new SqliteStore(directory.resolve("post-write.db"))) {
            var engine=new RuleEngine(store,List.of(enabled),CLOCK);
            store.transaction(c->{DefinitionCatalog.archive(c,List.of(story("hidden","1",null,null)),"RETIRED");try(var s=c.createStatement()) {
                s.execute("CREATE TRIGGER corrupt_hidden AFTER INSERT ON story_definitions WHEN NEW.story='candidate' BEGIN UPDATE story_definitions SET json=json||char(10) WHERE story='hidden'; END");
            }return null;});
            assertThrows(IllegalStateException.class,()->engine.reload(List.of(candidate)));
            assertEquals(2,store.transaction(DefinitionCatalog::read).keys().size());
            var actor=UUID.randomUUID();var context=new PlayerContext(actor,"",Map.of(),Set.of());
            assertEquals(1,engine.accept(new WorldEvent("still-old","ENABLED",TIME,Set.of(actor),Set.of(),Map.of(),"test","committed"),List.of(context)).instanceIds().size());
            assertTrue(engine.accept(new WorldEvent("not-new","CANDIDATE",TIME,Set.of(actor),Set.of(),Map.of(),"test","committed"),List.of(context)).instanceIds().isEmpty());
        }
    }

    @Test void missingHistoricalEndpointStillRejectsReloadAndValidateWithoutArchivingCandidate() {
        var current=story("consumer","1",past("missing","1"),null);
        try(var store=new SqliteStore(directory.resolve("missing.db"))) {
            var engine=new RuleEngine(store,List.of(),CLOCK);
            assertThrows(IllegalArgumentException.class,()->engine.validate(List.of(current)));
            assertThrows(IllegalArgumentException.class,()->engine.reload(List.of(current)));
            assertTrue(store.transaction(DefinitionCatalog::read).keys().isEmpty());
        }
    }

    static void insertV1(Connection c,StoryDefinition story)throws Exception {
        String raw=DefinitionCanonicalCodec.encodeV1(story),hash=DefinitionCanonicalCodec.sha256(raw);
        try(var s=c.prepareStatement("INSERT INTO story_definitions(story,version,fingerprint,json,provenance) VALUES(?,?,?,?,?)")) {
            s.setString(1,story.id());s.setString(2,story.version());s.setString(3,hash);s.setString(4,raw);s.setString(5,"V1_EVIDENCE");s.executeUpdate();
        }
    }
}
