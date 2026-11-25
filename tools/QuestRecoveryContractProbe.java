package io.github.addxiaoyi.starchronicle.integration;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.starcore.starcore.core.persistence.PersistenceService;
import dev.starcore.starcore.core.scheduler.StarCoreScheduler;
import dev.starcore.starcore.quest.*;
import dev.starcore.starcore.quest.evidence.QuestEvidenceService;
import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.quest.*;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.core.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Three-process contract using both packaged services and real persistence. Bukkit surfaces are mocks. */
public final class QuestRecoveryContractProbe {
    static final UUID PLAYER=new UUID(7,11);
    static final String QUEST="contract-recovery";
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[0]);String phase=args[1];
        JavaPlugin plugin=mock(JavaPlugin.class);Player player=mock(Player.class);StarCoreScheduler scheduler=mock(StarCoreScheduler.class);
        when(plugin.isEnabled()).thenReturn(true);when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getDataFolder()).thenReturn(output.resolve("native").toFile());
        when(player.getUniqueId()).thenReturn(PLAYER);when(player.isOnline()).thenReturn(true);
        when(scheduler.callForEntity(any(),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(1)).get()));
        when(scheduler.runGlobalIfAvailable(any())).thenAnswer(call->{((Runnable)call.getArgument(0)).run();return true;});
        when(scheduler.runAsyncVoid(any())).thenAnswer(call->{((Runnable)call.getArgument(0)).run();return CompletableFuture.completedFuture(null);});
        when(scheduler.supplyAsync(any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(0)).get()));
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);bukkit.when(()->Bukkit.getPlayer(PLAYER)).thenReturn(player);
            PersistenceService persistence=new PersistenceService(plugin,scheduler);persistence.start();
            QuestService nativeQuests=new QuestService(null,null,null,persistence,Logger.getAnonymousLogger(),scheduler);
            Quest quest=new Quest.Builder(QUEST,"Recovery contract","Native objective",QuestType.SIDE,QuestDifficulty.NORMAL)
                .addObjective(new QuestObjective("objective",QuestObjective.ObjectiveType.KILL_ENTITY,"Native progress",2)).build();
            nativeQuests.registerQuest(quest);nativeQuests.initialize();
            try(var evidence=new QuestEvidenceService(plugin,nativeQuests,persistence,()->null,scheduler);
                var engine=new RuleEngine(new SqliteStore(output.resolve("chronicle.db")),List.of(story()),Clock.systemUTC())) {
                evidence.initialize();check(evidence.available(),"native available");
                var provider=new StarCoreQuestEvidenceProvider(()->evidence,false,Clock.systemUTC());
                PlayerContext actor=new PlayerContext(PLAYER,"",Map.of(),Set.of("starchronicle.use"));
                if(phase.equals("first")) engine.accept(new WorldEvent("contract:recovery","ROOT",Instant.now(),Set.of(PLAYER),Set.of(),Map.of(),"contract","root"),List.of(actor));
                var gate=engine.pendingQuestGates(Set.of(PLAYER),100).getFirst();
                if(phase.equals("first")) {
                    gate=engine.prepareQuestGate(gate.id(),provider.providerId(),actor);
                    observe(engine,gate,provider,provider.ensureStarted(gate.request()));
                    gate=engine.questGate(gate.id());check(gate.status()==QuestObservation.Status.ACTIVE,"first active");
                    check(nativeQuests.abandonQuest(PLAYER,QUEST),"actual native abandon");
                    evidence.abandonNative(PLAYER,QUEST);
                    awaitTerminal(evidence,gate.bindingId());
                    observe(engine,gate,provider,provider.lookup(gate.request()));
                    check(engine.questGate(gate.id()).status()==QuestObservation.Status.ABANDONED,"first durable terminal");
                    Files.writeString(output.resolve("first-binding.txt"),gate.bindingId());
                } else if(phase.equals("second")) {
                    var old=gate;check(old.attempt()==1,"restored first attempt");
                    observe(engine,old,provider,provider.reconcile(old.request()));
                    var terminal=provider.termination(old.request()).toCompletableFuture().get(10,TimeUnit.SECONDS);
                    check(terminal!=null,"terminal restored from journal");
                    gate=engine.retryQuestGate(old.id(),1,provider.providerId(),terminal,actor);
                    check(gate.attempt()==2&&!gate.bindingId().equals(old.bindingId()),"new durable binding");
                    var ignored=engine.observeQuestGate(old.id(),old.bindingId(),provider.providerId(),new QuestObservation(QuestObservation.Status.UNKNOWN,null,null,Instant.now(),"late old callback"));
                    check(!ignored.changed(),"old callback ignored");
                    observe(engine,gate,provider,provider.ensureStarted(gate.request()));
                    nativeQuests.updateObjectiveProgress(PLAYER,QUEST,0,1);
                    nativeQuests.saveConfirmed().toCompletableFuture().get(10,TimeUnit.SECONDS);
                    check(engine.questGate(gate.id()).status()==QuestObservation.Status.ACTIVE,"new native active");
                    check(!engine.questGate(gate.id()).acceptedRun().runId().equals(terminal.acceptedRun().runId()),"independent native run");
                    Files.writeString(output.resolve("second-binding.txt"),gate.bindingId());
                } else if(phase.equals("third")) {
                    check(gate.attempt()==2,"second attempt survives fresh JVM");
                    check(gate.bindingId().equals(Files.readString(output.resolve("second-binding.txt"))),"binding restored");
                    check(evidence.termination(Files.readString(output.resolve("first-binding.txt")))!=null,"original termination retained");
                    var run=gate.acceptedRun();observe(engine,gate,provider,provider.lookup(gate.request()));
                    observe(engine,gate,provider,provider.reconcile(gate.request()));
                    check(run.equals(engine.questGate(gate.id()).acceptedRun()),"acceptance stable after replay");
                    check(nativeQuests.getPlayerQuest(PLAYER).getActiveQuest(QUEST).getObjectives().getFirst().getCurrentProgress()==1,"native progress restored");
                } else throw new IllegalArgumentException("Unknown phase");
                check(engine.history(null,null,0,100).stream().noneMatch(e->e.type().equals("quest.completed")),"no synthetic completion history");
                check(nativeQuests.getPlayerQuest(PLAYER).getRunCompletions().isEmpty(),"no native completion or reward claim");
                persistence.stop();
                System.out.println("QUEST_RECOVERY_CONTRACT_PASS phase="+phase+" attempt="+engine.questGate(gate.id()).attempt()+" provider="+provider.providerId());
            }
        }
    }
    static void observe(RuleEngine engine,QuestGateLedger.Gate gate,StarCoreQuestEvidenceProvider provider,CompletionStage<QuestObservation> observation)throws Exception {
        engine.observeQuestGate(gate.id(),gate.bindingId(),provider.providerId(),observation.toCompletableFuture().get(10,TimeUnit.SECONDS));
    }
    static void awaitTerminal(QuestEvidenceService evidence,String binding)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(evidence.lookup(binding)==null||!"ABANDONED".equals(evidence.lookup(binding).status())) {
            if(System.nanoTime()>end)throw new AssertionError("No durable termination");Thread.sleep(5);
        }
    }
    static StoryDefinition story() {
        var wait=new Node("Wait",null,null,1,Map.of("next",new Choice("Continue","end",null,null,List.of())),List.of(new Effect("task","quest.await",QUEST,"PLAYER","starchronicle.use")),false);
        return new StoryDefinition("recovery-contract","1","Recovery",Scope.PLAYER,List.of("ROOT"),null,"wait",0,Map.of("wait",wait,"end",new Node("End",null,null,1,Map.of(),List.of(),true)));
    }
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
}
