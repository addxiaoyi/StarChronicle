package io.github.addxiaoyi.starchronicle.paper;

import io.github.addxiaoyi.starchronicle.api.WorldEvent;
import io.github.addxiaoyi.starchronicle.api.NativeScene;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.core.StoryInstance;
import io.github.addxiaoyi.starchronicle.memory.HistoryBook;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
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
                put(s,49,Material.COMPASS,"我的剧情",List.of(),()->stories(viewer,0));
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
                put(s,31,Material.COMPASS,"相关剧情",List.of("按当前身份和已发生的事件筛选。"),()->stories(viewer,0));
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
                put(s,49,Material.COMPASS,"专属剧情",List.of(),()->stories(viewer,0));p.openInventory(s.inventory);
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
                else {plugin.message(viewer,"原生补充场景已结束；共同议会进度仍由编年史剧情控制。");stories(viewer,0);}
            });
            if(scene.choices().isEmpty())put(s,31,Material.LIME_DYE,"继续",List.of("由StarCore执行分支和效果。"),()->act.accept(0));
            else {int slot=9;for(var choice:scene.choices())put(s,slot++,Material.LIME_DYE,choice.text(),List.of("原生选项: "+choice.index()),()->act.accept(choice.index()));}
            put(s,49,Material.COMPASS,"共同剧情",List.of(),()->stories(viewer,0));p.openInventory(s.inventory);
        });
    }
    public void stories(UUID viewer,int requestedPage) {
        plugin.service().instances(viewer).thenCombine(plugin.service().availableInstances(viewer),(own,active)->{
            Map<String,StoryInstance> all=new TreeMap<>();own.forEach(i->all.put(i.id(),i));active.forEach(i->all.put(i.id(),i));return List.copyOf(all.values());
        }).whenComplete((items,error)->{
            if(error!=null){plugin.failure(viewer,error);return;}
            int page=Math.max(0,Math.min(requestedPage,Math.max(0,(items.size()-1)/PAGE)));
            plugin.present(viewer,p->{
                Session s=create(viewer,"共同书写历史 · "+(page+1));
                for(int i=0;i<PAGE && page*PAGE+i<items.size();i++) {
                    StoryInstance story=items.get(page*PAGE+i);
                    put(s,i,story.completed()?Material.ENCHANTED_BOOK:Material.BOOK,story.title(),List.of("实例: "+story.id(),"范围: "+story.definition().scope(),"参与人数: "+story.members().size(),story.completed()?"已完成":story.contains(viewer)?"点击继续剧情":"点击查看并加入"),()->story(viewer,story));
                }
                if(items.isEmpty()) put(s,22,Material.PAPER,"暂无剧情",List.of("世界事件满足前置条件后会开启剧情。"),null);
                if(page>0) put(s,45,Material.ARROW,"上一页",List.of(),()->stories(viewer,page-1));
                put(s,49,Material.CLOCK,"世界历史",List.of(),()->history(viewer,null,null,0));
                put(s,48,Material.WRITTEN_BOOK,"继续原生补充场景",List.of("使用StarCore已有剧情执行器。"),()->scene(viewer));
                if((page+1)*PAGE<items.size()) put(s,53,Material.ARROW,"下一页",List.of(),()->stories(viewer,page+1));
                p.openInventory(s.inventory);
            });
        });
    }
    private void story(UUID viewer,StoryInstance instance) {
        plugin.service().availableChoices(instance.id(),viewer).whenComplete((available,error)->{
        if(error!=null){plugin.failure(viewer,error);return;}
        plugin.present(viewer,p->{
            Session s=create(viewer,instance.title());
            StoryDefinition.Node node=instance.definition().nodes().get(instance.node());
            put(s,4,Material.WRITTEN_BOOK,instance.title(),List.of("实例: "+instance.id(),"当前阶段: "+instance.node(),"投票: "+instance.votes().size()+" / "+node.quorum()),null);
            List<String> text=wrap(node.text()==null?"":node.text());
            put(s,0,Material.PAPER,"剧情文本",text,null);
            if(!instance.contains(viewer) && !instance.completed()) {
                put(s,31,Material.EMERALD,"申请加入剧情",List.of("服务端将重新验证身份、前置条件与国家。"),()->plugin.service().join(instance.id(),viewer).whenComplete((report,joinError)->{
                    if(joinError!=null)plugin.failure(viewer,joinError);else{plugin.message(viewer,report.message());stories(viewer,0);}
                }));
            } else if(!instance.completed()) {
                int slot=9;
                for(var entry:new TreeMap<>(available).entrySet()) {
                    if(slot>=45)break;
                    String id=entry.getKey();StoryDefinition.Choice choice=entry.getValue();
                    put(s,slot++,Material.LIME_DYE,choice.label(),List.of("选项: "+id,"权限和条件由服务端即时重新验证。","同一阶段每人保留一票；达到法定人数后推进。"),()->plugin.service().choose(instance.id(),viewer,instance.node(),id).whenComplete((report,choiceError)->{
                        if(choiceError!=null)plugin.failure(viewer,choiceError);else{plugin.message(viewer,report.message());stories(viewer,0);}
                    }));
                }
            } else put(s,31,Material.GOLD_INGOT,"此篇历史已落定",List.of("结果与效果收据已持久化。"),null);
            put(s,49,Material.ARROW,"返回剧情列表",List.of(),()->stories(viewer,0));
            p.openInventory(s.inventory);
        });
        });
    }
    private static void chunks(String label,String value,List<String> out) { String s=Objects.toString(value,"");if(s.isEmpty()){out.add(label+"\n（空）");return;}for(int i=0;i<s.length();i+=240)out.add(label+" · "+(i/240+1)+"\n"+s.substring(i,Math.min(i+240,s.length()))); }
    static List<String> wrap(String text) { List<String> lines=new ArrayList<>();for(String part:text.split("\\R",-1)){if(part.isEmpty())lines.add("");else for(int i=0;i<part.length();i+=38)lines.add(part.substring(i,Math.min(i+38,part.length())));}return lines; }
    private static Session create(UUID viewer,String title) { Session s=new Session(viewer);s.inventory=Bukkit.createInventory(s,54,Component.text(title));return s; }
    private static void put(Session s,int slot,Material material,String title,List<String> lore,Runnable action) {
        ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(Objects.toString(title,""),NamedTextColor.GOLD));meta.lore(lore.stream().flatMap(line->wrap(line).stream()).map(line->Component.text(line,NamedTextColor.GRAY)).toList());item.setItemMeta(meta);s.inventory.setItem(slot,item);if(action!=null)s.actions.put(slot,action);
    }
}
