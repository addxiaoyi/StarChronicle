package io.github.addxiaoyi.starchronicle.paper;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StateWorkerTest {
    @Test void fullQueueStillDrainsAndClosesStorageLast() throws Exception {
        List<String> order=Collections.synchronizedList(new ArrayList<>());
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        StateWorker worker=new StateWorker(1,()->order.add("closed"));
        try {
            worker.execute(()->{started.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("test release timed out");order.add("first");}catch(InterruptedException error){Thread.currentThread().interrupt();}});
            assertTrue(started.await(3,TimeUnit.SECONDS));
            worker.execute(()->order.add("queued"));
            assertThrows(RejectedExecutionException.class,()->worker.execute(()->order.add("must-not-run")));
            worker.shutdown();release.countDown();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));
            assertEquals(List.of("first","queued","closed"),order);
        } finally {release.countDown();worker.shutdownNow();}
    }
    @Test void unusedWorkerClosesStorageWhenDisabledBeforeFirstPoll() throws Exception {
        List<String> closed=new ArrayList<>();StateWorker worker=new StateWorker(1,()->closed.add("closed"));worker.shutdown();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));assertEquals(List.of("closed"),closed);
    }
}
