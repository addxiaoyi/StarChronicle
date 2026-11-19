package io.github.addxiaoyi.starchronicle.memory;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.util.*;
import static io.github.addxiaoyi.starchronicle.persistence.SqliteStore.*;

/** Persistent placement registry. A site must cite an event already committed to this world's history. */
public final class MemoryLedger {
    private final SqliteStore store;
    public MemoryLedger(SqliteStore store) {
        this.store=Objects.requireNonNull(store);
        store.transaction(c->{try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS memory_sites(id TEXT PRIMARY KEY,event_id TEXT NOT NULL REFERENCES events(id),json TEXT NOT NULL)");
        } return null;});
    }
    public MemorySite create(MemorySite site) {
        return store.transaction(c->{
            try(var s=c.prepareStatement("SELECT 1 FROM memory_sites WHERE id=?")) {
                s.setString(1,site.id());try(var r=s.executeQuery()){if(r.next())throw new IllegalStateException("纪念点ID已存在或保留为清理墓碑，请使用新的纪念点ID");}
            }
            try(var s=c.prepareStatement("INSERT INTO memory_sites VALUES(?,?,?)")) {
                s.setString(1,site.id());s.setString(2,site.eventId());s.setString(3,json(site));s.executeUpdate();
            }
            return site;
        });
    }
    /** Tombstones survive restart: an unloaded old display must still be removed on chunk load. */
    public MemorySite disable(String id) {
        return store.transaction(c->{
            MemorySite old;
            try(var s=c.prepareStatement("SELECT json FROM memory_sites WHERE id=?")){
                s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("纪念点不存在");old=decode(r.getString(1),MemorySite.class);}
            }
            MemorySite disabled=new MemorySite(old.id(),old.world(),old.x(),old.y(),old.z(),old.title(),old.eventId(),old.npcId(),false);
            try(var s=c.prepareStatement("UPDATE memory_sites SET json=? WHERE id=?")){s.setString(1,json(disabled));s.setString(2,id);s.executeUpdate();}
            return disabled;
        });
    }
    public List<MemorySite> sites() {
        return store.transaction(c->{List<MemorySite> result=new ArrayList<>();try(var s=c.createStatement();var r=s.executeQuery("SELECT json FROM memory_sites ORDER BY id")){while(r.next())result.add(decode(r.getString(1),MemorySite.class));}return List.copyOf(result);});
    }
    public WorldEvent source(String id) {
        return store.transaction(c->{try(var s=c.prepareStatement("SELECT json FROM events WHERE id=?")){s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("不存在的历史事件ID");return decode(r.getString(1),WorldEvent.class);}}});
    }
}
