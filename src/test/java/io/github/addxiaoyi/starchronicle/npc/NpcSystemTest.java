package io.github.addxiaoyi.starchronicle.npc;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.config.Condition;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class NpcSystemTest {
    @TempDir Path directory;
    private static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private SqliteStore store;
    private NpcCatalog catalog;
    private NpcLedger ledger;
    @BeforeEach void open() throws Exception {
        Path file=directory.resolve("npcs.yml");
        try(var input=getClass().getResourceAsStream("/npcs.yml")){assertNotNull(input);Files.copy(input,file);}
        catalog=NpcCatalog.load(file);store=new SqliteStore(directory.resolve("npc.db"));ledger=new NpcLedger(store,catalog);
    }
    @AfterEach void close(){if(store!=null)store.close();}
    private static PlayerContext context(UUID player,String role){return new PlayerContext(player,"river",Map.of("player.role",role,"player.name","访客"),Set.of());}
    private static WorldEvent event(String id,String type,UUID...players){return new WorldEvent(id,type,Instant.parse("2026-11-19T00:00:00Z"),Set.of(players),Set.of("river"),Map.of(),"NpcSystemTest","committed="+id);}
    private void commit(WorldEvent event){store.transaction(connection->{insert(connection,event);ledger.applyEvent(connection,event);return null;});}
    private static void insert(Connection connection,WorldEvent event) throws Exception {
        try(var insert=connection.prepareStatement("INSERT INTO events(id,type,time,hash,json) VALUES(?,?,?,?,?)")) {
            insert.setString(1,event.id());insert.setString(2,event.type());insert.setString(3,event.occurredAt().toString());insert.setString(4,"fixture");insert.setString(5,SqliteStore.json(event));insert.executeUpdate();
        }
    }
    private static NpcCatalog.Profile profile(List<NpcCatalog.Promotion> promotions,List<NpcCatalog.Binding> bindings,
                                               List<NpcCatalog.Dialogue> dialogues,List<NpcCatalog.Reaction> reactions) {
        return new NpcCatalog.Profile("守望者","保存地方历史的守望者。","library","visitor",promotions,bindings,dialogues,reactions);
    }
    @Test void packagedProfilesExposeBackgroundFactionAndImmutableDefaults(){
        assertEquals(Set.of("ancient-heir","siege-veteran","caravan-archivist"),catalog.profiles().keySet());
        var view=ledger.view(A,"ancient-heir",context(A,"scholar"),Map.of());
        assertEquals("imperial-claimants",view.faction());assertFalse(view.background().isBlank());
        assertEquals("claimant",view.position());assertEquals(0,view.trust());assertEquals("introduction",view.dialogueId());
        assertThrows(UnsupportedOperationException.class,()->view.facts().put("npc.trust","999"));
        assertThrows(UnsupportedOperationException.class,()->catalog.profiles().clear());
        assertThrows(UnsupportedOperationException.class,()->ledger.facts(A).clear());
    }
    @Test void committedReactionPersistsAndDeduplicatesAcrossRestart(){
        var event=event("heir-1","HEIR_RESTORATION_SUPPORTED",A,B);commit(event);
        var first=ledger.view(A,"ancient-heir",context(A,"scholar"),Map.of());
        assertEquals(15,first.reputation());assertEquals(12,first.trust());assertEquals(20,first.growth());assertEquals("council-speaker",first.position());
        store.close();store=new SqliteStore(directory.resolve("npc.db"));ledger=new NpcLedger(store,catalog);
        store.transaction(connection->{ledger.applyEvent(connection,event);ledger.applyEvent(connection,event);return null;});
        assertEquals(first,ledger.view(A,"ancient-heir",context(A,"scholar"),Map.of()));
        assertEquals(15,ledger.view(B,"ancient-heir",context(B,"soldier"),Map.of()).reputation());
        assertEquals(1,store.transaction(connection->{try(var s=connection.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM outcomes")){return r.next()?r.getInt(1):0;}}).intValue());
    }
    @Test void historicalSourceAndNpcReactionRollbackTogether(){
        var event=event("rollback","HEIR_RESTORATION_SUPPORTED",A);
        assertThrows(IllegalStateException.class,()->store.transaction(connection->{insert(connection,event);ledger.applyEvent(connection,event);throw new IllegalStateException("Injected transaction failure");}));
        assertEquals(0,ledger.view(A,"ancient-heir",context(A,"scholar"),Map.of()).reputation());
        assertEquals(0,store.transaction(connection->{try(var s=connection.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM events")){return r.next()?r.getInt(1):0;}}).intValue());
        commit(event);assertEquals(15,ledger.view(A,"ancient-heir",context(A,"scholar"),Map.of()).reputation());
    }
    @Test void sourceMustExistAndSuppliedPayloadCannotReplaceCanonicalParticipants(){
        var original=event("canonical","HEIR_RESTORATION_SUPPORTED",A);
        assertThrows(IllegalStateException.class,()->store.transaction(connection->{ledger.applyEvent(connection,original);return null;}));
        store.transaction(connection->{insert(connection,original);ledger.applyEvent(connection,event("canonical","UNRELATED",B));return null;});
        assertEquals(15,ledger.view(A,"ancient-heir",context(A,"scholar"),Map.of()).reputation());
        assertEquals(0,ledger.view(B,"ancient-heir",context(B,"scholar"),Map.of()).reputation());
    }
    @Test void globalHistoricalReactionWorksWithoutInventingPlayerParticipants(){
        commit(event("war","WAR_ENDED"));
        var view=ledger.view(A,"siege-veteran",context(A,"soldier"),Map.of());
        assertEquals(5,view.growth());assertEquals(0,view.trust());assertEquals("aftermath",view.dialogueId());
    }
    @Test void dialogueCombinesTrustedIdentityActualHistoryRegionAndRelationship(){
        commit(event("study","CARAVAN_RESEARCH_PUBLISHED",A));
        var scholar=ledger.view(A,"caravan-archivist",context(A,"scholar"),Map.of());
        assertEquals("scholar-exchange",scholar.dialogueId());assertTrue(scholar.dialogue().contains("同行"));
        assertEquals("researched",ledger.view(A,"caravan-archivist",context(A,"soldier"),Map.of()).dialogueId());
        commit(event("heir","HEIR_RESTORATION_SUPPORTED",A));
        assertEquals("trusted-restoration",ledger.view(A,"ancient-heir",context(A,"scholar"),Map.of("region.old-imperial-capital.legacy","restoration-initiative")).dialogueId());
        PlayerContext spoof=new PlayerContext(B,"river",Map.of("npc.trust","999","history.NATIONS_MERGED","999"),Set.of());
        assertEquals("introduction",ledger.view(B,"ancient-heir",spoof,Map.of("npc.trust","999","history.NATIONS_MERGED","999")).dialogueId());
        assertThrows(SecurityException.class,()->ledger.view(A,"ancient-heir",context(B,"scholar"),Map.of()));
    }
    @Test void growthThresholdsPromoteGloballyAndExplicitPositionMustBeDeclared(){
        for(int i=0;i<3;i++)commit(event("growth-"+i,"HEIR_RESTORATION_SUPPORTED",A));
        assertEquals("heritage-guardian",ledger.view(B,"ancient-heir",context(B,"merchant"),Map.of()).position());
        store.transaction(connection->{ledger.applyEffect(connection,A,"position","ancient-heir","claimant");return null;});
        assertEquals("claimant",ledger.view(A,"ancient-heir",context(A,"scholar"),Map.of()).position());
        assertThrows(IllegalArgumentException.class,()->store.transaction(connection->{ledger.applyEffect(connection,A,"position","ancient-heir","server-admin");return null;}));
    }
    @Test void relationshipAndGrowthChangesAreClampedAndCannotOverflow(){
        store.transaction(connection->{
            ledger.applyEffect(connection,A,"reputation","ancient-heir","1000000");
            ledger.applyEffect(connection,A,"trust","ancient-heir","-1000000");
            ledger.applyEffect(connection,A,"hostility","ancient-heir","1000000");
            ledger.applyEffect(connection,A,"growth","ancient-heir","1000000");
            ledger.applyEffect(connection,A,"growth","ancient-heir","1000000");return null;
        });
        var view=ledger.view(A,"ancient-heir",context(A,"scholar"),Map.of());
        assertEquals(1000,view.reputation());assertEquals(-1000,view.trust());assertEquals(1000,view.hostility());assertEquals(1000000,view.growth());assertEquals("hostility",view.dialogueId());
        assertThrows(IllegalArgumentException.class,()->catalog.validateEffect("reputation","ancient-heir",Long.toString(Long.MIN_VALUE)));
        assertThrows(IllegalArgumentException.class,()->catalog.validateEffect("reputation","unknown","10"));
        assertThrows(IllegalArgumentException.class,()->catalog.validateEffect("command","ancient-heir","op player"));
    }
    @Test void jsonBindingsResolveCaseInsensitivelyWithoutSpawningEntities() throws Exception {
        Path file=directory.resolve("binding.json");
        Files.writeString(file,"{\"profiles\":{\"guide\":{\"name\":\"Guide\",\"background\":\"Archive guide\",\"faction\":\"library\",\"initialPosition\":\"guide\",\"bindings\":[{\"provider\":\"FancyNpcs\",\"externalId\":\"actual-server-id\"}]}}}");
        var configured=NpcCatalog.load(file);
        assertEquals(Optional.of("guide"),configured.resolveExternal("fancynpcs","actual-server-id"));
        assertEquals(Optional.of("guide"),configured.resolveExternal("FancyNpcs:actual-server-id"));
        assertTrue(configured.resolveExternal("Citizens:actual-server-id").isEmpty());
        assertTrue(configured.resolveExternal("actual-server-id").isEmpty());
    }
    @Test void strictLoaderRejectsUnknownFieldsAndDuplicateLogicalBindings() throws Exception {
        Path file=directory.resolve("invalid.json");Files.writeString(file,"{\"profiles\":{},\"runCommand\":\"op player\"}");
        assertThrows(Exception.class,()->NpcCatalog.load(file));
        var binding=new NpcCatalog.Binding("Citizens","npc-1");var p=profile(List.of(),List.of(binding),List.of(),List.of());
        assertThrows(IllegalArgumentException.class,()->NpcCatalog.of(Map.of("one",p,"two",p)));
        assertThrows(IllegalArgumentException.class,()->NpcCatalog.of(Map.of("one",profile(List.of(new NpcCatalog.Promotion("bad",-1,"mayor")),List.of(),List.of(),List.of()))));
        assertThrows(IllegalArgumentException.class,()->NpcCatalog.of(Map.of("one",profile(List.of(),List.of(),List.of(new NpcCatalog.Dialogue("bad",0,new Condition(null,null,null,"npc.trust","execute","command"),"bad")),List.of()))));
    }
    @Test void playerDependentReactionCannotInventAnAnonymousTrustedParticipant(){
        Condition notTrusted=new Condition(null,null,new Condition(null,null,null,"npc.trust","eq","10"),null,null,null);
        var reaction=new NpcCatalog.Reaction("personal",List.of("PERSONAL"),notTrusted,0,5,0,10,null);
        var custom=NpcCatalog.of(Map.of("guide",profile(List.of(),List.of(),List.of(),List.of(reaction))));ledger=new NpcLedger(store,custom);
        commit(event("anonymous","PERSONAL"));
        assertEquals(0,ledger.view(A,"guide",context(A,"scholar"),Map.of()).growth());
        commit(event("named","PERSONAL",A));
        assertEquals(10,ledger.view(A,"guide",context(A,"scholar"),Map.of()).growth());
        assertEquals(5,ledger.view(A,"guide",context(A,"scholar"),Map.of()).trust());
    }
    @Test void falseReactionIsConsumedAndDoesNotBecomeRetroactiveAfterStateChanges(){
        Condition trusted=new Condition(null,null,null,"npc.trust","gte","10");
        var reaction=new NpcCatalog.Reaction("trusted-only",List.of("PERSONAL"),trusted,10,0,0,0,null);
        var custom=NpcCatalog.of(Map.of("guide",profile(List.of(),List.of(),List.of(),List.of(reaction))));ledger=new NpcLedger(store,custom);
        var event=event("once","PERSONAL",A);commit(event);
        store.transaction(connection->{ledger.applyEffect(connection,A,"trust","guide","20");ledger.applyEvent(connection,event);return null;});
        assertEquals(0,ledger.view(A,"guide",context(A,"scholar"),Map.of()).reputation());
    }
}
