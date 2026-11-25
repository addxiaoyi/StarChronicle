package io.github.addxiaoyi.starchronicle.persistence;

import io.github.addxiaoyi.starchronicle.core.StoryInstance;
import java.sql.*;

/** Rebuildable membership lookup, never a substitute for the pinned instance or live authority. */
public final class InstanceIndex {
    private InstanceIndex(){}

    public static boolean createSchema(Connection connection)throws SQLException {
        inTransaction(connection);
        boolean rebuild;
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE (type='table' AND name='instance_members') OR (type='trigger' AND name IN ('instance_members_insert','instance_members_update'))")){r.next();rebuild=r.getInt(1)!=3;}
        try(var s=connection.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS instance_members(instance_id TEXT NOT NULL REFERENCES instances(id) ON DELETE CASCADE,player_id TEXT NOT NULL,PRIMARY KEY(instance_id,player_id)) WITHOUT ROWID");
            s.execute("CREATE INDEX IF NOT EXISTS member_instances ON instance_members(player_id,instance_id)");
            s.execute("CREATE INDEX IF NOT EXISTS instance_scope_active ON instances(completed,scope,updated DESC,id)");
            s.execute("CREATE INDEX IF NOT EXISTS instance_active_order ON instances(completed,updated DESC,id)");
            s.execute("CREATE INDEX IF NOT EXISTS instance_active_id ON instances(completed,id)");
            s.execute("CREATE TRIGGER IF NOT EXISTS instance_members_insert AFTER INSERT ON instances BEGIN "+
                    "INSERT INTO instance_members SELECT NEW.id,lower(key) FROM json_each(NEW.json,'$.members'); END");
            s.execute("CREATE TRIGGER IF NOT EXISTS instance_members_update AFTER UPDATE OF json ON instances "+
                    "WHEN json_extract(OLD.json,'$.members') IS NOT json_extract(NEW.json,'$.members') BEGIN "+
                    "DELETE FROM instance_members WHERE instance_id=OLD.id; "+
                    "INSERT INTO instance_members SELECT NEW.id,lower(key) FROM json_each(NEW.json,'$.members'); END");
        }
        return rebuild;
    }

    /** One snapshot at a time; schema upgrade rolls back this projection with every other change. */
    public static void rebuild(Connection connection)throws SQLException {
        inTransaction(connection);
        try(var s=connection.createStatement()){s.executeUpdate("DELETE FROM instance_members");}
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT id,story,scope,completed,json FROM instances ORDER BY id");
            var insert=connection.prepareStatement("INSERT INTO instance_members VALUES(?,?)")) {
            while(r.next()) {
                StoryInstance instance=SqliteStore.decode(r.getString("json"),StoryInstance.class);
                if(!instance.id().equals(r.getString("id"))||!instance.definition().id().equals(r.getString("story"))
                        ||!instance.scopeKey().equals(r.getString("scope"))||instance.completed()!=(r.getInt("completed")!=0))
                    throw new IllegalStateException("Instance membership source metadata is inconsistent: "+r.getString("id"));
                for(var player:instance.members().keySet()){insert.setString(1,instance.id());insert.setString(2,player.toString());insert.executeUpdate();}
            }
        }
    }
    private static void inTransaction(Connection c)throws SQLException {
        if(c==null||c.getAutoCommit())throw new IllegalStateException("Instance index requires the enclosing Chronicle transaction");
    }
}
