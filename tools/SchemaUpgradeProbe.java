import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Isolated production-constructor probe. Never opens the original source database. */
public final class SchemaUpgradeProbe {
    private static List<List<Object>> rows(Connection c,String sql)throws SQLException {
        List<List<Object>> result=new ArrayList<>();
        try(var s=c.createStatement();var r=s.executeQuery(sql)) {
            while(r.next()){List<Object> row=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getObject(i));result.add(row);}
        }
        return result;
    }
    private static Map<String,Object> open(Path clone) {
        try(var store=new SqliteStore(clone)) {
            return store.transaction(c->{Map<String,Object> data=new LinkedHashMap<>();
                data.put("version",rows(c,"PRAGMA user_version").getFirst().getFirst());
                data.put("choices",rows(c,"SELECT * FROM story_choice_log ORDER BY seq"));
                data.put("invalid_legacy",rows(c,"SELECT COUNT(*) FROM story_choice_log WHERE provenance<>'LEGACY_INSTANCE' OR chosen_at IS NOT NULL OR committed_by IS NOT NULL").getFirst().getFirst());
                data.put("causes",rows(c,"SELECT COUNT(*) FROM event_causes").getFirst().getFirst());
                data.put("foreign_keys",rows(c,"PRAGMA foreign_key_check"));
                data.put("integrity",rows(c,"PRAGMA integrity_check").getFirst().getFirst());
                return data;
            });
        }
    }
    public static void main(String[] args) {
        if(args.length!=1)throw new IllegalArgumentException("Expected the fresh clone database path");
        Path clone=Path.of(args[0]);Map<String,Object> first=open(clone),second=open(clone);
        System.out.println(SqliteStore.json(Map.of("first",first,"second",second)));
    }
}
