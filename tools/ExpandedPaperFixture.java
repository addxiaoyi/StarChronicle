package smoke;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.core.RuleEngine;
import io.github.addxiaoyi.starchronicle.core.StoryInstance;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Test fixture only: actual connected players, actual StarCore services, no direct database writes. */
public final class ExpandedPaperFixture extends JavaPlugin implements CommandExecutor {
    private ChronicleService api;
    private Plugin core;
    private Object registry;
    private final Map<UUID,PermissionAttachment> permissions=new HashMap<>();
    private WorldEvent pendingOffline;
    private static final String ALLY="starcore.nation.ally.add";

    @Override public void onEnable(){
        try{
            core=Objects.requireNonNull(getServer().getPluginManager().getPlugin("StarCore"));
            registry=call(call(core,"context"),"serviceRegistry");
            api=Objects.requireNonNull(getServer().getServicesManager().load(ChronicleService.class));
            Objects.requireNonNull(getCommand("expanded")).setExecutor(this);
            log("READY core="+core.isEnabled()+" service=true");
        }catch(Exception error){fail("ENABLE",error);getServer().getPluginManager().disablePlugin(this);}
    }
    private void log(String message){getLogger().info("EXPANDED_"+message);}
    private void fail(String operation,Throwable error){getLogger().severe("EXPANDED_FAILED operation="+operation+" cause="+error);error.printStackTrace();}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private Player player(String shortName){Player p=getServer().getPlayerExact(shortName.equals("A")?"ExpandedCoreA":"ExpandedCoreB");require(p!=null&&p.isOnline(),"Expected actual connected client "+shortName);return p;}
    private Object service(String suffix)throws Exception{
        Class<?> type=Class.forName("dev.starcore.starcore."+suffix,true,core.getClass().getClassLoader());
        return ((Optional<?>)call(registry,"find",type)).orElseThrow(()->new IllegalStateException("Missing service "+suffix));
    }
    private Object nation(Player player)throws Exception{return ((Optional<?>)call(service("module.nation.NationService"),"nationOf",player.getUniqueId())).orElseThrow(()->new IllegalStateException("Client has no real native nation"));}
    private String nationId(Player player)throws Exception{return call(nation(player),"id").toString();}
    private void grant(Player player,boolean ally){
        PermissionAttachment attachment=permissions.compute(player.getUniqueId(),(ignored,old)->old!=null&&old.getPermissible()==player?old:player.addAttachment(this));
        attachment.setPermission("starchronicle.use",true);attachment.setPermission("starchronicle.admin",true);attachment.setPermission(ALLY,ally);
        player.recalculatePermissions();require(!player.isOp(),"Acceptance clients must not be operators");
        require(player.hasPermission(ALLY)==ally,"Actual permission attachment did not apply");
        log("PERMISSIONS player="+player.getName()+" ally="+player.hasPermission(ALLY)+" op="+player.isOp());
    }
    private void seed()throws Exception{
        require(getServer().getOnlinePlayers().size()==2,"Expected exactly two actual protocol clients");Object nations=service("module.nation.NationService");
        for(String name:List.of("A","B")){
            Player p=player(name);grant(p,false);p.setGameMode(GameMode.CREATIVE);
            String nationName=name.equals("A")?"ExpandedAlpha":"ExpandedBeta";
            Object found=((Optional<?>)call(nations,"nationByName",nationName)).orElse(null);
            if(found==null)found=call(nations,"createNation",p.getUniqueId(),p.getName(),nationName);
            require(found!=null,"Native nation creation returned null");Object id=call(found,"id");
            require(Boolean.TRUE.equals(call(nations,"isFounder",p.getUniqueId(),id)),"Real connected player is not native founder");
            require(nationId(p).equals(id.toString()),"Native membership does not match created nation");
            log("NATION player="+p.getName()+" playerId="+p.getUniqueId()+" nation="+id+" founder=true");
        }
        Object chronicle=getServer().getPluginManager().getPlugin("StarChronicle");Object gateway=call(chronicle,"gateway");
        for(String scene:List.of("chronicle_last_heir","chronicle_siege_embers","chronicle_silk_road"))require(Boolean.TRUE.equals(call(gateway,"hasScript",scene)),"Chronicle did not auto-install native scene "+scene);
        log("SCENE_CAPABILITIES allThreeInstalled=true");
    }
    private Instant eventTime(){
        String time=getConfig().getString("fixture-source-time");
        if(time==null){time=Instant.now().toString();getConfig().set("fixture-source-time",time);saveConfig();}
        return Instant.parse(time);
    }
    private WorldEvent event(String id,String type,Collection<Player> players)throws Exception{
        Set<UUID> actors=new TreeSet<>();Set<String> nations=new TreeSet<>();for(Player p:players){actors.add(p.getUniqueId());nations.add(nationId(p));}
        return new WorldEvent(id,type,eventTime(),actors,nations,Map.of("test-only","expanded actual-client acceptance"),"ExpandedPaperFixture","Isolated test-only story signal from two real connected clients; membership is read from actual NationService. Not a claim of a natural production-world discovery.");
    }
    private void submitNative()throws Exception{
        api.submit(event("expanded:heir-discovery:1","ANCIENT_HEIR_DISCOVERED",List.of(player("A"),player("B")))).whenComplete((r,e)->{if(e!=null)fail("BEGIN",e);else log("NATIVE_SUBMITTED duplicate="+r.duplicate()+" instances="+r.instanceIds());});
    }
    private void vote(String shortName,String choice){vote("last-heir",shortName,choice);}
    private void vote(String storyId,String shortName,String choice){
        Player p=player(shortName);UUID id=p.getUniqueId();String name=p.getName();
        api.instances(id).thenCompose(items->{var item=items.stream().filter(i->i.definition().id().equals(storyId)).findFirst().orElseThrow();return api.choose(item.id(),id,item.node(),choice);})
                .whenComplete((r,e)->{if(e!=null)fail("VOTE",e);else log("VOTE story="+storyId+" player="+name+" choice="+choice+" result="+r.message());});
    }
    private void scene(String shortName){
        UUID id=player(shortName).getUniqueId();api.scene(id).whenComplete((scene,error)->{if(error!=null)fail("SCENE",error);else log("SCENE player="+shortName+" present="+scene.isPresent()+scene.map(s->" script="+s.scriptId()+" node="+s.nodeId()+" choices="+s.choices()+" text="+s.text()).orElse(""));});
    }
    private void inviteConfig()throws Exception{
        Player a=player("A"),b=player("B");String target=nationId(b);
        String definition="""
                id: expanded-invite
                version: "1"
                title: "Actual StarCore alliance invitation acceptance"
                scope: PLAYER
                triggers: [EXPANDED_INVITATION_TEST]
                condition: {fact: player.id, op: eq, value: "%s"}
                start: proposal
                nodes:
                  proposal:
                    text: "Explicit native permission is required before sending."
                    choices:
                      send: {label: "Send actual native invitation", next: end}
                  end:
                    text: "The authoritative native adapter decides the result."
                    terminal: true
                    effects:
                      - {id: invite, type: authority, target: diplomacy.invite, value: "%s", permission: starcore.nation.ally.add}
                """.formatted(a.getUniqueId(),target);
        Path stories=getServer().getPluginManager().getPlugin("StarChronicle").getDataFolder().toPath().resolve("stories");
        Files.writeString(stories.resolve("expanded-invite.yml"),definition);log("INVITE_CONFIG sourceNation="+nationId(a)+" targetNation="+target);
        getServer().dispatchCommand(getServer().getConsoleSender(),"chronicle reload");
    }
    private void inviteBegin()throws Exception{api.submit(event("expanded:invite-source:1","EXPANDED_INVITATION_TEST",List.of(player("A")))).whenComplete((r,e)->{if(e!=null)fail("INVITE_BEGIN",e);else log("INVITE_SUBMITTED duplicate="+r.duplicate()+" instances="+r.instanceIds());});}
    private void inviteChoose(){
        UUID id=player("A").getUniqueId();api.instances(id).thenCompose(items->{var item=items.stream().filter(i->i.definition().id().equals("expanded-invite")).findFirst().orElseThrow();return api.choose(item.id(),id,item.node(),"send");})
                .whenComplete((r,e)->log(e==null?"INVITE_CHOSEN result="+r.message():"INVITE_CHOICE_REJECTED "+e));
    }
    private void invitationState()throws Exception{
        Player a=player("A"),b=player("B");Object idA=call(nation(a),"id"),idB=call(nation(b),"id"),diplomacy=service("module.diplomacy.DiplomacyService");
        Object pending=call(diplomacy,"getPendingInvites",idB);require(pending instanceof Collection<?> list&&list.contains(idA),"No actual native pending invitation");
        log("INVITE_NATIVE pending="+pending+" relation="+call(diplomacy,"relationBetween",idA,idB));
        UUID actor=a.getUniqueId();String own=idA.toString(),target=idB.toString();
        api.instances(actor).whenComplete((items,error)->{
            if(error!=null){fail("INVITE_DUPLICATE",error);return;}
            var item=items.stream().filter(i->i.definition().id().equals("expanded-invite")&&i.completed()).findFirst().orElseThrow();
            String outcome=RuleEngine.stable(item.id(),"node/end","invite","authority",own);
            Delivery duplicate=new Delivery(outcome,item.id(),"authority","diplomacy.invite",target,actor,own,ALLY);
            a.getScheduler().execute(this,()->{
                AuthorityProvider provider=getServer().getServicesManager().load(AuthorityProvider.class);require(provider!=null,"Native provider not registered");
                provider.apply(duplicate).whenComplete((result,failure)->{
                    if(failure!=null){fail("INVITE_DUPLICATE",failure);return;}
                    require(result==CoreGateway.DeliveryResult.APPLIED,"Repeated admitted ID did not coalesce to APPLIED");
                    log("INVITE_DUPLICATE result="+result+" outcome="+outcome+" originalAlreadyCommitted=true");
                });
            },()->log("RETIRED invite-duplicate"),1L);
        });
    }
    private void technology()throws Exception{
        Player a=player("A");Object id=call(nation(a),"id"),technology=service("module.technology.TechnologyService");
        @SuppressWarnings("unchecked") Collection<String> available=(Collection<String>)call(technology,"availableTechnologies");
        String key=available.stream().sorted().filter(candidate->{try{return !Boolean.TRUE.equals(call(technology,"hasTechnology",id,candidate));}catch(Exception error){throw new IllegalStateException(error);}}).findFirst().orElseThrow(()->new IllegalStateException("No actual locked technology available"));
        require(Boolean.TRUE.equals(call(technology,"unlock",id,key)),"Native technology unlock was rejected");require(Boolean.TRUE.equals(call(technology,"hasTechnology",id,key)),"Native unlock not readable after persistence");
        log("TECHNOLOGY_UNLOCKED nation="+id+" key="+key+" source=TechnologyService.unlock");
    }
    private void disband()throws Exception{
        Player b=player("B");Object nation=nation(b),id=call(nation,"id"),nations=service("module.nation.NationService");
        require(Boolean.TRUE.equals(call(nations,"disbandNation",id,b.getUniqueId())),"Native founder disband was rejected");
        require(((Optional<?>)call(nations,"nationById",id)).isEmpty(),"Nation remains present after committed disband");
        log("NATION_DISBANDED nation="+id+" actor="+b.getUniqueId()+" source=NationService.disbandNation");
    }
    private void inspect(String shortName){
        Player p=player(shortName);UUID id=p.getUniqueId();
        api.npc(id,"ancient-heir").whenComplete((v,e)->{if(e!=null)fail("NPC",e);else log("NPC player="+shortName+" reputation="+v.reputation()+" trust="+v.trust()+" growth="+v.growth()+" position="+v.position()+" dialogue="+v.dialogueId());});
        api.fate(id).whenComplete((v,e)->{if(e!=null)fail("FATE",e);else log("FATE player="+shortName+" role="+v.get("player.role")+" honor="+v.get("player.honor")+" title="+v.get("title.old-capital-guardian"));});
    }
    private void history(){api.history(null,null,0,100).whenComplete((events,error)->{if(error!=null)fail("HISTORY",error);else for(var event:events)log("HISTORY id="+event.id()+" type="+event.type()+" nations="+event.nations()+" actors="+event.participants()+" source="+event.source()+" evidence="+event.evidence());});}
    private void armOffline()throws Exception{
        pendingOffline=event("expanded:offline-source:1","EXPANDED_OFFLINE_TEST",List.of(player("A"),player("B")));
        String definition="""
                id: expanded-offline
                version: "1"
                title: "Deferred real audience acceptance"
                scope: WORLD
                triggers: [EXPANDED_OFFLINE_TEST]
                start: waiting
                nodes:
                  waiting:
                    text: "Both actual participants were offline when the source was submitted."
                    quorum: 2
                    choices:
                      finish: {label: "Confirm recovered shared instance", next: end}
                  end:
                    text: "Recovered without replaying the source history."
                    terminal: true
                """;
        Path stories=getServer().getPluginManager().getPlugin("StarChronicle").getDataFolder().toPath().resolve("stories");Files.writeString(stories.resolve("expanded-offline.yml"),definition);
        getServer().dispatchCommand(getServer().getConsoleSender(),"chronicle reload");log("OFFLINE_ARMED actualParticipants="+pendingOffline.participants()+" realNations="+pendingOffline.nations());
    }
    private void submitOffline(){
        require(pendingOffline!=null,"First capture the actual connected source audience with offline-arm");require(getServer().getOnlinePlayers().isEmpty(),"Both clients must have normally disconnected before offline submission");
        api.submit(pendingOffline).whenComplete((report,error)->{if(error!=null)fail("OFFLINE_SUBMIT",error);else log("OFFLINE_SUBMITTED duplicate="+report.duplicate()+" instances="+report.instanceIds());});
    }
    private void offlineState(){api.activeInstances().whenComplete((instances,error)->{if(error!=null)fail("OFFLINE_STATE",error);else log("OFFLINE_STATE instances="+instances.stream().filter(i->i.definition().id().equals("expanded-offline")).map(i->i.id()+" members="+i.members().size()+" source="+i.sourceEventId()+" node="+i.node()).toList());});}
    private void nativeState(String shortName)throws Exception{
        Player p=player(shortName);UUID id=p.getUniqueId();Object nativeEngine=service("module.narrative.engine.NarrativeEngine");
        Object state=call(nativeEngine,"getState",id),session=call(nativeEngine,"getSession",id);
        log("NATIVE_STATE player="+shortName+" routing="+call(nativeEngine,"hasActiveSession",id)+" session="+(session==null?"<absent>":call(session,"scriptId")+"/"+call(session,"currentNodeId"))+" variables="+call(state,"getVariables")+" flags="+call(state,"getFlags")+" evidence="+call(state,"getEvidence"));
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(sender instanceof Player){sender.sendMessage("Isolated console acceptance only");return true;}
        String action=args.length==0?"help":args[0];try{
            switch(action){
                case "seed"->seed();case "begin"->submitNative();case "scene"->scene(args[1]);case "vote"->vote(args[1],args[2]);
                case "invite-config"->inviteConfig();case "invite-begin"->inviteBegin();case "invite-choose"->inviteChoose();case "invite-check"->invitationState();
                case "allow"->{Player a=player("A");a.getScheduler().execute(this,()->grant(a,true),()->log("RETIRED permission"),1L);}
                case "tech"->technology();case "disband"->disband();case "history"->history();case "inspect"->inspect(args[1]);
                case "offline-arm"->armOffline();case "offline-submit"->submitOffline();case "offline-state"->offlineState();case "offline-vote"->vote("expanded-offline",args[1],"finish");case "native-state"->nativeState(args[1]);
                case "phase"->log("PHASE "+String.join(" ",Arrays.copyOfRange(args,1,args.length)));
                case "view"->{Player p=player(args[1]);p.getScheduler().execute(this,()->{var view=p.getOpenInventory();log("VIEW player="+p.getName()+" title="+view.getTitle()+" holder="+view.getTopInventory().getHolder().getClass().getName()+" items="+Arrays.stream(view.getTopInventory().getContents()).filter(Objects::nonNull).count());},()->log("RETIRED view"),1L);}
                default->log("HELP seed|begin|scene A|vote A choice|invite-config|invite-begin|invite-choose|allow|invite-check|tech|disband|history|inspect A|view A");
            }
        }catch(Throwable error){fail(action,error);}return true;
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
    private static Class<?> box(Class<?> type){if(type==int.class)return Integer.class;if(type==long.class)return Long.class;if(type==boolean.class)return Boolean.class;return type;}
}
