package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.ChronicleService;
import io.github.addxiaoyi.starchronicle.api.quest.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.core.QuestGateLedger;
import io.github.addxiaoyi.starchronicle.core.StoryInstance;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuestGateMenusTest {
    private final UUID viewer=UUID.randomUUID();
    private final ChronicleService service=mock(ChronicleService.class);

    private StoryInstance instance(String node){
        var choice=new StoryDefinition.Choice("继续","next",null,"",List.of());
        var start=new StoryDefinition.Node("开始",null,null,1,Map.of("continue",choice),List.of(),false);
        var next=new StoryDefinition.Node("后续",null,null,1,Map.of(),List.of(),true);
        var definition=new StoryDefinition("quest-gate-story","1","原生任务剧情",StoryDefinition.Scope.WORLD,List.of("START"),null,"start",0,Map.of("start",start,"next",next));
        return new StoryInstance("instance",definition,"world","source",node,Map.of(viewer,"nation-a"),Map.of(),Map.of(),Instant.EPOCH,Instant.EPOCH,false);
    }
    private QuestGateState.GateSummary summary(String id,UUID player,QuestRequest.Mode mode){
        return new QuestGateState.GateSummary(id,player,"await-native","native-quest",mode,"nation-a",QuestObservation.Status.ACTIVE,"native","run-"+id,"","waiting");
    }
    private QuestGateState state(String node,List<QuestGateState.GateSummary> gates){
        return new QuestGateState("instance",node,false,List.of("await-native"),gates);
    }
    private InventoryClickEvent click(Menus.Session session,boolean left,boolean shift,UUID actor){
        Inventory top=mock(Inventory.class);InventoryView view=mock(InventoryView.class);HumanEntity human=mock(HumanEntity.class);
        when(top.getHolder()).thenReturn(session);when(top.getSize()).thenReturn(54);when(view.getTopInventory()).thenReturn(top);when(human.getUniqueId()).thenReturn(actor);
        InventoryClickEvent event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(human);when(event.getRawSlot()).thenReturn(50);when(event.isLeftClick()).thenReturn(left);when(event.isShiftClick()).thenReturn(shift);return event;
    }
    private QuestGateLedger.Gate ledgerGate(String id,String node,UUID owner,boolean completed){
        var nation=new NationProof(NationProof.State.KNOWN_NATION,"nation-a",Instant.EPOCH,"native","secret-nation-evidence");
        var accepted=new AcceptedRun(id,"run-"+id,owner,"native-quest","a".repeat(64),Instant.EPOCH,nation,"native","secret-acceptance-evidence");
        var completion=completed?new CommittedCompletion(id,"run-"+id,"completion-"+id,owner,"native-quest","a".repeat(64),Instant.EPOCH,Instant.EPOCH,Instant.EPOCH,nation,nation,"native","secret-acceptance-evidence","secret-completion-evidence"):null;
        return new QuestGateLedger.Gate(id,"instance","source","story","1","f".repeat(64),"world",node,"await-native",owner,"native-quest",QuestRequest.Mode.PLAYER,"nation-a",Instant.EPOCH,"native",Instant.EPOCH,completed?QuestObservation.Status.COMMITTED:QuestObservation.Status.ACTIVE,accepted,completion,Instant.EPOCH,Instant.EPOCH,1,"safe status",true);
    }

    @Test void projectionKeepsOnlyReadOnlyIdsAndDefensivelyCopiesLists(){
        List<QuestGateLedger.Gate> gates=new ArrayList<>(List.of(ledgerGate("b","start",viewer,true),ledgerGate("a","start",viewer,false)));
        var state=QuestGateState.from("instance","start",new QuestGateLedger.View(false,gates,List.of("await-native")));
        gates.clear();assertEquals(List.of("a","b"),state.gates().stream().map(QuestGateState.GateSummary::gateId).toList());
        assertEquals("run-b",state.gates().get(1).runId());assertEquals("completion-b",state.gates().get(1).completionId());
        assertFalse(state.toString().contains("secret-"));assertFalse(state.toString().contains("a".repeat(64)));
        assertThrows(UnsupportedOperationException.class,()->state.gates().clear());
        assertThrows(UnsupportedOperationException.class,()->state.blockedEffectIds().clear());
    }
    @Test void projectionRejectsMixedInstanceOrNodeAndInconsistentReadyState(){
        var view=new QuestGateLedger.View(false,List.of(ledgerGate("a","next",viewer,false)),List.of("await-native"));
        assertThrows(IllegalArgumentException.class,()->QuestGateState.from("instance","start",view));
        assertThrows(IllegalArgumentException.class,()->new QuestGateState("instance","start",true,List.of("blocked"),List.of()));
    }
    @Test void defaultApiIsUnavailableRatherThanFabricatedReady(){
        ChronicleService defaultApi=mock(ChronicleService.class,CALLS_REAL_METHODS);
        CompletionException failure=assertThrows(CompletionException.class,()->defaultApi.questGateState("instance",viewer).toCompletableFuture().join());
        assertInstanceOf(UnsupportedOperationException.class,failure.getCause());
    }
    @Test void currentNodePageHidesOtherPlayersPersonalGateButKeepsServerFilteredCountryGate(){
        StoryInstance instance=instance("start");UUID other=UUID.randomUUID();
        var state=state("start",List.of(summary("own",viewer,QuestRequest.Mode.PLAYER),summary("private",other,QuestRequest.Mode.PLAYER),summary("country",other,QuestRequest.Mode.NATION)));
        when(service.instances(viewer)).thenReturn(CompletableFuture.completedFuture(List.of(instance)));
        when(service.questGateState("instance",viewer)).thenReturn(CompletableFuture.completedFuture(state));
        var page=Menus.questGatePage(service,viewer,"instance","start",0).toCompletableFuture().join();
        assertEquals(List.of("own","country"),page.state().gates().stream().map(QuestGateState.GateSummary::gateId).toList());
        verify(service,times(2)).instances(viewer);verify(service).questGateState("instance",viewer);verifyNoMoreInteractions(service);
    }
    @Test void staleDisplayedNodeCannotFetchNewNodeGateDetails(){
        when(service.instances(viewer)).thenReturn(CompletableFuture.completedFuture(List.of(instance("next"))));
        assertThrows(CompletionException.class,()->Menus.questGatePage(service,viewer,"instance","start",2).toCompletableFuture().join());
        verify(service).instances(viewer);verifyNoMoreInteractions(service);
    }
    @Test void nodeChangeDuringReadDiscardsTheEntirePage(){
        when(service.instances(viewer)).thenReturn(CompletableFuture.completedFuture(List.of(instance("start"))),CompletableFuture.completedFuture(List.of(instance("next"))));
        when(service.questGateState("instance",viewer)).thenReturn(CompletableFuture.completedFuture(state("start",List.of())));
        assertThrows(CompletionException.class,()->Menus.questGatePage(service,viewer,"instance","start",0).toCompletableFuture().join());
    }
    @Test void serverStateForAnotherInstanceCannotBeMixedWithPinnedText(){
        when(service.instances(viewer)).thenReturn(CompletableFuture.completedFuture(List.of(instance("start"))));
        when(service.questGateState("instance",viewer)).thenReturn(CompletableFuture.completedFuture(new QuestGateState("other","start",true,List.of(),List.of())));
        assertThrows(CompletionException.class,()->Menus.questGatePage(service,viewer,"instance","start",0).toCompletableFuture().join());
    }
    @Test void unjoinedPlayerCannotQueryArbitraryGateStates(){
        when(service.instances(viewer)).thenReturn(CompletableFuture.completedFuture(List.of()));
        CompletionException failure=assertThrows(CompletionException.class,()->Menus.questGatePage(service,viewer,"instance","start",0).toCompletableFuture().join());
        assertInstanceOf(SecurityException.class,failure.getCause());verify(service).instances(viewer);verifyNoMoreInteractions(service);
    }
    @Test void gateButtonOnlyReadsAndNeverCallsChooseJoinOrAnyMutation(){
        when(service.instances(viewer)).thenReturn(CompletableFuture.completedFuture(List.of(instance("start"))));
        when(service.questGateState("instance",viewer)).thenReturn(CompletableFuture.completedFuture(state("start",List.of(summary("own",viewer,QuestRequest.Mode.PLAYER)))));
        List<Menus.QuestGatePage> shown=new ArrayList<>();Menus.Session session=new Menus.Session(viewer);
        session.actions.put(50,Menus.questGateReadAction(service,viewer,"instance","start",shown::add,error->fail(error)));
        InventoryClickEvent event=click(session,true,false,viewer);new Menus(null).click(event);verify(event).setCancelled(true);
        assertEquals(1,shown.size());verify(service,times(2)).instances(viewer);verify(service).questGateState("instance",viewer);verifyNoMoreInteractions(service);
    }
    @Test void gateReadButtonRejectsForeignShiftAndRightClicks(){
        Menus.Session session=new Menus.Session(viewer);AtomicInteger calls=new AtomicInteger();
        session.actions.put(50,()->{calls.incrementAndGet();Menus.questGateReadAction(service,viewer,"instance","start",page->{},error->{}).run();});
        Menus menus=new Menus(null);
        for(var event:List.of(click(session,true,true,viewer),click(session,false,false,viewer),click(session,true,false,UUID.randomUUID()))){menus.click(event);verify(event).setCancelled(true);}
        assertEquals(0,calls.get());verifyNoInteractions(service);
    }
    @Test void gateDetailsPaginateWithoutTruncatingDiagnosticMessageOrClaimingDelivery(){
        String text="状态说明".repeat(4000);
        var gate=new QuestGateState.GateSummary("gate",viewer,"await-native","native-quest",QuestRequest.Mode.PLAYER,"nation-a",QuestObservation.Status.UNKNOWN,"native","run","",text);
        var details=Menus.questGateDetails(gate);assertTrue(details.size()>45);
        String reconstructed=details.stream().filter(part->part.startsWith("状态说明 · ")).map(part->part.substring(part.indexOf('\n')+1)).collect(java.util.stream.Collectors.joining());
        assertEquals(text,reconstructed);assertFalse(Menus.questGateStatus(QuestObservation.Status.ACTIVE).contains("完成"));
        assertTrue(Menus.questGateStatus(QuestObservation.Status.COMMITTED).contains("记录"));
        for(var status:QuestObservation.Status.values())assertFalse(Menus.questGateStatus(status).isBlank());
    }
    @Test void gatePageIndexClampsEmptyNegativeAndOversizedRequests(){
        assertEquals(0,Menus.questGatePageIndex(-5,0));assertEquals(0,Menus.questGatePageIndex(8,1));
        assertEquals(0,Menus.questGatePageIndex(1,36));assertEquals(1,Menus.questGatePageIndex(99,37));assertEquals(2,Menus.questGatePageIndex(2,100));
    }
}
