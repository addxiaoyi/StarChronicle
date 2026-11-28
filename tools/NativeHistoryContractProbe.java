package io.github.addxiaoyi.starchronicle.integration;

import dev.starcore.starcore.core.database.DatabaseService;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.resource.SimpleTradeHistoryService;
import dev.starcore.starcore.module.resource.model.TradeRecord;
import dev.starcore.starcore.module.resource.model.TradeOrder;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import io.github.addxiaoyi.starchronicle.api.contribution.ContributionType;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;

/** Low-memory contract probe: real native and Chronicle SQLite services, no Minecraft server. */
public final class NativeHistoryContractProbe {
    private static final UUID SELLER=new UUID(1,1),BUYER=new UUID(2,2);
    private static final NationId SELLER_NATION=NationId.of(new UUID(3,3)),BUYER_NATION=NationId.of(new UUID(4,4));
    private static final Instant EXECUTED=Instant.parse("2026-11-20T12:00:00.123456789Z");
    public static void main(String[] args)throws Exception {
        Path directory=Path.of(args[0]);String phase=args[1];
        var nativeHistory=new SimpleTradeHistoryService(new FileDatabase(directory.resolve("native.db")),null);
        try(var engine=new RuleEngine(new SqliteStore(directory.resolve("chronicle.db")),List.of(),Clock.systemUTC())) {
            var collector=new CommittedSourceCollector(type->nativeHistory);
            if(phase.equals("first")) {
                nativeHistory.saveRecords(List.of(trade(1,10),trade(2,20).withStatus(TradeRecord.TradeRecordStatus.DISPUTED,"awaiting native settlement")));
                ingest(engine,collector);
                check(engine.history(null,null,0,100).size()==2,"first history");
                check(engine.committedSourceCursor("starcore.trade").orElseThrow().sequence()==2,"first cursor");
            } else if(phase.equals("second")) {
                nativeHistory.updateStatus(new UUID(0,2),TradeRecord.TradeRecordStatus.COMPLETED,"late completion");
                nativeHistory.updateStatus(new UUID(0,1),TradeRecord.TradeRecordStatus.REFUNDED,"native refund");
                ingest(engine,collector);ingest(engine,collector);
                check(engine.history(null,null,0,100).size()==4,"late history and refund");
                check(engine.committedSourceCursor("starcore.trade").orElseThrow().sequence()==4,"late cursor");
            } else if(phase.equals("third")) {
                nativeHistory.persistAllRecords();
                check(ingest(engine,collector)==0,"restart replay must be duplicate");
                check(engine.history(null,null,0,100).size()==4,"restart history");
                check(nativeHistory.committedChangesAfter(4,100).isEmpty(),"restart generated no native commits");
            } else throw new IllegalArgumentException("Unknown probe phase");
        }
        try(var store=new SqliteStore(directory.resolve("chronicle.db"))) {
            var ledger=new ContributionLedger(store,Clock.systemUTC());
            int expected=phase.equals("first")?4:8;
            check(ledger.list(null,null,0,100).size()==expected,"contribution rows");
            BigDecimal expectedTotal=new BigDecimal(phase.equals("first")?"10":"20");
            check(ledger.total("nation:"+SELLER_NATION,ContributionType.TRADE,"resource.industrial").compareTo(expectedTotal)==0,"net contribution total");
            if(!phase.equals("first"))check(ledger.list(null,null,0,100).stream().filter(e->e.sourceStatus().equals("REFUNDED")).count()==4,"refund evidence retained");
        }
        System.out.println("NATIVE_HISTORY_CONTRACT_PASS phase="+phase);
    }
    private static int ingest(RuleEngine engine,CommittedSourceCollector collector)throws Exception {
        var source=CommittedSourceCollector.Source.TRADE;
        return engine.acceptCommittedSource(collector.collect(source,engine.committedSourceCursor(source.id)).orElseThrow(),List.of());
    }
    private static TradeRecord trade(long id,long quantity) {
        return new TradeRecord(new UUID(0,id),TradeOrder.OrderType.BUY,TradeOrder.OrderSource.MARKET,SELLER,BUYER,SELLER_NATION,BUYER_NATION,"industrial",quantity,2.5,0.5,EXECUTED.minusSeconds(id),new UUID(5,id),new UUID(6,id),"probe committed trade");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final class FileDatabase extends DatabaseService {
        final String url;
        FileDatabase(Path path){super(null,null);url="jdbc:sqlite:"+path.toAbsolutePath();}
        @Override public Connection getConnection(){try{return DriverManager.getConnection(url);}catch(SQLException error){throw new IllegalStateException(error);}}
    }
}
