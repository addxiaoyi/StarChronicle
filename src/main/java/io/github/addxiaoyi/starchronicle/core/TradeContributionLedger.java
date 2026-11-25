package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.contribution.*;
import io.github.addxiaoyi.starchronicle.history.CommittedSourcePage;
import java.sql.*;
import java.time.Instant;

/** Append-only contribution evidence with a separate current native status projection. */
public final class TradeContributionLedger {
    private TradeContributionLedger() {}
    public static void createSchema(Connection c)throws SQLException {
        try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS native_trade_states(source TEXT NOT NULL,domain TEXT NOT NULL,record_id TEXT NOT NULL,terms_hash TEXT NOT NULL,status TEXT NOT NULL,sequence INTEGER NOT NULL,credited INTEGER NOT NULL,suppress_credit INTEGER NOT NULL,PRIMARY KEY(source,domain,record_id))");
            s.execute("CREATE TABLE IF NOT EXISTS native_trade_contributions(contribution_id TEXT PRIMARY KEY REFERENCES contributions(id),source TEXT NOT NULL,domain TEXT NOT NULL,record_id TEXT NOT NULL,FOREIGN KEY(source,domain,record_id) REFERENCES native_trade_states(source,domain,record_id))");
        }
    }
    public static void apply(Connection c,CommittedSourcePage page,CommittedSourcePage.Entry entry,Instant recordedAt)throws SQLException {
        var trade=entry.trade();if(trade==null)return;
        boolean exists=false,credited=false,suppress=false;
        try(var s=c.prepareStatement("SELECT terms_hash,sequence,credited,suppress_credit FROM native_trade_states WHERE source=? AND domain=? AND record_id=?")) {
            s.setString(1,page.source());s.setString(2,page.domain());s.setString(3,trade.recordId());
            try(var r=s.executeQuery()) {if(r.next()) {
                exists=true;
                if(!r.getString(1).equals(trade.termsHash())||entry.sequence()<=r.getLong(2))throw new IllegalStateException("Trade terms changed or status moved backwards");
                credited=r.getInt(3)!=0;suppress=r.getInt(4)!=0;
            }}
        }
        if(entry.baseline()) {
            if(exists)throw new IllegalStateException("Trade baseline appeared after a prior transition");
            // Migration cannot prove whether a disputed/refunded trade was credited in old history.
            suppress=true;
        }
        boolean credit=!entry.baseline()&&!suppress&&!credited&&trade.status().equals("COMPLETED");
        try(var s=c.prepareStatement("INSERT INTO native_trade_states(source,domain,record_id,terms_hash,status,sequence,credited,suppress_credit) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(source,domain,record_id) DO UPDATE SET status=excluded.status,sequence=excluded.sequence,credited=excluded.credited,suppress_credit=excluded.suppress_credit")) {
            s.setString(1,page.source());s.setString(2,page.domain());s.setString(3,trade.recordId());s.setString(4,trade.termsHash());s.setString(5,trade.status());s.setLong(6,entry.sequence());s.setInt(7,credited||credit?1:0);s.setInt(8,suppress?1:0);s.executeUpdate();
        }
        if(credit)for(var request:trade.contributions()) {
            if(request.type()!=ContributionType.TRADE)throw new IllegalArgumentException("Non-trade contribution in trade journal");
            var result=ContributionLedger.record(c,request,recordedAt);
            try(var s=c.prepareStatement("INSERT INTO native_trade_contributions(contribution_id,source,domain,record_id) VALUES(?,?,?,?)")) {
                s.setString(1,result.entry().id());s.setString(2,page.source());s.setString(3,page.domain());s.setString(4,trade.recordId());s.executeUpdate();
            }
        }
    }
}
