package io.github.addxiaoyi.starchronicle.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InvocationGateTest {
    @Test void timeoutBeforeEntryPermanentlyPreventsTheQueuedMutation(){
        var gate=new InvocationGate();assertTrue(gate.cancelBeforeEntry());assertFalse(gate.enter());assertTrue(gate.cancelBeforeEntry());
    }
    @Test void entryBeforeTimeoutAlwaysRequiresUncertainReconciliation(){
        var gate=new InvocationGate();assertTrue(gate.enter());assertFalse(gate.cancelBeforeEntry());assertFalse(gate.enter());assertFalse(gate.cancelBeforeEntry());
    }
    @Test void timeoutAndEntityAdmissionCannotBothWin()throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)){
            for(int i=0;i<200;i++){
                var gate=new InvocationGate();CountDownLatch start=new CountDownLatch(1);AtomicInteger invoked=new AtomicInteger();
                Future<Boolean> entity=pool.submit(()->{start.await();boolean admitted=gate.enter();if(admitted)invoked.incrementAndGet();return admitted;});
                Future<Boolean> timeout=pool.submit(()->{start.await();return gate.cancelBeforeEntry();});
                start.countDown();boolean entered=entity.get(5,TimeUnit.SECONDS),cancelled=timeout.get(5,TimeUnit.SECONDS);
                assertNotEquals(entered,cancelled);assertEquals(entered?1:0,invoked.get());assertFalse(gate.enter());
            }
        }
    }
}
