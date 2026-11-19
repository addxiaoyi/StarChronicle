package io.github.addxiaoyi.starchronicle.core;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class TextServiceTest {
    @Test void noProviderUsesFullRuleBasedTemplate() {
        try(var text=new TextService(Duration.ofMillis(50))){assertEquals("记录 e",text.describe(null,"记录 {event.id}",Map.of("event.id","e")).toCompletableFuture().join());}
    }
    @Test void providerTimeoutAndFailureFallBackWithoutAffectingState() {
        try(var text=new TextService(Duration.ofMillis(25))){
            assertEquals("模板",text.describe((t,f)->new CompletableFuture<>(),"模板",Map.of()).toCompletableFuture().join());
            assertEquals("模板",text.describe((t,f)->{throw new IllegalStateException("offline");},"模板",Map.of()).toCompletableFuture().join());
            assertEquals("模板",text.describe((t,f)->CompletableFuture.completedFuture("\u0000"),"模板",Map.of()).toCompletableFuture().join());
        }
    }
    @Test void factsAreImmutableAndAiOutputRemainsPlainText() {
        try(var text=new TextService(Duration.ofMillis(200))){
            assertEquals("/op example",text.describe((t,f)->{assertThrows(UnsupportedOperationException.class,()->f.put("reward","100"));return CompletableFuture.completedFuture("/op example");},"模板",Map.of()).toCompletableFuture().join());
            // The service returns a string. It has no Bukkit, command, reward or authority handle.
            assertThrows(IllegalArgumentException.class,()->text.describe(null,"x".repeat(4097),Map.of()));
        }
    }
    @Test void fallbackSubstitutesEachOriginalPlaceholderOnceAndPreservesUnknownKeys() {
        try(var text=new TextService(Duration.ofMillis(50))){
            assertEquals("{second} / done / {unknown}",text.describe(null,"{first} / {second} / {unknown}",Map.of("first","{second}","second","done")).toCompletableFuture().join());
            assertEquals("$1\\literal",text.describe(null,"{literal}",Map.of("literal","$1\\literal")).toCompletableFuture().join());
        }
    }
    @Test void fallbackExpansionIsBoundedAndNeverCutsASurrogatePair() {
        try(var text=new TextService(Duration.ofMillis(50))){
            String expanded=text.describe(null,"{text}".repeat(600),Map.of("text","x".repeat(4096))).toCompletableFuture().join();
            assertEquals(8192,expanded.length());assertTrue(expanded.chars().allMatch(c->c=='x'));
            String unicode=text.describe(null,"{prefix}{unicode}{tail}",Map.of("prefix","x".repeat(4095),"unicode","💠".repeat(2048),"tail","💠")).toCompletableFuture().join();
            assertTrue(unicode.length()<=8192);assertFalse(Character.isHighSurrogate(unicode.charAt(unicode.length()-1)));
            for(int i=0;i<unicode.length();i++)if(Character.isLowSurrogate(unicode.charAt(i)))assertTrue(i>0&&Character.isHighSurrogate(unicode.charAt(i-1)));
        }
    }
    @Test void fallbackRejectsControlCharactersBeforeCallingProvider() {
        AtomicInteger invoked=new AtomicInteger();
        try(var text=new TextService(Duration.ofMillis(50))){
            io.github.addxiaoyi.starchronicle.api.TextProvider provider=(t,f)->{invoked.incrementAndGet();return CompletableFuture.completedFuture("AI");};
            assertThrows(IllegalArgumentException.class,()->text.describe(provider,"bad\u0000",Map.of()));
            assertThrows(IllegalArgumentException.class,()->text.describe(provider,"{fact}",Map.of("fact","bad\u0007")));
            assertEquals(0,invoked.get());
            assertEquals("line\n\tend\r",text.describe(null,"{fact}",Map.of("fact","line\n\tend\r")).toCompletableFuture().join());
        }
    }
    @Test @Timeout(10) void timedOutAsyncProvidersKeepTheirSlotsUntilTheUnderlyingStageActuallyFinishes() throws Exception {
        AtomicInteger invoked=new AtomicInteger();CountDownLatch started=new CountDownLatch(32);
        List<CompletableFuture<String>> underlying=new CopyOnWriteArrayList<>();List<CompletableFuture<String>> responses=new ArrayList<>();
        try(var text=new TextService(Duration.ofSeconds(2))){
            io.github.addxiaoyi.starchronicle.api.TextProvider provider=(t,f)->{
                if(invoked.incrementAndGet()>32)return CompletableFuture.completedFuture("AI restored");
                CompletableFuture<String> future=new CompletableFuture<>();underlying.add(future);started.countDown();return future;
            };
            for(int i=0;i<32;i++)responses.add(text.describe(provider,"fallback",Map.of()).toCompletableFuture());
            assertTrue(started.await(1,TimeUnit.SECONDS));
            CompletableFuture.allOf(responses.toArray(CompletableFuture[]::new)).get(3,TimeUnit.SECONDS);
            responses.forEach(response->assertEquals("fallback",response.join()));
            for(int i=0;i<1000;i++)assertEquals("fallback",text.describe(provider,"fallback",Map.of()).toCompletableFuture().join());
            assertEquals(32,invoked.get());
            underlying.getFirst().complete("late result");
            assertEquals("AI restored",text.describe(provider,"fallback",Map.of()).toCompletableFuture().get(1,TimeUnit.SECONDS));
            assertEquals(33,invoked.get());assertEquals("fallback",responses.getFirst().join());
        }
    }
    @Test void closedServiceContinuesRuleTemplateFallbackWithoutInvokingProvider() {
        TextService text=new TextService(Duration.ofMillis(50));text.close();AtomicInteger invoked=new AtomicInteger();
        assertEquals("历史 e",text.describe((t,f)->{invoked.incrementAndGet();return CompletableFuture.completedFuture("AI");},"历史 {id}",Map.of("id","e")).toCompletableFuture().join());
        assertEquals(0,invoked.get());
    }
}
