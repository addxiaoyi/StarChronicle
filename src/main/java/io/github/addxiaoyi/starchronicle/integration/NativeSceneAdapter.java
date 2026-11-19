package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.NativeScene;
import java.util.*;
import java.util.function.*;
import org.bukkit.entity.Player;
import static io.github.addxiaoyi.starchronicle.integration.StarCoreBridge.call;

/** Thin presenter for owned native scenes. Every read/action runs on the player's owning thread.
 * Native ConditionEvaluator and NarrativeEngine retain all condition/effect/traversal execution. */
public final class NativeSceneAdapter {
    private final Supplier<Object> engines;
    private final Function<String,Object> scripts;
    private final BiPredicate<String,Object> owned;
    private final Function<Object,Object> evaluators;
    private final boolean folia;
    NativeSceneAdapter(Supplier<Object> engines,Function<String,Object> scripts,BiPredicate<String,Object> owned,boolean folia) {
        this(engines,scripts,owned,folia,engine->{try{return Class.forName("dev.starcore.starcore.module.narrative.engine.ConditionEvaluator",true,engine.getClass().getClassLoader()).getConstructor().newInstance();}catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}});
    }
    NativeSceneAdapter(Supplier<Object> engines,Function<String,Object> scripts,BiPredicate<String,Object> owned,boolean folia,Function<Object,Object> evaluators) {
        this.engines=engines;this.scripts=scripts;this.owned=owned;this.folia=folia;this.evaluators=evaluators;
    }
    boolean allowsNativeAccess(){return !folia;}
    public Optional<NativeScene> view(UUID player)throws ReflectiveOperationException {
        if(folia)return Optional.empty();Object engine=engines.get();if(engine==null)return Optional.empty();
        Object session=call(engine,"getSession",player);if(session==null||!Boolean.TRUE.equals(call(session,"isActive")))return Optional.empty();
        String id=Objects.toString(call(session,"scriptId"),"");Object script=scripts.apply(id);
        if(script==null||!owned.test(id,script))return Optional.empty();
        String nodeId=Objects.toString(call(session,"currentNodeId"),"");Object node=call(script,"findNode",nodeId);if(node instanceof Optional<?> option)node=option.orElse(null);if(node==null)return Optional.empty();
        Object state=call(engine,"getState",player),evaluator=evaluators.apply(engine);
        List<String> lines=new ArrayList<>();Object dialogues=call(node,"getDialogues");
        if(dialogues instanceof Collection<?> values)for(Object line:values) {
            if(Boolean.TRUE.equals(call(evaluator,"evaluate",call(line,"getShowCondition"),state)))
                lines.add(Objects.toString(call(line,"getSpeakerName"),"")+"："+Objects.toString(call(line,"getText"),""));
        }
        List<NativeScene.Choice> choices=new ArrayList<>();Object raw=call(node,"getChoices");
        if(raw instanceof Collection<?> values)for(Object choice:values)if(Boolean.TRUE.equals(call(evaluator,"evaluateChoice",choice,state))) {
            if(choices.size()>=36)throw new IllegalStateException("Owned scene exceeds GUI choice limit");choices.add(new NativeScene.Choice(choices.size()+1,Objects.toString(call(choice,"getText"),"")));
        }
        String text=String.join("\n",lines);if(text.length()>16384)text=text.substring(0,16384);
        return Optional.of(new NativeScene(id,nodeId,Objects.toString(call(script,"getTitle"),id),text,choices));
    }
    public Optional<NativeScene> act(Player player,String expectedScript,String expectedNode,int choice)throws ReflectiveOperationException {
        return act(player,expectedScript,expectedNode,choice,()->{});
    }
    /** The callback runs after validation and immediately before the only native mutation. */
    public Optional<NativeScene> act(Player player,String expectedScript,String expectedNode,int choice,Runnable beforeInvocation)throws ReflectiveOperationException {
        Objects.requireNonNull(beforeInvocation);
        if(folia||!player.hasPermission("starchronicle.use"))throw new SecurityException("Native scene unavailable");
        NativeScene current=view(player.getUniqueId()).orElseThrow(()->new IllegalStateException("没有正在进行的自有StarCore场景"));
        if(!current.scriptId().equals(expectedScript)||!current.nodeId().equals(expectedNode))throw new IllegalStateException("场景已经变化，请刷新");
        Object engine=Objects.requireNonNull(engines.get(),"Native engine unavailable");
        if(choice==0) {
            if(!current.choices().isEmpty())throw new IllegalArgumentException("请先选择一个当前有效选项");
            beforeInvocation.run();
            call(engine,"advanceNode",player);
        }else {
            if(current.choices().stream().noneMatch(c->c.index()==choice))throw new IllegalArgumentException("不存在的原生选项");
            beforeInvocation.run();
            call(engine,"selectChoice",player,choice);
        }
        return view(player.getUniqueId());
    }
}
