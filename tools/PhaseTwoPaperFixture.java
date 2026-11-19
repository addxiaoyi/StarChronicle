package smoke;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.core.StoryInstance;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.bukkit.GameMode;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated Paper acceptance fixture. No database writes, invented contexts or reward setters.
 * IMPORTANT: inspect/invalid/complete-replay call api.instances, which intentionally resumes
 * story triggers. Do not use them after the boot-one ending and before the first restart. */
public final class PhaseTwoPaperFixture extends JavaPlugin implements CommandExecutor {
    private static final List<String> ORIGINALS=List.of("A","B","C","D");
    private static final String SOURCE="phase-two:heir-discovery:1";
    private static final List<String> REFERENCES=List.of(
            "past.player.choice.last-heir/1/council","past.resolution.world.last-heir/1/council",
            "past.resolution.nation.last-heir/1/council","past.resolution.world.last-heir/1/preparations",
            "chronicle.history.player.HEIR_RESTORATION_SUPPORTED","chronicle.history.nation.HEIR_RESTORATION_SUPPORTED",
            "chronicle.history.world.HEIR_RESTORATION_SUPPORTED");
    private ChronicleService api;
    private Plugin core;
    private Object registry;
    private final Map<UUID,PermissionAttachment> permissions=new HashMap<>();

    @Override public void onEnable(){
        try{
            core=Objects.requireNonNull(getServer().getPluginManager().getPlugin("StarCore"));
            registry=call(call(core,"context"),"serviceRegistry");
            api=Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));
            Objects.requireNonNull(getCommand("phase2")).setExecutor(this);
            log("READY",Map.of("core",core.isEnabled(),"service",true,"fixtureOnly",true));
        }catch(Throwable error){fail("enable",error);getServer().getPluginManager().disablePlugin(this);}
    }
    private void log(String event,Object data){getLogger().info("PHASE2_"+event+" "+SqliteStore.json(data));}
    private void fail(String operation,Throwable error){getLogger().severe("PHASE2_FAILED "+SqliteStore.json(Map.of("operation",operation,"error",error.toString())));error.printStackTrace();}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private Player player(String shortName){require(List.of("A","B","C","D","E").contains(shortName),"Unknown acceptance client");Player p=getServer().getPlayerExact("PhaseTwo"+shortName);require(p!=null&&p.isOnline(),"Actual client is not connected: "+shortName);return p;}
    private Object service(String suffix)throws Exception{Class<?> type=Class.forName("dev.starcore.starcore."+suffix,true,core.getClass().getClassLoader());return ((Optional<?>)call(registry,"find",type)).orElseThrow(()->new IllegalStateException("Missing service "+suffix));}
    private Object nations()throws Exception{return service("module.nation.NationService");}
    private Optional<?> nationOf(Player player)throws Exception{return (Optional<?>)call(nations(),"nationOf",player.getUniqueId());}
    private Object nation(Player player)throws Exception{return nationOf(player).orElseThrow(()->new IllegalStateException("Client has no actual native nation"));}
    private String nationId(Player player)throws Exception{return call(nation(player),"id").toString();}
    private void grant(Player player){
        var attachment=permissions.compute(player.getUniqueId(),(ignored,old)->old!=null&&old.getPermissible()==player?old:player.addAttachment(this));
        attachment.setPermission("starchronicle.use",true);attachment.setPermission("starcore.nation.ally.add",false);player.recalculatePermissions();
        require(!player.isOp(),"Clients must remain non-operators");require(player.hasPermission("starchronicle.use"),"Use permission attachment failed");player.setGameMode(GameMode.CREATIVE);
        log("PERMISSION",Map.of("player",player.getName(),"uuid",player.getUniqueId(),"op",player.isOp(),"use",player.hasPermission("starchronicle.use")));
    }
    private void seed()throws Exception{
        Object nations=nations();Set<String> found=new HashSet<>();
        for(String shortName:ORIGINALS){
            Player p=player(shortName);grant(p);String name="PhaseTwoNation"+shortName;
            Object n=((Optional<?>)call(nations,"nationByName",name)).orElse(null);
            if(n==null)n=call(nations,"createNation",p.getUniqueId(),p.getName(),name);
            require(n!=null,"Native createNation returned no nation");Object id=call(n,"id");require(Boolean.TRUE.equals(call(nations,"isFounder",p.getUniqueId(),id)),"Actual client is not founder");require(nationId(p).equals(id.toString()),"Actual membership differs");require(found.add(id.toString()),"Original clients must found four distinct nations");
            log("FOUNDER",Map.of("player",shortName,"uuid",p.getUniqueId(),"nation",id.toString(),"founder",true));
        }
        Object gateway=call(getServer().getPluginManager().getPlugin("StarChronicle"),"gateway");
        require(Boolean.TRUE.equals(call(gateway,"hasScript","chronicle_last_heir")),"Owned native scene was not installed by StarChronicle");
        log("SEEDED",Map.of("distinctNations",found.size(),"nativeInstalled",true));
    }
    private WorldEvent capturedSource()throws Exception{
        if(!getConfig().contains("source.time")){
            Set<String> actors=new TreeSet<>(),nations=new TreeSet<>();
            for(String shortName:ORIGINALS){Player p=player(shortName);actors.add(p.getUniqueId().toString());nations.add(nationId(p));}
            require(actors.size()==4&&nations.size()==4,"Source requires four real founders");
            getConfig().set("source.time",Instant.now().toString());getConfig().set("source.players",new ArrayList<>(actors));getConfig().set("source.nations",new ArrayList<>(nations));saveConfig();
        }
        Set<UUID> actors=new TreeSet<>();for(String id:getConfig().getStringList("source.players"))actors.add(UUID.fromString(id));
        Set<String> countries=new TreeSet<>(getConfig().getStringList("source.nations"));require(actors.size()==4&&countries.size()==4,"Invalid retained source receipt");
        return new WorldEvent(SOURCE,"ANCIENT_HEIR_DISCOVERED",Instant.parse(Objects.requireNonNull(getConfig().getString("source.time"))),actors,countries,
                Map.of("test-only","phase two actual-client acceptance"),"PhaseTwoPaperFixture","Isolated test-only discovery signal: UUIDs and four distinct nations captured from real connected founders through NationService. Not a natural production-world discovery or national state change.");
    }
    private void begin(boolean replay)throws Exception{
        WorldEvent source=capturedSource();log("SOURCE",source);
        api.submit(source).whenComplete((report,error)->{if(error!=null)fail("submit",error);else log("SUBMITTED",Map.of("replayRequested",replay,"duplicate",report.duplicate(),"instances",report.instanceIds(),"source",source.id()));});
    }
    private void vote(String shortName,String node,String choice){
        UUID actor=player(shortName).getUniqueId();
        api.instances(actor).thenCompose(items->{StoryInstance item=items.stream().filter(i->i.definition().id().equals("last-heir")).findFirst().orElseThrow();require(item.contains(actor),"Actual player is not a stored epic member");return api.choose(item.id(),actor,node,choice);})
                .whenComplete((report,error)->{if(error!=null)fail("main-vote",error);else log("VOTE",Map.of("player",shortName,"uuid",actor,"node",node,"choice",choice,"message",report.message()));});
    }
    private void lateMember()throws Exception{
        Player a=player("A"),e=player("E");grant(e);Object id=call(nation(a),"id"),nations=nations();Optional<?> existing=nationOf(e);boolean added=false;
        if(existing.isEmpty()){require(Boolean.TRUE.equals(call(nations,"addMember",id,e.getUniqueId(),e.getName())),"Actual addMember was rejected");added=true;}
        require(nationId(e).equals(id.toString()),"Late client's native membership is not founder A's nation");require(!Boolean.TRUE.equals(call(nations,"isFounder",e.getUniqueId(),id)),"Late client must not be a founder");
        log("LATE_MEMBER",Map.of("player","E","uuid",e.getUniqueId(),"nation",id.toString(),"sameAs","A","addedByNativeService",added,"founder",false));
    }
    private void facts(String shortName){
        Player p=player(shortName);UUID id=p.getUniqueId();
        api.recollections(id,REFERENCES).whenComplete((values,error)->{if(error!=null)fail("recollections",error);else log("RECOLLECTIONS",Map.of("player",shortName,"uuid",id,"facts",values));});
        api.npc(id,"ancient-heir").whenComplete((value,error)->{if(error!=null)fail("npc",error);else log("NPC",Map.of("player",shortName,"dialogue",value.dialogueId(),"reputation",value.reputation(),"trust",value.trust(),"growth",value.growth(),"text",value.dialogue()));});
        api.fate(id).whenComplete((value,error)->{if(error!=null)fail("fate",error);else log("FATE",Map.of("player",shortName,"facts",value));});
    }
    private void inspect(String shortName){
        facts(shortName);UUID id=player(shortName).getUniqueId();
        api.instances(id).whenComplete((items,error)->{
            if(error!=null){fail("instances",error);return;}
            var relevant=items.stream().filter(i->Set.of("last-heir","heir-legacy").contains(i.definition().id())).map(i->Map.of("id",i.id(),"story",i.definition().id(),"scope",i.scopeKey(),"source",i.sourceEventId(),"node",i.node(),"completed",i.completed(),"members",i.members())).toList();
            log("INSTANCES",Map.of("player",shortName,"uuid",id,"instances",relevant));
        });
    }
    private void rejectChoice(String shortName,String choice,boolean completed){
        UUID actor=player(shortName).getUniqueId();
        api.instances(actor).thenCompose(items->{var item=items.stream().filter(i->i.definition().id().equals("heir-legacy")).findFirst().orElseThrow();return api.choose(item.id(),actor,"archive",choice);})
                .whenComplete((report,error)->{if(error==null)fail("expected-rejection",new IllegalStateException("Unexpected accepted choice "+choice));else log(completed?"COMPLETED_REJECTED":"INVALID_REJECTED",Map.of("player",shortName,"choice",choice,"error",error.toString()));});
    }
    private void nativeState(String shortName)throws Exception{
        Player p=player(shortName);UUID id=p.getUniqueId();Object engine=service("module.narrative.engine.NarrativeEngine"),session=call(engine,"getSession",id),state=call(engine,"getState",id);
        log("NATIVE",Map.of("player",shortName,"routing",call(engine,"hasActiveSession",id),"session",session==null?"<absent>":call(session,"scriptId")+"/"+call(session,"currentNodeId"),"variables",call(state,"getVariables"),"flags",call(state,"getFlags"),"evidence",call(state,"getEvidence")));
        api.scene(id).whenComplete((scene,error)->{if(error!=null)fail("scene",error);else log("SCENE",Map.of("player",shortName,"present",scene.isPresent(),"script",scene.map(NativeScene::scriptId).orElse(""),"node",scene.map(NativeScene::nodeId).orElse("")));});
    }
    private void inviteConfig()throws Exception{
        Player a=player("A"),b=player("B");String target=nationId(b);
        String definition="""
                id: phase-two-invite
                version: "1"
                title: "Phase two actual national invitation"
                scope: PLAYER
                triggers: [PHASE_TWO_INVITATION_TEST]
                condition: {fact: player.id, op: eq, value: "%s"}
                start: proposal
                nodes:
                  proposal:
                    text: "An actual founder and explicit native permission are required."
                    choices:
                      send: {label: "Send native invitation", next: end}
                  end:
                    text: "Only the native authority service can commit the invitation."
                    terminal: true
                    effects:
                      - {id: invite, type: authority, target: diplomacy.invite, value: "%s", permission: starcore.nation.ally.add}
                """.formatted(a.getUniqueId(),target);
        Path stories=getServer().getPluginManager().getPlugin("StarChronicle").getDataFolder().toPath().resolve("stories");
        Files.writeString(stories.resolve("phase-two-invite.yml"),definition);
        getServer().dispatchCommand(getServer().getConsoleSender(),"chronicle reload");
        log("INVITE_CONFIG",Map.of("sourceNation",nationId(a),"targetNation",target,"actor",a.getUniqueId()));
    }
    private void inviteBegin()throws Exception{
        Player a=player("A");String time=getConfig().getString("invite.time");if(time==null){time=Instant.now().toString();getConfig().set("invite.time",time);saveConfig();}
        WorldEvent signal=new WorldEvent("phase-two:invitation:1","PHASE_TWO_INVITATION_TEST",Instant.parse(time),Set.of(a.getUniqueId()),Set.of(nationId(a)),Map.of("test-only","phase two native invitation permission acceptance"),"PhaseTwoPaperFixture","Isolated invitation test signal with actual founder UUID and service-read nation; the real authority adapter must validate every effect.");
        api.submit(signal).whenComplete((report,error)->{if(error!=null)fail("invite-submit",error);else log("INVITE_SUBMITTED",Map.of("duplicate",report.duplicate(),"instances",report.instanceIds()));});
    }
    private void inviteChoose(){
        UUID actor=player("A").getUniqueId();
        api.instances(actor).thenCompose(items->{var item=items.stream().filter(i->i.definition().id().equals("phase-two-invite")).findFirst().orElseThrow();return api.choose(item.id(),actor,item.node(),"send");})
                .whenComplete((report,error)->{if(error!=null)log("INVITE_REJECTED",Map.of("actor",actor,"error",error.toString()));else log("INVITE_CHOSEN",Map.of("actor",actor,"message",report.message()));});
    }
    private void allowInvite(){
        Player a=player("A");a.getScheduler().execute(this,()->{
            var attachment=permissions.compute(a.getUniqueId(),(ignored,old)->old!=null&&old.getPermissible()==a?old:a.addAttachment(this));
            attachment.setPermission("starcore.nation.ally.add",true);a.recalculatePermissions();require(a.hasPermission("starcore.nation.ally.add")&&!a.isOp(),"Actual alliance attachment failed");
            log("INVITE_ALLOWED",Map.of("actor",a.getUniqueId(),"permission",a.hasPermission("starcore.nation.ally.add"),"op",a.isOp()));
        },()->fail("invite-permission",new IllegalStateException("Actual actor retired")),1L);
    }
    private void inviteCheck()throws Exception{
        Player a=player("A"),b=player("B");Object from=call(nation(a),"id"),to=call(nation(b),"id"),diplomacy=service("module.diplomacy.DiplomacyService");
        Object pending=call(diplomacy,"getPendingInvites",to);require(pending instanceof Collection<?> list&&list.contains(from),"No actual native pending invitation");
        log("INVITE_NATIVE",Map.of("from",from.toString(),"to",to.toString(),"pending",((Collection<?>)pending).stream().map(Object::toString).toList(),"relation",call(diplomacy,"relationBetween",from,to).toString()));
        UUID actor=a.getUniqueId();String nation=from.toString(),target=to.toString();
        api.instances(actor).whenComplete((items,error)->{
            if(error!=null){fail("invite-receipt",error);return;}
            var item=items.stream().filter(i->i.definition().id().equals("phase-two-invite")&&i.completed()).findFirst().orElseThrow();
            String outcome=RuleEngine.stable(item.id(),"node/end","invite","authority",nation);
            Delivery duplicate=new Delivery(outcome,item.id(),"authority","diplomacy.invite",target,actor,nation,"starcore.nation.ally.add");
            a.getScheduler().execute(this,()->{
                AuthorityProvider provider=getServer().getServicesManager().load(AuthorityProvider.class);require(provider!=null,"Native provider absent");
                provider.apply(duplicate).whenComplete((result,failure)->{if(failure!=null)fail("invite-coalesce",failure);else if(result!=CoreGateway.DeliveryResult.APPLIED)fail("invite-coalesce",new IllegalStateException("Unexpected retained result "+result));else log("INVITE_COALESCED",Map.of("outcome",outcome,"result",result.toString(),"originalAlreadyCommitted",true));});
            },()->fail("invite-coalesce",new IllegalStateException("Actual actor retired")),1L);
        });
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(sender instanceof Player){sender.sendMessage("Isolated console fixture only");return true;}
        try{
            switch(args.length==0?"help":args[0]){
                case "seed"->seed();case "begin"->begin(false);case "replay"->begin(true);case "source"->log("SOURCE",capturedSource());
                case "vote"->vote(args[1],args[2],args[3]);case "late-member"->lateMember();
                case "facts"->facts(args[1]);case "inspect"->inspect(args[1]);case "native"->nativeState(args[1]);
                case "invalid"->rejectChoice(args[1],args[2],false);case "complete-replay"->rejectChoice(args[1],args[2],true);
                case "invite-config"->inviteConfig();case "invite-begin"->inviteBegin();case "invite-choose"->inviteChoose();case "allow"->allowInvite();case "invite-check"->inviteCheck();
                case "phase"->log("PHASE",Map.of("name",String.join(" ",Arrays.copyOfRange(args,1,args.length))));
                default->log("HELP",Map.of("commands","seed|begin|vote A summons convene|facts D|inspect E|late-member|native A|invalid A witness|complete-replay A testimony|replay|phase BOOT1"));
            }
        }catch(Throwable error){fail(args.length==0?"command":args[0],error);}return true;
    }
    private static Object call(Object target,String name,Object...args)throws Exception{
        if(target==null)throw new IllegalStateException("Missing native object for "+name);
        for(Method method:target.getClass().getMethods()){
            if(!method.getName().equals(name)||method.getParameterCount()!=args.length)continue;boolean fits=true;Class<?>[] types=method.getParameterTypes();
            for(int i=0;i<types.length;i++)if(args[i]!=null&&!box(types[i]).isInstance(args[i])){fits=false;break;}
            if(fits)return method.invoke(target,args);
        }
        throw new NoSuchMethodException(target.getClass().getName()+"."+name);
    }
    private static Class<?> box(Class<?> type){if(type==boolean.class)return Boolean.class;if(type==int.class)return Integer.class;if(type==long.class)return Long.class;return type;}
}
