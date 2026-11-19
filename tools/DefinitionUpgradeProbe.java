import io.github.addxiaoyi.starchronicle.core.DefinitionCatalog;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Runs only against the orchestrator's specially named isolated clone, never the original DB.
 * Production SqliteStore owns both upgrade opens; this probe only reads the resulting state. */
public final class DefinitionUpgradeProbe {
    private static String quote(String name){return "\""+name.replace("\"","\"\"")+"\"";}
    private static Map<String,Object> value(Object value) {
        Map<String,Object> result=new LinkedHashMap<>();
        if(value==null){result.put("type","null");result.put("value",null);}
        else if(value instanceof byte[] bytes){result.put("type","blob");result.put("value",Base64.getEncoder().encodeToString(bytes));}
        else if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long){result.put("type","integer");result.put("value",value.toString());}
        else if(value instanceof Float||value instanceof Double){result.put("type","real");result.put("value",HexFormat.of().toHexDigits(Double.doubleToRawLongBits(((Number)value).doubleValue())));}
        else if(value instanceof String text){result.put("type","text");result.put("value",text);}
        else throw new IllegalStateException("Unsupported SQLite value class: "+value.getClass().getName());
        return result;
    }
    private static List<List<Object>> rows(Connection connection,String query)throws SQLException {
        List<List<Object>> result=new ArrayList<>();try(var s=connection.createStatement();var r=s.executeQuery(query)){int width=r.getMetaData().getColumnCount();while(r.next()){List<Object> row=new ArrayList<>();for(int i=1;i<=width;i++)row.add(r.getObject(i));result.add(row);}}return result;
    }
    private static Map<String,Object> snapshot(Connection connection)throws SQLException {
        List<String> names=new ArrayList<>();
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")){while(r.next())names.add(r.getString(1));}
        Map<String,Object> result=new LinkedHashMap<>();
        for(String name:names) {
            List<String> columns=new ArrayList<>();List<List<Object>> values=new ArrayList<>();
            try(var s=connection.createStatement();var r=s.executeQuery("SELECT * FROM "+quote(name))) {
                int width=r.getMetaData().getColumnCount();for(int i=1;i<=width;i++)columns.add(r.getMetaData().getColumnName(i));
                while(r.next()){List<Object> row=new ArrayList<>();for(int i=1;i<=width;i++)row.add(value(r.getObject(i)));values.add(row);}
            }
            result.put(name,Map.of("columns",columns,"rows",values));
        }
        return result;
    }
    private static List<Map<String,Object>> definitions(Connection connection)throws SQLException {
        var catalog=DefinitionCatalog.read(connection);List<Map<String,Object>> result=new ArrayList<>();
        for(var key:catalog.keys()) {
            var variants=catalog.entries().get(key);List<Map<String,Object>> evidence=new ArrayList<>();
            for(var entry:variants)evidence.add(Map.of("fingerprint",entry.fingerprint(),"provenance",entry.provenance(),"first_seen",entry.firstSeen()));
            // Conflicts are retained as conflicts, not omitted or replaced by a chosen winner.
            Map<String,Object> row=new LinkedHashMap<>();row.put("story",key.storyId());row.put("version",key.version());row.put("status",variants.size()==1?"UNIQUE":"CONFLICT_RETAINED");row.put("variants",evidence);
            if(variants.size()==1){var definition=catalog.require(key.storyId(),key.version());row.put("resolved_story",definition.id());row.put("resolved_version",definition.version());}
            result.add(row);
        }
        return result;
    }
    private static Map<String,Object> originPlan(Connection connection)throws SQLException {
        String story="unused-probe",scope="world",version="0",source="unused-probe",sample=null;
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT id,story,scope,json_extract(json,'$.definition.version'),json_extract(json,'$.sourceEventId') FROM instances ORDER BY id LIMIT 1")) {
            if(r.next()){sample=r.getString(1);story=r.getString(2);scope=r.getString(3);version=r.getString(4);source=r.getString(5);}
        }
        String predicate=" FROM instances WHERE story=? AND scope=? AND json_extract(json,'$.definition.version')=? AND json_extract(json,'$.sourceEventId')=?";
        List<String> plan=new ArrayList<>(),matches=new ArrayList<>();
        try(var s=connection.prepareStatement("EXPLAIN QUERY PLAN SELECT id"+predicate)) {
            s.setString(1,story);s.setString(2,scope);s.setString(3,version);s.setString(4,source);try(var r=s.executeQuery()){while(r.next())plan.add(r.getString("detail"));}
        }
        try(var s=connection.prepareStatement("SELECT id"+predicate)) {
            s.setString(1,story);s.setString(2,scope);s.setString(3,version);s.setString(4,source);try(var r=s.executeQuery()){while(r.next())matches.add(r.getString(1));}
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("sample_instance_id",sample);result.put("story",story);result.put("scope",scope);result.put("version",version);result.put("source_event_id",source);result.put("plan",plan);result.put("matched_instance_ids",matches);return result;
    }
    private static Map<String,Object> open(Path clone) {
        try(var store=new SqliteStore(clone)) {
            return store.transaction(connection->{
                Map<String,Object> data=new LinkedHashMap<>();data.put("version",rows(connection,"PRAGMA user_version").getFirst().getFirst());
                data.put("tables",snapshot(connection));data.put("schema_objects",rows(connection,"SELECT type,name,tbl_name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type,name"));
                data.put("catalog",definitions(connection));data.put("origin_query",originPlan(connection));
                data.put("foreign_keys",rows(connection,"PRAGMA foreign_key_check"));data.put("integrity",rows(connection,"PRAGMA integrity_check"));
                data.put("jdbc_driver",connection.getMetaData().getDriverVersion());return data;
            });
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected the orchestrator's isolated clone path");
        Path clone=Path.of(args[0]).toRealPath(),run=clone.getParent();
        if(!clone.getFileName().toString().equals("chronicle-upgrade-clone.db")||run==null||!run.getFileName().toString().matches("definition-upgrade-[a-z0-9][a-z0-9-]{0,47}")||run.getParent()==null||!run.getParent().getFileName().toString().equals("run"))
            throw new IllegalArgumentException("Refusing a database outside the isolated definition-upgrade run layout");
        Map<String,Object> first=open(clone);Files.writeString(run.resolve("first-open.json"),SqliteStore.json(first)+"\n",StandardCharsets.UTF_8);
        Map<String,Object> second=open(clone);Files.writeString(run.resolve("second-open.json"),SqliteStore.json(second)+"\n",StandardCharsets.UTF_8);
        System.out.println(SqliteStore.json(Map.of("first","first-open.json","second","second-open.json","compiled_schema_version",SqliteStore.SCHEMA_VERSION,"java_version",System.getProperty("java.version"))));
    }
}
