package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.memory.MemorySite;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Region-owned monument and veteran lifecycle; no blocks, foreign entities or chunks are modified. */
public final class MemorySites implements Listener,AutoCloseable {
    private final JavaPlugin plugin;
    private final NamespacedKey siteKey,kindKey;
    private final BiConsumer<UUID,String> library;
    private final BiConsumer<UUID,String> dialogue;
    private volatile Map<String,MemorySite> sites=Map.of();
    private volatile boolean closed;
    public MemorySites(JavaPlugin plugin,Consumer<UUID> library,BiConsumer<UUID,String> dialogue) {
        this(plugin,(viewer,event)->library.accept(viewer),dialogue);
    }
    public MemorySites(JavaPlugin plugin,BiConsumer<UUID,String> library,BiConsumer<UUID,String> dialogue) {
        this.plugin=plugin;this.library=library;this.dialogue=dialogue;
        siteKey=new NamespacedKey(plugin,"memory-site");kindKey=new NamespacedKey(plugin,"memory-kind");
    }
    public void refresh(List<MemorySite> values) {
        Map<String,MemorySite> next=new LinkedHashMap<>();for(var site:values)next.put(site.id(),site);sites=Map.copyOf(next);
        if(closed)return;
        // Resolving a world is read-only; every chunk/entity operation is deferred to its region.
        plugin.getServer().getGlobalRegionScheduler().execute(plugin,()->{for(var site:values){World world=Bukkit.getWorld(site.world());if(world!=null)plugin.getServer().getRegionScheduler().execute(plugin,world,site.chunkX(),site.chunkZ(),()->reconcile(world,site));}});
    }
    @EventHandler public void loaded(ChunkLoadEvent event) {
        if(closed)return;
        World world=event.getWorld();int x=event.getChunk().getX(),z=event.getChunk().getZ();
        for(var site:sites.values())if(site.world().equals(world.getUID())&&site.chunkX()==x&&site.chunkZ()==z)reconcile(world,site);
    }
    private void reconcile(World world,MemorySite candidate) {
        if(closed)return;
        // Re-read the immutable registry on execution: queued work cannot resurrect a disabled site.
        MemorySite site=sites.get(candidate.id());
        if(site==null||!site.world().equals(world.getUID())||site.chunkX()!=candidate.chunkX()||site.chunkZ()!=candidate.chunkZ()||!world.isChunkLoaded(site.chunkX(),site.chunkZ()))return;
        Map<String,Entity> owned=new HashMap<>();
        for(Entity entity:world.getChunkAt(site.chunkX(),site.chunkZ()).getEntities()) {
            if(!site.id().equals(entity.getPersistentDataContainer().get(siteKey,PersistentDataType.STRING)))continue;
            String kind=entity.getPersistentDataContainer().get(kindKey,PersistentDataType.STRING);
            boolean desired=site.enabled()&&(kind!=null)&&(kind.equals("monument")&&entity instanceof Interaction || kind.equals("text")&&entity instanceof TextDisplay || kind.equals("veteran")&&!site.npcId().isEmpty()&&entity instanceof Villager);
            if(!desired||owned.putIfAbsent(kind,entity)!=null)entity.remove();
        }
        if(!current(site))return;
        if(site.y()<world.getMinHeight()||site.y()+3>=world.getMaxHeight())return;
        Location location=new Location(world,site.x()+0.5,site.y(),site.z()+0.5);
        if(!owned.containsKey("text")&&current(site))owned(world.spawn(location.clone().add(0,2.6,0),TextDisplay.class,e->{
            tag(e,site,"text");e.text(Component.text(site.title()+"\n"+site.eventId()+"\n点击纪念点阅读历史"));e.setBillboard(Display.Billboard.CENTER);e.setViewRange(0.5f);
        }),site);
        if(!owned.containsKey("monument")&&current(site))owned(world.spawn(location,Interaction.class,e->{tag(e,site,"monument");e.setInteractionHeight(2.2f);e.setInteractionWidth(1.2f);e.setResponsive(true);}),site);
        // Keep the spawn centre in the SAME block/chunk: +1 at a chunk edge crosses Folia ownership.
        if(!site.npcId().isEmpty()&&!owned.containsKey("veteran")&&current(site))owned(world.spawn(location.clone().add(0.2,0,0.2),Villager.class,e->{
            tag(e,site,"veteran");e.setAI(false);e.setInvulnerable(true);e.setSilent(true);e.setCollidable(false);e.setRemoveWhenFarAway(false);e.setProfession(Villager.Profession.LIBRARIAN);e.customName(Component.text("历史见证者 · "+site.npcId()));e.setCustomNameVisible(true);
        }),site);
    }
    private boolean current(MemorySite site){return !closed&&site.enabled()&&site.equals(sites.get(site.id()));}
    private void owned(Entity entity,MemorySite site){if(!current(site))entity.remove();}
    private void tag(Entity entity,MemorySite site,String kind){entity.setPersistent(true);entity.getPersistentDataContainer().set(siteKey,PersistentDataType.STRING,site.id());entity.getPersistentDataContainer().set(kindKey,PersistentDataType.STRING,kind);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(PlayerInteractEntityEvent event) {
        if(closed||event.getHand()!=EquipmentSlot.HAND)return;
        String id=event.getRightClicked().getPersistentDataContainer().get(siteKey,PersistentDataType.STRING);
        MemorySite site=id==null?null:sites.get(id);if(site==null||!site.enabled())return;
        event.setCancelled(true);
        if(!event.getPlayer().hasPermission("starchronicle.use"))return;
        if("veteran".equals(event.getRightClicked().getPersistentDataContainer().get(kindKey,PersistentDataType.STRING)))dialogue.accept(event.getPlayer().getUniqueId(),site.npcId());
        else library.accept(event.getPlayer().getUniqueId(),site.eventId());
    }
    @Override public void close(){closed=true;HandlerList.unregisterAll(this);/* persistent owned entities reconcile at the next enable/chunk load */}
}
