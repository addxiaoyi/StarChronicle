package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.config.Condition;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** Read-only canonical history projection within the caller's transaction.
 * Counts come from events and its subject indexes, never from supplied contexts or copied facts.
 * Legacy history.TYPE aliases are preserved verbatim; chronicle.history scopes use a separate
 * namespace so no existing event type can be mistaken for an identity-scoped count. */
public final class HistoricalFacts {
    private HistoricalFacts() {}

    /** Condition projection: only requested keys, with no history query for unrelated conditions.
     * The complete read method remains available to callers that need a full compatibility view. */
    public static Map<String,String> readRequested(Connection connection,UUID playerId,String nationId,
                                                   Collection<String> requestedFacts)throws SQLException {
        Objects.requireNonNull(connection,"connection");
        Map<String,Map<String,Set<String>>> requested=new LinkedHashMap<>();
        for(String scope:List.of("world","nation","player"))requested.put(scope,new TreeMap<>());
        if(requestedFacts!=null)for(String key:requestedFacts) {
            if(!accepts(key))continue;
            String scope="world",type;
            if(key.startsWith("history."))type=key.substring("history.".length());
            else {String rest=key.substring("chronicle.history.".length());int dot=rest.indexOf('.');scope=rest.substring(0,dot);type=rest.substring(dot+1);}
            requested.get(scope).computeIfAbsent(type,ignored->new LinkedHashSet<>()).add(key);
        }
        Map<String,String> facts=new LinkedHashMap<>();
        for(var group:requested.entrySet()) {
            String scope=group.getKey();
            String subject=switch(scope){case "nation"->nationId==null||nationId.isBlank()?null:nationId;case "player"->playerId==null?null:playerId.toString();default->"world";};
            if(subject==null||group.getValue().isEmpty())continue;
            List<String> types=new ArrayList<>(group.getValue().keySet());
            // Keep parameter count bounded even for a catalog with many different event types.
            for(int offset=0;offset<types.size();offset+=256) {
                List<String> batch=types.subList(offset,Math.min(types.size(),offset+256));
                String marks=String.join(",",Collections.nCopies(batch.size(),"?"));
                String sql=switch(scope) {
                    case "nation"->"SELECT e.type,COUNT(*) FROM event_nations n JOIN events e ON e.id=n.event_id WHERE n.nation=? AND e.type IN ("+marks+") GROUP BY e.type";
                    case "player"->"SELECT e.type,COUNT(*) FROM event_players p JOIN events e ON e.id=p.event_id WHERE p.player=? AND e.type IN ("+marks+") GROUP BY e.type";
                    default->"SELECT type,COUNT(*) FROM events WHERE type IN ("+marks+") GROUP BY type";
                };
                try(var query=connection.prepareStatement(sql)) {
                    int parameter=1;if(!scope.equals("world"))query.setString(parameter++,subject);
                    for(String type:batch)query.setString(parameter++,type);
                    try(var rows=query.executeQuery()) {
                        while(rows.next())for(String key:group.getValue().get(rows.getString(1)))facts.put(key,Long.toString(rows.getLong(2)));
                    }
                }
            }
            for(var aliases:group.getValue().values())for(String key:aliases)
                if(key.startsWith("chronicle.history."))facts.putIfAbsent(key,"0");
        }
        return Map.copyOf(facts);
    }

    /**
     * Return existing legacy world counts and scoped counts. Explicit scoped requests receive
     * zero for an absent type only when the scope's subject is known. A null player or null/blank
     * nation is unknown, not a claim that the subject has experienced zero events.
     * This method does not open/commit a transaction or mutate the connection's transaction mode.
     */
    public static Map<String,String> read(Connection connection,UUID playerId,String nationId,
                                          Collection<String> requestedFacts) throws SQLException {
        Objects.requireNonNull(connection,"connection");
        if(requestedFacts!=null)for(String fact:requestedFacts)accepts(fact);
        String nation=nationId==null||nationId.isBlank()?null:nationId;
        StringBuilder sql=new StringBuilder("SELECT 'world' AS scope,type,COUNT(*) AS total FROM events GROUP BY type");
        if(nation!=null)sql.append(" UNION ALL SELECT 'nation',e.type,COUNT(*) FROM event_nations n JOIN events e ON e.id=n.event_id WHERE n.nation=? GROUP BY e.type");
        if(playerId!=null)sql.append(" UNION ALL SELECT 'player',e.type,COUNT(*) FROM event_players p JOIN events e ON e.id=p.event_id WHERE p.player=? GROUP BY e.type");
        Map<String,String> worldCounts=new TreeMap<>(),nationCounts=new TreeMap<>(),playerCounts=new TreeMap<>();
        try(var query=connection.prepareStatement(sql.toString())) {
            int parameter=1;
            if(nation!=null)query.setString(parameter++,nation);
            if(playerId!=null)query.setString(parameter,playerId.toString());
            try(var rows=query.executeQuery()) {
                while(rows.next()) {
                    Map<String,String> target=switch(rows.getString(1)) {
                        case "world"->worldCounts;
                        case "nation"->nationCounts;
                        case "player"->playerCounts;
                        default->throw new SQLException("Unexpected historical scope");
                    };
                    target.put(rows.getString(2),Long.toString(rows.getLong(3)));
                }
            }
        }
        Map<String,String> facts=new LinkedHashMap<>();
        worldCounts.forEach((type,count)->facts.put("history."+type,count));
        scoped(facts,"world",worldCounts);
        if(nation!=null)scoped(facts,"nation",nationCounts);
        if(playerId!=null)scoped(facts,"player",playerCounts);
        if(requestedFacts!=null)for(String requested:requestedFacts) {
            if(requested==null)continue;
            if(scopedRequest(requested,"world")
                    ||nation!=null&&scopedRequest(requested,"nation")
                    ||playerId!=null&&scopedRequest(requested,"player"))facts.putIfAbsent(requested,"0");
        }
        return Map.copyOf(facts);
    }

    /** Extract requested history keys from every all/any/not branch without evaluating it. */
    public static Set<String> requested(Condition... conditions) {
        Set<String> keys=new LinkedHashSet<>();
        Deque<Condition> pending=new ArrayDeque<>();
        if(conditions!=null)for(Condition condition:conditions)if(condition!=null)pending.addLast(condition);
        while(!pending.isEmpty()) {
            Condition condition=pending.removeFirst();
            if(accepts(condition.fact()))keys.add(condition.fact());
            if(condition.not()!=null)pending.addLast(condition.not());
            if(condition.all()!=null)pending.addAll(condition.all());
            if(condition.any()!=null)pending.addAll(condition.any());
        }
        return Set.copyOf(keys);
    }

    private static void scoped(Map<String,String> facts,String scope,Map<String,String> counts) {
        counts.forEach((type,count)->facts.put("chronicle.history."+scope+"."+type,count));
    }
    private static boolean scopedRequest(String key,String scope) {
        String prefix="chronicle.history."+scope+".";
        return key.startsWith(prefix)&&key.length()>prefix.length();
    }
    /** Ordinary facts are not references. Historical references preserve the event type's exact
     * nonblank text (including dots/Unicode/spaces), bounded by WorldEvent's 128-character type. */
    public static boolean accepts(String fact) {
        if(fact==null)return false;
        String type;
        if(fact.startsWith("chronicle.history.")) {
            String scoped=fact.substring("chronicle.history.".length());int separator=scoped.indexOf('.');
            if(separator<1||!Set.of("world","nation","player").contains(scoped.substring(0,separator)))throw new IllegalArgumentException("Historical reference requires world/nation/player scope: "+fact);
            type=scoped.substring(separator+1);
        } else if(fact.startsWith("history."))type=fact.substring("history.".length());
        else return false;
        if(type.isBlank()||type.length()>128)throw new IllegalArgumentException("Historical reference requires an event type of 1..128 nonblank characters: "+fact);
        return true;
    }
}
