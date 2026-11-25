package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionType;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.history.CommittedHistoryJournal;
import io.github.addxiaoyi.starchronicle.history.NationProductionEvidence;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class StarCoreNationProductionSourceTest {
    @TempDir Path directory;
    final UUID domain=UUID.randomUUID(),resourceDomain=UUID.randomUUID(),actor=UUID.randomUUID(),nation=UUID.randomUUID();
    final Instant now=Instant.parse("2026-11-25T20:00:00Z");
    public record Run(UUID runId,UUID actorId,UUID nationId,UUID resourceDomain,String recipeId,long batches,
                      Map<String,Long> inputs,Map<String,Long> outputs,Instant startedAt,Instant readyAt){}
    public record Receipt(UUID authorityDomain,long sequence,Run run,Instant committedAt){}
    public enum FactoryType { SMELTER }
    public record Factory(UUID authorityDomain,UUID factoryId,long version,FactoryType type,int level,int minimumLevel){}
    public record FactoryRun(UUID runId,UUID actorId,UUID nationId,UUID resourceDomain,String recipeId,long batches,
                             Map<String,Long> inputs,Map<String,Long> outputs,Instant startedAt,Instant readyAt,Factory factory){}
    public record FactoryReceipt(UUID authorityDomain,long sequence,FactoryRun run,Instant committedAt){}
    FactoryReceipt factoryReceipt(){
        var r=receipt(1);var v=r.run();
        return new FactoryReceipt(domain,1,new FactoryRun(v.runId(),actor,nation,resourceDomain,v.recipeId(),v.batches(),v.inputs(),v.outputs(),v.startedAt(),v.readyAt(),
                new Factory(UUID.randomUUID(),UUID.randomUUID(),3,FactoryType.SMELTER,2,1)),r.committedAt());
    }
    public static class Source {
        Optional<UUID> domain;List<Receipt> rows;int reads;boolean change;RuntimeException failure;
        Source(UUID domain,List<Receipt> rows){this.domain=Optional.of(domain);this.rows=rows;}
        public Optional<UUID> completionAuthorityDomain(){return domain;}
        public List<Receipt> completionsAfter(UUID expected,long after,int limit){
            assertEquals(domain.orElseThrow(),expected);assertEquals(100,limit);reads++;
            if(failure!=null)throw failure;if(change)domain=Optional.of(UUID.randomUUID());
            return rows.stream().filter(r->r.sequence()>after).limit(limit).toList();
        }
    }
    Receipt receipt(long sequence){return new Receipt(domain,sequence,new Run(UUID.randomUUID(),actor,nation,resourceDomain,"industrial_refining",2,
            Map.of("mineral",6L,"energy",2L),Map.of("industrial",4L),now,now.plusSeconds(60)),now.plusSeconds(60+sequence));}
    WorldEvent event(Receipt r)throws Exception{return StarCoreNationProductionSource.convert(domain,r).event();}
    RuleEngine engine(SqliteStore store){return new RuleEngine(store,List.of(),Clock.fixed(now,ZoneOffset.UTC));}
    void consume(CommittedHistoryJournal journal,RuleEngine engine){
        for(var e:journal.pending(StarCoreNationProductionSource.SOURCE,100)){engine.commitNationalProduction(e,List.of());journal.acknowledge(e.id());}
    }

    @Test void exactInputsOutputsTimesAndOriginalInitiatorSurviveCanonicalProjection()throws Exception{
        Receipt r=receipt(1);WorldEvent e=event(r);var proof=NationProductionEvidence.fromEvent(e);
        assertEquals(Set.of(actor),e.participants());assertEquals(Set.of(nation.toString()),e.nations());
        assertEquals("NATIONAL_PRODUCTION_COMMITTED",e.type());assertEquals(resourceDomain,proof.resourceDomain());
        assertEquals(r.run().inputs(),proof.inputs());assertEquals(r.run().outputs(),proof.outputs());
        assertEquals(r.run().startedAt(),proof.startedAt());assertEquals(r.run().readyAt(),proof.readyAt());assertEquals(2,proof.batches());
    }

    @Test void factoryAdmissionSurvivesInboxRestartAndDoesNotMintAdditionalContribution()throws Exception{
        var nativeReceipt=factoryReceipt();var entry=StarCoreNationProductionSource.convert(domain,nativeReceipt);
        var proof=NationProductionEvidence.fromEvent(entry.event());var f=nativeReceipt.run().factory();
        assertEquals(new NationProductionEvidence.FactoryAdmission(f.authorityDomain(),f.factoryId(),3,"SMELTER",2,1),proof.factory());
        Path path=directory.resolve("factory.db");
        try(var store=new SqliteStore(path)){
            var journal=new CommittedHistoryJournal(store);journal.accept(StarCoreNationProductionSource.SOURCE,domain,0,List.of(entry));
        }
        try(var store=new SqliteStore(path)){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);
            assertEquals(entry.event(),journal.pending(StarCoreNationProductionSource.SOURCE,1).getFirst());
            consume(journal,engine);assertEquals(2,engine.contributions(null,ContributionType.CIVILIZATION,0,100).size());
            assertTrue(engine.commitNationalProduction(entry.event(),List.of()).duplicate());
        }
    }

    @ParameterizedTest @ValueSource(strings={"authority_domain","id","version","type","level","minimum_level"})
    void incompleteFactoryProvenanceCannotBeConsumedAsLegacyProduction(String key)throws Exception{
        var entry=StarCoreNationProductionSource.convert(domain,factoryReceipt());var good=entry.event();
        var facts=new TreeMap<>(good.facts());facts.remove("production.factory."+key);
        var partial=new WorldEvent(good.id(),good.type(),good.occurredAt(),good.participants(),good.nations(),facts,good.source(),good.evidence());
        assertThrows(RuntimeException.class,()->NationProductionEvidence.fromEvent(partial));
    }

    @Test void newNativeRunWithNoFactoryPreservesLegacyCanonicalProjection()throws Exception{
        var r=receipt(1);var v=r.run();
        var newer=new FactoryReceipt(domain,1,new FactoryRun(v.runId(),actor,nation,resourceDomain,v.recipeId(),v.batches(),v.inputs(),v.outputs(),v.startedAt(),v.readyAt(),null),r.committedAt());
        assertEquals(event(r),StarCoreNationProductionSource.convert(domain,newer).event());
    }

    @Test void impossibleFactoryLevelFailsBeforeIngress() {
        var r=factoryReceipt();var v=r.run();var f=v.factory();
        var impossible=new FactoryReceipt(domain,1,new FactoryRun(v.runId(),actor,nation,resourceDomain,v.recipeId(),v.batches(),v.inputs(),v.outputs(),v.startedAt(),v.readyAt(),
                new Factory(f.authorityDomain(),f.factoryId(),1,FactoryType.SMELTER,2,1)),r.committedAt());
        assertThrows(IllegalArgumentException.class,()->StarCoreNationProductionSource.convert(domain,impossible));
    }

    @Test void emptyServerCompletionAndRestartReplayKeepTwoViewsOfOneRealOutput()throws Exception{
        var source=new Source(domain,List.of(receipt(1)));var adapter=new StarCoreNationProductionSource(()->source);
        Path path=directory.resolve("restart.db");WorldEvent e;
        try(var store=new SqliteStore(path)){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);
            assertTrue(adapter.poll(journal));e=journal.pending(StarCoreNationProductionSource.SOURCE,1).getFirst();
            assertFalse(engine.commitNationalProduction(e,List.of()).duplicate());assertFalse(adapter.poll(journal));
            var entries=engine.contributions(null,ContributionType.CIVILIZATION,0,100);assertEquals(2,entries.size());
            for(var entry:entries){var r=entry.request();assertEquals(actor,r.actorId());assertEquals(nation.toString(),r.nationId());
                assertEquals("production.resource:industrial",r.unit());assertEquals(0,new BigDecimal("4").compareTo(r.amount()));
                assertEquals(e.id(),r.sourceEventId());assertEquals(e.occurredAt(),entry.recordedAt());}
        }
        try(var store=new SqliteStore(path)){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);
            assertTrue(engine.commitNationalProduction(e,List.of()).duplicate());consume(journal,engine);assertTrue(adapter.poll(journal));
            assertEquals(1,engine.history(null,null,0,100).size());assertEquals(2,engine.contributions(null,ContributionType.CIVILIZATION,0,100).size());
        }
    }

    @Test void eventTextAndOtherSourceNamespacesCannotIssueCivilizationCredit()throws Exception{
        try(var store=new SqliteStore(directory.resolve("forged.db"))){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);var e=event(receipt(1));
            assertThrows(SecurityException.class,()->engine.commitNationalProduction(e,List.of()));
            engine.accept(e,List.of());assertTrue(engine.contributions(null,null,0,100).isEmpty());
            journal.accept(StarCoreResourceHistorySource.SOURCE,domain,0,List.of(new CommittedHistoryJournal.Entry(1,e)));
            assertThrows(SecurityException.class,()->engine.commitNationalProduction(e,List.of()));
            assertTrue(engine.contributions(null,null,0,100).isEmpty());
        }
    }

    @Test void contributionWriteFailureRollsBackHistoryAndAllViewsRetainingNativeInbox()throws Exception{
        try(var store=new SqliteStore(directory.resolve("rollback.db"))){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);var source=new Source(domain,List.of(receipt(1)));
            assertTrue(new StarCoreNationProductionSource(()->source).poll(journal));var e=journal.pending(StarCoreNationProductionSource.SOURCE,1).getFirst();
            store.transaction(c->{try(var s=c.createStatement()){
                s.execute("CREATE TRIGGER fail_production BEFORE INSERT ON contributions WHEN NEW.subject_id LIKE 'player:%' BEGIN SELECT RAISE(ABORT,'injected production contribution failure'); END");
            }return null;});
            assertThrows(RuntimeException.class,()->engine.commitNationalProduction(e,List.of()));
            assertTrue(engine.history(null,null,0,100).isEmpty());assertTrue(engine.contributions(null,null,0,100).isEmpty());
            assertEquals(List.of(e),journal.pending(StarCoreNationProductionSource.SOURCE,100));
            store.transaction(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER fail_production");}return null;});
            consume(journal,engine);assertEquals(2,engine.contributions(null,null,0,100).size());
        }
    }

    @ParameterizedTest @ValueSource(strings={"production.batches","production.actor","production.resource_domain","source.sequence","production.asset_atomicity","production.output.industrial","production.ready_at"})
    void changedEventCannotMatchTheExactCanonicalIngress(String key)throws Exception{
        try(var store=new SqliteStore(directory.resolve("altered.db"))){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);var good=event(receipt(1));
            var facts=new HashMap<>(good.facts());facts.put(key,key.equals("production.batches")||key.equals("source.sequence")||key.equals("production.output.industrial")?"3":UUID.randomUUID().toString());
            var altered=new WorldEvent(good.id(),good.type(),good.occurredAt(),good.participants(),good.nations(),facts,good.source(),good.evidence());
            journal.accept(StarCoreNationProductionSource.SOURCE,domain,0,List.of(new CommittedHistoryJournal.Entry(1,good)));
            assertThrows(RuntimeException.class,()->engine.commitNationalProduction(altered,List.of()));assertTrue(engine.contributions(null,null,0,100).isEmpty());
        }
    }

    @Test void missingCapabilityAndEmptyUnboundJournalDoNotInventCompletion() {
        try(var store=new SqliteStore(directory.resolve("old.db"))){
            var journal=new CommittedHistoryJournal(store);assertFalse(new StarCoreNationProductionSource(()->new Object()).poll(journal));
            var source=new Source(domain,List.of());source.domain=Optional.empty();assertFalse(new StarCoreNationProductionSource(()->source).poll(journal));
            assertTrue(journal.cursor(StarCoreNationProductionSource.SOURCE).isEmpty());assertEquals(0,source.reads);
        }
    }

    @Test void providerDomainAndSequenceGapsCannotAdvanceTheCursor(){
        try(var store=new SqliteStore(directory.resolve("switch.db"))){
            var journal=new CommittedHistoryJournal(store);var source=new Source(domain,List.of());source.change=true;
            assertThrows(IllegalStateException.class,()->new StarCoreNationProductionSource(()->source).poll(journal));
            source.change=false;source.domain=Optional.of(domain);int[] calls={0};
            assertThrows(IllegalStateException.class,()->new StarCoreNationProductionSource(()->++calls[0]==1?source:new Object()).poll(journal));
            source.rows=List.of(receipt(2));assertThrows(IllegalArgumentException.class,()->new StarCoreNationProductionSource(()->source).poll(journal));
            source.failure=new IllegalStateException("missing production prefix");assertThrows(IllegalStateException.class,()->new StarCoreNationProductionSource(()->source).poll(journal));
            assertTrue(journal.cursor(StarCoreNationProductionSource.SOURCE).isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings={"same","disjoint","new-actor"})
    void sameRunCannotCreateAnotherCompletionEvenWithDisjointOutputsOrAnotherActor(String change)throws Exception{
        var first=receipt(1);var original=first.run();
        var changed=new Run(original.runId(),change.equals("new-actor")?UUID.randomUUID():actor,nation,resourceDomain,original.recipeId(),2,
                original.inputs(),change.equals("disjoint")?Map.of("chemical",9L):original.outputs(),original.startedAt(),original.readyAt());
        var second=new Receipt(domain,2,changed,now.plusSeconds(62));
        try(var store=new SqliteStore(directory.resolve("rebound.db"))){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);var source=new Source(domain,List.of(first,second));
            assertTrue(new StarCoreNationProductionSource(()->source).poll(journal));var pending=journal.pending(StarCoreNationProductionSource.SOURCE,100);
            engine.commitNationalProduction(pending.get(0),List.of());assertThrows(IllegalStateException.class,()->engine.commitNationalProduction(pending.get(1),List.of()));
            assertEquals(2,engine.contributions(null,null,0,100).size());assertEquals(1,engine.history(null,null,0,100).size());
        }
    }

    @Test void multipleOutputsHaveIndependentUnitsAndShareOneImmutableRun()throws Exception{
        var first=receipt(1);var r=first.run();
        var multiple=new Receipt(domain,1,new Run(r.runId(),actor,nation,resourceDomain,r.recipeId(),2,r.inputs(),Map.of("industrial",4L,"chemical",2L),r.startedAt(),r.readyAt()),first.committedAt());
        try(var store=new SqliteStore(directory.resolve("multi.db"))){
            var journal=new CommittedHistoryJournal(store);var engine=engine(store);var source=new Source(domain,List.of(multiple));
            assertTrue(new StarCoreNationProductionSource(()->source).poll(journal));consume(journal,engine);
            assertEquals(4,engine.contributions(null,null,0,100).size());assertEquals(1,engine.history(null,null,0,100).size());
        }
    }
}
