package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.memory.MemorySite;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.registry.RegistryAccess;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.*;
import java.util.function.Consumer;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Paper interfaces are mocked; assertions observe the region scheduling and entity ownership boundary. */
class MemorySitesTest {
    private static final UUID WORLD_ID=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAYER_ID=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private JavaPlugin plugin;
    private World world;
    private Chunk chunk;
    private GlobalRegionScheduler global;
    private RegionScheduler region;
    private NamespacedKey siteKey,kindKey;
    private MockedStatic<Bukkit> bukkit;
    private MemorySites sites;
    private final Queue<Runnable> globals=new ArrayDeque<>(),regions=new ArrayDeque<>();
    private final List<Entity> entities=new ArrayList<>();
    private final List<UUID> libraries=new ArrayList<>();
    private final List<String> dialogues=new ArrayList<>();
    private final List<Class<?>> spawned=new ArrayList<>();
    private final List<Location> spawnLocations=new ArrayList<>();

    /** Paper 1.21.11 professions are registry values rather than enum literals. A unit test has
     * no server-provided RegistryAccess service, so bootstrap the API's real static registry
     * lookup with a test-only registry. This keeps the production setProfession call exercised. */
    @BeforeAll static void bootstrapPaperRegistry() {
        Map<NamespacedKey,Villager.Profession> professions=new HashMap<>();
        Object[] registry={null};
        RegistryAccess access=mock(RegistryAccess.class,invocation->{
            if(!invocation.getMethod().getName().equals("getRegistry"))return RETURNS_DEFAULTS.answer(invocation);
            if(registry[0]==null)registry[0]=Proxy.newProxyInstance(Registry.class.getClassLoader(),new Class<?>[]{Registry.class},(proxy,method,args)->{
                if(method.getName().equals("get")||method.getName().equals("getOrThrow")) {
                    NamespacedKey key=NamespacedKey.fromString(args[0].toString());
                    return professions.computeIfAbsent(key,k->(Villager.Profession)Proxy.newProxyInstance(Villager.Profession.class.getClassLoader(),new Class<?>[]{Villager.Profession.class},(value,member,parameters)->switch(member.getName()) {
                        case "getKey" -> k;
                        case "name" -> k.getKey().toUpperCase(Locale.ROOT);
                        case "translationKey" -> "entity.minecraft.villager."+k.getKey();
                        case "toString" -> k.toString();
                        case "hashCode" -> System.identityHashCode(value);
                        case "equals" -> value==parameters[0];
                        case "ordinal","compareTo" -> 0;
                        default -> throw new UnsupportedOperationException("Unexpected profession method: "+member);
                    }));
                }
                return switch(method.getName()) {
                    case "stream","keyStream" -> java.util.stream.Stream.empty();
                    case "iterator" -> Collections.emptyIterator();
                    case "size" -> 0;
                    case "toString" -> "MemorySitesTest profession registry";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy==args[0];
                    default -> throw new UnsupportedOperationException("Unexpected registry method: "+method);
                };
            });
            return registry[0];
        });
        try(MockedStatic<RegistryAccess> lookup=mockStatic(RegistryAccess.class)) {
            lookup.when(RegistryAccess::registryAccess).thenReturn(access);
            assertEquals(NamespacedKey.minecraft("librarian"),Villager.Profession.LIBRARIAN.getKey());
        }
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    @BeforeEach void setup(){
        plugin=mock(JavaPlugin.class);Server server=mock(Server.class);global=mock(GlobalRegionScheduler.class);region=mock(RegionScheduler.class);
        when(plugin.getName()).thenReturn("StarChronicle");when(plugin.namespace()).thenReturn("starchronicle");when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(global);when(server.getRegionScheduler()).thenReturn(region);
        world=mock(World.class);chunk=mock(Chunk.class);when(world.getUID()).thenReturn(WORLD_ID);when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(-1,-2)).thenReturn(true);when(world.getChunkAt(-1,-2)).thenReturn(chunk);
        when(chunk.getX()).thenReturn(-1);when(chunk.getZ()).thenReturn(-2);when(chunk.getWorld()).thenReturn(world);
        when(chunk.getEntities()).thenAnswer(i->entities.toArray(Entity[]::new));
        doAnswer(i->{globals.add(i.getArgument(1));return null;}).when(global).execute(eq(plugin),any(Runnable.class));
        doAnswer(i->{regions.add(i.getArgument(4));return null;}).when(region).execute(eq(plugin),eq(world),eq(-1),eq(-2),any(Runnable.class));
        doAnswer(i->{Class<? extends Entity> type=i.getArgument(1);Entity entity=create(type,null,null);spawned.add(type);spawnLocations.add(((Location)i.getArgument(0)).clone());((Consumer<Entity>)i.getArgument(2)).accept(entity);return entity;})
                .when(world).spawn(any(Location.class),any(Class.class),any(Consumer.class));
        bukkit=mockStatic(Bukkit.class);bukkit.when(()->Bukkit.getWorld(WORLD_ID)).thenReturn(world);
        siteKey=new NamespacedKey(plugin,"memory-site");kindKey=new NamespacedKey(plugin,"memory-kind");
        sites=new MemorySites(plugin,(UUID id)->libraries.add(id),(player,npc)->dialogues.add(player+"/"+npc));
    }
    @AfterEach void tearDown(){try{if(sites!=null)sites.close();}finally{if(bukkit!=null)bukkit.close();}}
    private MemorySite site(boolean enabled){return new MemorySite("siege",WORLD_ID,-1,70,-17,"围城纪念点","event:siege","siege-veteran",enabled);}
    private <T extends Entity>T create(Class<T> type,String owner,String kind){
        T entity=mock(type);PersistentDataContainer data=mock(PersistentDataContainer.class);Map<NamespacedKey,String> values=new HashMap<>();
        when(entity.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(NamespacedKey.class),eq(PersistentDataType.STRING))).thenAnswer(i->values.get(i.getArgument(0)));
        doAnswer(i->{values.put(i.getArgument(0),i.getArgument(2));return null;}).when(data).set(any(NamespacedKey.class),eq(PersistentDataType.STRING),anyString());
        if(owner!=null)values.put(siteKey,owner);if(kind!=null)values.put(kindKey,kind);
        entities.add(entity);doAnswer(i->{entities.remove(entity);return null;}).when(entity).remove();return entity;
    }
    private void flush(){while(!globals.isEmpty())globals.remove().run();while(!regions.isEmpty())regions.remove().run();}
    private long live(Class<?> type){return entities.stream().filter(type::isInstance).count();}
    private PlayerInteractEntityEvent interaction(Entity entity,boolean permission,EquipmentSlot hand){
        Player player=mock(Player.class);when(player.getUniqueId()).thenReturn(PLAYER_ID);when(player.hasPermission("starchronicle.use")).thenReturn(permission);
        PlayerInteractEntityEvent event=mock(PlayerInteractEntityEvent.class);when(event.getPlayer()).thenReturn(player);when(event.getRightClicked()).thenReturn(entity);when(event.getHand()).thenReturn(hand);return event;
    }

    @Test void refreshDefersEveryChunkAndEntityMutationToTheCorrectRegion(){
        sites.refresh(List.of(site(true)));
        assertEquals(1,globals.size());assertTrue(regions.isEmpty());assertTrue(spawned.isEmpty());verify(world,never()).getChunkAt(anyInt(),anyInt());
        globals.remove().run();assertEquals(1,regions.size());assertTrue(spawned.isEmpty());verify(world,never()).isChunkLoaded(anyInt(),anyInt());
        regions.remove().run();assertEquals(3,spawned.size());assertEquals(1,live(TextDisplay.class));assertEquals(1,live(Interaction.class));assertEquals(1,live(Villager.class));
        verify(region).execute(eq(plugin),eq(world),eq(-1),eq(-2),any(Runnable.class));
        for(Entity entity:entities){verify(entity).setPersistent(true);assertEquals("siege",entity.getPersistentDataContainer().get(siteKey,PersistentDataType.STRING));}
        verify((Villager)entities.stream().filter(Villager.class::isInstance).findFirst().orElseThrow()).setProfession(Villager.Profession.LIBRARIAN);
    }

    @Test void reconcileRemovesOnlyOwnedDuplicatesAndWrongKindsAndDoesNotRespawnValidEntities(){
        TextDisplay original=create(TextDisplay.class,"siege","text"),duplicate=create(TextDisplay.class,"siege","text");
        Entity wrongKind=create(Entity.class,"siege","monument");Villager foreign=create(Villager.class,null,null);TextDisplay otherSite=create(TextDisplay.class,"other-site","text");
        sites.refresh(List.of(site(true)));flush();
        verify(duplicate).remove();verify(wrongKind).remove();verify(original,never()).remove();verify(foreign,never()).remove();verify(otherSite,never()).remove();
        assertTrue(entities.containsAll(List.of(original,foreign,otherSite)));assertEquals(2,spawned.size());
        int count=entities.size();sites.refresh(List.of(site(true)));flush();assertEquals(count,entities.size());assertEquals(2,spawned.size());
        verify(foreign,never()).setAI(anyBoolean());verify(foreign,never()).customName(any());verify(foreign,never()).setPersistent(anyBoolean());
    }

    @Test void disabledTombstoneCleansOwnedEntitiesAndLeavesExternalEntitiesUntouched(){
        Interaction own=create(Interaction.class,"siege","monument");Villager foreign=create(Villager.class,null,null);Villager otherSite=create(Villager.class,"another","veteran");
        sites.refresh(List.of(site(false)));flush();verify(own).remove();verify(foreign,never()).remove();verify(otherSite,never()).remove();
        assertEquals(List.of(foreign,otherSite),entities);assertTrue(spawned.isEmpty());
        verify(foreign,never()).setAI(anyBoolean());verify(foreign,never()).customName(any());verify(foreign,never()).setPersistent(anyBoolean());
    }

    @Test void queuedEnabledRefreshCannotResurrectARecentlyDisabledSite(){
        sites.refresh(List.of(site(true)));globals.remove().run();assertEquals(1,regions.size());
        sites.refresh(List.of(site(false)));regions.remove().run();flush();
        assertTrue(spawned.isEmpty());assertTrue(entities.isEmpty());
    }

    @Test void queuedReconciliationAndFutureRefreshCannotSpawnAfterClose(){
        sites.refresh(List.of(site(true)));globals.remove().run();sites.close();regions.remove().run();
        sites.refresh(List.of(site(true)));flush();assertTrue(spawned.isEmpty());assertTrue(entities.isEmpty());
    }

    @Test void closeDuringChunkInspectionStopsAllSubsequentSpawns(){
        when(chunk.getEntities()).thenAnswer(i->{sites.close();return new Entity[0];});
        sites.refresh(List.of(site(true)));flush();assertTrue(spawned.isEmpty());assertTrue(entities.isEmpty());
    }

    @Test void disableDuringChunkInspectionStopsAllSubsequentSpawns(){
        when(chunk.getEntities()).thenAnswer(i->{sites.refresh(List.of(site(false)));return new Entity[0];});
        sites.refresh(List.of(site(true)));globals.remove().run();regions.remove().run();
        assertTrue(spawned.isEmpty());assertTrue(entities.isEmpty());
        // The new refresh is intentionally left queued; the stale task must already be harmless.
        globals.clear();regions.clear();
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    @Test void closeDuringSpawnRemovesTheJustCreatedEntityAndStopsRemainingKinds(){
        doAnswer(i->{Entity entity=create(TextDisplay.class,null,null);spawned.add(TextDisplay.class);((Consumer<Entity>)i.getArgument(2)).accept(entity);sites.close();return entity;})
                .when(world).spawn(any(Location.class),eq(TextDisplay.class),any(Consumer.class));
        sites.refresh(List.of(site(true)));flush();assertEquals(List.of(TextDisplay.class),spawned);assertTrue(entities.isEmpty());
    }

    @Test void unloadedChunkIsNeverLoadedForMemoryReconciliation(){
        when(world.isChunkLoaded(-1,-2)).thenReturn(false);sites.refresh(List.of(site(true)));flush();
        verify(world,never()).getChunkAt(anyInt(),anyInt());assertTrue(spawned.isEmpty());
    }

    @Test void chunkLoadReconcilesOnlyMatchingRegisteredChunk(){
        sites.refresh(List.of(site(true)));globals.clear();
        Chunk other=mock(Chunk.class);when(other.getX()).thenReturn(0);when(other.getZ()).thenReturn(-2);
        ChunkLoadEvent unrelated=mock(ChunkLoadEvent.class);when(unrelated.getWorld()).thenReturn(world);when(unrelated.getChunk()).thenReturn(other);
        sites.loaded(unrelated);assertTrue(spawned.isEmpty());
        ChunkLoadEvent matching=mock(ChunkLoadEvent.class);when(matching.getWorld()).thenReturn(world);when(matching.getChunk()).thenReturn(chunk);
        sites.loaded(matching);assertEquals(3,spawned.size());assertTrue(globals.isEmpty());assertTrue(regions.isEmpty());
    }

    @Test void interactionRequiresUsePermissionBeforeOpeningHistoryOrNpcDialogue(){
        sites.refresh(List.of(site(true)));globals.clear();Interaction monument=create(Interaction.class,"siege","monument");Villager veteran=create(Villager.class,"siege","veteran");
        PlayerInteractEntityEvent denied=interaction(monument,false,EquipmentSlot.HAND);sites.interact(denied);verify(denied).setCancelled(true);assertTrue(libraries.isEmpty());
        sites.interact(interaction(monument,true,EquipmentSlot.HAND));assertEquals(List.of(PLAYER_ID),libraries);assertTrue(dialogues.isEmpty());
        sites.interact(interaction(veteran,true,EquipmentSlot.HAND));assertEquals(List.of(PLAYER_ID+"/siege-veteran"),dialogues);
    }

    @Test void offhandUnmarkedDisabledAndClosedInteractionsLeaveExternalEntityBehaviorAlone(){
        sites.refresh(List.of(site(true)));globals.clear();Entity foreign=create(Entity.class,null,null);Interaction own=create(Interaction.class,"siege","monument");
        var external=interaction(foreign,true,EquipmentSlot.HAND);sites.interact(external);verify(external,never()).setCancelled(anyBoolean());
        var offhand=interaction(own,true,EquipmentSlot.OFF_HAND);sites.interact(offhand);verify(offhand,never()).setCancelled(anyBoolean());
        sites.refresh(List.of(site(false)));globals.clear();var disabled=interaction(own,true,EquipmentSlot.HAND);sites.interact(disabled);verify(disabled,never()).setCancelled(anyBoolean());
        sites.close();var closed=interaction(own,true,EquipmentSlot.HAND);sites.interact(closed);verify(closed,never()).setCancelled(anyBoolean());
        assertTrue(libraries.isEmpty());assertTrue(dialogues.isEmpty());verify(foreign,never()).remove();
    }

    @Test void siteWithoutNpcCreatesOnlyTheLibraryDisplayAndHitbox(){
        MemorySite plain=new MemorySite("siege",WORLD_ID,-1,70,-17,"史册","event:siege","",true);
        sites.refresh(List.of(plain));flush();assertEquals(2,spawned.size());assertEquals(0,live(Villager.class));
    }

    @Test void everySpawnCenterStaysInItsOwningChunkAtNegativeChunkEdges(){
        MemorySite edge=site(true);sites.refresh(List.of(edge));flush();assertEquals(3,spawnLocations.size());
        for(Location location:spawnLocations){assertSame(world,location.getWorld());assertEquals(edge.chunkX(),location.getBlockX()>>4);assertEquals(edge.chunkZ(),location.getBlockZ()>>4);}
    }

    @Test void worldHeightBoundsPreventPartialMemorySitesFromSpawning(){
        for(int y:List.of(-65,317,320)){
            MemorySite invalid=new MemorySite("siege",WORLD_ID,-1,y,-17,"史册","event:siege","siege-veteran",true);
            sites.refresh(List.of(invalid));flush();assertTrue(spawned.isEmpty());assertTrue(entities.isEmpty());
        }
    }

    @Test void libraryInteractionPassesTheExactCommittedSourceEventId(){
        sites.close();List<String> evidence=new ArrayList<>();
        sites=new MemorySites(plugin,(player,event)->{libraries.add(player);evidence.add(event);},(player,npc)->dialogues.add(npc));
        sites.refresh(List.of(site(true)));globals.clear();Interaction monument=create(Interaction.class,"siege","monument");
        sites.interact(interaction(monument,true,EquipmentSlot.HAND));assertEquals(List.of(PLAYER_ID),libraries);assertEquals(List.of("event:siege"),evidence);assertTrue(dialogues.isEmpty());
    }

    @Test void veteranObservationRetainsTheEventPlayerAndExpiresWhenTheSiteChanges(){
        sites.close();List<NpcHooks.Interaction> observations=new ArrayList<>();
        sites=new MemorySites(plugin,(player,event)->libraries.add(player),observed->observations.add(observed));
        sites.refresh(List.of(site(true)));globals.clear();Villager veteran=create(Villager.class,"siege","veteran");
        var event=interaction(veteran,true,EquipmentSlot.HAND);sites.interact(event);
        assertEquals(1,observations.size());assertSame(event.getPlayer(),observations.getFirst().player());
        assertEquals("siege-veteran",observations.getFirst().npcId());assertTrue(observations.getFirst().current().getAsBoolean());
        sites.refresh(List.of(site(false)));assertFalse(observations.getFirst().current().getAsBoolean());
        sites.refresh(List.of(site(true)));assertFalse(observations.getFirst().current().getAsBoolean());
        sites.close();assertFalse(observations.getFirst().current().getAsBoolean());
    }
}
