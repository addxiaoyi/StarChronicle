import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.territory.upgrade.*;
import dev.starcore.starcore.module.territory.upgrade.model.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.bukkit.plugin.java.JavaPlugin;
import static org.mockito.Mockito.*;

/** Runs file-only production storage in independent JVMs; never initializes a Minecraft server. */
public class TerritoryUpgradeProbe {
    private static final NationId ID = NationId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static DatabaseAwareUpgradeStateStorage storage(Path root) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(root.toFile());
        return new DatabaseAwareUpgradeStateStorage(plugin, "territory_upgrade", null, null);
    }
    static void props(Path path, Map<String,String> values) throws Exception {
        Files.createDirectories(path.getParent());
        Properties props = new Properties(); props.putAll(values);
        try(var out=Files.newOutputStream(path)){props.store(out,"offline frozen-product fixture");}
    }
    public static void main(String[] args) throws Exception {
        String phase=args[0]; Path root=Path.of(args[1]); Files.createDirectories(root);
        if(phase.equals("old-write") || phase.equals("old-change")) {
            UpgradeProgressData p=new UpgradeProgressData(ID);
            p.addExp(1000); p.spendExp(300); p.setPathLevel("removed_path",2);
            p.addActiveUpgrade("removed_path",new UpgradeProcess("removed_path",2,300,300,Instant.ofEpochSecond(1700000000),null,true));
            require(p.totalExp()==700 && p.totalExpSpent()==300,"Expected actual old spending implementation");
            Map<String,String> state=UpgradeStateCodec.encodeNation(p);
            require(!state.containsKey("_schema"),"Expected old unversioned codec");
            if(phase.equals("old-write")) props(root.resolve("territory_upgrades.dat"),state);
            else state.put(ID+".exp","900");
            props(root.resolve("upgrade/states.props"),state);
        } else if(phase.equals("upgrade")) {
            var store=storage(root);
            var p=UpgradeStateCodec.decodeNation(store.load(),ID,Set.of("basic"));
            require(p.totalExp()==1000 && p.totalExpSpent()==300,"Legacy balance migration");
            require(p.getPathLevel("removed_path")==2 && p.getActiveUpgrade("removed_path").isCompleted(),"Retain removed definitions");
            p.addExp(50);p.spendExp(200);p.setPathLevel("basic",3);p.setLastDailyReward(1795700000000L);
            store.save(UpgradeStateCodec.encodeNation(p));
        } else if(phase.equals("reopen")) {
            var store=storage(root);var state=store.load();
            var p=UpgradeStateCodec.decodeNation(state,ID,Set.of("basic"));
            require(p.totalExp()==1050 && p.totalExpSpent()==500,"Independent reopen balance");
            require(p.getPathLevel("basic")==3 && p.getPathLevel("removed_path")==2,"Independent reopen paths");
            require(p.lastDailyReward()==1795700000000L,"Independent reopen daily reward");
            store.saveAsync(state);
        } else if(phase.equals("stale")) {
            var a=storage(root);var b=storage(root);
            var first=a.load();var second=b.load();a.save(first);
            boolean refused=false;
            try{b.save(second);}catch(IllegalStateException expected){refused=true;}
            require(refused,"Reject old loaded writer");
        } else if(phase.equals("reject")) {
            boolean refused=false;
            try{storage(root).load();}catch(RuntimeException expected){refused=true;}
            require(refused,"Reject ambiguous or damaged evidence");
        } else throw new IllegalArgumentException(phase);
        System.out.println("{\"phase\":\""+phase+"\",\"verified\":true,\"server_started\":false}");
    }
}
