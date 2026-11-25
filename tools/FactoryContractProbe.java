package io.github.addxiaoyi.starchronicle.integration;

import dev.starcore.starcore.core.storage.AbstractModuleStateStorage;
import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.*;
import dev.starcore.starcore.module.resource.*;
import dev.starcore.starcore.module.resource.ProcessingService.*;
import dev.starcore.starcore.module.resource.model.Factory;
import dev.starcore.starcore.module.resource.model.ProcessingRecipe;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionType;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Logger;
import org.sqlite.SQLiteDataSource;

/** Real packaged services and SQLite, a detached identity fixture and controlled time; no Minecraft. */
public final class FactoryContractProbe {
    static final UUID ACTOR=new UUID(1,1);
    static final NationId NATION=new NationId(new UUID(3,3));
    static final class TestClock extends Clock {
        Instant now=Instant.parse("2026-11-26T10:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[0]);String phase=args[1];var clock=new TestClock();
        if(phase.equals("second"))clock.now=clock.now.plusSeconds(9);
        if(phase.equals("third"))clock.now=clock.now.plusSeconds(20);
        var database=new SQLiteDataSource();database.setUrl("jdbc:sqlite:"+output.resolve("native.db").toAbsolutePath());
        Class<?> storageType=Class.forName("dev.starcore.starcore.module.resource.ResourceStateStorage");
        var storageConstructor=Class.forName("dev.starcore.starcore.module.resource.SqlResourceStateStorage").getDeclaredConstructor(javax.sql.DataSource.class,Properties.class,Logger.class);
        storageConstructor.setAccessible(true);Object storage=storageConstructor.newInstance(database,new Properties(),Logger.getLogger("factory-contract"));
        var resourceConstructor=ResourceModule.class.getDeclaredConstructor(storageType,Clock.class);resourceConstructor.setAccessible(true);
        ResourceModule resources=resourceConstructor.newInstance(storage,clock);
        Nation fixtureNation=new Nation(NATION,"Contract nation",ACTOR,"Contract founder");
        NationService fixture=(NationService)Proxy.newProxyInstance(NationService.class.getClassLoader(),new Class<?>[]{NationService.class},(proxy,method,values)->{
            if(method.getName().equals("nationOf"))return ACTOR.equals(values[0])?Optional.of(fixtureNation):Optional.empty();
            throw new UnsupportedOperationException("Fixture only supplies detached nationOf");
        });
        var nations=ResourceModule.class.getDeclaredField("nationService");nations.setAccessible(true);nations.set(resources,fixture);
        var processing=new SimpleProcessingService(resources,new SimpleResourcePriceService());
        try {
            if(phase.equals("first")) {
                processing.initializeDefaultRecipes();
                check(resources.grant(NATION,"mineral",90)&&resources.grant(NATION,"energy",30),"seed inventory");
                var factory=processing.buildFactory(NATION,"Contract factory",Factory.FactoryType.SMELTER);
                Files.writeString(output.resolve("factory-id.txt"),factory.factoryId().toString());
            }
            UUID factory=UUID.fromString(Files.readString(output.resolve("factory-id.txt")));
            var original=new FactoryRequest("player-factory-1",factory,NATION,ACTOR,"national_industry",10);
            try(var engine=new RuleEngine(new SqliteStore(output.resolve("chronicle.db")),List.of(),clock)) {
                var collector=new CommittedSourceCollector(type->processing);
                if(phase.equals("first")) {
                    var first=processing.startFactoryRun(original);check(first.status()==FactoryStatus.STARTED,"start commit");
                    check(resources.amount(NATION,"mineral")==60&&resources.amount(NATION,"energy")==20,"all inputs deducted");
                    check(resources.amount(NATION,"industrial")==0,"no early output");
                    check(ingest(engine,collector)==0,"start is not committed output history");
                    check(processing.productionReceiptsAfter(0,10).isEmpty(),"no source before completion");
                    Files.writeString(output.resolve("ready-at.txt"),first.run().readyAt().toString());
                } else if(phase.equals("second")) {
                    check(processing.getAllRecipes().isEmpty(),"no recipes after restart");
                    check(processing.startFactoryRun(original).status()==FactoryStatus.ALREADY_STARTED,"exact retry without recipe");
                    check(processing.completeFactoryRun(original.runId(),NATION,ACTOR).status()==FactoryStatus.NOT_READY,"early collection refused");
                    processing.registerRecipe(new ProcessingRecipe("national_industry","changed",Map.of("energy",99L),Map.of("industrial",100L),999,0,1));
                    processing.upgradeFactory(factory);
                    clock.now=clock.now.plusSeconds(1);
                    var completed=processing.completeFactoryRun(original.runId(),NATION,ACTOR);
                    check(completed.status()==FactoryStatus.COMPLETED,"frozen completion");
                    check(completed.run().readyAt().toString().equals(Files.readString(output.resolve("ready-at.txt"))),"deadline survives upgrade");
                    check(completed.run().outputs().equals(Map.of("industrial",20L)),"frozen output ignores changed recipe");
                    Files.writeString(output.resolve("first-receipt.txt"),completed.run().productionReceipt().fingerprint());
                    check(ingest(engine,collector)==1,"one completion becomes history and contribution");
                    check(ingest(engine,collector)==0,"duplicate ingestion");
                } else if(phase.equals("third")) {
                    var replay=processing.completeFactoryRun(original.runId(),NATION,ACTOR);
                    check(replay.status()==FactoryStatus.ALREADY_COMPLETED,"completion replay after restart");
                    check(replay.run().productionReceipt().fingerprint().equals(Files.readString(output.resolve("first-receipt.txt"))),"exact durable receipt");
                    check(processing.startFactoryRun(original).status()==FactoryStatus.ALREADY_COMPLETED,"start replay after completion");
                    check(ingest(engine,collector)==0,"durable Chronicle cursor replay");
                    processing.initializeDefaultRecipes();
                    var system=new FactoryRequest("system-factory-2",factory,NATION,null,"national_industry",2);
                    check(processing.startFactoryRun(system).status()==FactoryStatus.STARTED,"next system batch");
                    check(processing.completeFactoryRun(original.runId(),NATION,ACTOR).status()==FactoryStatus.ALREADY_COMPLETED,"old retry cannot collect next batch");
                    clock.now=clock.now.plusSeconds(2);
                    processing.refreshFactories();processing.refreshFactories();
                    check(processing.factoryRun(system.runId()).orElseThrow().completed(),"automatic completion");
                    check(ingest(engine,collector)==1,"system history without fabricated actor");
                    check(ingest(engine,collector)==0,"system replay");
                    check(resources.amount(NATION,"mineral")==54&&resources.amount(NATION,"energy")==18,"final deductions");
                    check(resources.amount(NATION,"industrial")==24,"exact final outputs");
                } else throw new IllegalArgumentException("Unknown phase");
                long expected=phase.equals("first")?0:phase.equals("second")?1:2;
                check(engine.history(null,null,0,100).size()==expected,"history count");
                if(expected>0)check(engine.committedSourceCursor("starcore.production").orElseThrow().sequence()==expected,"durable cursor");
            }
            try(var store=new SqliteStore(output.resolve("chronicle.db"))) {
                var ledger=new ContributionLedger(store,clock);
                check(ledger.list(null,null,0,100).size()==(phase.equals("first")?0:2),"no start or actorless credit");
                BigDecimal expected=new BigDecimal(phase.equals("first")?"0":"20");
                check(ledger.total("player:"+ACTOR,ContributionType.CIVILIZATION,"resource.industrial").compareTo(expected)==0,"actor committed output contribution");
                check(ledger.total("nation:"+NATION,ContributionType.CIVILIZATION,"resource.industrial").compareTo(expected)==0,"nation committed output contribution");
            }
            System.out.println("FACTORY_CONTRACT_PASS phase="+phase);
        } finally { AbstractModuleStateStorage.shutdownNamespace("test"); }
    }
    static int ingest(RuleEngine engine,CommittedSourceCollector collector)throws Exception {
        var source=CommittedSourceCollector.Source.PRODUCTION;
        return engine.acceptCommittedSource(collector.collect(source,engine.committedSourceCursor(source.id)).orElseThrow(),List.of());
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
