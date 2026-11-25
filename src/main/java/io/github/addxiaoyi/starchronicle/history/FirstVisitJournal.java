package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Map;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.decode;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.json;

/** Durable first-visit ingress. The first observed event is immutable and redelivered until acknowledged. */
public final class FirstVisitJournal {
    private final SqliteStore store;

    public FirstVisitJournal(SqliteStore store) {
        this.store=Objects.requireNonNull(store);
        store.transaction(c->{try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS exploration_discoveries(event_id TEXT PRIMARY KEY,player_id TEXT NOT NULL,region_scope TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('PENDING','ACKED')),json TEXT NOT NULL,UNIQUE(player_id,region_scope))");
            s.execute("CREATE INDEX IF NOT EXISTS exploration_pending ON exploration_discoveries(status,event_id)");
            s.execute("CREATE TABLE IF NOT EXISTS exploration_region_scopes(region_id TEXT PRIMARY KEY,scope TEXT NOT NULL)");
        }return null;});
    }

    /** Bind logical IDs permanently so a region name cannot silently be repurposed after restart. */
    public void registerRegions(Map<String,String> regions) {
        Objects.requireNonNull(regions);
        store.transaction(c->{for(var region:regions.entrySet()) {
            if(!region.getKey().matches("[a-z0-9][a-z0-9._-]{0,63}")||!region.getValue().matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid exploration region binding");
            try(var query=c.prepareStatement("SELECT scope FROM exploration_region_scopes WHERE region_id=?")) {
                query.setString(1,region.getKey());try(var row=query.executeQuery()) {
                    if(row.next()) {if(!region.getValue().equals(row.getString(1)))throw new IllegalStateException("Exploration region ID is permanently bound to another world/bounds: "+region.getKey());continue;}
                }
            }
            try(var insert=c.prepareStatement("INSERT INTO exploration_region_scopes(region_id,scope) VALUES(?,?)")) {insert.setString(1,region.getKey());insert.setString(2,region.getValue());insert.executeUpdate();}
        }return null;});
    }

    /**
     * Reserve an observed arrival. Repeated moves and process restarts return the original
     * immutable event; they cannot replace its timestamp, coordinates, nation, or evidence.
     * Empty means that this player/scope already has an acknowledged first-visit event.
     */
    public java.util.Optional<WorldEvent> observe(WorldEvent candidate) {
        validate(candidate);
        String player=candidate.participants().iterator().next().toString();
        String scope=candidate.facts().get("region.scope");
        return store.transaction(c->{
            try(var binding=c.prepareStatement("SELECT scope FROM exploration_region_scopes WHERE region_id=?")) {
                binding.setString(1,candidate.facts().get("region.id"));try(var row=binding.executeQuery()) {
                    if(!row.next()||!scope.equals(row.getString(1)))throw new IllegalStateException("First-visit scope is not bound to its configured region");
                }
            }
            try(var insert=c.prepareStatement("INSERT OR IGNORE INTO exploration_discoveries(event_id,player_id,region_scope,status,json) VALUES(?,?,?,'PENDING',?)")) {
                insert.setString(1,candidate.id());insert.setString(2,player);insert.setString(3,scope);insert.setString(4,json(candidate));insert.executeUpdate();
            }
            try(var select=c.prepareStatement("SELECT event_id,status,json FROM exploration_discoveries WHERE player_id=? AND region_scope=?")) {
                select.setString(1,player);select.setString(2,scope);
                try(var row=select.executeQuery()) {
                    if(!row.next())throw new IllegalStateException("Exploration claim disappeared before commit");
                    if(!candidate.id().equals(row.getString(1)))throw new IllegalStateException("Exploration scope maps to another event identity");
                    return "PENDING".equals(row.getString(2))?java.util.Optional.of(decode(row.getString(3),WorldEvent.class)):java.util.Optional.empty();
                }
            }
        });
    }

    public List<WorldEvent> pending(int limit) {
        if(limit<1||limit>1000)throw new IllegalArgumentException("Ingress batch must be 1..1000");
        return store.transaction(c->{List<WorldEvent> result=new ArrayList<>();try(var s=c.prepareStatement("SELECT json FROM exploration_discoveries WHERE status='PENDING' ORDER BY event_id LIMIT ?")) {
            s.setInt(1,limit);try(var rows=s.executeQuery()){while(rows.next())result.add(decode(rows.getString(1),WorldEvent.class));}
        }return List.copyOf(result);});
    }

    /** Acknowledge only after RuleEngine.accept has durably committed or deduplicated the event. */
    public boolean acknowledge(String eventId) {
        return store.transaction(c->{try(var s=c.prepareStatement("UPDATE exploration_discoveries SET status='ACKED' WHERE event_id=? AND status='PENDING'")) {s.setString(1,eventId);return s.executeUpdate()==1;}});
    }

    private static void validate(WorldEvent event) {
        Objects.requireNonNull(event);
        if(!event.type().equals("EXPLORATION_DISCOVERY")||!event.source().equals("StarChronicle.ConfiguredRegionFirstVisit")||event.participants().size()!=1||event.nations().size()>1)
            throw new IllegalArgumentException("Expected one observed configured-region discovery participant");
        UUID player=event.participants().iterator().next();String scope=event.facts().get("region.scope");
        if(scope==null||!scope.matches("[0-9a-f]{64}")||!event.id().equals("exploration:first-visit:"+player+":"+scope)||!"OBSERVED".equals(event.facts().get("time_basis"))||!"FIRST_VISIT".equals(event.facts().get("discovery.kind")))
            throw new IllegalArgumentException("Invalid first-visit event identity or evidence basis");
    }
}
