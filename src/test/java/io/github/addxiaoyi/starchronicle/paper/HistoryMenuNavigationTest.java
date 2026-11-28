package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoryMenuNavigationTest {
    @Test void numberedJumpContinuesByActualLastIdAndBackReturnsToTheSameNumberedLocation() {
        var service=mock(ChronicleService.class);UUID actor=UUID.randomUUID();
        var events=IntStream.range(0,45).mapToObj(n->new WorldEvent("e"+n,"SAVED",Instant.EPOCH,Set.of(actor),Set.of("north"),Map.of(),"source","evidence")).toList();
        when(service.history("north",actor,800,45)).thenReturn(CompletableFuture.completedFuture(events));
        when(service.historyPage("north",actor,"e44",45)).thenReturn(CompletableFuture.completedFuture(new HistoryPage(List.of(),null)));
        var jump=Menus.HistoryLocation.at(800);var page=Menus.historyQuery(service,"north",actor,jump).toCompletableFuture().join();
        assertEquals("e44",page.nextCursor());var next=jump.next(page.nextCursor());
        assertNull(Menus.historyQuery(service,"north",actor,next).toCompletableFuture().join().nextCursor());
        assertEquals(jump,next.back());assertEquals(801,next.page());
        verify(service).history("north",actor,800,45);verify(service).historyPage("north",actor,"e44",45);verifyNoMoreInteractions(service);
    }
    @Test void ordinaryFirstAndForwardPagesUseCursorsAndFreshBackDoesNotReuseOldRows() {
        var service=mock(ChronicleService.class);var location=Menus.HistoryLocation.at(0);
        when(service.historyPage(null,null,null,45)).thenReturn(CompletableFuture.completedFuture(new HistoryPage(List.of(),null)));
        Menus.historyQuery(service,null,null,location).toCompletableFuture().join();
        var second=location.next("anchor-a");var third=second.next("anchor-b");
        assertEquals(second,third.back());assertEquals(location,third.back().back());
        Menus.historyQuery(service,null,null,third.back().back()).toCompletableFuture().join();
        verify(service,times(2)).historyPage(null,null,null,45);verifyNoMoreInteractions(service);
    }
    @Test void breadcrumbsRemainBoundedAndOldBackNavigationRetainsANumberedFallback() {
        var location=Menus.HistoryLocation.at(0);
        for(int n=0;n<1000;n++)location=location.next("event-"+n);
        assertEquals(128,location.previous().size());assertEquals(1000,location.page());
        for(int n=0;n<128;n++)location=location.back();
        assertEquals(872,location.page());assertEquals("event-871",location.cursor());
        var fallback=location.back();assertEquals(871,fallback.page());assertNull(fallback.cursor());assertTrue(fallback.previous().isEmpty());
        assertThrows(IllegalArgumentException.class,()->Menus.HistoryLocation.at(1_000_000).next("last"));
    }
    @Test void legacyServiceImplementationsFailTheNewOptionalApiExplicitly() {
        var service=mock(ChronicleService.class,CALLS_REAL_METHODS);
        var error=assertThrows(CompletionException.class,()->service.historyPage(null,null,null,10).toCompletableFuture().join());
        assertInstanceOf(UnsupportedOperationException.class,error.getCause());
    }
}
