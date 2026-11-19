package smoke;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.core.StoryInstance;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Actual standalone Folia downgrade only. Reads ChronicleService and real owned GUI state;
 * never opens SQLite, constructs identity/events, installs authority, mutates countries,
 * invalidates ballots, grants rewards, or uses Bukkit's primary-thread scheduler. */
public final class NationalCouncilFoliaFixture extends JavaPlugin implements CommandExecutor,Listener {
    public record Expected(String instanceId,String nodeId,boolean completed,String resolution,int quorum,
                           Map<String,Long> counts,List<NationalVoteState.Ballot> ballots){}
    private static final List<String> CLIENTS=List.of("A","B","C","D");
    private static final Map<String,String> STORIES=Map.of("primary","national-smoke-primary","invalidation","national-smoke-invalidation");
    private final Map<UUID,PermissionAttachment> attachments=new ConcurrentHashMap<>();
    private final Map<String,Expected> expected=new HashMap<>();
    private final Map<String,String> expectedHonor=new HashMap<>();
    private final Object logLock=new Object();
    private ChronicleService api;private Path run;

    @Override public void onEnable(){try{
        run=getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();String sha=getConfig().getString("expected-sha","");
        require(sha.matches("[0-9a-f]{64}")&&run.getFileName().toString().equals("folia-national-"+sha.substring(0,8))&&Files.isRegularFile(run.resolve("preparation.json")),"Require the isolated SHA-bound prepared Folia run");
        require(digest(run.resolve("plugins/StarChronicle.jar")).equals(sha),"Frozen product SHA drift");
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
        require(getServer().getPluginManager().getPlugin("StarCore")==null,"StarCore must not be installed in this downgrade run");
        Set<String> installed=new TreeSet<>();for(var plugin:getServer().getPluginManager().getPlugins())installed.add(plugin.getName());
        require(installed.equals(Set.of("StarChronicle","NationalCouncilFoliaFixture")),"Only the product and this verifier may be installed: "+installed);
        List<String> providers=new ArrayList<>();
        for(RegisteredServiceProvider<NationalVoteAuthority> registration:getServer().getServicesManager().getRegistrations(NationalVoteAuthority.class)){
            Object provider=registration.getProvider();String name=provider.getClass().getName();
            require(registration.getPlugin().getName().equals("StarChronicle")&&name.equals("io.github.addxiaoyi.starchronicle.integration.StarCoreNationalVoteAuthority"),"A replacement national authority was installed");
            require(Boolean.FALSE.equals(provider.getClass().getMethod("available").invoke(provider)),"Native national authority unexpectedly available without StarCore");providers.add(name);
        }
        api=Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class),"ChronicleService unavailable");
        for(String which:STORIES.keySet())expected.put(which,SqliteStore.decode(Objects.requireNonNull(getConfig().getString("expected."+which)),Expected.class));
        for(String who:CLIENTS)expectedHonor.put(who,getConfig().getString("expected-honor."+who,"0"));
        Objects.requireNonNull(getCommand("ncfolia")).setExecutor(this);getServer().getPluginManager().registerEvents(this,this);
        log("READY",Map.of("plugin_sha256",sha,"server_port",getServer().getPort(),"installedPlugins",installed,"authorityProviders",providers,"noDatabaseAccess",true,"noFabricatedNation",true));
    }catch(Throwable error){failure("enable",error);getServer().getPluginManager().disablePlugin(this);}}

    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private void log(String kind,Object value){
        getLogger().info("NATIONAL_FOLIA_"+kind+" "+SqliteStore.json(value));
        if(run!=null)synchronized(logLock){try{Files.writeString(run.resolve("evidence/national-folia-fixture.jsonl"),SqliteStore.json(Map.of("time",Instant.now().toString(),"kind",kind,"value",value))+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception error){getLogger().severe("Evidence append failed: "+error);}}
    }
    private void failure(String operation,Throwable error){log("FAILED",Map.of("operation",operation,"error",error.toString()));error.printStackTrace();}
    private static UUID id(String who){return UUID.nameUUIDFromBytes(("OfflinePlayer:PhaseTwo"+who).getBytes(StandardCharsets.UTF_8));}
    private void owned(Player player,String who){
        require(Bukkit.isOwnedByCurrentRegion(player),"Player access must execute on this entity's Folia owner");
        require(player.getUniqueId().equals(id(who))&&player.getName().equals("PhaseTwo"+who)&&player.isOnline(),"Unexpected actual player identity");
        require(!player.isOp(),"Actual verification clients must remain non-operators");
    }
    private void owner(String who,Consumer<Player> work){
        require(CLIENTS.contains(who),"Unknown original verification client");Player player=getServer().getPlayerExact("PhaseTwo"+who);
        require(player!=null,"Actual client is not online: PhaseTwo"+who);
        player.getScheduler().execute(this,()->{try{owned(player,who);work.accept(player);}catch(Throwable error){failure("owned-"+who,error);}},()->failure("retired-"+who,new IllegalStateException("Actual player's entity scheduler retired")),1L);
    }
    private void grant(Player player,String who){
        owned(player,who);PermissionAttachment attachment=attachments.compute(player.getUniqueId(),(key,old)->old!=null&&old.getPermissible()==player?old:player.addAttachment(this));
        attachment.setPermission("starchronicle.use",true);attachment.setPermission("starchronicle.admin",false);player.recalculatePermissions();
        require(player.hasPermission("starchronicle.use")&&!player.hasPermission("starchronicle.admin")&&!player.isOp(),"Basic-use/non-admin client permission contract failed");
        log("IDENTITY",Map.of("player",who,"uuid",player.getUniqueId(),"op",player.isOp(),"use",player.hasPermission("starchronicle.use"),"admin",player.hasPermission("starchronicle.admin"),"owned",true,"grantedNation",false));
    }
    @EventHandler(priority=EventPriority.LOWEST) public void permissionBeforeSnapshot(PlayerJoinEvent event){
        Player player=event.getPlayer();String name=player.getName();
        if(name.matches("PhaseTwo[A-D]"))try{grant(player,name.substring(name.length()-1));}catch(Throwable error){failure("join-permission",error);}
    }
    private Expected expected(String which){Expected value=expected.get(which);require(value!=null,"Use primary|invalidation");return value;}
    private void state(String who,String which){Expected original=expected(which);owner(who,player->{UUID viewer=player.getUniqueId();
        api.nationalState(original.instanceId(),viewer).thenCompose(value->{
            require(value.viewerNationId().isBlank()&&value.viewerRevision()==0&&!value.representative()&&!value.canVote()&&!value.canWithdraw(),"Fresh Folia identity must have no nation or national authority");
            require(value.instanceId().equals(original.instanceId())&&value.nodeId().equals(original.nodeId())&&value.quorum()==original.quorum()&&value.completed()==original.completed()&&Objects.equals(value.resolution(),original.resolution()),"Copied national instance/resolution identity changed");
            require(value.counts().equals(original.counts())&&value.ballots().equals(original.ballots()),"Copied national counts, revisions or original evidence changed");
            log("STATE",Map.of("player",who,"story",STORIES.get(which),"freshNationBlank",true,"originalStatePreserved",true,"state",value));
            return api.availableChoices(original.instanceId(),viewer);
        }).whenComplete((choices,error)->{if(error!=null)failure("state-"+who+"-"+which,error);else try{require(choices.isEmpty(),"Standalone Folia must expose no actionable national choice");log("READ_ONLY_CHOICES",Map.of("player",who,"story",STORIES.get(which),"choices",choices.keySet()));}catch(Throwable invalid){failure("readonly-choices",invalid);}});
    });}
    private void instances(String who){owner(who,player->{UUID viewer=player.getUniqueId();api.instances(viewer).whenComplete((items,error)->{
        if(error!=null){failure("instances-"+who,error);return;}try{
            for(var entry:expected.entrySet()){
                StoryInstance instance=items.stream().filter(i->i.id().equals(entry.getValue().instanceId())).findFirst().orElseThrow();
                require(instance.contains(viewer)&&instance.members().size()==4&&instance.definition().voteUnit()==io.github.addxiaoyi.starchronicle.config.StoryDefinition.VoteUnit.NATION&&instance.votes().isEmpty(),"Copied joined membership/unit changed");
                log("INSTANCE",Map.of("player",who,"story",instance.definition().id(),"id",instance.id(),"completed",instance.completed(),"members",instance.members(),"choices",instance.choices()));
            }
        }catch(Throwable invalid){failure("instances-"+who,invalid);}
    });});}
    private void reject(String who,String which,String choice,long revision,boolean withdrawal){Expected original=expected(which);require(revision>=0,"Revision must be nonnegative");owner(who,player->{UUID viewer=player.getUniqueId();
        CompletionStage<?> call=withdrawal?api.withdrawNationalVote(original.instanceId(),viewer,original.nodeId(),revision):api.nationalVote(original.instanceId(),viewer,original.nodeId(),revision,choice);
        call.whenComplete((value,error)->{try{
            require(error!=null,"A country action unexpectedly succeeded without a native authority");Throwable root=error;
            while((root instanceof CompletionException||root instanceof ExecutionException)&&root.getCause()!=null)root=root.getCause();
            require(root instanceof SecurityException,"Expected actual native-authority denial, not another runtime error: "+root);
            require(Set.of("A current native nation is required","Audited native national authority is unavailable","No authoritative national representative service is available").contains(root.getMessage()),"Rejection was not the actual default national authority boundary: "+root);
            log("EXPECTED_REJECTION",Map.of("action",withdrawal?"WITHDRAW":"VOTE","player",who,"story",STORIES.get(which),"revision",revision,"choice",withdrawal?"":choice,"cause",root.toString()));
        }catch(Throwable invalid){failure("expected-native-denial-"+who,invalid);}});
    });}
    private void probe(String who){owner(who,player->{UUID viewer=player.getUniqueId();String suffix=STORIES.get("primary")+"/1/council";
        List<String> refs=List.of("past.player.choice."+suffix,"past.nation.choice."+suffix,"past.resolution.world."+suffix,"past.resolution.nation."+suffix,"chronicle.history.nation.NATIONAL_COUNCIL_SMOKE_FINISHED","chronicle.history.player.NATIONAL_COUNCIL_SMOKE_FINISHED","chronicle.history.world.NATIONAL_COUNCIL_SMOKE_FINISHED");
        api.recollections(viewer,refs).whenComplete((facts,error)->{if(error!=null){failure("probe-"+who,error);return;}try{
            require(Objects.equals(expected("primary").resolution(),facts.get("past.resolution.world."+suffix)),"Original world resolution must remain readable");
            require(!facts.containsKey("past.player.choice."+suffix)&&!facts.containsKey("past.nation.choice."+suffix)&&!facts.containsKey("past.resolution.nation."+suffix)&&!facts.containsKey("chronicle.history.nation.NATIONAL_COUNCIL_SMOKE_FINISHED"),"Fresh absent nationality must not inherit an old country's or individual ballot fact");
            log("RECOLLECTIONS",Map.of("player",who,"facts",new TreeMap<>(facts),"nationFactsAbsent",true));
        }catch(Throwable invalid){failure("historical-scope-"+who,invalid);}});
        api.fate(viewer).whenComplete((facts,error)->{if(error!=null){failure("fate-"+who,error);return;}try{require(expectedHonor.get(who).equals(facts.getOrDefault("player.honor","0")),"Copied historical reward changed");log("FATE",Map.of("player",who,"facts",new TreeMap<>(facts),"expectedHonor",expectedHonor.get(who)));}catch(Throwable invalid){failure("reward-invariant-"+who,invalid);}});
    });}
    private void view(String who){owner(who,player->{var inventory=player.getOpenInventory();var top=inventory.getTopInventory();List<Map<String,Object>> slots=new ArrayList<>();
        for(int slot=0;slot<top.getSize();slot++){ItemStack item=top.getItem(slot);if(item==null||item.getType().isAir())continue;Map<String,Object> value=new LinkedHashMap<>();value.put("slot",slot);value.put("material",item.getType().name());var meta=item.getItemMeta();if(meta!=null){value.put("name",meta.hasDisplayName()?meta.getDisplayName():"");value.put("lore",meta.hasLore()?meta.getLore():List.of());}slots.add(value);}
        log("VIEW",Map.of("player",who,"owned",true,"title",inventory.getTitle(),"holder",top.getHolder()==null?"null":top.getHolder().getClass().getName(),"slots",slots));
    });}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(sender instanceof Player){sender.sendMessage("Isolated coordinator console verifier only");return true;}
        try{switch(args.length==0?"help":args[0]){
            case "seed"->{for(String who:CLIENTS)owner(who,p->grant(p,who));}
            case "instances"->{require(args.length==2,"instances A");instances(args[1]);}
            case "state"->{require(args.length==3,"state A primary|invalidation");state(args[1],args[2]);}
            case "reject-vote"->{require(args.length==5,"reject-vote A invalidation yes REV");reject(args[1],args[2],args[3],Long.parseLong(args[4]),false);}
            case "reject-withdraw"->{require(args.length==4,"reject-withdraw A invalidation REV");reject(args[1],args[2],null,Long.parseLong(args[3]),true);}
            case "probe"->{require(args.length==2,"probe A");probe(args[1]);}
            case "view"->{require(args.length==2,"view A");view(args[1]);}
            case "phase"->log("PHASE",Map.of("label",String.join(" ",Arrays.copyOfRange(args,1,args.length))));
            default->log("HELP",Map.of("commands","seed | instances A | state A primary|invalidation | reject-vote A invalidation yes REV | reject-withdraw A invalidation REV | probe A | view A | phase LABEL"));
        }}catch(Throwable error){failure("command",error);}return true;
    }
    private static String digest(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
}
