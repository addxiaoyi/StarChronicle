package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.api.NativeScene;
import io.github.addxiaoyi.starchronicle.api.NationalVoteState;
import io.github.addxiaoyi.starchronicle.api.ChronicleService;
import io.github.addxiaoyi.starchronicle.api.quest.QuestGateState;
import io.github.addxiaoyi.starchronicle.api.quest.QuestObservation;
import io.github.addxiaoyi.starchronicle.api.quest.QuestRequest;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.core.StoryInstance;
import io.github.addxiaoyi.starchronicle.core.ProcessReport;
import io.github.addxiaoyi.starchronicle.memory.HistoryBook;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BookMeta;

/** Immutable query snapshots are fetched off-region; inventories are built on the viewer's entity thread. */
public final class Menus implements Listener {
    private static final int PAGE=45;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
    private final StarChroniclePlugin plugin;
    private final Map<UUID,Long> nationalRequests=new ConcurrentHashMap<>();
    private final AtomicLong nationalRequestSequence=new AtomicLong();
    private final Map<UUID,Long> questGateRequests=new ConcurrentHashMap<>();
    private final AtomicLong questGateRequestSequence=new AtomicLong();
    private static final int GATE_PAGE=36;
    public Menus(StarChroniclePlugin plugin) { this.plugin=plugin; }

    /** Identity, not inventory title, authorizes menu handling. Each session belongs to one viewer. */
    static final class Session implements InventoryHolder {
        final UUID owner; final Map<Integer,Runnable> actions=new HashMap<>(); Inventory inventory;
        Session(UUID owner) { this.owner=owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
    @EventHandler public void click(InventoryClickEvent event) {
        Inventory top=event.getView().getTopInventory();
        if(!(top.getHolder() instanceof Session session)) return;
        event.setCancelled(true); // includes shift, number-key, drop, collect and lower-inventory transfers
        if(!session.owner.equals(event.getWhoClicked().getUniqueId())) return;
        if(event.getRawSlot()<0 || event.getRawSlot()>=top.getSize()) return;
        if(!event.isLeftClick() || event.isShiftClick()) return;
        Runnable action=session.actions.get(event.getRawSlot());
        if(action!=null) action.run();
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof Session) event.setCancelled(true);
    }
    @EventHandler public void left(PlayerQuitEvent event){nationalRequests.remove(event.getPlayer().getUniqueId());questGateRequests.remove(event.getPlayer().getUniqueId());}
    public void history(UUID viewer, String nation, UUID person, int page) {
        int safePage=Math.max(0,page);
        plugin.service().history(nation,person,safePage,PAGE).whenComplete((events,error)-> {
            if(error!=null) { plugin.failure(viewer,error);return; }
            plugin.present(viewer,p->{
                Session s=create(viewer,"世界编年史 · "+(safePage+1));
                for(int i=0;i<Math.min(PAGE,events.size());i++) {
                    WorldEvent e=events.get(i); int slot=i;
                    put(s,slot,Material.WRITTEN_BOOK,e.type(),List.of(TIME.format(e.occurredAt()),"国家: "+String.join(", ",e.nations()),"来源: "+e.source(),"点击查看完整证据与事件 ID"),()->evidence(viewer,e,nation,person,safePage,0));
                }
                if(safePage>0) put(s,45,Material.ARROW,"上一页",List.of(),()->history(viewer,nation,person,safePage-1));
                put(s,46,Material.CLOCK,"全服历史",List.of(),()->history(viewer,null,null,0));
                String ownNation=plugin.gateway().context(viewer).nationId();
                put(s,47,Material.WHITE_BANNER,"本国历史",List.of(ownNation.isBlank()?"暂无可核验国家身份":ownNation),()-> {
                    if(ownNation.isBlank()) plugin.message(viewer,"暂无可核验国家身份。");else history(viewer,ownNation,null,0);
                });
                put(s,48,Material.PLAYER_HEAD,"个人传记",List.of(),()->history(viewer,null,viewer,0));
                put(s,49,Material.COMPASS,"我的剧情",List.of(),()->stories(viewer));
                if(events.size()>=PAGE) put(s,53,Material.ARROW,"下一页",List.of(),()->history(viewer,nation,person,safePage+1));
                if(events.isEmpty()) put(s,22,Material.PAPER,"暂无匹配历史",List.of("已提交的真实世界事件会出现在此处。"),null);
                p.openInventory(s.inventory);
            });
        });
    }
    private void evidence(UUID viewer,WorldEvent event,String nation,UUID person,int historyPage,int detailPage) {
        List<String> details=new ArrayList<>();
        details.add("事件 ID\n"+event.id());details.add("类型\n"+event.type());details.add("发生时间\n"+TIME.format(event.occurredAt()));
        details.add("来源\n"+event.source());details.add("所属国家\n"+String.join(", ",event.nations()));
        event.participants().stream().sorted().forEach(p->details.add("参与者 UUID\n"+p));
        chunks("来源证据",event.evidence(),details);
        new TreeMap<>(event.facts()).forEach((key,value)->chunks("事实 · "+key,value,details));
        int page=Math.max(0,Math.min(detailPage,Math.max(0,(details.size()-1)/PAGE)));
        plugin.present(viewer,p->{
            Session s=create(viewer,"历史证据 · "+(page+1));
            for(int i=0;i<PAGE && page*PAGE+i<details.size();i++) {
                String part=details.get(page*PAGE+i);int split=part.indexOf('\n');
                put(s,i,Material.PAPER,split<0?part:part.substring(0,split),wrap(split<0?"":part.substring(split+1)),null);
            }
            put(s,49,Material.BOOK,"返回历史",List.of(),()->history(viewer,nation,person,historyPage));
            put(s,48,Material.WRITTEN_BOOK,"阅读真实历史书",List.of("书中保留本事件 ID、来源与完整证据。"),()->book(viewer,List.of(event)));
            if(page>0) put(s,45,Material.ARROW,"上一页证据",List.of(),()->evidence(viewer,event,nation,person,historyPage,page-1));
            if((page+1)*PAGE<details.size()) put(s,53,Material.ARROW,"下一页证据",List.of(),()->evidence(viewer,event,nation,person,historyPage,page+1));
            p.openInventory(s.inventory);
        });
    }
    public void event(UUID viewer,WorldEvent event){evidence(viewer,event,null,null,0,0);}
    public void book(UUID viewer,List<WorldEvent> events) {
        final List<String> pages;
        try{pages=HistoryBook.pages(events);}catch(IllegalArgumentException error){plugin.failure(viewer,error);return;}
        plugin.present(viewer,p->{
            ItemStack book=new ItemStack(Material.WRITTEN_BOOK);BookMeta meta=(BookMeta)book.getItemMeta();
            meta.title(Component.text("世界编年史"));meta.author(Component.text("StarChronicle"));meta.pages(pages.stream().<Component>map(Component::text).toList());book.setItemMeta(meta);p.openBook(book);
        });
    }
    public void npc(UUID viewer,String npcId) {
        plugin.service().npc(viewer,npcId).whenComplete((npc,error)->{
            if(error!=null){plugin.failure(viewer,error);return;}
            plugin.present(viewer,p->{
                Session s=create(viewer,"人物档案 · "+npc.name());
                put(s,4,Material.PLAYER_HEAD,npc.name(),List.of("阵营: "+npc.faction(),"职位: "+npc.position(),"成长: "+npc.growth()),null);
                put(s,10,Material.BOOK,"人物背景",wrap(npc.background()),null);
                put(s,12,Material.EMERALD,"你与此人的关系",List.of("声望: "+npc.reputation(),"信任: "+npc.trust(),"敌对: "+npc.hostility()),null);
                put(s,14,Material.PAPER,"此刻的对话",wrap(npc.dialogue()),null);
                put(s,31,Material.COMPASS,"相关剧情",List.of("按当前身份和已发生的事件筛选。"),()->stories(viewer));
                put(s,49,Material.CLOCK,"查阅世界历史",List.of(),()->history(viewer,null,null,0));p.openInventory(s.inventory);
            });
        });
    }
    public void fate(UUID viewer) {
        plugin.service().fate(viewer).whenComplete((facts,error)->{
            if(error!=null){plugin.failure(viewer,error);return;}
            plugin.present(viewer,p->{
                Session s=create(viewer,"我的命运与历史荣誉");
                put(s,4,Material.PLAYER_HEAD,"当前命运",List.of("身份: "+facts.getOrDefault("player.role","unassigned"),"荣誉: "+facts.getOrDefault("player.honor","0")),null);
                int slot=9;for(var e:new TreeMap<>(facts).entrySet())if(e.getKey().startsWith("title.")&&slot<45)put(s,slot++,Material.NAME_TAG,e.getValue(),List.of("历史称号: "+e.getKey()),null);
                put(s,48,Material.WRITTEN_BOOK,"我的传记",List.of(),()->history(viewer,null,viewer,0));
                put(s,49,Material.COMPASS,"专属剧情",List.of(),()->stories(viewer));p.openInventory(s.inventory);
            });
        });
    }
    public void scene(UUID viewer) {
        plugin.service().scene(viewer).whenComplete((scene,error)->{
            if(error!=null){plugin.failure(viewer,error);return;}
            if(scene.isEmpty()){plugin.message(viewer,"暂无自有StarCore场景；Folia上未完成上游兼容时保持关闭。");return;}
            nativeScene(viewer,scene.get());
        });
    }
    private void nativeScene(UUID viewer,NativeScene scene) {
        plugin.present(viewer,p->{
            Session s=create(viewer,"原生场景 · "+scene.title());
            put(s,4,Material.WRITTEN_BOOK,scene.title(),List.of("执行器: StarCore NarrativeEngine","脚本: "+scene.scriptId(),"节点: "+scene.nodeId()),null);
            put(s,0,Material.PAPER,"场景文本",wrap(scene.text()),null);
            java.util.function.IntConsumer act=choice->plugin.service().sceneAction(viewer,scene.scriptId(),scene.nodeId(),choice).whenComplete((next,error)->{
                if(error!=null)plugin.failure(viewer,error);
                else if(next.isPresent())nativeScene(viewer,next.get());
                else {plugin.message(viewer,"原生补充场景已结束；共同议会进度仍由编年史剧情控制。");stories(viewer);}
            });
            if(scene.choices().isEmpty())put(s,31,Material.LIME_DYE,"继续",List.of("由StarCore执行分支和效果。"),()->act.accept(0));
            else {int slot=9;for(var choice:scene.choices())put(s,slot++,Material.LIME_DYE,choice.text(),List.of("原生选项: "+choice.index()),()->act.accept(choice.index()));}
            put(s,49,Material.COMPASS,"共同剧情",List.of(),()->stories(viewer));p.openInventory(s.inventory);
        });
    }
    public void stories(UUID viewer) {stories(viewer,List.of(""),0);}
    private void stories(UUID viewer,List<String> cursors,int page) {
        nationalRequests.remove(viewer);
        questGateRequests.remove(viewer);
        plugin.service().storyPage(viewer,cursors.get(page).isEmpty()?null:cursors.get(page),PAGE).whenComplete((result,error)->{
            if(error!=null){plugin.failure(viewer,error);return;}
            var items=result.instances();
            plugin.present(viewer,p->{
                Session s=create(viewer,"共同书写历史 · "+(page+1));
                for(int i=0;i<items.size();i++) {
                    StoryInstance story=items.get(i);
                    put(s,i,story.completed()?Material.ENCHANTED_BOOK:Material.BOOK,story.title(),List.of("实例: "+story.id(),"范围: "+story.definition().scope(),"参与人数: "+story.members().size(),story.completed()?"已完成":story.contains(viewer)?"点击继续剧情":"点击查看并加入"),()->story(viewer,story));
                }
                if(items.isEmpty()) put(s,22,Material.PAPER,"本页暂无可见剧情",List.of(result.nextCursor()==null?"世界事件满足前置条件后会开启剧情。":"可继续翻页查看后续剧情。"),null);
                if(page>0) put(s,45,Material.ARROW,"上一页",List.of(),()->stories(viewer,cursors,page-1));
                put(s,49,Material.CLOCK,"世界历史",List.of(),()->history(viewer,null,null,0));
                put(s,48,Material.WRITTEN_BOOK,"继续原生补充场景",List.of("使用StarCore已有剧情执行器。"),()->scene(viewer));
                if(result.nextCursor()!=null) put(s,53,Material.ARROW,"下一页",List.of(),()->{
                    List<String> next=new ArrayList<>(cursors.subList(0,page+1));next.add(result.nextCursor());stories(viewer,List.copyOf(next),page+1);
                });
                p.openInventory(s.inventory);
            });
        });
    }
    void membership(UUID viewer,String instanceId) {
        plugin.service().instance(viewer,instanceId).thenCompose(item->{
            StoryInstance instance=item.filter(i->i.contains(viewer))
                    .orElseThrow(()->new SecurityException("只能管理自己已加入的剧情。"));
            return plugin.service().memberReview(instanceId,viewer).thenAccept(review->plugin.present(viewer,p->{
                Session s=create(viewer,"成员资格 · "+instance.title());
                List<String> details=new ArrayList<>(List.of("实例: "+instanceId,"加入时国家: "+instance.members().get(viewer)));
                details.add(review.map(r->switch(r.status()){case PENDING->"资格待复核";case APPROVED->"已核验";case EXITED->"已退出";}).orElse("已加入"));
                review.ifPresent(r->{details.add("最近观察国家: "+r.observedNationId());details.add("检查时间: "+r.checkedAt());});
                put(s,4,Material.PLAYER_HEAD,"当前成员记录",details,null);
                if(!instance.completed()) {
                    put(s,20,Material.EMERALD,"重新核验资格",List.of("须恢复原国家，并满足当前剧情条件。","换国后可以先退出，再显式申请加入。"),()->plugin.service().revalidateMember(instanceId,viewer).whenComplete((report,error)->{
                        if(error!=null)plugin.failure(viewer,error);else plugin.message(viewer,report.message());membership(viewer,instanceId);
                    }));
                    put(s,24,Material.BARRIER,"退出此剧情…",List.of("移除当前个人票；保留历史选择和任务回执。","国家席位需由当前代表单独撤回。"),()->plugin.present(viewer,owned->{
                        Session confirm=create(viewer,"确认退出 · "+instance.title());
                        put(confirm,20,Material.BARRIER,"确认退出",List.of("后台不会自动将你重新加入。","重新加入仍须满足剧情条件。"),()->plugin.service().exitMember(instanceId,viewer,instance.node()).whenComplete((report,error)->{
                            if(error!=null)plugin.failure(viewer,error);else{plugin.message(viewer,report.message());stories(viewer);}
                        }));
                        put(confirm,24,Material.ARROW,"保留成员资格",List.of(),()->membership(viewer,instanceId));owned.openInventory(confirm.inventory);
                    }));
                }
                put(s,49,Material.ARROW,"返回剧情列表",List.of(),()->stories(viewer));p.openInventory(s.inventory);
            }));
        }).exceptionally(error->{plugin.failure(viewer,error);return null;});
    }
    private void story(UUID viewer,StoryInstance instance) {
        questGateRequests.remove(viewer);
        if(instance.definition().voteUnit()==StoryDefinition.VoteUnit.NATION && instance.contains(viewer)){national(viewer,instance.id());return;}
        plugin.service().availableChoices(instance.id(),viewer).whenComplete((available,error)->{
        if(error!=null){plugin.failure(viewer,error);return;}
        plugin.present(viewer,p->{
            Session s=create(viewer,instance.title());
            StoryDefinition.Node node=instance.definition().nodes().get(instance.node());
            put(s,4,Material.WRITTEN_BOOK,instance.title(),instance.definition().voteUnit()==StoryDefinition.VoteUnit.NATION
                    ?List.of("实例: "+instance.id(),"当前阶段: "+instance.node(),"国家投票：一国一席，加入后可查看已记录票。")
                    :List.of("实例: "+instance.id(),"当前阶段: "+instance.node(),"投票: "+instance.votes().size()+" / "+node.quorum()),null);
            List<String> text=wrap(node.text()==null?"":node.text());
            put(s,0,Material.PAPER,"剧情文本",text,null);
            if(!instance.contains(viewer) && !instance.completed()) {
                put(s,31,Material.EMERALD,"申请加入剧情",List.of("服务端将重新验证身份、前置条件与国家。"),()->plugin.service().join(instance.id(),viewer).whenComplete((report,joinError)->{
                    if(joinError!=null)plugin.failure(viewer,joinError);else{plugin.message(viewer,report.message());stories(viewer);}
                }));
            } else if(!instance.completed()) {
                int slot=9;
                for(var entry:new TreeMap<>(available).entrySet()) {
                    if(slot>=45)break;
                    String id=entry.getKey();StoryDefinition.Choice choice=entry.getValue();
                    put(s,slot++,Material.LIME_DYE,choice.label(),List.of("选项: "+id,"权限和条件由服务端即时重新验证。","同一阶段每人保留一票；达到法定人数后推进。"),()->plugin.service().choose(instance.id(),viewer,instance.node(),id).whenComplete((report,choiceError)->{
                        if(choiceError!=null)plugin.failure(viewer,choiceError);else{plugin.message(viewer,report.message());stories(viewer);}
                    }));
                }
            } else put(s,31,Material.GOLD_INGOT,"此篇历史已落定",List.of("结果与效果收据已持久化。"),null);
            if(instance.contains(viewer))put(s,50,Material.IRON_DOOR,"任务门槛状态",List.of("查看本阶段原生任务与完成记录摘要。","可核对本人的任务，或重试已确认终止的任务。"),()->questGates(viewer,instance.id(),instance.node(),0));
            if(instance.contains(viewer)&&!instance.completed())put(s,48,Material.PLAYER_HEAD,"成员资格与退出",List.of("查看待复核原因、重新核验或退出此剧情。"),()->membership(viewer,instance.id()));
            put(s,49,Material.ARROW,"返回剧情列表",List.of(),()->stories(viewer));
            p.openInventory(s.inventory);
        });
        });
    }
    /** A country page is assembled from one pinned node and one revision token. The
     * second state read detects changes while choice conditions were being evaluated. */
    record NationalPage(StoryInstance instance,NationalVoteState state,Map<String,StoryDefinition.Choice> choices){
        NationalPage{choices=Map.copyOf(choices);}
    }
    private static final class ChangedNationalPage extends IllegalStateException {
        ChangedNationalPage(){super("国家投票节点或修订号已变化，请刷新后再操作。");}
    }
    static CompletionStage<NationalPage> nationalPage(ChronicleService service,UUID viewer,String instanceId,int retries){
        return service.instance(viewer,instanceId).thenCompose(item->{
            StoryInstance instance=item.filter(i->i.id().equals(instanceId)&&i.contains(viewer))
                    .orElseThrow(()->new SecurityException("只能查看自己已加入实例的国家票。"));
            if(instance.definition().voteUnit()!=StoryDefinition.VoteUnit.NATION)throw new IllegalArgumentException("该实例使用个人投票，请从剧情列表继续。");
            return service.nationalState(instanceId,viewer).thenCompose(before->{
                CompletionStage<Map<String,StoryDefinition.Choice>> visible=before.canVote()&&!before.completed()
                        ?service.availableChoices(instanceId,viewer):CompletableFuture.completedFuture(Map.of());
                return visible.thenCompose(choices->service.nationalState(instanceId,viewer).thenApply(after->{
                        if(!nationalPageMatches(instance,before,after))throw new ChangedNationalPage();
                        return new NationalPage(instance,after,choices);
                    }));
            });
        }).exceptionallyCompose(error->{
            Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
            return cause instanceof ChangedNationalPage&&retries>0?nationalPage(service,viewer,instanceId,retries-1):CompletableFuture.failedFuture(cause);
        });
    }
    static boolean nationalPageMatches(StoryInstance instance,NationalVoteState before,NationalVoteState after){
        return instance.id().equals(before.instanceId())&&instance.id().equals(after.instanceId())
                &&before.nodeId().equals(after.nodeId())&&(instance.completed()||instance.node().equals(after.nodeId()))
                &&instance.definition().nodes().containsKey(after.nodeId())
                &&before.viewerRevision()==after.viewerRevision()&&Objects.equals(before.viewerNationId(),after.viewerNationId())
                &&before.completed()==after.completed()&&instance.completed()==after.completed()
                &&before.representative()==after.representative()&&before.canVote()==after.canVote()&&before.canWithdraw()==after.canWithdraw()
                &&before.quorum()==after.quorum()&&instance.definition().nodes().get(after.nodeId()).quorum()==after.quorum()
                &&Objects.equals(before.resolution(),after.resolution());
    }
    /** Tokens are captured from the displayed state, never recovered from latest state at
     * click time. The backend retains the final CAS and live native authority check. */
    static Runnable nationalVoteAction(ChronicleService service,UUID viewer,NationalVoteState state,String choice,
                                      Consumer<ProcessReport> success,Consumer<Throwable> failure,Runnable refresh){
        return ()->{
            if(state.completed()||!state.canVote()){failure.accept(new SecurityException("当前国家票只能旁观或已冻结。"));refresh.run();return;}
            try{service.nationalVote(state.instanceId(),viewer,state.nodeId(),state.viewerRevision(),choice)
                    .whenComplete((report,error)->{if(error!=null)failure.accept(error);else success.accept(report);refresh.run();});}
            catch(RuntimeException error){failure.accept(error);refresh.run();}
        };
    }
    static Runnable nationalWithdrawAction(ChronicleService service,UUID viewer,NationalVoteState state,
                                          Consumer<ProcessReport> success,Consumer<Throwable> failure,Runnable refresh){
        return ()->{
            if(state.completed()||!state.canWithdraw()){failure.accept(new SecurityException("当前国家票不可撤回或已冻结。"));refresh.run();return;}
            try{service.withdrawNationalVote(state.instanceId(),viewer,state.nodeId(),state.viewerRevision())
                    .whenComplete((report,error)->{if(error!=null)failure.accept(error);else success.accept(report);refresh.run();});}
            catch(RuntimeException error){failure.accept(error);refresh.run();}
        };
    }
    public void national(UUID viewer,String instanceId){national(viewer,instanceId,false,0);}
    private void national(UUID viewer,String instanceId,boolean ledger,int requestedPage){
        questGateRequests.remove(viewer);
        long request=nationalRequestSequence.incrementAndGet();nationalRequests.put(viewer,request);
        nationalPage(plugin.service(),viewer,instanceId,2).whenComplete((page,error)->{
            if(!Objects.equals(nationalRequests.get(viewer),request))return;
            if(error!=null){plugin.failure(viewer,error);return;}
            plugin.present(viewer,p->{
                if(!Objects.equals(nationalRequests.get(viewer),request))return;
                if(ledger){nationalLedger(viewer,page,requestedPage,p);return;}
                NationalVoteState state=page.state();StoryDefinition.Node node=page.instance().definition().nodes().get(page.instance().completed()?page.instance().node():state.nodeId());
                Session s=create(viewer,"国家议会 · "+page.instance().title());
                put(s,4,Material.WRITTEN_BOOK,page.instance().title(),List.of("实例: "+state.instanceId(),"当前阶段: "+page.instance().node(),"国家票节点: "+state.nodeId(),"一国一席；法定国家席数: "+state.quorum()),null);
                put(s,0,Material.PAPER,"剧情文本",wrap(Objects.toString(node.text(),"")),null);
                put(s,1,Material.WHITE_BANNER,"国家票规则",List.of("国家票不等于代表本人或全体国民的个人意愿。","计数仅为已记录投影；最终落定会重新核验国家存续。","已冻结或已完成的票不能更改。"),null);
                List<String> viewerInfo=new ArrayList<>(List.of("当前核验国家: "+(state.viewerNationId().isBlank()?"无":state.viewerNationId()),"国家票修订号: "+state.viewerRevision(),state.representative()?"当前真实国家持有人资格已核验。":"当前没有已核验的国家代表资格，可旁观已记录票。"));
                viewerInfo.add(Objects.toString(state.message(),""));
                put(s,2,Material.PLAYER_HEAD,"你的国家代表资格",viewerInfo,null);
                List<String> counts=new ArrayList<>();new TreeMap<>(state.counts()).forEach((choice,count)->counts.add(choice+": "+count));
                if(counts.isEmpty())counts.add("暂无已记录的有效国家票。");counts.add("落定结果: "+(state.resolution()==null||state.resolution().isBlank()?"尚未落定":state.resolution()));
                put(s,6,Material.CLOCK,"已记录票投影",counts,null);
                Runnable refresh=()->national(viewer,instanceId);
                if(!state.completed()&&state.canVote()){
                    int slot=9;for(var entry:new TreeMap<>(page.choices()).entrySet()){
                        if(slot>=45)break;String choice=entry.getKey();
                        put(s,slot++,Material.LIME_DYE,entry.getValue().label(),List.of("国家选项: "+choice,"节点: "+state.nodeId(),"修订号: "+state.viewerRevision(),"提交时重新核验真实持有人和国家存续。"),
                                nationalVoteAction(plugin.service(),viewer,state,choice,report->plugin.message(viewer,report.message()),failure->plugin.failure(viewer,failure),refresh));
                    }
                }else put(s,31,Material.PAPER,state.completed()?"此篇国家决议已落定":"当前仅可旁观",List.of(Objects.toString(state.message(),"")),null);
                if(!state.completed()&&state.canWithdraw())put(s,46,Material.BARRIER,"撤回本国当前票",List.of("仅撤回未冻结票；保留修订历史。","节点: "+state.nodeId(),"修订号: "+state.viewerRevision()),
                        nationalWithdrawAction(plugin.service(),viewer,state,report->plugin.message(viewer,report.message()),failure->plugin.failure(viewer,failure),refresh));
                put(s,47,Material.BOOK,"各国已记录票",List.of("查看每国修订号、状态和提交者。"),()->national(viewer,instanceId,true,0));
                if(!state.completed())put(s,45,Material.PLAYER_HEAD,"成员资格与退出",List.of("个人退出保留国家席位与已冻结决议。"),()->membership(viewer,instanceId));
                put(s,48,Material.COMPASS,"刷新资格与票数",List.of("刷新不会自动代替旧修订号提交。"),refresh);
                put(s,50,Material.IRON_DOOR,"任务门槛状态",List.of("本国可见的原生任务与完成记录摘要。","任务达标不会自动授予国家代表权。"),()->questGates(viewer,instanceId,page.instance().node(),0));
                put(s,49,Material.ARROW,"返回剧情列表",List.of(),()->stories(viewer));p.openInventory(s.inventory);
            });
        });
    }
    private void nationalLedger(UUID viewer,NationalPage page,int requestedPage,Player player){
        NationalVoteState state=page.state();List<NationalVoteState.Ballot> ballots=state.ballots().stream().sorted(Comparator.comparing(NationalVoteState.Ballot::nationId)).toList();
        int current=Math.max(0,Math.min(requestedPage,Math.max(0,(ballots.size()-1)/PAGE)));Session s=create(viewer,"已记录国家票 · "+(current+1));
        for(int i=0;i<PAGE&&current*PAGE+i<ballots.size();i++){
            var ballot=ballots.get(current*PAGE+i);List<String> lore=new ArrayList<>(List.of("修订号: "+ballot.revision(),"状态: "+ballot.status(),"选项: "+Objects.toString(ballot.choiceId(),"（已撤回）"),"提交者: "+Objects.toString(ballot.actorId(),"无"),"核验时间: "+(ballot.checkedAt()==null?"无":TIME.format(ballot.checkedAt()))));
            lore.add("权威来源: "+Objects.toString(ballot.authorityEvidence(),""));put(s,i,Material.WHITE_BANNER,ballot.nationId(),lore,null);
        }
        if(ballots.isEmpty())put(s,22,Material.PAPER,"暂无国家票记录",List.of("一国一席；个人身份、OP和职业不会自动获得国家票权。"),null);
        if(current>0)put(s,45,Material.ARROW,"上一页",List.of(),()->national(viewer,state.instanceId(),true,current-1));
        put(s,48,Material.COMPASS,"返回国家议会",List.of(),()->national(viewer,state.instanceId()));
        put(s,49,Material.ARROW,"返回剧情列表",List.of(),()->stories(viewer));
        if((current+1)*PAGE<ballots.size())put(s,53,Material.ARROW,"下一页",List.of(),()->national(viewer,state.instanceId(),true,current+1));
        player.openInventory(s.inventory);
    }

    record QuestGatePage(StoryInstance instance,QuestGateState state) {}
    private static final class ChangedQuestGatePage extends IllegalStateException {
        ChangedQuestGatePage(){super("任务门槛所属剧情阶段已变化，请刷新剧情列表。");}
    }

    /** Every page is freshly authorized. Personal gates are additionally hidden unless they belong to the viewer. */
    static CompletionStage<QuestGatePage> questGatePage(ChronicleService service,UUID viewer,String instanceId,String expectedNode,int retries){
        return service.instance(viewer,instanceId).thenCompose(item->{
            StoryInstance before=item.filter(i->i.id().equals(instanceId)&&i.contains(viewer))
                    .orElseThrow(()->new SecurityException("只能查看自己已加入剧情的任务门槛。"));
            if(expectedNode!=null&&!expectedNode.equals(before.node()))throw new ChangedQuestGatePage();
            return service.questGateState(instanceId,viewer).thenCompose(state->service.instance(viewer,instanceId).thenApply(latest->{
                StoryInstance after=latest.filter(i->i.id().equals(instanceId)&&i.contains(viewer))
                        .orElseThrow(()->new SecurityException("剧情成员资格已变化。"));
                if(!questGatePageMatches(before,state,after))throw new ChangedQuestGatePage();
                var visible=state.gates().stream().filter(g->g.mode()!=QuestRequest.Mode.PLAYER||g.playerId().equals(viewer)).toList();
                return new QuestGatePage(after,new QuestGateState(state.instanceId(),state.nodeId(),state.ready(),state.blockedEffectIds(),visible));
            }));
        }).exceptionallyCompose(error->{
            Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
            return cause instanceof ChangedQuestGatePage&&retries>0&&expectedNode==null
                    ?questGatePage(service,viewer,instanceId,null,retries-1):CompletableFuture.failedFuture(cause);
        });
    }

    static boolean questGatePageMatches(StoryInstance before,QuestGateState state,StoryInstance after){
        return state!=null&&before.id().equals(state.instanceId())&&after.id().equals(state.instanceId())
                &&before.node().equals(state.nodeId())&&after.node().equals(state.nodeId())
                &&before.definition().id().equals(after.definition().id())&&before.definition().version().equals(after.definition().version())
                &&before.sourceEventId().equals(after.sourceEventId())&&before.startedAt().equals(after.startedAt())
                &&before.completed()==after.completed();
    }

    static Runnable questGateReadAction(ChronicleService service,UUID viewer,String instanceId,String expectedNode,
                                       Consumer<QuestGatePage> display,Consumer<Throwable> failure){
        return ()->{try{questGatePage(service,viewer,instanceId,expectedNode,1).whenComplete((page,error)->{
            if(error!=null)failure.accept(error);else display.accept(page);
        });}catch(RuntimeException error){failure.accept(error);}};
    }

    private void questGates(UUID viewer,String instanceId,String expectedNode,int requestedPage){
        loadQuestGates(viewer,instanceId,expectedNode,(page,p)->{
            int current=questGatePageIndex(requestedPage,page.state().gates().size());
            Session s=create(viewer,"任务门槛 · "+(current+1));
            put(s,4,Material.IRON_DOOR,page.instance().title(),List.of("阶段: "+page.state().nodeId(),page.state().ready()?"当前任务门槛已满足。":"当前任务门槛仍在等待。","这只表示任务条件；选择和国家权限仍由服务端验证。"),null);
            put(s,0,Material.PAPER,"等待的效果",page.state().blockedEffectIds().isEmpty()?List.of("没有待确认的任务效果。"):page.state().blockedEffectIds(),null);
            for(int i=0;i<GATE_PAGE&&current*GATE_PAGE+i<page.state().gates().size();i++){
                var gate=page.state().gates().get(current*GATE_PAGE+i);
                List<String> lore=List.of("状态: "+questGateStatus(gate.status()),"效果: "+gate.effectId(),"参与者: "+gate.playerId(),"范围: "+gate.mode(),"点击查看只读运行标识与状态详情。");
                put(s,9+i,gate.status()==QuestObservation.Status.COMMITTED?Material.LIME_DYE:Material.BOOK,gate.questId(),lore,
                        ()->questGateDetail(viewer,instanceId,page.state().nodeId(),gate.gateId(),current,0));
            }
            if(page.state().gates().isEmpty())put(s,22,Material.PAPER,"暂无可见任务门槛",List.of("个人门槛仅本人可见；国家门槛由服务端核验可见范围。"),null);
            if(current>0)put(s,45,Material.ARROW,"上一页",List.of(),()->questGates(viewer,instanceId,page.state().nodeId(),current-1));
            put(s,48,Material.COMPASS,"刷新任务状态",List.of("重新查询持久记录；不会完成任务或发放奖励。"),()->questGates(viewer,instanceId,page.state().nodeId(),current));
            put(s,49,Material.ARROW,"返回剧情列表",List.of(),()->stories(viewer));
            if((current+1)*GATE_PAGE<page.state().gates().size())put(s,53,Material.ARROW,"下一页",List.of(),()->questGates(viewer,instanceId,page.state().nodeId(),current+1));
            p.openInventory(s.inventory);
        });
    }

    private void questGateDetail(UUID viewer,String instanceId,String expectedNode,String gateId,int listPage,int detailPage){
        loadQuestGates(viewer,instanceId,expectedNode,(page,p)->{
            var gate=page.state().gates().stream().filter(g->g.gateId().equals(gateId)).findFirst();
            if(gate.isEmpty()){plugin.message(viewer,"该任务门槛当前不可见，请刷新列表。");return;}
            List<String> details=questGateDetails(gate.get());int current=Math.max(0,Math.min(detailPage,Math.max(0,(details.size()-1)/PAGE)));
                Session s=create(viewer,"任务门槛详情 · "+(current+1));
                for(int i=0;i<PAGE&&current*PAGE+i<details.size();i++){
                    String detail=details.get(current*PAGE+i);int split=detail.indexOf('\n');
                    put(s,i,Material.PAPER,split<0?detail:detail.substring(0,split),wrap(split<0?"":detail.substring(split+1)),null);
                }
                if(current>0)put(s,45,Material.ARROW,"上一页详情",List.of(),()->questGateDetail(viewer,instanceId,expectedNode,gateId,listPage,current-1));
                var selected=gate.get();
                if(selected.playerId().equals(viewer)&&selected.status()!=QuestObservation.Status.COMMITTED) {
                    put(s,46,Material.COMPASS,"核对原任务",List.of("向原任务服务核对当前轮次。","已有完成记录可恢复；不补发奖励。"),
                            questGateRecoveryAction(plugin.service(),viewer,selected,false,updated->{plugin.message(viewer,updated.message());questGateDetail(viewer,instanceId,expectedNode,gateId,listPage,current);},error->plugin.failure(viewer,error)));
                    if(selected.status()==QuestObservation.Status.FAILED||selected.status()==QuestObservation.Status.ABANDONED)
                        put(s,47,Material.CLOCK,"开启新轮次",List.of("须验证原任务已持久确认失败或放弃。","旧记录永久保留，新轮次重新接取。"),
                                questGateRecoveryAction(plugin.service(),viewer,selected,true,updated->{plugin.message(viewer,updated.message());questGateDetail(viewer,instanceId,expectedNode,gateId,listPage,current);},error->plugin.failure(viewer,error)));
                }
                put(s,48,Material.COMPASS,"刷新详情",List.of("完成 ID 只表示已提交的原生完成记录，不承诺奖励到账。"),()->questGateDetail(viewer,instanceId,expectedNode,gateId,listPage,current));
                put(s,49,Material.ARROW,"返回任务门槛",List.of(),()->questGates(viewer,instanceId,expectedNode,listPage));
                if((current+1)*PAGE<details.size())put(s,53,Material.ARROW,"下一页详情",List.of(),()->questGateDetail(viewer,instanceId,expectedNode,gateId,listPage,current+1));
                p.openInventory(s.inventory);
        });
    }

    private void loadQuestGates(UUID viewer,String instanceId,String expectedNode,java.util.function.BiConsumer<QuestGatePage,Player> display){
        nationalRequests.remove(viewer);long request=questGateRequestSequence.incrementAndGet();questGateRequests.put(viewer,request);
        questGateReadAction(plugin.service(),viewer,instanceId,expectedNode,page->{
            if(Objects.equals(questGateRequests.get(viewer),request))plugin.present(viewer,p->{
                if(Objects.equals(questGateRequests.get(viewer),request))display.accept(page,p);
            });
        },error->{if(Objects.equals(questGateRequests.get(viewer),request))plugin.failure(viewer,error);}).run();
    }

    static int questGatePageIndex(int requested,int size){return Math.max(0,Math.min(requested,Math.max(0,(size-1)/GATE_PAGE)));}
    static Runnable questGateRecoveryAction(ChronicleService service,UUID viewer,QuestGateState.GateSummary gate,boolean retry,
            java.util.function.Consumer<QuestGateState.GateSummary> done,java.util.function.Consumer<Throwable> failed) {
        return ()->{
            if(!gate.playerId().equals(viewer)){failed.accept(new SecurityException("只能恢复本人的任务"));return;}
            try {
                var operation=retry?service.retryQuestGate(gate.gateId(),viewer,gate.attempt()):service.reconcileQuestGate(gate.gateId(),viewer,gate.attempt());
                operation.whenComplete((value,error)->{if(error==null)done.accept(value);else failed.accept(error);});
            }catch(RuntimeException failure){failed.accept(failure);}
        };
    }
    static List<String> questGateDetails(QuestGateState.GateSummary gate){
        List<String> details=new ArrayList<>();
        chunks("门槛 ID",gate.gateId(),details);chunks("参与者 UUID",gate.playerId().toString(),details);
        chunks("当前轮次",Integer.toString(gate.attempt()),details);
        chunks("效果 ID",gate.effectId(),details);chunks("任务 ID",gate.questId(),details);chunks("范围",gate.mode().name(),details);
        chunks("原始国家",gate.nationId(),details);chunks("状态",questGateStatus(gate.status()),details);chunks("提供者",gate.providerId(),details);
        chunks("原生运行 ID",gate.runId(),details);chunks("已提交完成 ID",gate.completionId(),details);chunks("状态说明",gate.message(),details);
        return List.copyOf(details);
    }
    static String questGateStatus(QuestObservation.Status status){return switch(status){
        case UNSUPPORTED->"原生提供者未就绪";case NOT_STARTED->"等待原生接取";case ADMITTING->"正在确认原生接取";
        case ACTIVE->"原生任务进行中";case COMMITTED->"原生完成记录已确认";case ABANDONED->"原生任务已放弃";
        case FAILED->"原生任务失败";case CONFLICT->"绑定冲突，等待核对";case UNKNOWN->"状态待核对";
    };}
    private static void chunks(String label,String value,List<String> out) { String s=Objects.toString(value,"");if(s.isEmpty()){out.add(label+"\n（空）");return;}for(int i=0;i<s.length();i+=240)out.add(label+" · "+(i/240+1)+"\n"+s.substring(i,Math.min(i+240,s.length()))); }
    static List<String> wrap(String text) { List<String> lines=new ArrayList<>();for(String part:text.split("\\R",-1)){if(part.isEmpty())lines.add("");else for(int i=0;i<part.length();i+=38)lines.add(part.substring(i,Math.min(i+38,part.length())));}return lines; }
    private static Session create(UUID viewer,String title) { Session s=new Session(viewer);s.inventory=Bukkit.createInventory(s,54,Component.text(title));return s; }
    private static void put(Session s,int slot,Material material,String title,List<String> lore,Runnable action) {
        ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(Objects.toString(title,""),NamedTextColor.GOLD));meta.lore(lore.stream().flatMap(line->wrap(line).stream()).map(line->Component.text(line,NamedTextColor.GRAY)).toList());item.setItemMeta(meta);s.inventory.setItem(slot,item);if(action!=null)s.actions.put(slot,action);
    }
}
