package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.SnapshotProvider;
import io.github.addxiaoyi.starchronicle.history.ObservationSnapshot;
import io.github.addxiaoyi.starchronicle.history.ObservationSnapshot.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnapshotCollectorTest {
    @Test void committedTechnologyScopeNeverAdvancesTheLegacyObservationBaseline() {
        StarCoreBridge bridge=mock(StarCoreBridge.class);when(bridge.committedTechnologyHistory()).thenReturn(true);
        var scope=new SnapshotCollector(bridge).collect().stream().filter(s->s.namespace().equals("starcore.technologies")).findFirst().orElseThrow();
        assertFalse(scope.complete());assertTrue(scope.evidence().contains("Durable native technology log"));
    }
    @Test void committedCapitalScopeKeepsSnapshotBaselineWithoutASecondMigrationEvent() {
        StarCoreBridge bridge=mock(StarCoreBridge.class);when(bridge.committedCapitalHistory()).thenReturn(true);
        var scope=new SnapshotCollector(bridge).collect().stream().filter(s->s.namespace().equals("starcore.capitals")).findFirst().orElseThrow();
        assertFalse(scope.complete());assertTrue(scope.evidence().contains("Durable native capital log"));
    }
    @Test void missingNativeAuthoritiesReportIncompleteScopesInsteadOfEmptySuccess() {
        StarCoreBridge bridge=mock(StarCoreBridge.class);var snapshots=new SnapshotCollector(bridge).collect();
        assertEquals(3,snapshots.size());assertTrue(snapshots.stream().noneMatch(ObservationSnapshot::complete));
        assertTrue(snapshots.stream().allMatch(s->s.entries().isEmpty()));
    }
    @Test void trustedCapitalProviderCanSupplyDurableCityIdsWithoutLocationCacheGuessing() {
        StarCoreBridge bridge=mock(StarCoreBridge.class);
        var capitals=new ObservationSnapshot("trusted.capitals","DurableCapitalService.designatedCityId",Kind.CAPITALS,true,true,Map.of("nation-a",new Entry("nation-a","city-b",Map.of())),"Designated city ID read after durable transaction commit");
        SnapshotProvider provider=()->List.of(capitals);var snapshots=new SnapshotCollector(bridge,()->List.of(provider)).collect();
        assertTrue(snapshots.contains(capitals));assertFalse(snapshots.stream().filter(s->s.namespace().equals("starcore.capitals")).findFirst().orElseThrow().complete());
    }
    @Test void providerCannotImpersonateBuiltInAuthorityNamespace() {
        StarCoreBridge bridge=mock(StarCoreBridge.class);
        SnapshotProvider provider=()->List.of(new ObservationSnapshot("starcore.nations","Forged native authority",Kind.NATIONS,true,true,Map.of(),"not native"));
        assertThrows(IllegalArgumentException.class,()->new SnapshotCollector(bridge,()->List.of(provider)).collect());
    }
}
