package smoke;

import dev.starcore.starcore.core.event.StarCoreEventBus;
import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.war.WarModule;
import dev.starcore.starcore.module.war.WarService;
import dev.starcore.starcore.module.war.event.WarEndedEvent;
import dev.starcore.starcore.module.war.gui.WarMenu;
import dev.starcore.starcore.war.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.persistence.PersistentDataType;

/** Explicit test battlefield objects; only actual protocol clients capture and end the war. */
public final class BattlefieldPaperFixture extends ProductionStackPaperFixture implements Listener {
    private Runnable unsubscribe; private String endReason; private boolean bound;
    private UUID warId(){return UUID.fromString(getConfig().getString("warId"));}
    private NationId restored(){return NationId.fromString(getConfig().getString("restored"));}
    private WarModule wars()throws Exception{return (WarModule)service(WarService.class);}
    private BattlefieldService fields()throws Exception{return service(BattlefieldService.class);}
    private UUID stronghold(int index){return UUID.fromString(getConfig().getString("battle.hold"+index));}
    private UUID battlefield(){return UUID.fromString(getConfig().getString("battle.field"));}
    private CompletionStage<?> bind(){return main(()->{
        require(!bound,"Battle listeners already bound");bound=true;
        getServer().getPluginManager().registerEvents(this,this);
        var core=getServer().getPluginManager().getPlugin("StarCore");
        Object context=core.getClass().getMethod("context").invoke(core);
        StarCoreEventBus bus=(StarCoreEventBus)context.getClass().getMethod("eventBus").invoke(context);
        unsubscribe=bus.subscribe(WarEndedEvent.class,event->{
            if(!getConfig().contains("warId")||!event.getWar().id().equals(warId()))return;
            endReason=event.getReason().name();War war=event.getWar();
            log("BATTLE_END_EVENT",Map.of("warId",war.id(),"reason",endReason,"aggressorScore",war.aggressorWarScore(),"defenderScore",war.defenderWarScore(),"winner",war.determineWinner().orElseThrow().name(),"endedAt",war.endedAt(),"syntheticEvent",false));
        });
        for(String who:List.of("A","B","C","D")){
            var permission=player(who).addAttachment(this);permission.setPermission("starcore.war",true);
            permission.setPermission("starcore.war.end",true);permission.setPermission("starcore.war.admin",false);
            player(who).recalculatePermissions();require(!player(who).hasPermission("starcore.war.admin"),"No war administrator bypass allowed");
        }
        log("BATTLE_BOUND",Map.of("eventObserver",true,"nonOp",true,"warAdmin",false));return null;
    });}
    @EventHandler(priority=EventPriority.MONITOR)
    public void inventoryClick(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof WarMenu)||event.getRawSlot()<0||event.getRawSlot()>=event.getView().getTopInventory().getSize())return;
        var item=event.getCurrentItem();Map<String,String> actions=new TreeMap<>();
        if(item!=null&&item.hasItemMeta())for(var key:item.getItemMeta().getPersistentDataContainer().getKeys()){
            String value=item.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.STRING);if(value!=null)actions.put(key.toString(),value);
        }
        log("BATTLE_GUI_CLICK",Map.of("actor",event.getWhoClicked().getUniqueId(),"slot",event.getRawSlot(),"item",item==null?"AIR":item.getType().name(),"actions",actions,"cancelled",event.isCancelled(),"click",event.getClick().name(),"syntheticEvent",false));
    }
    private CompletionStage<?> prepare(){return main(()->{
        require(!getConfig().contains("battle.field"),"Fresh battlefield required");
        War war=wars().getWar(warId()).orElseThrow();require(war.status()==WarStatus.PREPARATION,"Native preparation boundary missing");
        require(war.aggressorWarScore()==0&&war.defenderWarScore()==0,"Initial scores must be zero");
        require(service(NationService.class).isFounder(uuid("A"),restored()),"Actual attacker founder required");
        var field=fields().createBattlefield(warId(),"Epic acceptance field",player("A").getLocation(),32,country("B"));
        getConfig().set("battle.field",field.id().toString());
        List<Map<String,Object>> holds=new ArrayList<>();
        for(int index=1;index<=2;index++){
            var hold=fields().createStronghold(field.id(),"Epic citadel "+index,player("A").getLocation().add(index,0,0),Stronghold.StrongholdType.CITADEL,country("B"),1000);
            require(hold.strategicValue()==50,"Default citadel value changed");getConfig().set("battle.hold"+index,hold.id().toString());
            holds.add(Map.of("id",hold.id(),"name",hold.name(),"owner",hold.owner().toString(),"points",hold.strategicValue(),"type",hold.type().name()));
        }
        saveConfig();log("BATTLE_PREPARED",Map.of("warId",war.id(),"field",field.id(),"holds",holds,"aggressor",restored().toString(),"defender",country("B").toString(),"fixtureObjects",true));return null;
    });}
    private CompletionStage<?> start(){return main(()->{
        require(service(NationService.class).isFounder(uuid("A"),restored()),"Actual attacker founder required");
        wars().getWarServiceImpl().startWar(warId(),restored());
        require(wars().getWar(warId()).orElseThrow().status().isCombatAllowed(),"Native combat did not start");
        log("BATTLE_STARTED",Map.of("warId",warId(),"actorNation",restored().toString(),"nativeService",true,"preparationTimerBypassedInFixture",true));return null;
    });}
    private CompletionStage<?> view(String who){return main(()->{
        wars().getWarMenu().openStrongholdMenu(player(who),battlefield());
        log("BATTLE_READ_ONLY_VIEW",Map.of("player",who,"field",battlefield()));return null;
    });}
    private CompletionStage<?> check(String expected){return main(()->{
        War war=wars().getWar(warId()).orElseThrow();boolean ended=expected.equals("ended");int score=ended?100:Integer.parseInt(expected);
        require(war.aggressorWarScore()==score&&war.defenderWarScore()==0,"Native battlefield score mismatch");
        require(war.isEnded()==ended,"Native battlefield lifecycle mismatch");
        List<Map<String,Object>> holds=new ArrayList<>();
        for(int index=1;index<=2;index++){
            var hold=fields().getStronghold(stronghold(index)).orElseThrow();boolean captured=score>=index*50;
            require(hold.owner().equals(captured?restored():country("B")),"Native stronghold owner mismatch");
            require((hold.lastCapturedAt()!=null)==captured,"Native capture time missing or fabricated");
            holds.add(Map.of("id",hold.id(),"owner",hold.owner().toString(),"capturedAt",hold.lastCapturedAt()==null?"":hold.lastCapturedAt().toString(),"points",hold.strategicValue()));
        }
        if(ended){require(war.determineWinner().orElseThrow()==War.WarWinner.AGGRESSOR,"Native score winner differs");require(wars().warCompletion(warId()).isPresent(),"Ended live projection is not a durable receipt");}
        log("BATTLE_STATE",Map.of("warId",war.id(),"expected",expected,"status",war.status().name(),"aggressorScore",war.aggressorWarScore(),"defenderScore",war.defenderWarScore(),"winner",ended?war.determineWinner().orElseThrow().name():"", "holds",holds));return null;
    });}
    private CompletionStage<?> finish(){
        CompletableFuture<Void> future=new CompletableFuture<>();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        new org.bukkit.scheduler.BukkitRunnable(){@Override public void run(){try{
            var receipt=wars().warCompletion(warId());
            if(receipt.isPresent()&&endReason!=null){
                require(endReason.equals("VICTORY_DETERMINED"),"Native end reason is not a score victory: "+endReason);
                var value=receipt.orElseThrow();log("NATIVE_WAR_ENDED",Map.of("warId",value.warId(),"endedAt",value.endedAt(),"domain",value.authorityDomainId(),"ending","native stronghold score victory","reason",endReason));cancel();future.complete(null);
            }else if(System.nanoTime()>=deadline)throw new IllegalStateException("Native victory event or durable completion missing");
        }catch(Throwable error){cancel();future.completeExceptionally(error);}}}.runTaskTimer(this,1L,2L);return future;
    }
    @Override public void onDisable(){if(unsubscribe!=null)unsubscribe.run();super.onDisable();}
    @Override protected CompletionStage<?> extraAction(String action,String[] args){return switch(action){
        case "battle-bind"->bind();case "battle-prepare"->prepare();case "battle-start"->start();case "battle-view"->view(args[1]);case "battle-check"->check(args[1]);case "battle-finish"->finish();default->super.extraAction(action,args);
    };}
}
