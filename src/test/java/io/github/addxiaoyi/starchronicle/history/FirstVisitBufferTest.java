package io.github.addxiaoyi.starchronicle.history;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FirstVisitBufferTest {
    private WorldEvent event(UUID player,int x) {
        return new WorldEvent("arrival:"+player,"EXPLORATION_DISCOVERY",Instant.parse("2026-11-20T00:00:00Z").plusSeconds(x),java.util.Set.of(player),java.util.Set.of("nation"),java.util.Map.of("region.x",Integer.toString(x)),"buffer-test","Original observed location "+x);
    }
    @Test void failedBatchRetainsEveryUnprocessedArrivalAndItsOriginalSnapshot() {
        var buffer=new FirstVisitBuffer(3);var first=event(new UUID(0,2),1);var second=event(new UUID(0,3),2);var third=event(new UUID(0,4),3);
        assertEquals(FirstVisitBuffer.Admission.ADDED,buffer.offer(first));buffer.offer(second);buffer.offer(third);
        List<WorldEvent> written=new ArrayList<>();
        assertThrows(IllegalStateException.class,()->buffer.drain(item->{if(item.id().equals(second.id()))throw new IllegalStateException("disk unavailable");written.add(item);},3));
        assertEquals(List.of(first),written);assertEquals(2,buffer.size());
        assertEquals(FirstVisitBuffer.Admission.ALREADY_PENDING,buffer.offer(event(new UUID(0,3),9)));
        assertEquals(2,buffer.drain(written::add,3));assertEquals(List.of(first,second,third),written);
    }
    @Test void saturationRejectsNewKeyWithoutReplacingFirstAndSealingStillAllowsFinalDrain() {
        var buffer=new FirstVisitBuffer(1);var first=event(new UUID(0,2),1);
        buffer.offer(first);assertEquals(FirstVisitBuffer.Admission.ALREADY_PENDING,buffer.offer(event(new UUID(0,2),9)));
        assertEquals(FirstVisitBuffer.Admission.FULL,buffer.offer(event(new UUID(0,3),1)));
        buffer.seal();assertEquals(FirstVisitBuffer.Admission.FULL,buffer.offer(event(new UUID(0,4),1)));
        List<WorldEvent> written=new ArrayList<>();assertEquals(1,buffer.drain(written::add,10));assertEquals(List.of(first),written);
    }
}
