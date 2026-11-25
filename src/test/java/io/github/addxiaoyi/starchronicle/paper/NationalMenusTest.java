package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.core.*;
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

/** Real session click routing uses the exact node/revision carried by the displayed page. */
class NationalMenusTest {
    private final UUID viewer=UUID.randomUUID();
    private final ChronicleService service=mock(ChronicleService.class);
    private NationalVoteState state(String node,long revision,boolean completed,boolean canVote,boolean canWithdraw){
        return new NationalVoteState("council-1",node,2,"",completed,"nation-a",revision,true,canVote,canWithdraw,"checked",Map.of("aid",1L),List.of());
    }
    private StoryInstance instance(String node,StoryDefinition.VoteUnit unit){
        var choice=new StoryDefinition.Choice("援助","archive",null,"",List.of());
        var council=new StoryDefinition.Node("议会",null,null,2,Map.of("aid",choice),List.of(),false);
        var archive=new StoryDefinition.Node("档案",null,null,2,Map.of("read",choice),List.of(),false);
        var definition=new StoryDefinition("national-council","1","国家议会",StoryDefinition.Scope.WORLD,List.of("COUNCIL"),null,"council",0,Map.of("council",council,"archive",archive),unit);
        return new StoryInstance("council-1",definition,"world","source",node,Map.of(viewer,"nation-a"),Map.of(),Map.of(),Instant.EPOCH,Instant.EPOCH,false);
    }
    private InventoryClickEvent click(Menus.Session session,int raw,boolean left,boolean shift,UUID actor){
        Inventory top=mock(Inventory.class);InventoryView view=mock(InventoryView.class);HumanEntity human=mock(HumanEntity.class);
        when(top.getHolder()).thenReturn(session);when(top.getSize()).thenReturn(54);when(view.getTopInventory()).thenReturn(top);when(human.getUniqueId()).thenReturn(actor);
        InventoryClickEvent event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(human);when(event.getRawSlot()).thenReturn(raw);when(event.isLeftClick()).thenReturn(left);when(event.isShiftClick()).thenReturn(shift);return event;
    }
    @Test void displayedVoteTokenIsSentUnchangedAndRejectedTokenRefreshes(){
        var pending=new CompletableFuture<ProcessReport>();when(service.nationalVote("council-1",viewer,"council",7,"aid")).thenReturn(pending);
        AtomicInteger refresh=new AtomicInteger();List<Throwable> failures=new ArrayList<>();List<ProcessReport> successes=new ArrayList<>();
        Menus.Session session=new Menus.Session(viewer);session.actions.put(9,Menus.nationalVoteAction(service,viewer,state("council",7,false,true,true),"aid",successes::add,failures::add,refresh::incrementAndGet));
        InventoryClickEvent event=click(session,9,true,false,viewer);new Menus(null).click(event);verify(event).setCancelled(true);
        verify(service).nationalVote("council-1",viewer,"council",7,"aid");verifyNoMoreInteractions(service);assertEquals(0,refresh.get());
        pending.completeExceptionally(new IllegalStateException("stale country revision"));assertEquals(1,failures.size());assertTrue(successes.isEmpty());assertEquals(1,refresh.get());
    }
    @Test void withdrawButtonCarriesItsOwnDisplayedNodeAndRevision(){
        ProcessReport report=new ProcessReport(false,List.of("council-1"),"withdrawn");when(service.withdrawNationalVote("council-1",viewer,"council",12)).thenReturn(CompletableFuture.completedFuture(report));
        AtomicInteger refresh=new AtomicInteger();List<ProcessReport> successes=new ArrayList<>();
        Menus.Session session=new Menus.Session(viewer);session.actions.put(46,Menus.nationalWithdrawAction(service,viewer,state("council",12,false,true,true),successes::add,error->fail(error),refresh::incrementAndGet));
        new Menus(null).click(click(session,46,true,false,viewer));verify(service).withdrawNationalVote("council-1",viewer,"council",12);verifyNoMoreInteractions(service);assertEquals(List.of(report),successes);assertEquals(1,refresh.get());
    }
    @Test void topShiftAndForeignSessionCannotInvokeEitherNationalButton(){
        Menus.Session session=new Menus.Session(viewer);session.actions.put(9,Menus.nationalVoteAction(service,viewer,state("council",7,false,true,true),"aid",report->{},error->{},()->{}));session.actions.put(46,Menus.nationalWithdrawAction(service,viewer,state("council",7,false,true,true),report->{},error->{},()->{}));
        Menus menus=new Menus(null);
        for(int slot:List.of(9,46))for(InventoryClickEvent event:List.of(click(session,slot,true,true,viewer),click(session,slot,true,false,UUID.randomUUID()),click(session,slot,false,false,viewer))){menus.click(event);verify(event).setCancelled(true);}
        verifyNoInteractions(service);
    }
    @Test void completedOrReadOnlyPagesHaveNoCallableNationalMutation(){
        AtomicInteger failures=new AtomicInteger(),refresh=new AtomicInteger();
        for(var state:List.of(state("council",2,true,true,true),state("council",2,false,false,false))){
            Menus.nationalVoteAction(service,viewer,state,"aid",report->fail("read only"),error->failures.incrementAndGet(),refresh::incrementAndGet).run();
            Menus.nationalWithdrawAction(service,viewer,state,report->fail("read only"),error->failures.incrementAndGet(),refresh::incrementAndGet).run();
        }
        assertEquals(4,failures.get());assertEquals(4,refresh.get());verifyNoInteractions(service);
    }
    @Test void revisionChangeDiscardsTheFirstChoiceMapAndFetchesACoherentPage(){
        StoryInstance instance=instance("council",StoryDefinition.VoteUnit.NATION);var oldChoice=instance.definition().nodes().get("council").choices();var freshChoice=Map.of("new-option",new StoryDefinition.Choice("新选项","archive",null,"",List.of()));
        when(service.instance(viewer,"council-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(instance)));
        when(service.nationalState("council-1",viewer)).thenReturn(CompletableFuture.completedFuture(state("council",7,false,true,true)),CompletableFuture.completedFuture(state("council",8,false,true,true)),CompletableFuture.completedFuture(state("council",8,false,true,true)),CompletableFuture.completedFuture(state("council",8,false,true,true)));
        when(service.availableChoices("council-1",viewer)).thenReturn(CompletableFuture.completedFuture(oldChoice),CompletableFuture.completedFuture(freshChoice));
        Menus.NationalPage page=Menus.nationalPage(service,viewer,"council-1",1).toCompletableFuture().join();assertEquals(8,page.state().viewerRevision());assertEquals(Set.of("new-option"),page.choices().keySet());verify(service,times(2)).instance(viewer,"council-1");verify(service,times(4)).nationalState("council-1",viewer);
    }
    @Test void changedNodeNeverCombinesNewChoicesWithOldPinnedText(){
        StoryInstance old=instance("council",StoryDefinition.VoteUnit.NATION),fresh=instance("archive",StoryDefinition.VoteUnit.NATION);
        when(service.instance(viewer,"council-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(old)),CompletableFuture.completedFuture(Optional.of(fresh)));
        when(service.nationalState("council-1",viewer)).thenReturn(CompletableFuture.completedFuture(state("council",7,false,true,true)),CompletableFuture.completedFuture(state("archive",0,false,true,false)),CompletableFuture.completedFuture(state("archive",0,false,true,false)),CompletableFuture.completedFuture(state("archive",0,false,true,false)));
        when(service.availableChoices("council-1",viewer)).thenReturn(CompletableFuture.completedFuture(old.definition().nodes().get("council").choices()),CompletableFuture.completedFuture(fresh.definition().nodes().get("archive").choices()));
        Menus.NationalPage page=Menus.nationalPage(service,viewer,"council-1",1).toCompletableFuture().join();assertEquals("archive",page.instance().node());assertEquals("archive",page.state().nodeId());assertEquals(Set.of("read"),page.choices().keySet());
    }
    @Test void unjoinedViewerCannotInspectAnArbitraryInstance(){
        when(service.instance(viewer,"unknown")).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        CompletionException error=assertThrows(CompletionException.class,()->Menus.nationalPage(service,viewer,"unknown",0).toCompletableFuture().join());assertInstanceOf(SecurityException.class,error.getCause());verify(service).instance(viewer,"unknown");verifyNoMoreInteractions(service);
    }
    @Test void joinedNonRepresentativeCanReadRecordedBallotsWithoutRequestingChoices(){
        StoryInstance instance=instance("council",StoryDefinition.VoteUnit.NATION);NationalVoteState spectator=new NationalVoteState("council-1","council",2,"",false,"nation-a",7,false,false,false,"not holder",Map.of("aid",1L),List.of(new NationalVoteState.Ballot("nation-b",3,"aid","RECORDED",UUID.randomUUID(),Instant.EPOCH,"native")));
        when(service.instance(viewer,"council-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(instance)));when(service.nationalState("council-1",viewer)).thenReturn(CompletableFuture.completedFuture(spectator));
        Menus.NationalPage page=Menus.nationalPage(service,viewer,"council-1",0).toCompletableFuture().join();assertEquals(spectator,page.state());assertTrue(page.choices().isEmpty());assertEquals(1,page.state().ballots().size());verify(service,never()).availableChoices(anyString(),any(UUID.class));verify(service,never()).nationalVote(anyString(),any(UUID.class),anyString(),anyLong(),anyString());
    }
    @Test void personalVotingIsNeverSentToTheNationalInterface(){
        when(service.instance(viewer,"council-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(instance("council",StoryDefinition.VoteUnit.PLAYER))));
        CompletionException error=assertThrows(CompletionException.class,()->Menus.nationalPage(service,viewer,"council-1",0).toCompletableFuture().join());assertInstanceOf(IllegalArgumentException.class,error.getCause());verify(service).instance(viewer,"council-1");verifyNoMoreInteractions(service);
    }
    @Test void differentInstanceOrNationalityCannotBeMixedIntoAPage(){
        StoryInstance instance=instance("council",StoryDefinition.VoteUnit.NATION);NationalVoteState before=state("council",7,false,true,true);
        var other=new NationalVoteState("other","council",2,"",false,"nation-a",7,true,true,true,"",Map.of(),List.of());assertFalse(Menus.nationalPageMatches(instance,before,other));
        other=new NationalVoteState("council-1","council",2,"",false,"nation-b",7,true,true,true,"",Map.of(),List.of());assertFalse(Menus.nationalPageMatches(instance,before,other));
    }
    @Test void completedStoryCanShowTheFrozenDecisionNodeInsteadOfItsTerminalNode(){
        StoryInstance open=instance("archive",StoryDefinition.VoteUnit.NATION);StoryInstance completed=new StoryInstance(open.id(),open.definition(),open.scopeKey(),open.sourceEventId(),open.node(),open.members(),open.votes(),open.choices(),open.startedAt(),open.updatedAt(),true);
        NationalVoteState frozen=new NationalVoteState("council-1","council",2,"aid",true,"nation-a",7,true,false,false,"frozen",Map.of("aid",2L),List.of());assertTrue(Menus.nationalPageMatches(completed,frozen,frozen));
    }
}
