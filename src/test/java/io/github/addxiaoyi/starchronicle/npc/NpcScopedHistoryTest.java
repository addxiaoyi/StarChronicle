package io.github.addxiaoyi.starchronicle.npc;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.config.Condition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NpcScopedHistoryTest {
    @TempDir Path temp;
    private static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C=UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant TIME=Instant.parse("2026-11-20T00:00:00Z");

    @Test void dialogueUsesCurrentNationAndCanonicalPlayerCountsRatherThanForgedFacts() {
        Condition witnessed=all(eq("history.AWARD","2"),eq("chronicle.history.world.AWARD","2"),eq("chronicle.history.nation.AWARD","1"),eq("chronicle.history.player.AWARD","1"));
        var catalog=catalog(List.of(new NpcCatalog.Dialogue("witness",100,witnessed,"Recorded witness"),new NpcCatalog.Dialogue("not-personal",50,eq("chronicle.history.player.AWARD","0"),"Not your event"),fallback()),List.of());
        try(var store=new SqliteStore(temp.resolve("view.db"))) {
            seed(store);var ledger=new NpcLedger(store,catalog);
            store.transaction(c->{fact(c,"WORLD","history.AWARD","999");fact(c,"WORLD","chronicle.history.nation.AWARD","999");return null;});
            Map<String,String> forged=Map.of("history.AWARD","999","chronicle.history.world.AWARD","999","chronicle.history.nation.AWARD","999","chronicle.history.player.AWARD","999");
            var a=ledger.view(A,"guide",new PlayerContext(A,"north",forged,Set.of()),forged);
            assertEquals("witness",a.dialogueId());assertEquals("2",a.facts().get("history.AWARD"));assertEquals("1",a.facts().get("chronicle.history.nation.AWARD"));assertEquals("1",a.facts().get("chronicle.history.player.AWARD"));
            var b=ledger.view(B,"guide",new PlayerContext(B,"south",forged,Set.of()),forged);
            assertEquals("not-personal",b.dialogueId());assertEquals("1",b.facts().get("chronicle.history.nation.AWARD"));assertEquals("0",b.facts().get("chronicle.history.player.AWARD"));
            var unknown=ledger.view(A,"guide",new PlayerContext(A,"",forged,Set.of()),forged);
            assertEquals("fallback",unknown.dialogueId());assertFalse(unknown.facts().containsKey("chronicle.history.nation.AWARD"));
        }
    }

    @Test void dialogueWithoutHistoryPredicatesStillRendersUnconditionalHistoryPlaceholders() {
        var catalog=catalog(List.of(new NpcCatalog.Dialogue("display",10,null,"World={history.AWARD}; player={chronicle.history.player.AWARD}")),List.of());
        try(var store=new SqliteStore(temp.resolve("template-history.db"))) {
            seed(store);var ledger=new NpcLedger(store,catalog);
            var view=ledger.view(A,"guide",context(A,"north"),Map.of());
            assertEquals("World=2; player=1",view.dialogue());
            assertEquals("1",view.facts().get("chronicle.history.nation.AWARD"));
        }
    }

    @Test void requestedZeroCountDialogueDistinguishesKnownNationFromUnknownNation() {
        var catalog=catalog(List.of(new NpcCatalog.Dialogue("national-zero",100,eq("chronicle.history.nation.MISSING","0"),"Known national zero"),new NpcCatalog.Dialogue("personal-zero",50,eq("chronicle.history.player.MISSING","0"),"Known personal zero"),fallback()),List.of());
        try(var store=new SqliteStore(temp.resolve("zero.db"))) {
            var ledger=new NpcLedger(store,catalog);
            assertEquals("national-zero",ledger.view(A,"guide",context(A,"north"),Map.of()).dialogueId());
            var unknown=ledger.view(A,"guide",context(A,""),Map.of());
            assertEquals("personal-zero",unknown.dialogueId());assertFalse(unknown.facts().containsKey("chronicle.history.nation.MISSING"));
            assertEquals("0",unknown.facts().get("chronicle.history.player.MISSING"));assertFalse(unknown.facts().containsKey("history.MISSING"));
        }
    }

    @Test void reactionMatchesOnlyAnEvidencedActorWithPersonalHistoryAndDeduplicates() {
        var catalog=catalog(List.of(fallback()),List.of(new NpcCatalog.Reaction("personal",List.of("TRIGGER"),eq("chronicle.history.player.AWARD","1"),5,0,0,3,null)));
        try(var store=new SqliteStore(temp.resolve("reaction.db"))) {
            seed(store);var ledger=new NpcLedger(store,catalog);var trigger=event("trigger","TRIGGER",Set.of(A,B),Set.of("north","south"));
            store.transaction(c->{insert(c,trigger);ledger.applyEvent(c,trigger);ledger.applyEvent(c,trigger);return null;});
            assertEquals(5,ledger.view(A,"guide",context(A,"north"),Map.of()).reputation());
            assertEquals(0,ledger.view(B,"guide",context(B,"south"),Map.of()).reputation());
            assertEquals(3,ledger.view(C,"guide",context(C,"south"),Map.of()).growth());
        }
    }

    @Test void reactionNeverInventsCurrentNationalityFromItsEventNations() {
        var catalog=catalog(List.of(fallback()),List.of(new NpcCatalog.Reaction("national",List.of("TRIGGER"),eq("chronicle.history.nation.AWARD","1"),5,0,0,3,null)));
        try(var store=new SqliteStore(temp.resolve("nation.db"))) {
            seed(store);var ledger=new NpcLedger(store,catalog);
            var trigger=event("trigger","TRIGGER",Set.of(A,B),Set.of("north","south"));
            store.transaction(c->{insert(c,trigger);ledger.applyEvent(c,trigger);return null;});
            assertEquals(0,ledger.view(A,"guide",context(A,"north"),Map.of()).reputation());assertEquals(0,ledger.view(B,"guide",context(B,"south"),Map.of()).reputation());
            assertEquals(0,ledger.view(A,"guide",context(A,"north"),Map.of()).growth());
        }
    }

    @Test void negatedPersonalHistoryAndPastFactsCannotPromoteNpcWithoutAnActor() {
        var catalog=catalog(List.of(fallback()),List.of(
                new NpcCatalog.Reaction("personal-history",List.of("TRIGGER"),notExists("chronicle.history.player.AWARD"),0,0,0,10,null),
                new NpcCatalog.Reaction("personal-choice",List.of("TRIGGER"),notExists("past.player.choice.chapter-a/1/vote"),0,0,0,20,null)));
        try(var store=new SqliteStore(temp.resolve("anonymous.db"))) {
            var ledger=new NpcLedger(store,catalog);var trigger=event("anonymous","TRIGGER",Set.of(),Set.of("north"));
            store.transaction(c->{insert(c,trigger);ledger.applyEvent(c,trigger);return null;});
            assertEquals(0,ledger.view(A,"guide",context(A,"north"),Map.of()).growth());
        }
    }

    @Test void legacyHistoryPlayerNamedTypeStillHasWorldReactionSemantics() {
        var catalog=catalog(List.of(fallback()),List.of(new NpcCatalog.Reaction("legacy",List.of("TRIGGER"),eq("history.player.AWARD","1"),0,0,0,2,null)));
        try(var store=new SqliteStore(temp.resolve("legacy.db"))) {
            var ledger=new NpcLedger(store,catalog);
            store.transaction(c->{insert(c,event("legacy-history","player.AWARD",Set.of(A),Set.of("north")));var trigger=event("anonymous","TRIGGER",Set.of(),Set.of());insert(c,trigger);ledger.applyEvent(c,trigger);return null;});
            assertEquals(2,ledger.view(A,"guide",context(A,"north"),Map.of()).growth());
        }
    }

    @Test void completedPersonalChoiceDrivesDialogueAndReactionWithoutCrossPlayerSpoofing() {
        String past="past.player.choice.chapter-a/1/vote";
        var catalog=catalog(List.of(new NpcCatalog.Dialogue("remembered",100,eq(past,"yes"),"Your recorded decision={"+past+"}; unknown={missing.choice}"),fallback()),List.of(new NpcCatalog.Reaction("choice",List.of("RECHECK"),eq(past,"yes"),7,0,0,2,null)));
        Path file=temp.resolve("choice.db");
        try(var store=new SqliteStore(file)) {
            var ledger=new NpcLedger(store,catalog);var engine=new RuleEngine(store,List.of(chapter()),Clock.fixed(TIME,ZoneOffset.UTC),ledger);
            var accepted=engine.accept(event("chapter-source","BEGIN",Set.of(A),Set.of("north")),List.of(context(A,"north")));
            engine.choose(accepted.instanceIds().getFirst(),A,"vote","yes",context(A,"north"));
            store.transaction(c->{fact(c,"WORLD",past,"yes");var trigger=event("recheck","RECHECK",Set.of(A,B),Set.of("north"));insert(c,trigger);ledger.applyEvent(c,trigger);return null;});
            assertEquals("remembered",ledger.view(A,"guide",context(A,"south"),Map.of()).dialogueId());
            assertEquals("Your recorded decision=yes; unknown={missing.choice}",ledger.view(A,"guide",context(A,"south"),Map.of()).dialogue());
            var other=ledger.view(B,"guide",new PlayerContext(B,"north",Map.of(past,"yes"),Set.of()),Map.of(past,"yes"));
            assertEquals("fallback",other.dialogueId());assertFalse(other.facts().containsKey(past));assertEquals(0,other.reputation());
            assertEquals(7,ledger.view(A,"guide",context(A,"north"),Map.of()).reputation());
        }
        try(var store=new SqliteStore(file)) {
            var ledger=new NpcLedger(store,catalog);
            assertEquals("yes",ledger.view(A,"guide",context(A,"south"),Map.of()).facts().get(past));
            assertFalse(ledger.view(B,"guide",context(B,"north"),Map.of()).facts().containsKey(past));
        }
    }

    private static StoryDefinition chapter() {
        var vote=new StoryDefinition.Node("Choose",null,null,1,Map.of("yes",new StoryDefinition.Choice("Yes","end",null,null,List.of()),"no",new StoryDefinition.Choice("No","end",null,null,List.of())),List.of(),false);
        return new StoryDefinition("chapter-a","1","Chapter",StoryDefinition.Scope.PLAYER,List.of("BEGIN"),null,"vote",0,Map.of("vote",vote,"end",new StoryDefinition.Node("End",null,null,1,Map.of(),List.of(),true)));
    }
    @Test void invalidReservedFactsAreRejectedBeforeTheyCanBlockAnEventTransaction() {
        for(String key:List.of("past.invalid.chapter/1/vote","past.player.choice.chapter/1","chronicle.history.typo.AWARD","chronicle.history.player.")) {
            assertThrows(IllegalArgumentException.class,()->catalog(List.of(new NpcCatalog.Dialogue("bad",1,eq(key,"yes"),"Bad")),List.of()));
            assertThrows(IllegalArgumentException.class,()->catalog(List.of(fallback()),List.of(new NpcCatalog.Reaction("bad",List.of("TRIGGER"),eq(key,"yes"),1,0,0,1,null))));
        }
        assertThrows(IllegalArgumentException.class,()->catalog(List.of(new NpcCatalog.Dialogue("bad",1,new Condition(null,null,null,"past.player.choice.chapter/1/vote","gte","1"),"Bad")),List.of()));
    }
    @Test void fullLengthStableChoiceIdsAreAcceptedInNpcConditions() {
        String key="past.player.choice."+"a".repeat(80)+"/"+"v".repeat(80)+"/"+"n".repeat(80);
        assertDoesNotThrow(()->catalog(List.of(new NpcCatalog.Dialogue("valid",1,new Condition(null,null,null,key,"exists",null),"Valid")),List.of()));
    }
    private static Condition eq(String fact,String value){return new Condition(null,null,null,fact,"eq",value);}
    private static Condition all(Condition...conditions){return new Condition(List.of(conditions),null,null,null,null,null);}
    private static Condition notExists(String fact){return new Condition(null,null,new Condition(null,null,null,fact,"exists",null),null,null,null);}
    private static NpcCatalog.Dialogue fallback(){return new NpcCatalog.Dialogue("fallback",0,null,"Ordinary greeting");}
    private static NpcCatalog catalog(List<NpcCatalog.Dialogue> dialogues,List<NpcCatalog.Reaction> reactions){return NpcCatalog.of(Map.of("guide",new NpcCatalog.Profile("Guide","Local archive guide","archive","guide",List.of(),List.of(),dialogues,reactions)));}
    private static PlayerContext context(UUID player,String nation){return new PlayerContext(player,nation,Map.of(),Set.of());}
    private static WorldEvent event(String id,String type,Set<UUID> players,Set<String> nations){return new WorldEvent(id,type,TIME,players,nations,Map.of(),"NpcScopedHistoryTest","committed test="+id);}
    private static void seed(SqliteStore store){store.transaction(c->{insert(c,event("a-award","AWARD",Set.of(A),Set.of("north")));insert(c,event("c-award","AWARD",Set.of(C),Set.of("south")));return null;});}
    private static void insert(Connection c,WorldEvent event)throws Exception {
        try(var s=c.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?)")){s.setString(1,event.id());s.setString(2,event.type());s.setString(3,event.occurredAt().toString());s.setString(4,"fixture");s.setString(5,SqliteStore.json(event));s.executeUpdate();}
        try(var s=c.prepareStatement("INSERT INTO event_players VALUES(?,?)")){for(UUID player:event.participants()){s.setString(1,event.id());s.setString(2,player.toString());s.addBatch();}s.executeBatch();}
        try(var s=c.prepareStatement("INSERT INTO event_nations VALUES(?,?)")){for(String nation:event.nations()){s.setString(1,event.id());s.setString(2,nation);s.addBatch();}s.executeBatch();}
    }
    private static void fact(Connection c,String owner,String key,String value)throws Exception{try(var s=c.prepareStatement("INSERT INTO facts VALUES(?,?,?) ON CONFLICT(owner,key) DO UPDATE SET value=excluded.value")){s.setString(1,owner);s.setString(2,key);s.setString(3,value);s.executeUpdate();}}
}
