package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.Delivery;
import io.github.addxiaoyi.starchronicle.api.CoreGateway.DeliveryResult;
import java.util.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Permission, replay and uncertain-mutation boundary contracts; no management commands. */
class StarCoreAuthorityProviderTest {
    UUID playerId;String own,target;Player player;NativeFixture authority;StarCoreAuthorityProvider provider;
    @BeforeEach void setup() {
        playerId=UUID.randomUUID();own=UUID.randomUUID().toString();target="abcdef12-2222-4222-8222-222222222222";
        player=mock(Player.class);when(player.getUniqueId()).thenReturn(playerId);when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("starchronicle.use")).thenReturn(true);when(player.hasPermission(StarCoreAuthorityProvider.PERMISSION)).thenReturn(true);
        authority=new NativeFixture(own);provider=new StarCoreAuthorityProvider(id->player,()->authority);
    }
    Delivery delivery(){return new Delivery("outcome-1","instance-1","authority","diplomacy.invite",target,playerId,own,StarCoreAuthorityProvider.PERMISSION);}
    DeliveryResult apply(Delivery delivery){return provider.apply(delivery).toCompletableFuture().join();}

    @Test void committedNativeSuccessRequiresEveryLiveNationalCheckUnderAuthorityMonitor() {
        assertTrue(provider.available());assertEquals(DeliveryResult.APPLIED,apply(delivery()));assertEquals(1,authority.invocations);
        assertEquals(List.of("nationOf","exists:"+own,"exists:"+target,"founder","policy","invite"),authority.calls);
    }
    @Test void changedNationalityRejectsEvenWhenStoryInstanceRetainsOldNation() {
        authority.current=UUID.randomUUID().toString();assertEquals(DeliveryResult.REJECTED,apply(delivery()));assertEquals(0,authority.invocations);
    }
    @Test void authoritativeFounderCheckRejectsRoleLabelOrBukkitPermissionAlone() {
        authority.founder=false;assertEquals(DeliveryResult.REJECTED,apply(delivery()));assertEquals(0,authority.invocations);
        assertFalse(authority.calls.contains("policy"));
    }
    @Test void actualStarCoreNationalPolicyDenialRejectsBeforeMutation() {
        authority.policy=false;assertEquals(DeliveryResult.REJECTED,apply(delivery()));assertEquals(0,authority.invocations);
    }
    @Test void liveBukkitPermissionRevocationRejectsBeforeNationalAuthorityAccess() {
        when(player.hasPermission(StarCoreAuthorityProvider.PERMISSION)).thenReturn(false);
        assertEquals(DeliveryResult.REJECTED,apply(delivery()));assertTrue(authority.calls.isEmpty());
    }
    @Test void missingChronicleUsePermissionRejectsBeforeMutation() {
        when(player.hasPermission("starchronicle.use")).thenReturn(false);assertEquals(DeliveryResult.REJECTED,apply(delivery()));assertEquals(0,authority.invocations);
    }
    @Test void departedOrDeletedTargetNationRejectsBeforeMutation() {
        authority.missing=target;assertEquals(DeliveryResult.REJECTED,apply(delivery()));assertEquals(0,authority.invocations);
    }
    @Test void nativeBusinessRejectionIsConclusiveRejected() {
        authority.accept=false;assertEquals(DeliveryResult.REJECTED,apply(delivery()));assertEquals(1,authority.invocations);
    }
    @Test void failureAfterInvokingNativeMutationIsUnknownAndNeverBlindlyReinvoked() {
        authority.mutationFailure=true;Delivery d=delivery();
        assertEquals(DeliveryResult.UNKNOWN,apply(d));assertEquals(DeliveryResult.UNKNOWN,apply(d));assertEquals(1,authority.invocations);
    }
    @Test void failureBeforeMutationDoesNotClaimAppliedOrUnknownMutation() {
        authority.preconditionFailure=true;assertEquals(DeliveryResult.REJECTED,apply(delivery()));assertEquals(0,authority.invocations);
    }
    @Test void sameOutcomeAndPayloadReturnsSameCompletedReceiptWithoutSecondMutation() {
        Delivery d=delivery();assertEquals(DeliveryResult.APPLIED,apply(d));assertEquals(DeliveryResult.APPLIED,apply(d));assertEquals(1,authority.invocations);
    }
    @Test void sameOutcomeWithDifferentPayloadIsRejectedRatherThanReplayed() {
        assertEquals(DeliveryResult.APPLIED,apply(delivery()));
        Delivery collision=new Delivery("outcome-1","instance-1","authority","diplomacy.invite",UUID.randomUUID().toString(),playerId,own,StarCoreAuthorityProvider.PERMISSION);
        assertEquals(DeliveryResult.REJECTED,apply(collision));assertEquals(1,authority.invocations);
    }
    @Test void unknownEffectCommandsNoncanonicalValuesAndMissingPermissionAreRejected() {
        for(Delivery d:List.of(
                new Delivery("a","i","command","diplomacy.invite",target,playerId,own,StarCoreAuthorityProvider.PERMISSION),
                new Delivery("b","i","authority","diplomacy.force-ally",target,playerId,own,StarCoreAuthorityProvider.PERMISSION),
                new Delivery("c","i","authority","diplomacy.invite",target.toUpperCase(Locale.ROOT),playerId,own,StarCoreAuthorityProvider.PERMISSION),
                new Delivery("d","i","authority","diplomacy.invite","/op attacker",playerId,own,StarCoreAuthorityProvider.PERMISSION),
                new Delivery("e","i","authority","diplomacy.invite",target,playerId,own,""),
                new Delivery("f","i","authority","diplomacy.invite",own,playerId,own,StarCoreAuthorityProvider.PERMISSION)))assertEquals(DeliveryResult.REJECTED,apply(d));
        assertEquals(DeliveryResult.REJECTED,apply(null));assertEquals(0,authority.invocations);
    }
    @Test void offlineActorAndUnavailableNativeServicesFailClosed() {
        when(player.isOnline()).thenReturn(false);assertEquals(DeliveryResult.REJECTED,apply(delivery()));
        StarCoreAuthorityProvider missing=new StarCoreAuthorityProvider(id->player,()->null);assertFalse(missing.available());
        assertEquals(DeliveryResult.REJECTED,missing.apply(delivery()).toCompletableFuture().join());
    }
    @Test void nativeCapabilityDetectionDoesNotAcceptUnrelatedServiceImplementation() {
        StarCoreBridge bridge=mock(StarCoreBridge.class);when(bridge.service("module.nation.NationService")).thenReturn(new Object());
        when(bridge.service("module.diplomacy.DiplomacyService")).thenReturn(new Object());assertFalse(new StarCoreAuthorityProvider(bridge).available());
    }
    @Test void receiptCapacityFailsClosedWithoutEvictingUnknownOutcomes() {
        provider=new StarCoreAuthorityProvider(id->player,()->authority,1);authority.mutationFailure=true;Delivery first=delivery();assertEquals(DeliveryResult.UNKNOWN,apply(first));
        Delivery second=new Delivery("outcome-2","instance-1","authority","diplomacy.invite",target,playerId,own,StarCoreAuthorityProvider.PERMISSION);
        assertEquals(DeliveryResult.REJECTED,apply(second));assertEquals(DeliveryResult.UNKNOWN,apply(first));assertEquals(1,authority.invocations);
    }

    static final class NativeFixture implements StarCoreAuthorityProvider.Authority {
        final Object lock=new Object();final List<String> calls=new ArrayList<>();String current,missing;
        boolean founder=true,policy=true,accept=true,mutationFailure,preconditionFailure;int invocations;
        NativeFixture(String own){current=own;}
        void checkLock(){assertTrue(Thread.holdsLock(lock),"membership/permission/mutation must share native nation monitor");}
        public Object nationLock(){return lock;}
        public String nationOf(UUID actor){checkLock();calls.add("nationOf");if(preconditionFailure)throw new IllegalStateException("authority unavailable");return current;}
        public boolean nationExists(String nation){checkLock();calls.add("exists:"+nation);return !nation.equals(missing);}
        public boolean isFounder(UUID actor,String nation){checkLock();calls.add("founder");return founder;}
        public boolean nationalPermission(Player actor){checkLock();calls.add("policy");return policy;}
        public boolean invite(String own,String target){checkLock();calls.add("invite");invocations++;if(mutationFailure)throw new IllegalStateException("native response unavailable after mutation");return accept;}
    }
}
