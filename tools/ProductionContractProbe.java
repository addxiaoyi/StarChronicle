package io.github.addxiaoyi.starchronicle.integration;

import dev.starcore.starcore.core.storage.AbstractModuleStateStorage;
import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.*;
import dev.starcore.starcore.module.resource.*;
import dev.starcore.starcore.module.resource.ProcessingService.*;
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

/** Real packaged resource/processing/SQLite services; fixed NationService fixture, no Minecraft. */
public final class ProductionContractProbe {
    static final UUID ACTOR=new UUID(1,1);
    static final NationId NATION=new NationId(new UUID(3,3));
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[0]);String phase=args[1];
        var database=new SQLiteDataSource();database.setUrl("jdbc:sqlite:"+output.resolve("native.db").toAbsolutePath());
        Class<?> storageType=Class.forName("dev.starcore.starcore.module.resource.ResourceStateStorage");
        var storageConstructor=Class.forName("dev.starcore.starcore.module.resource.SqlResourceStateStorage").getDeclaredConstructor(javax.sql.DataSource.class,Properties.class,Logger.class);
        storageConstructor.setAccessible(true);Object storage=storageConstructor.newInstance(database,new Properties(),Logger.getLogger("production-contract"));
        var resourceConstructor=ResourceModule.class.getDeclaredConstructor(storageType);resourceConstructor.setAccessible(true);
        ResourceModule resources=resourceConstructor.newInstance(storage);
        Nation fixtureNation=new Nation(NATION,"Contract nation",ACTOR,"Contract founder");
        NationService fixture=(NationService)Proxy.newProxyInstance(NationService.class.getClassLoader(),new Class<?>[]{NationService.class},(proxy,method,values)->{
            if(method.getName().equals("nationOf"))return ACTOR.equals(values[0])?Optional.of(fixtureNation):Optional.empty();
            throw new UnsupportedOperationException("Fixture only supplies detached nationOf");
        });
        var nations=ResourceModule.class.getDeclaredField("nationService");nations.setAccessible(true);nations.set(resources,fixture);
        var processing=new SimpleProcessingService(resources,new SimpleResourcePriceService());processing.initializeDefaultRecipes();
        var original=new ProductionRequest("player-production-1",NATION,ACTOR,"national_industry",10);
        try {
            try(var engine=new RuleEngine(new SqliteStore(output.resolve("chronicle.db")),List.of(),Clock.systemUTC())) {
                var collector=new CommittedSourceCollector(type->processing);
                if(phase.equals("first")) {
                    check(resources.grant(NATION,"mineral",90)&&resources.grant(NATION,"energy",30),"fixture inventory seed");
                    var first=processing.process(original);check(first.status()==ProductionStatus.APPLIED,"first native commit");
                    check(resources.amount(NATION,"industrial")==20,"actual native output");
                    check(ingest(engine,collector)==1,"first ingestion");
                    Files.writeString(output.resolve("first-receipt.txt"),first.receipt().fingerprint());
                } else if(phase.equals("second")) {
                    check(processing.process(original).status()==ProductionStatus.ALREADY_APPLIED,"replay after restart");
                    check(processing.process(new ProductionRequest(original.operationId(),NATION,ACTOR,"national_industry",11)).status()==ProductionStatus.REJECTED,"conflicting retry");
                    check(processing.process(new ProductionRequest("player-production-2",NATION,ACTOR,"national_industry",2)).applied(),"second actor conversion");
                    check(processing.autoProcess(NATION,"national_chemicals",3),"system conversion");
                    check(ingest(engine,collector)==2,"catch up two commits including system history");
                    check(ingest(engine,collector)==0,"same process replay");
                } else if(phase.equals("third")) {
                    processing.registerRecipe(new ProcessingRecipe("national_industry","changed recipe",Map.of("energy",100L),Map.of("industrial",100L),1,0,1));
                    var restored=processing.process(original);
                    check(restored.status()==ProductionStatus.ALREADY_APPLIED,"pinned result after recipe change");
                    check(restored.receipt().fingerprint().equals(Files.readString(output.resolve("first-receipt.txt"))),"original receipt survives all JVMs");
                    check(ingest(engine,collector)==0,"restart ingestion replay");
                    check(processing.productionReceiptsAfter(3,100).isEmpty(),"queries create no commits");
                } else throw new IllegalArgumentException("Unknown phase");
                long expected=phase.equals("first")?1:3;
                check(engine.committedSourceCursor("starcore.production").orElseThrow().sequence()==expected,"durable cursor");
                check(engine.history(null,null,0,100).size()==expected,"history count");
            }
            try(var store=new SqliteStore(output.resolve("chronicle.db"))) {
                var ledger=new ContributionLedger(store,Clock.systemUTC());
                check(ledger.list(null,null,0,100).size()==(phase.equals("first")?2:4),"no invented system actor credit");
                BigDecimal expected=new BigDecimal(phase.equals("first")?"20":"24");
                check(ledger.total("player:"+ACTOR,ContributionType.CIVILIZATION,"resource.industrial").compareTo(expected)==0,"actor gross production contribution");
                check(ledger.total("nation:"+NATION,ContributionType.CIVILIZATION,"resource.industrial").compareTo(expected)==0,"national production contribution");
                check(ledger.total("nation:"+NATION,ContributionType.CIVILIZATION,"resource.chemical").signum()==0,"actorless output has no credit");
            }
            if(!phase.equals("first")) {
                check(resources.amount(NATION,"mineral")==54,"deducted mineral");
                check(resources.amount(NATION,"energy")==15,"deducted energy");
                check(resources.amount(NATION,"industrial")==18,"industrial after subsequent input");
                check(resources.amount(NATION,"chemical")==3,"chemical output");
            }
            System.out.println("PRODUCTION_CONTRACT_PASS phase="+phase);
        } finally { AbstractModuleStateStorage.shutdownNamespace("test"); }
    }
    static int ingest(RuleEngine engine,CommittedSourceCollector collector)throws Exception {
        var source=CommittedSourceCollector.Source.PRODUCTION;
        return engine.acceptCommittedSource(collector.collect(source,engine.committedSourceCursor(source.id)).orElseThrow(),List.of());
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
