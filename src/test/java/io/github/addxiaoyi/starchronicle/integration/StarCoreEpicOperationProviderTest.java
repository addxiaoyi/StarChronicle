package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.epic.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Native receipt shapes mirror the audited StarCore candidate; corrupt fields must fail closed. */
class StarCoreEpicOperationProviderTest {
    static final String DOMAIN="00000000-0000-0000-0000-000000000099";
    static final String NA="00000000-0000-0000-0000-000000000011", NB="00000000-0000-0000-0000-000000000012";
    static final UUID ACTOR=UUID.fromString("00000000-0000-0000-0000-000000000001"), WAR=UUID.randomUUID();
    static final Instant NOW=Instant.parse("2026-11-25T00:00:00Z");
    public static class NativeJournal {
        Object raw;String domain=DOMAIN;int writes;
        public String authorityDomainId(){return domain;}
        public Optional<Object> transferReceipt(String id){return Optional.ofNullable(raw);}
        public Optional<Object> allianceInviteReceipt(UUID id){return Optional.ofNullable(raw);}
        public Optional<Object> allianceAcceptanceReceipt(UUID id){return Optional.ofNullable(raw);}
        public Optional<Object> foundingReceipt(UUID id){return Optional.ofNullable(raw);}
        public Optional<Object> warDeclaration(UUID id){return Optional.ofNullable(raw);}
        public Optional<Object> getWar(UUID id){return Optional.ofNullable(raw);}
        public Optional<Object> warCompletion(UUID id){return Optional.ofNullable(raw);}
        public Optional<Object> getCompletionRecord(UUID id){return Optional.ofNullable(raw);}
    }
    public record Resource(String transactionId,String authorityDomainId,String payerNationId,String receiverNationId,
                           Map<String,Long> resources,String category,String description,Instant appliedAt) {}
    public record Invite(UUID operationId,UUID actorId,String inviter,String invited,String authorityDomainId,Instant committedAt) {}
    public record Acceptance(UUID operationId,UUID invitationOperationId,UUID actorId,String inviter,String acceptor,String authorityDomainId,Instant committedAt) {}
    public record Founding(UUID operationId,String nationId,UUID founderId,String nationName,String authorityDomainId,Instant committedAt) {}
    public record Declaration(UUID operationId,UUID warId,String actorId,String aggressor,String defender,Instant declaredAt) {}
    public record Ended(UUID warId,UUID declarationOperationId,String aggressor,String defender,String authorityDomainId,Instant declaredAt,Instant endedAt) {}
    public record Completion(UUID recordId,String dungeonId,UUID playerId,String nationId,boolean isSuccess,Instant completedAt) {}
    private StarCoreEpicOperationProvider provider(NativeJournal journal,EpicOperation.Type type) {
        var bridge=mock(StarCoreBridge.class);when(bridge.service(anyString())).thenReturn(journal);
        doAnswer(call->{call.getArgument(0,Runnable.class).run();return null;}).when(bridge).global(any(),any());
        return new StarCoreEpicOperationProvider(bridge,type);
    }
    private EpicOperation operation(EpicOperation.Type type,String target,String reference) {
        String permission=switch(type){case RESOURCE_AID->"starcore.resource";case DIPLOMACY_INVITE,DIPLOMACY_ACCEPT->"starcore.nation.ally.add";
            case NATION_FOUNDING->"starcore.nation.create";case WAR_COMMIT->"starcore.nation.war.declare";default->"starchronicle.use";};
        return new EpicOperation("op:"+type,"heir-strategy","instance","source","phase",type,ACTOR,NA,target,reference,"a".repeat(64),permission,NOW);
    }
    private EpicObservation read(StarCoreEpicOperationProvider provider,EpicOperation op){return provider.lookup(op).toCompletableFuture().join();}
    private void rejects(StarCoreEpicOperationProvider provider,EpicOperation op){assertNotEquals(EpicObservation.Status.COMMITTED,read(provider,op).status());}

    @Test void resourceReceiptBindsTransactionBothNationsQuantitiesAndDescription() {
        var journal=new NativeJournal();var op=operation(EpicOperation.Type.RESOURCE_AID,NB,"agricultural:10");var provider=provider(journal,op.type());
        String id=StarCoreEpicOperationProvider.nativeId(op).toString(),desc=StarCoreEpicOperationProvider.resourceDescription(op);
        journal.raw=new Resource(id,DOMAIN,NA,NB,Map.of("agricultural",10L),"OTHER",desc,NOW.plusSeconds(1));
        assertEquals(EpicObservation.Status.COMMITTED,read(provider,op).status());
        for(Object corrupt:List.of(
                new Resource(UUID.randomUUID().toString(),DOMAIN,NA,NB,Map.of("agricultural",10L),"OTHER",desc,NOW),
                new Resource(id,DOMAIN,NB,NA,Map.of("agricultural",10L),"OTHER",desc,NOW),
                new Resource(id,DOMAIN,NA,NB,Map.of("agricultural",9L),"OTHER",desc,NOW),
                new Resource(id,DOMAIN,NA,NB,Map.of("agricultural",10L),"OTHER","unrelated transfer",NOW),
                new Resource(id,NB,NA,NB,Map.of("agricultural",10L),"OTHER",desc,NOW),
                new Resource(id,DOMAIN,NA,NB,Map.of("agricultural",10L),"OTHER",desc,NOW.minusSeconds(1)))) {
            journal.raw=corrupt;rejects(provider,op);
        }
    }
    @Test void allianceAcceptanceMustBindNativeInvitationAndAcceptor() {
        var journal=new NativeJournal();UUID invitation=UUID.randomUUID();var op=operation(EpicOperation.Type.DIPLOMACY_ACCEPT,NB,invitation.toString());
        var provider=provider(journal,op.type());UUID id=StarCoreEpicOperationProvider.nativeId(op);
        journal.raw=new Acceptance(id,invitation,ACTOR,NB,NA,DOMAIN,NOW);assertEquals(EpicObservation.Status.COMMITTED,read(provider,op).status());
        journal.raw=new Acceptance(id,UUID.randomUUID(),ACTOR,NB,NA,DOMAIN,NOW);rejects(provider,op);
        journal.raw=new Acceptance(id,invitation,ACTOR,NA,NB,DOMAIN,NOW);rejects(provider,op);
        journal.raw=new Acceptance(id,invitation,UUID.randomUUID(),NB,NA,DOMAIN,NOW);rejects(provider,op);
    }
    @Test void foundingUsesThePinnedUtf8NationNameAndNewNationId() {
        var journal=new NativeJournal();var op=operation(EpicOperation.Type.NATION_FOUNDING,NB,EpicContract.foundingReference("星辰帝国"));var provider=provider(journal,op.type());
        UUID id=StarCoreEpicOperationProvider.nativeId(op);
        journal.raw=new Founding(id,NB,ACTOR,"星辰帝国",DOMAIN,NOW);assertEquals(EpicObservation.Status.COMMITTED,read(provider,op).status());
        journal.raw=new Founding(id,NA,ACTOR,"星辰帝国",DOMAIN,NOW);rejects(provider,op);
        journal.raw=new Founding(id,NB,ACTOR,"另一帝国",DOMAIN,NOW);rejects(provider,op);
        assertThrows(IllegalArgumentException.class,()->EpicContract.foundingName("NATION_FOUNDING_V1:Name:culture"));
    }
    @Test void declarationLinksTheWarIdAndFinalReceiptUsesEndTimeAndSameSides() {
        var journal=new NativeJournal();var declaration=operation(EpicOperation.Type.WAR_COMMIT,NB,"WAR_DECLARATION_V1");var provider=provider(journal,declaration.type());
        journal.raw=new Declaration(StarCoreEpicOperationProvider.nativeId(declaration),WAR,NA,NA,NB,NOW);
        assertEquals(WAR.toString(),read(provider,declaration).receipt().nativeReceiptId());
        var finale=operation(EpicOperation.Type.FINAL_BATTLE,NB,WAR.toString());
        journal.raw=new Ended(WAR,WAR,NA,NB,DOMAIN,NOW.minusSeconds(30),NOW.plusSeconds(30));
        assertEquals(NOW.plusSeconds(30),read(provider,finale).receipt().appliedAt());
        journal.raw=new Ended(WAR,WAR,NB,NA,DOMAIN,NOW,NOW.plusSeconds(30));rejects(provider,finale);
        journal.raw=new Ended(WAR,UUID.randomUUID(),NA,NB,DOMAIN,NOW,NOW.plusSeconds(30));rejects(provider,finale);
    }
    @Test void dungeonIsReadOnlyAndRequiresExactSuccessfulDurableRecord() {
        var journal=new NativeJournal();UUID record=UUID.randomUUID();var op=operation(EpicOperation.Type.DUNGEON_COMPLETION,"COOPERATIVE_RELIC",record.toString());var provider=provider(journal,op.type());
        journal.raw=new Completion(record,op.target(),ACTOR,NA,true,NOW.plusSeconds(10));
        assertEquals(EpicObservation.Status.COMMITTED,provider.apply(op).toCompletableFuture().join().status());
        journal.raw=new Completion(record,op.target(),ACTOR,NB,true,NOW.plusSeconds(10));rejects(provider,op);
        journal.raw=new Completion(record,op.target(),ACTOR,NA,false,NOW.plusSeconds(10));rejects(provider,op);
        journal.domain=NB;assertFalse(provider.supports(op));rejects(provider,op);
    }

    @Test void unregisterBetweenLookupAndOwnerCallbackPreventsNativeInvocation() {
        var journal=new NativeJournal();var bridge=mock(StarCoreBridge.class);
        when(bridge.service(anyString())).thenReturn(journal);
        doAnswer(call->{call.getArgument(0,Runnable.class).run();return null;}).when(bridge).global(any(),any());
        List<java.util.function.Consumer<org.bukkit.entity.Player>> pending=new ArrayList<>();
        doAnswer(call->{pending.add(call.getArgument(1));return null;}).when(bridge).ownedPlayer(eq(ACTOR),any(),any());
        var provider=new StarCoreEpicOperationProvider(bridge,EpicOperation.Type.RESOURCE_AID);
        var lease=new java.util.concurrent.atomic.AtomicBoolean(true);
        var result=provider.apply(operation(EpicOperation.Type.RESOURCE_AID,NB,"agricultural:10"),lease::get).toCompletableFuture();
        assertFalse(result.isDone());assertEquals(1,pending.size());lease.set(false);
        pending.getFirst().accept(mock(org.bukkit.entity.Player.class));
        assertEquals(EpicObservation.Status.UNKNOWN,result.join().status());
        verify(bridge,never()).refreshContext(any(),any());
    }
}
