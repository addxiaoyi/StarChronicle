package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Direct core callers use the same storage linearization boundary as the serialized
 * Bukkit service. A request blocked before its transaction must see one publication. */
class RuntimePublicationTest {
    @TempDir Path directory;
    @Test void reloadThatCommitsBeforeBlockedAcceptCannotMixOldProducerWithNewConsumers()throws Exception {
        UUID actor=UUID.fromString("00000000-0000-0000-0000-000000000091");
        Instant now=Instant.parse("2026-11-20T12:00:00Z");
        var context=new PlayerContext(actor,"",Map.of(),Set.of());
        var source=new WorldEvent("publication:root","ROOT",now,Set.of(actor),Set.of(),Map.of(),"RuntimePublicationTest","Committed operation");
        var old=new StoryDefinition("old-producer","1","Old producer",Scope.WORLD,List.of("ROOT"),null,"end",0,
                Map.of("end",new Node("Old",null,null,1,Map.of(),List.of(new Effect("history","history","OLD_RESULT","Old result",null)),true)));
        var next=new StoryDefinition("new-consumer","1","New consumer",Scope.WORLD,List.of("OLD_RESULT"),null,"end",0,
                Map.of("end",new Node("New",null,null,1,Map.of(),List.of(),true)));
        try(var store=new SqliteStore(directory.resolve("publication.db"));var executor=Executors.newSingleThreadExecutor()) {
            var engine=new RuleEngine(store,List.of(old),Clock.fixed(now,ZoneOffset.UTC));
            CountDownLatch started=new CountDownLatch(1);AtomicReference<Thread> caller=new AtomicReference<>();
            Future<ProcessReport> request;
            synchronized(store) {
                request=executor.submit(()->{caller.set(Thread.currentThread());started.countDown();return engine.accept(source,List.of(context));});
                assertTrue(started.await(5,TimeUnit.SECONDS));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean storageBlocked=false;
                while(System.nanoTime()<deadline) {
                    Thread thread=caller.get();
                    storageBlocked=thread.getState()==Thread.State.BLOCKED&&Arrays.stream(thread.getStackTrace())
                            .anyMatch(frame->frame.getClassName().equals(SqliteStore.class.getName())&&frame.getMethodName().equals("transaction"));
                    if(storageBlocked)break;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                assertTrue(storageBlocked,"Prove the exact live request is blocked at the store monitor before publishing");
                // The monitor is reentrant; this real reload commits and publishes before
                // the waiting request can enter its own transaction.
                engine.reload(List.of(next));
            }
            assertTrue(request.get(5,TimeUnit.SECONDS).instanceIds().isEmpty());
            assertTrue(engine.instances(actor).isEmpty());assertTrue(engine.pendingDefinitions().isEmpty());
            assertEquals(List.of(source),engine.history(null,null,0,10));
            var subsequent=new WorldEvent("publication:subsequent","OLD_RESULT",now,Set.of(actor),Set.of(),Map.of(),"RuntimePublicationTest","Independent committed operation");
            assertEquals(1,engine.accept(subsequent,List.of(context)).instanceIds().size());
            assertEquals("new-consumer",engine.instances(actor).getFirst().definition().id());
        }
    }
}
