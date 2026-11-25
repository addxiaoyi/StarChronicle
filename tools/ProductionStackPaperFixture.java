package smoke;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import de.oliver.fancynpcs.api.*;
import de.oliver.fancynpcs.api.events.NpcInteractEvent;
import org.bukkit.event.*;
import org.bukkit.entity.EntityType;
import io.github.addxiaoyi.starchronicle.api.ChronicleService;
import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.foundation.territory.model.ChunkClaimSelection;
import org.bukkit.*;
import org.bukkit.plugin.Plugin;

/** Extra acceptance checks compiled only with the frozen production candidate stack. */
public class ProductionStackPaperFixture extends EpicCampaignPaperFixture implements Listener {
    private static final String BOUND="00000000-0000-4000-8000-000000000701", UNBOUND="00000000-0000-4000-8000-000000000702";
    private boolean cancelNpc, npcListeners;
    private int observedNpcInteractions;
    private CompletionStage<?> stackCheck(){return main(()->{
        Map<String,String> versions=new TreeMap<>();
        for(String name:List.of("StarCore","StarChronicle","FancyNpcs","WorldGuard","WorldEdit","Essentials","Vault")){
            Plugin plugin=getServer().getPluginManager().getPlugin(name);require(plugin!=null&&plugin.isEnabled(),"Production candidate plugin is unavailable: "+name);versions.put(name,plugin.getPluginMeta().getVersion());
        }
        var economy=service(dev.starcore.starcore.foundation.economy.EconomyService.class);
        require(economy instanceof dev.starcore.starcore.foundation.economy.PlayerVaultEconomyService,"Production candidate did not select external economy");
        log("STACK_LOADED",Map.of("plugins",versions,"businessEconomy",economy.getClass().getName(),"protection",service(dev.starcore.starcore.foundation.protection.ExternalProtectionService.class).bridgeStatuses()));return null;
    });}
    private CompletionStage<?> stackEconomy(String phase){return main(()->{
        var economy=service(dev.starcore.starcore.foundation.economy.EconomyService.class);
        var essentials=(com.earth2me.essentials.Essentials)getServer().getPluginManager().getPlugin("Essentials");
        if(phase.equals("first")){
            require(economy.setBalance(uuid("A"),new BigDecimal("100.00"))&&economy.setBalance(uuid("B"),new BigDecimal("20.00")),"Explicit external economy test funds failed");
            require(essentials.getUser(uuid("A")).getMoney().compareTo(new BigDecimal("100.00"))==0,"Native funds did not reach Essentials");
            require(economy.transfer(uuid("A"),uuid("B"),new BigDecimal("12.50")),"Native external economy transfer failed");
            require(!economy.transfer(uuid("A"),uuid("B"),new BigDecimal("1000.00")),"Insufficient external transfer was accepted");
        }
        Map<String,String> balances=new TreeMap<>();
        for(String who:List.of("A","B")){
            BigDecimal expected=new BigDecimal(who.equals("A")?"87.50":"32.50");
            require(economy.getBalance(uuid(who)).compareTo(expected)==0&&essentials.getUser(uuid(who)).getMoney().compareTo(expected)==0,"Native/Essentials balances differ");balances.put(who,expected.toPlainString());
        }
        log("STACK_ECONOMY",Map.of("phase",phase,"balances",balances,"provider","Essentials","testFunds",true));return null;
    });}
    private CompletionStage<?> stackProtection(String phase){return main(()->{
        World world=getServer().getWorlds().getFirst();
        var manager=com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer().get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world));
        require(manager!=null,"WorldGuard region manager unavailable");
        String id="isolated_starcore_protected";
        if(phase.equals("first")){
            require(manager.getRegion(id)==null,"Fresh WorldGuard region required");
            var region=new com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion(id,com.sk89q.worldedit.math.BlockVector3.at(257,180,257),com.sk89q.worldedit.math.BlockVector3.at(258,181,258));
            region.setFlag(com.sk89q.worldguard.protection.flags.Flags.BUILD,com.sk89q.worldguard.protection.flags.StateFlag.State.DENY);manager.addRegion(region);manager.save();
        }
        require(manager.getRegion(id)!=null,"Saved WorldGuard region was not restored");
        var bridge=service(dev.starcore.starcore.foundation.protection.ExternalProtectionService.class);
        var protectedSelection=new dev.starcore.starcore.foundation.territory.model.ChunkClaimSelection(world.getName(),16,16,16,16);
        var freeSelection=new dev.starcore.starcore.foundation.territory.model.ChunkClaimSelection(world.getName(),17,17,16,16);
        var denied=bridge.findClaimConflict(protectedSelection);var allowed=bridge.findClaimConflict(freeSelection);
        log("STACK_PROTECTION_OBSERVED",Map.of("region",id,"world",world.getName(),"denied",denied.isPresent(),"allowed",allowed.isEmpty(),"statuses",bridge.bridgeStatuses()));
        require(denied.isPresent()&&denied.get().providerName().equals("WorldGuard")&&denied.get().rangeId().equals(id),"StarCore did not recognize an actual WorldGuard protected region");
        require(allowed.isEmpty(),"Unprotected neighboring chunk was rejected");
        require(bridge.isProtectedAt(new Location(world,257,180,257))&&!bridge.isProtectedAt(new Location(world,257,64,257)),"Point protection used the whole chunk");
        var nations=service(NationService.class);var economy=service(dev.starcore.starcore.foundation.economy.EconomyService.class);
        if(phase.equals("first"))require(economy.setBalance(uuid("C"),new BigDecimal("10000.00")),"Explicit claim test funds failed");
        BigDecimal before=economy.getBalance(uuid("C"));
        var preview=nations.previewClaimSelection(uuid("C"),protectedSelection);
        require(!preview.canSubmit()&&preview.explanation().state().equals("external-protection-conflict"),"Protected claim preview was not rejected by WorldGuard");
        boolean rejected=false;try{nations.claimSelection(uuid("C"),protectedSelection);}catch(IllegalStateException expected){rejected=true;}
        require(rejected&&nations.claimAt(world.getName(),16,16).isEmpty()&&economy.getBalance(uuid("C")).compareTo(before)==0,"Rejected claim changed funds or ownership");
        if(phase.equals("first")){
            var free=nations.previewClaimSelection(uuid("C"),freeSelection);require(free.canSubmit()&&free.price().signum()>0,"Unprotected claim was not available for a real fee: "+free);
            var result=nations.claimSelection(uuid("C"),freeSelection);require(result.claimedChunks()==1,"Native claim did not commit");
            log("STACK_CLAIM_PAYMENT",Map.of("before",before.toPlainString(),"fee",result.price().toPlainString(),"after",economy.getBalance(uuid("C")).toPlainString(),"essentials",((com.earth2me.essentials.Essentials)getServer().getPluginManager().getPlugin("Essentials")).getUser(uuid("C")).getMoney().toPlainString()));
            require(economy.getBalance(uuid("C")).compareTo(before.subtract(result.price()))==0,"Native claim did not charge external funds exactly");
            getConfig().set("stack.claimBalance",economy.getBalance(uuid("C")).toPlainString());getConfig().set("stack.claimPrice",result.price().toPlainString());saveConfig();
        }
        require(nations.claimAt(world.getName(),17,16).orElseThrow().ownerId().equals(country("C").toString()),"Native claim owner was not restored");
        require(economy.getBalance(uuid("C")).compareTo(new BigDecimal(getConfig().getString("stack.claimBalance")))==0,"Native claim funds changed across restart");
        require(((com.earth2me.essentials.Essentials)getServer().getPluginManager().getPlugin("Essentials")).getUser(uuid("C")).getMoney().compareTo(economy.getBalance(uuid("C")))==0,"Claim funds did not match Essentials");
        log("STACK_CLAIMS",Map.of("phase",phase,"denied",true,"protectedOwnerAbsent",true,"owner",country("C").toString(),"world",world.getName(),"claimedX",17,"claimedZ",16,"balance",economy.getBalance(uuid("C")).toPlainString(),"price",getConfig().getString("stack.claimPrice")));
        return null;
    });}
    private CompletionStage<?> stackGeometry(){return main(()->{
        World world=getServer().getWorlds().getFirst();
        var container=com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer();
        var manager=container.get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world));
        var bridge=service(dev.starcore.starcore.foundation.protection.ExternalProtectionService.class);
        var triangle=new com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion("isolated_polygon",List.of(com.sk89q.worldedit.math.BlockVector2.at(400,400),com.sk89q.worldedit.math.BlockVector2.at(431,400),com.sk89q.worldedit.math.BlockVector2.at(400,431)),180,181);
        var allowed=new com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion("isolated_build_allowed",com.sk89q.worldedit.math.BlockVector3.at(-1,180,-1),com.sk89q.worldedit.math.BlockVector3.at(-1,180,-1));
        allowed.setFlag(com.sk89q.worldguard.protection.flags.Flags.BUILD,com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW);
        manager.addRegion(triangle);manager.addRegion(allowed);
        try{
            require(bridge.findClaimConflict(new ChunkClaimSelection(world.getName(),25,25,25,25)).orElseThrow().rangeId().equals("isolated_polygon"),"Actual polygon intersection missed");
            require(bridge.findClaimConflict(new ChunkClaimSelection(world.getName(),26,26,26,26)).isEmpty(),"Polygon bounding box caused false overlap");
            require(bridge.findClaimConflict(new ChunkClaimSelection(world.getName(),-1,-1,-1,-1)).orElseThrow().rangeId().equals("isolated_build_allowed"),"BUILD ALLOW reservation or negative boundary missed");
            require(bridge.findClaimConflict(new ChunkClaimSelection(world.getName(),0,0,0,0)).isEmpty(),"Negative boundary leaked into neighboring chunk");
            var global=new com.sk89q.worldguard.protection.regions.GlobalProtectedRegion("__global__");manager.addRegion(global);
            try{require(bridge.findClaimConflict(new ChunkClaimSelection(world.getName(),100,100,100,100)).orElseThrow().rangeId().equals("__global__"),"Explicit global reservation was ignored");}
            finally{manager.removeRegion("__global__");}
        }finally{manager.removeRegion("isolated_polygon");manager.removeRegion("isolated_build_allowed");manager.save();}
        log("STACK_GEOMETRY",Map.of("tinyHighRegion",true,"polygonNotBoundingBox",true,"negativeBoundary",true,"buildAllowReserved",true,"globalReserved",true));return null;
    });}
    private Npc createNpc(String id,String name,Location location){
        NpcData data=new NpcData(id,name,uuid("A"),name,null,location,false,true,false,false,net.kyori.adventure.text.format.NamedTextColor.WHITE,EntityType.VILLAGER,new HashMap<>(),false,5,p->{},new HashMap<>(),0f,1f,48,new HashMap<>(),false);
        Npc npc=FancyNpcsPlugin.get().getNpcAdapter().apply(data);npc.setSaveToFile(true);npc.create();FancyNpcsPlugin.get().getNpcManager().registerNpc(npc);npc.spawnForAll();return npc;
    }
    private CompletionStage<Void> awaitNpcs(){
        CompletableFuture<Void> ready=new CompletableFuture<>();
        new org.bukkit.scheduler.BukkitRunnable(){
            private final long started=System.nanoTime();
            @Override public void run(){
                if(FancyNpcsPlugin.get().getNpcManager().isLoaded()){
                    log("STACK_NPC_READY",Map.of("loaded",true));cancel();ready.complete(null);
                }else if(System.nanoTime()-started>java.util.concurrent.TimeUnit.SECONDS.toNanos(30)){
                    cancel();ready.completeExceptionally(new IllegalStateException("FancyNpcs data did not load within 30 seconds"));
                }
            }
        }.runTaskTimer(this,1L,5L);
        return ready;
    }
    private CompletionStage<?> stackNpcs(String phase){return awaitNpcs().thenCompose(ignored->main(()->{
        var manager=FancyNpcsPlugin.get().getNpcManager();require(manager.isLoaded(),"FancyNpcs data is not loaded");
        World world=getServer().getWorlds().getFirst();int y=world.getHighestBlockYAt(4,4)+1;
        player("A").teleport(new Location(world,4.5,y,4.5));
        if(phase.equals("first")){
            require(manager.getNpcById(BOUND)==null&&manager.getNpcById(UNBOUND)==null,"Fresh isolated NPC IDs required");
            createNpc(BOUND,"isolated_bound",new Location(world,6.5,y,4.5));createNpc(UNBOUND,"isolated_unbound",new Location(world,4.5,y,6.5));manager.saveNpcs(true);
        }
        Npc bound=manager.getNpcById(BOUND),unbound=manager.getNpcById(UNBOUND);
        require(bound!=null&&unbound!=null&&bound.isSaveToFile()&&unbound.isSaveToFile(),"External NPCs did not persist");
        require(bound.getData().getType()==EntityType.VILLAGER&&unbound.getData().getType()==EntityType.VILLAGER,"External NPC type changed");
        require(manager.getAllNpcs().size()==2,"Unexpected external NPC creation/deletion");
        if(!npcListeners){getServer().getPluginManager().registerEvents(this,this);npcListeners=true;}
        log("STACK_NPCS",Map.of("phase",phase,"bound",BOUND,"unbound",UNBOUND,"boundEntity",bound.getEntityId(),"unboundEntity",unbound.getEntityId(),"count",manager.getAllNpcs().size(),"createdBy","isolated acceptance fixture through FancyNpcs API"));return null;
    }));}
    @EventHandler(priority=EventPriority.HIGHEST) public void cancelInteraction(NpcInteractEvent event){if(cancelNpc&&event.getNpc().getData().getId().equals(BOUND))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR) public void observedInteraction(NpcInteractEvent event){
        if(!event.getPlayer().getUniqueId().equals(uuid("A")))return;
        observedNpcInteractions++;
        log("STACK_NPC_EVENT",Map.of("id",event.getNpc().getData().getId(),"player",event.getPlayer().getUniqueId(),"cancelled",event.isCancelled(),"interactionType",event.getInteractionType().name(),"observed",observedNpcInteractions));
    }
    private CompletionStage<?> stackNpcMode(String mode){return main(()->{cancelNpc=mode.equals("cancel");log("STACK_NPC_MODE",Map.of("cancel",cancelNpc));return null;});}
    private CompletionStage<?> stackNpcHistory(int expected,int observed){
        require(observedNpcInteractions==observed,"Expected real FancyNpcs events: "+observed+" got "+observedNpcInteractions);
        var api=getServer().getServicesManager().load(ChronicleService.class);require(api!=null,"Chronicle API unavailable");
        return api.history(country("A").toString(),uuid("A"),0,100).thenAccept(events->{
            var interactions=events.stream().filter(e->e.type().equals("NPC_INTERACT")).toList();require(interactions.size()==expected,"Expected NPC history count "+expected+" got "+interactions.size());
            require(interactions.stream().allMatch(e->e.source().equals("NPC_BRIDGE")&&e.participants().equals(Set.of(uuid("A")))&&e.facts().get("npc.id").equals("ancient-heir")&&e.facts().get("npc.binding").equals("fancynpcs:"+BOUND)),"NPC actor/binding provenance mismatch");
            log("STACK_NPC_HISTORY",Map.of("expected",expected,"observedEvents",observed,"history",interactions));
        });
    }
    private CompletionStage<?> stackFaults(){return main(()->{
        World world=getServer().getWorlds().getFirst();var editWorld=com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world);
        var container=com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer();
        var bridge=service(dev.starcore.starcore.foundation.protection.ExternalProtectionService.class);
        var free=new ChunkClaimSelection(world.getName(),18,18,16,16);
        var nations=service(NationService.class);var economy=service(dev.starcore.starcore.foundation.economy.EconomyService.class);
        BigDecimal balance=economy.getBalance(uuid("C"));
        container.unload(editWorld);
        try{
            require(container.get(editWorld)==null,"Actual WorldGuard manager did not unload");
            require(bridge.findClaimConflict(free).orElseThrow().rangeId().equals("__starcore_worldguard_unavailable__"),"Unavailable manager allowed a claim");
            var preview=nations.previewClaimSelection(uuid("C"),free);require(!preview.canSubmit()&&preview.explanation().state().equals("external-protection-conflict"),"Unavailable manager did not reach native claim gate");
            require(economy.getBalance(uuid("C")).compareTo(balance)==0,"Unavailable protection charged funds");
        }finally{container.reload();}
        require(bridge.findClaimConflict(free).isEmpty(),"Protection did not recover after real region reload");
        var essentials=(com.earth2me.essentials.Essentials)getServer().getPluginManager().getPlugin("Essentials");
        Map<String,String> funds=Map.of("A",essentials.getUser(uuid("A")).getMoney().toPlainString(),"B",essentials.getUser(uuid("B")).getMoney().toPlainString());
        var services=getServer().getServicesManager();int removed=0;
        for(var registration:List.copyOf(services.getRegistrations(net.milkbowl.vault.economy.Economy.class))){
            if(!(registration.getProvider() instanceof dev.starcore.starcore.integration.vault.VaultEconomyProvider)){services.unregister(net.milkbowl.vault.economy.Economy.class,registration.getProvider());removed++;}
        }
        require(removed>0&&!economy.transfer(uuid("A"),uuid("B"),BigDecimal.ONE),"Missing external provider allowed native transfer");
        require(essentials.getUser(uuid("A")).getMoney().compareTo(new BigDecimal(funds.get("A")))==0&&essentials.getUser(uuid("B")).getMoney().compareTo(new BigDecimal(funds.get("B")))==0,"Missing economy provider changed actual funds");
        log("STACK_FAULTS",Map.of("actualWorldGuardManagerUnload",true,"protectionRecovered",true,"removedExternalProviders",removed,"transferRejected",true,"externalBalances",funds));return null;
    });}
    @Override protected CompletionStage<?> extraAction(String action,String[] args) {
        return switch(action) {
            case "stack-check" -> stackCheck();
            case "stack-economy" -> stackEconomy(args[1]);
            case "stack-protection" -> stackProtection(args[1]);
            case "stack-geometry" -> stackGeometry();
            case "stack-npcs" -> stackNpcs(args[1]);
            case "stack-npc-mode" -> stackNpcMode(args[1]);
            case "stack-npc-history" -> stackNpcHistory(Integer.parseInt(args[1]),Integer.parseInt(args[2]));
            case "stack-faults" -> stackFaults();
            default -> super.extraAction(action,args);
        };
    }
}
