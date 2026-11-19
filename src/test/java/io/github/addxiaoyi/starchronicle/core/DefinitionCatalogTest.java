package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.config.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DefinitionCatalogTest {
    @TempDir Path directory;
    StoryDefinition definition(String id,String version,String text,String script,String quest) {
        var choices=new LinkedHashMap<String,Choice>();choices.put("yes",new Choice("Yes","end",null,null,List.of()));choices.put("no",new Choice("No","end",null,null,List.of()));
        var nodes=new LinkedHashMap<String,Node>();nodes.put("vote",new Node(text,script,quest,1,choices,List.of(),false));nodes.put("end",new Node("Done",null,null,1,Map.of(),List.of(),true));
        return new StoryDefinition(id,version,id,Scope.WORLD,List.of("ROOT"),null,"vote",0,nodes);
    }
    StoryDefinition plain(){return definition("archive.story","1.2","Original",null,null);}
    SqliteStore open(){return new SqliteStore(directory.resolve("catalog.db"));}
    long count(SqliteStore store){return store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM story_definitions")){r.next();return r.getLong(1);}});}

    @Test void emptyCatalogDoesNotFabricateAnyVersionAndValidatesOnlyUniqueCurrentIds() {
        var empty=DefinitionCatalog.empty();assertTrue(empty.keys().isEmpty());assertTrue(empty.entries().isEmpty());
        assertThrows(IllegalArgumentException.class,()->empty.require("archive.story","1.2"));
        assertDoesNotThrow(()->empty.validateCurrent(List.of(plain())));
        assertThrows(IllegalArgumentException.class,()->empty.validateCurrent(List.of(plain(),definition("archive.story","2","New",null,null))));
    }
    @Test void archiveIsIdempotentAndPreservesTheFirstReadableProvenanceAndArchiveTimeAcrossRestart() {
        DefinitionCatalog.Entry first;
        try(var store=open()) {
            assertEquals(1,store.transaction(c->DefinitionCatalog.archive(c,List.of(plain()),"ACCEPTED_CONFIGURATION")).intValue());
            first=store.transaction(DefinitionCatalog::read).entries().values().iterator().next().getFirst();
            assertEquals("ACCEPTED_CONFIGURATION",first.provenance());assertNotNull(first.firstSeen());
            assertEquals(0,store.transaction(c->DefinitionCatalog.archive(c,List.of(plain()),"LATER_DUPLICATE_SOURCE")).intValue());
            assertEquals(first,store.transaction(DefinitionCatalog::read).entries().get(first.key()).getFirst());
        }
        try(var store=open()) {assertEquals(1,count(store));assertEquals(first,store.transaction(DefinitionCatalog::read).entries().get(first.key()).getFirst());assertEquals(plain(),store.transaction(DefinitionCatalog::read).require("archive.story","1.2"));}
    }
    @Test void canonicalIdentityIgnoresMapAndJsonFormattingButRetainsEveryDefinitionField() {
        try(var store=open()) {
            var original=plain();var reversedNodes=new LinkedHashMap<String,Node>();reversedNodes.put("end",original.nodes().get("end"));
            var vote=original.nodes().get("vote");var reversedChoices=new LinkedHashMap<String,Choice>();reversedChoices.put("no",vote.choices().get("no"));reversedChoices.put("yes",vote.choices().get("yes"));
            reversedNodes.put("vote",new Node(vote.text(),vote.script(),vote.quest(),vote.quorum(),reversedChoices,vote.effects(),vote.terminal()));
            var reversed=new StoryDefinition(original.id(),original.version(),original.title(),original.scope(),original.triggers(),original.condition(),original.start(),original.cooldownSeconds(),reversedNodes);
            store.transaction(c->{assertEquals(1,DefinitionCatalog.archive(c,List.of(original),"FIRST"));assertEquals(0,DefinitionCatalog.archive(c,List.of(reversed),"REORDERED"));return null;});
            store.transaction(c->{String pretty=SqliteStore.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(original)+"\n";try(var s=c.prepareStatement("UPDATE story_definitions SET json=?")){s.setString(1,pretty);s.executeUpdate();}assertEquals(0,DefinitionCatalog.archive(c,List.of(reversed),"PRETTY_DUPLICATE"));return null;});
            assertEquals(original,store.transaction(DefinitionCatalog::read).require(original.id(),original.version()));assertEquals(1,count(store));
            assertThrows(IllegalArgumentException.class,()->store.transaction(DefinitionCatalog::read).validateCurrent(List.of(definition(original.id(),original.version(),"Changed text",null,null))));
        }
    }
    @Test void exactVersionsDoNotFallBackToNewestAndDifferentStoriesRemainIsolated() {
        try(var store=open()) {
            var second=definition("archive.story","2","Second",null,null);var other=definition("other","1","Other",null,null);
            store.transaction(c->{DefinitionCatalog.archive(c,List.of(plain(),second,other),"ACTUAL_VERSIONS");return null;});var catalog=store.transaction(DefinitionCatalog::read);
            assertEquals(plain(),catalog.require("archive.story","1.2"));assertEquals(second,catalog.require("archive.story","2"));assertEquals(other,catalog.require("other","1"));
            assertThrows(IllegalArgumentException.class,()->catalog.require("archive.story","3"));assertEquals(3,catalog.keys().size());
        }
    }
    @Test void realSameVersionConflictsAreRetainedAndOnlyTheirExactKeyIsUnresolvable() {
        try(var store=open()) {
            var changed=definition("archive.story","1.2","Different actual graph",null,null);var unrelated=definition("healthy","1","Healthy",null,null);
            store.transaction(c->{DefinitionCatalog.archive(c,List.of(plain()),"LEGACY_INSTANCE old-one");DefinitionCatalog.archive(c,List.of(changed,unrelated),"PENDING_TRIGGER old-two");return null;});
            var catalog=store.transaction(DefinitionCatalog::read);assertEquals(3,count(store));assertEquals(2,catalog.entries().get(new DefinitionCatalog.Key("archive.story","1.2")).size());
            var failure=assertThrows(IllegalArgumentException.class,()->catalog.require("archive.story","1.2"));assertTrue(failure.getMessage().contains("LEGACY_INSTANCE old-one"));assertTrue(failure.getMessage().contains("PENDING_TRIGGER old-two"));
            assertThrows(IllegalArgumentException.class,()->catalog.validateCurrent(List.of(plain())));assertThrows(IllegalArgumentException.class,()->catalog.validateCurrent(List.of(changed)));
            assertEquals(unrelated,catalog.require("healthy","1"));assertDoesNotThrow(()->catalog.validateCurrent(List.of(unrelated)));
            assertDoesNotThrow(()->catalog.validateCurrent(List.of(definition("archive.story","3","New explicit version",null,null))));
        }
    }
    @Test void automaticNativeBindingDifferencesRemainExplicitSameVersionConflicts() {
        try(var store=open()) {
            var bound=definition("archive.story","1.2","Original","chronicle_last_heir",null);
            store.transaction(c->{DefinitionCatalog.archive(c,List.of(plain()),"STANDALONE_EFFECTIVE");return null;});
            var catalog=store.transaction(DefinitionCatalog::read);assertThrows(IllegalArgumentException.class,()->catalog.validateCurrent(List.of(bound)));
            assertEquals(plain(),catalog.require("archive.story","1.2"));
            store.transaction(c->{assertEquals(1,DefinitionCatalog.archive(c,List.of(bound),"LEGACY_PAPER_EFFECTIVE"));return null;});
            assertThrows(IllegalArgumentException.class,()->store.transaction(DefinitionCatalog::read).require("archive.story","1.2"));
        }
    }
    @Test void retiredDefinitionMayKeepUnavailableQuestScriptAndItsOwnRetiredReferences() {
        try(var store=open()) {
            var template=definition("retired","7","Retired","removed-native-scene","removed-native-quest");
            var retired=new StoryDefinition(template.id(),template.version(),template.title(),template.scope(),template.triggers(),new Condition(null,null,null,"past.player.choice.long-gone/1/vote","eq","yes"),template.start(),template.cooldownSeconds(),template.nodes());
            store.transaction(c->{DefinitionCatalog.archive(c,List.of(retired),"REAL_PINNED_SNAPSHOT");return null;});
            assertEquals(retired,store.transaction(DefinitionCatalog::read).require("retired","7"));assertEquals(1,count(store));
        }
    }
    @Test void catalogAndRuntimeAreImmutableSnapshotsRatherThanLiveDatabaseOrMutableListViews() {
        try(var store=open()) {
            var candidates=new ArrayList<>(List.of(plain()));store.transaction(c->{DefinitionCatalog.archive(c,candidates,"ACCEPTED");return null;});var catalog=store.transaction(DefinitionCatalog::read);var runtime=new StoryRuntime(candidates,catalog);candidates.clear();
            assertEquals(List.of(plain()),runtime.enabledDefinitions());assertThrows(UnsupportedOperationException.class,()->runtime.enabledDefinitions().clear());assertThrows(UnsupportedOperationException.class,()->catalog.keys().clear());assertThrows(UnsupportedOperationException.class,()->catalog.entries().clear());assertThrows(UnsupportedOperationException.class,()->catalog.entries().values().iterator().next().clear());
            store.transaction(c->{DefinitionCatalog.archive(c,List.of(definition("later","1","Later",null,null)),"LATER");return null;});assertEquals(1,catalog.keys().size());assertEquals(2,store.transaction(DefinitionCatalog::read).keys().size());
        }
    }
    @Test void provenanceMustBeReadableAndFailedArchiveRollsBackAllVariantsTogether() {
        try(var store=open()) {
            for(String invalid:List.of(""," ","line\nbreak","x".repeat(513)))assertThrows(IllegalArgumentException.class,()->store.transaction(c->DefinitionCatalog.archive(c,List.of(plain()),invalid)));
            assertEquals(0,count(store));
            assertThrows(IllegalStateException.class,()->store.transaction(c->{DefinitionCatalog.archive(c,List.of(plain()),"FIRST_UNCOMMITTED");throw new IllegalStateException("later transaction failed");}));assertEquals(0,count(store));
        }
    }
    @Test void readRejectsTamperedMetadataOrContentInsteadOfTreatingItAsAnotherTrustworthyVariant() {
        try(var store=open()) {
            store.transaction(c->{DefinitionCatalog.archive(c,List.of(plain()),"ACTUAL");try(var s=c.createStatement()){s.execute("UPDATE story_definitions SET story='forged'");}return null;});
            assertThrows(IllegalStateException.class,()->store.transaction(DefinitionCatalog::read));
        }
    }
    @Test void noEventFactOrChoiceShapedTextCanManufactureAnArchivedDefinition() {
        try(var store=open()) {
            store.transaction(c->{try(var s=c.createStatement()){s.execute("INSERT INTO facts VALUES('WORLD','past.player.choice.fake/1/vote','yes')");}assertEquals(0,DefinitionCatalog.migrateSnapshots(c));return null;});
            assertTrue(store.transaction(DefinitionCatalog::read).keys().isEmpty());assertThrows(IllegalArgumentException.class,()->store.transaction(DefinitionCatalog::read).require("fake","1"));
        }
    }
    @Test void databaseMethodsRequireAnExplicitEnclosingTransaction()throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("raw.db"))) {
            assertThrows(IllegalStateException.class,()->DefinitionCatalog.createSchema(connection));assertThrows(IllegalStateException.class,()->DefinitionCatalog.read(connection));assertThrows(IllegalStateException.class,()->DefinitionCatalog.migrateSnapshots(connection));assertThrows(IllegalStateException.class,()->DefinitionCatalog.archive(connection,List.of(plain()),"SOURCE"));
        }
    }
}
