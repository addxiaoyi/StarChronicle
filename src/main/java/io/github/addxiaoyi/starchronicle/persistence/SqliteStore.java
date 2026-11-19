package io.github.addxiaoyi.starchronicle.persistence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.*;
import java.sql.*;

/** Single local durable database. Every orchestration transition and local outcome is one transaction. */
public final class SqliteStore implements AutoCloseable {
    public static final int SCHEMA_VERSION=2;
    public static final ObjectMapper JSON=new ObjectMapper().registerModule(new JavaTimeModule())
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Connection connection;
    public SqliteStore(Path path) {
        Connection opened=null;
        try {
            if(path.toAbsolutePath().getParent()!=null) Files.createDirectories(path.toAbsolutePath().getParent());
            Class.forName("org.sqlite.JDBC");
            connection=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());
            opened=connection;
            try(var s=connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");
                try(var r=s.executeQuery("PRAGMA user_version")){if(r.next()&&r.getInt(1)>SCHEMA_VERSION) throw new IllegalStateException("Database schema newer than this plugin");}
                s.execute("CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY,type TEXT NOT NULL,time TEXT NOT NULL,hash TEXT NOT NULL,json TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS event_nations(event_id TEXT REFERENCES events(id),nation TEXT NOT NULL,PRIMARY KEY(event_id,nation))");
                s.execute("CREATE TABLE IF NOT EXISTS event_players(event_id TEXT REFERENCES events(id),player TEXT NOT NULL,PRIMARY KEY(event_id,player))");
                s.execute("CREATE INDEX IF NOT EXISTS history_time ON events(time DESC,id)");
                s.execute("CREATE INDEX IF NOT EXISTS nation_history ON event_nations(nation,event_id)");
                s.execute("CREATE INDEX IF NOT EXISTS player_history ON event_players(player,event_id)");
                s.execute("CREATE TABLE IF NOT EXISTS instances(id TEXT PRIMARY KEY,story TEXT NOT NULL,scope TEXT NOT NULL,completed INTEGER NOT NULL,updated TEXT NOT NULL,json TEXT NOT NULL)");
                s.execute("CREATE INDEX IF NOT EXISTS active_instances ON instances(story,scope,completed)");
                s.execute("CREATE TABLE IF NOT EXISTS outcomes(id TEXT PRIMARY KEY,status TEXT NOT NULL,json TEXT NOT NULL)");
                s.execute("CREATE INDEX IF NOT EXISTS outcome_delivery_status ON outcomes(status,id)");
                s.execute("CREATE TABLE IF NOT EXISTS facts(owner TEXT NOT NULL,key TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(owner,key))");
                s.execute("CREATE TABLE IF NOT EXISTS story_triggers(id TEXT PRIMARY KEY,trigger_key TEXT NOT NULL,event_id TEXT NOT NULL REFERENCES events(id),scope TEXT NOT NULL,story_json TEXT NOT NULL,checked INTEGER NOT NULL DEFAULT 0)");
                s.execute("CREATE INDEX IF NOT EXISTS story_trigger_scope ON story_triggers(scope,checked,id)");
                s.execute("CREATE INDEX IF NOT EXISTS story_trigger_event ON story_triggers(event_id,id)");
                s.execute("CREATE TABLE IF NOT EXISTS story_trigger_receipts(trigger_key TEXT NOT NULL,scope TEXT NOT NULL,PRIMARY KEY(trigger_key,scope))");
                s.execute("CREATE TABLE IF NOT EXISTS native_scene_journal(id TEXT PRIMARY KEY,player TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('READY','RUNNING','UNKNOWN','COMPLETED')),revision INTEGER NOT NULL,json TEXT NOT NULL)");
                s.execute("CREATE INDEX IF NOT EXISTS native_scene_player_status ON native_scene_journal(player,status,id)");
                s.execute("PRAGMA user_version="+SCHEMA_VERSION);
                // A process may have died after a non-idempotent external call. Never replay it blindly.
                s.execute("UPDATE outcomes SET status='UNKNOWN' WHERE status='RUNNING'");
            }
        }catch(Exception e){if(opened!=null)try{opened.close();}catch(SQLException close){e.addSuppressed(close);}throw new IllegalStateException("Cannot open StarChronicle database",e);}
    }
    @FunctionalInterface public interface Work<T>{T run(Connection c)throws Exception;}
    public synchronized <T>T transaction(Work<T> work){
        try {
            connection.setAutoCommit(false);
            try { T result=work.run(connection); connection.commit(); return result; }
            catch(Exception|Error e){connection.rollback();throw e;}
            finally{connection.setAutoCommit(true);}
        }catch(RuntimeException|Error e){throw e;}catch(Exception e){throw new IllegalStateException("Chronicle transaction failed",e);}
    }
    public static String json(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    public static <T>T decode(String json,Class<T> type){try{return JSON.readValue(json,type);}catch(Exception e){throw new IllegalStateException("Corrupt stored "+type.getSimpleName(),e);}}
    @Override public synchronized void close(){try{connection.close();}catch(SQLException e){throw new IllegalStateException(e);}}
}
