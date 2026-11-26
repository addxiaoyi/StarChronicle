package io.github.addxiaoyi.starchronicle.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Rebuildable exact counts; original events and subject rows remain authoritative. */
public final class HistoryCountIndex {
    private static final String TABLE="history_event_counts";
    private static final String COLUMNS="(scope TEXT NOT NULL CHECK(scope IN ('world','nation','player')),subject TEXT NOT NULL,type TEXT NOT NULL,total INTEGER NOT NULL CHECK(typeof(total)='integer' AND total>0),PRIMARY KEY(scope,subject,type),CHECK(scope<>'world' OR subject='')) WITHOUT ROWID";
    private static final String TABLE_SQL="CREATE TABLE "+TABLE+COLUMNS;
    private static final String EXPECTED="temp.history_expected_counts";
    private static final List<String> TRIGGERS=List.of("history_counts_event_insert","history_counts_event_delete","history_counts_event_update",
            "history_counts_nation_insert","history_counts_nation_delete","history_counts_nation_update",
            "history_counts_player_insert","history_counts_player_delete","history_counts_player_update");

    private HistoryCountIndex(){}

    public static void initialize(Connection connection)throws SQLException {
        if(connection==null||connection.getAutoCommit())throw new IllegalStateException("History count index requires the enclosing Chronicle transaction");
        String objectType=null,definition=null;
        try(var query=connection.prepareStatement("SELECT type,sql FROM sqlite_master WHERE name=? AND type IN ('table','view')")) {
            query.setString(1,TABLE);
            try(var rows=query.executeQuery()){if(rows.next()){objectType=rows.getString(1);definition=rows.getString(2);}}
        }
        try(var statement=connection.createStatement()) {
            for(String trigger:TRIGGERS)statement.execute("DROP TRIGGER IF EXISTS "+trigger);
            if(!TABLE_SQL.equals(definition)) {
                if(objectType!=null) {
                    if(!objectType.equals("table")&&!objectType.equals("view"))throw new SQLException("Unexpected history count index object: "+objectType);
                    statement.execute("DROP "+objectType.toUpperCase(java.util.Locale.ROOT)+" "+TABLE);
                }
                statement.execute(TABLE_SQL);
            }
            statement.execute("DROP TABLE IF EXISTS "+EXPECTED);
            statement.execute("CREATE TEMP TABLE history_expected_counts"+COLUMNS);
            statement.executeUpdate("INSERT INTO "+EXPECTED+" SELECT 'world','',type,COUNT(*) FROM events GROUP BY type");
            statement.executeUpdate("INSERT INTO "+EXPECTED+" SELECT 'nation',n.nation,e.type,COUNT(*) FROM event_nations n JOIN events e ON e.id=n.event_id GROUP BY n.nation,e.type");
            statement.executeUpdate("INSERT INTO "+EXPECTED+" SELECT 'player',p.player,e.type,COUNT(*) FROM event_players p JOIN events e ON e.id=p.event_id GROUP BY p.player,e.type");
            boolean mismatch;
            try(var rows=statement.executeQuery("SELECT EXISTS(SELECT scope,subject,type,total FROM "+EXPECTED+" EXCEPT SELECT scope,subject,type,total FROM "+TABLE+") OR EXISTS(SELECT scope,subject,type,total FROM "+TABLE+" EXCEPT SELECT scope,subject,type,total FROM "+EXPECTED+")")) {
                rows.next();mismatch=rows.getBoolean(1);
            }
            if(mismatch) {
                statement.executeUpdate("DELETE FROM "+TABLE);
                statement.executeUpdate("INSERT INTO "+TABLE+" SELECT scope,subject,type,total FROM "+EXPECTED);
            }
            statement.execute("DROP TABLE "+EXPECTED);
            statement.execute("CREATE TRIGGER history_counts_event_insert AFTER INSERT ON events BEGIN "+eventDelta("NEW",true)+" END");
            statement.execute("CREATE TRIGGER history_counts_event_delete BEFORE DELETE ON events BEGIN "+eventDelta("OLD",false)+" END");
            statement.execute("CREATE TRIGGER history_counts_event_update AFTER UPDATE OF id,type ON events WHEN OLD.id IS NOT NEW.id OR OLD.type IS NOT NEW.type BEGIN "+eventDelta("OLD",false)+eventDelta("NEW",true)+" END");
            subjectTriggers(statement,"nation","event_nations","nation");
            subjectTriggers(statement,"player","event_players","player");
        }
    }

    private static void subjectTriggers(java.sql.Statement statement,String scope,String table,String column)throws SQLException {
        String prefix="history_counts_"+scope;
        statement.execute("CREATE TRIGGER "+prefix+"_insert AFTER INSERT ON "+table+" BEGIN "+subjectDelta(scope,column,"NEW",true)+" END");
        statement.execute("CREATE TRIGGER "+prefix+"_delete AFTER DELETE ON "+table+" BEGIN "+subjectDelta(scope,column,"OLD",false)+" END");
        statement.execute("CREATE TRIGGER "+prefix+"_update AFTER UPDATE OF event_id,"+column+" ON "+table+" WHEN OLD.event_id IS NOT NEW.event_id OR OLD."+column+" IS NOT NEW."+column+" BEGIN "+subjectDelta(scope,column,"OLD",false)+subjectDelta(scope,column,"NEW",true)+" END");
    }

    private static String eventDelta(String row,boolean add) {
        return delta("world","''",row+".type"," WHERE 1",add)
                +delta("nation","n.nation",row+".type"," FROM event_nations n WHERE n.event_id="+row+".id",add)
                +delta("player","p.player",row+".type"," FROM event_players p WHERE p.event_id="+row+".id",add);
    }

    private static String subjectDelta(String scope,String column,String row,boolean add) {
        return delta(scope,row+"."+column,"e.type"," FROM events e WHERE e.id="+row+".event_id",add);
    }

    private static String delta(String scope,String subject,String type,String source,boolean add) {
        String keys="SELECT '"+scope+"' AS scope,"+subject+" AS subject,"+type+" AS type"+source;
        if(add)return "INSERT INTO "+TABLE+"(scope,subject,type,total) SELECT '"+scope+"',"+subject+","+type+",1"+source+" ON CONFLICT(scope,subject,type) DO UPDATE SET total=total+excluded.total; ";
        return "SELECT RAISE(ABORT,'History count projection is inconsistent') WHERE EXISTS(SELECT 1 FROM ("+keys+") AS delta LEFT JOIN "+TABLE+" AS counts ON counts.scope=delta.scope AND counts.subject=delta.subject AND counts.type=delta.type WHERE counts.total IS NULL OR counts.total<1); "
                +"DELETE FROM "+TABLE+" WHERE total=1 AND (scope,subject,type) IN ("+keys+"); "
                +"UPDATE "+TABLE+" SET total=total-1 WHERE (scope,subject,type) IN ("+keys+"); ";
    }
}
