package smoke;

import dev.starcore.starcore.core.persistence.PersistenceService;
import dev.starcore.starcore.quest.QuestService;
import dev.starcore.starcore.quest.evidence.QuestEvidenceService;
import io.github.addxiaoyi.starchronicle.api.ChronicleService;
import io.github.addxiaoyi.starchronicle.api.quest.QuestGateState;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Only an isolated, prepared acceptance fixture. Delay the real shard write by holding its
 * existing persistence stripe; no proof, task progress, provider or database is replaced. */
public final class QuestRecoveryPaperFixture extends ProductionStackPaperFixture {
    private static final String QUEST="chronicle_heir_records";
    private volatile CountDownLatch release;
    private CompletableFuture<Void> released;
    private ChronicleService api(){return Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));}
    private String instance(){return getConfig().getString("instance");}
    private String gateId(String who){return RuleEngine.stable("quest-gate",instance(),"investigation","records-evidence",uuid(who).toString());}
    private String binding(String who,int round){String id=gateId(who);return round==0?id:RuleEngine.stable("quest-gate-round",id,Integer.toString(round));}
    private Path shard(String binding)throws Exception{
        String name=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binding.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return getServer().getPluginManager().getPlugin("StarCore").getDataFolder().toPath().resolve("quest/tracked-evidence/"+name+".properties");
    }
    private Map<String,Object> disk(String binding)throws Exception{
        Properties properties=new Properties();try(var input=Files.newInputStream(shard(binding))){properties.load(input);}
        return SqliteStore.decode(properties.getProperty("entry"),Map.class);
    }
    private CompletionStage<?> hold(String who){
        return main(()->{
            require(release==null,"Only one acceptance I/O barrier at a time");
            var evidence=service(QuestEvidenceService.class);var current=evidence.lookup(binding(who,0));
            require(current!=null&&current.status().equals("ACTIVE")&&evidence.durableTerminalStates(),"A durable accepted task is required");
            getConfig().set("recovery.originalRun",current.runId());getConfig().set("recovery.gate",gateId(who));saveConfig();
            var field=QuestEvidenceService.class.getDeclaredField("persistence");field.setAccessible(true);
            PersistenceService persistence=(PersistenceService)field.get(evidence);
            var method=PersistenceService.class.getDeclaredMethod("fileLock",String.class);method.setAccessible(true);
            String key="quest/tracked-evidence/"+shard(binding(who,0)).getFileName();Object stripe=method.invoke(persistence,key);
            CountDownLatch latch=new CountDownLatch(1);release=latch;released=new CompletableFuture<>();
            CompletableFuture<Void> held=new CompletableFuture<>();
            Thread worker=new Thread(()->{
                try {synchronized(stripe){
                    log("QUEST_IO_HELD",Map.of("player",who,"binding",current.bindingId(),"runId",current.runId(),"key",key,"method","existing PersistenceService fileLock stripe; no state edits"));held.complete(null);
                    if(!latch.await(90,TimeUnit.SECONDS))throw new IllegalStateException("Acceptance I/O barrier expired without explicit release");
                }released.complete(null);}catch(Throwable error){held.completeExceptionally(error);released.completeExceptionally(error);log("FAILED",Map.of("action","quest-hold","error",error.toString()));}
            },"quest-acceptance-storage-delay");worker.setDaemon(true);worker.start();return held;
        }).thenCompose(stage->stage);
    }
    private CompletionStage<?> resume(){
        require(release!=null,"No I/O barrier held");release.countDown();release=null;
        return released.thenApply(x->{log("QUEST_IO_RELEASED",Map.of("writesResumed",true));return null;});
    }
    private CompletionStage<?> check(String who,int round,String status){
        CompletableFuture<Void> result=new CompletableFuture<>();poll(who,round,status,System.nanoTime()+TimeUnit.SECONDS.toNanos(25),result);return result;
    }
    private void poll(String who,int round,String status,long deadline,CompletableFuture<Void> result){
        api().questGateState(instance(),uuid(who)).thenCompose(view->main(()->{
            try {
                var gate=view.gates().stream().filter(g->g.gateId().equals(gateId(who))).findFirst().orElseThrow();
                var evidence=service(QuestEvidenceService.class);var nativeState=evidence.lookup(binding(who,round));
                String nativeStatus=status.equals("COMMITTED")?"COMPLETED":status;
                if(gate.round()!=round||!gate.status().name().equals(status)||nativeState==null||!nativeState.status().equals(nativeStatus)) {
                    if(System.nanoTime()>=deadline)throw new IllegalStateException("Quest state did not converge: "+gate+" / "+nativeState);
                    getServer().getScheduler().runTaskLater(this,()->poll(who,round,status,deadline,result),2L);return null;
                }
                require(!player(who).isOp()&&!player(who).hasPermission("starchronicle.admin")&&!player(who).hasPermission("starcore.quest.admin"),"No administrative task bypass");
                require(gate.runId().equals(nativeState.runId()),"Native run and gate differ");
                var nativePlayer=service(QuestService.class).getPlayerQuest(uuid(who));boolean active=nativePlayer.hasActiveQuest(QUEST);
                require(active==status.equals("ACTIVE"),"Native task lifecycle differs from evidence");
                Map<String,Object> state=new LinkedHashMap<>();state.put("who",who);state.put("round",round);state.put("status",status);state.put("gate",gate);state.put("native",nativeState);state.put("active",active);state.put("ready",view.ready());state.put("disk",disk(binding(who,round)));
                if(who.equals("A")&&round==1){var retired=evidence.lookup(binding(who,0));require(retired.status().equals("ABANDONED")&&retired.runId().equals(getConfig().getString("recovery.originalRun"))&&!retired.runId().equals(nativeState.runId()),"Original abandoned run was replaced");state.put("retired",retired);}
                if(status.equals("UNKNOWN"))require(((Map<?,?>)disk(binding(who,round)).get("snapshot")).get("status").equals("ACTIVE"),"Storage delay did not retain the accepted shard");
                log("QUEST_RECOVERY_STATE",state);result.complete(null);
            }catch(Throwable error){result.completeExceptionally(error);}return null;
        })).exceptionally(error->{result.completeExceptionally(error);return null;});
    }
    private CompletionStage<?> retryUnknown(){return api().retryQuestGate(gateId("A"),uuid("A"),0).thenCompose(view->main(()->{
        var gate=view.gates().stream().filter(g->g.playerId().equals(uuid("A"))).findFirst().orElseThrow();
        require(gate.round()==0&&gate.status().name().equals("UNKNOWN")&&gate.runId().equals(getConfig().getString("recovery.originalRun")),"UNKNOWN authorized a new run");
        log("QUEST_UNKNOWN_RETRY_HELD",Map.of("gate",gate,"actualCanonicalRead",true));return null;
    }));}
    private CompletionStage<?> rejected(String kind){
        CompletionStage<QuestGateState> request=kind.equals("foreign")?api().retryQuestGate(gateId("A"),uuid("D"),0):api().retryQuestGate(gateId("A"),uuid("A"),0);
        return request.handle((value,error)->{
            require(error!=null,"Invalid task retry was accepted: "+kind);while(error.getCause()!=null)error=error.getCause();
            String message=Objects.toString(error.getMessage(),"");
            require(error instanceof SecurityException||message.contains("轮次")||message.contains("当前阶段")||message.contains("活动")||message.contains("结束"),"Unrelated rejection: "+error);
            log("QUEST_RETRY_REJECTED",Map.of("kind",kind,"gate",gateId("A"),"error",error.toString(),"expectedRound",0));return null;
        });
    }
    @Override public void onDisable(){if(release!=null)release.countDown();super.onDisable();}
    @Override protected CompletionStage<?> extraAction(String action,String[] args){return switch(action){
        case "quest-hold"->hold(args[1]);case "quest-release"->resume();case "quest-check"->check(args[1],Integer.parseInt(args[2]),args[3]);
        case "quest-unknown-retry"->retryUnknown();case "quest-reject"->rejected(args[1]);default->super.extraAction(action,args);
    };}
}
