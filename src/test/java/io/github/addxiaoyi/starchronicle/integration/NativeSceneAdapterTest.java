package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.NativeScene;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native public method fixtures verify delegation without loading, copying or interpreting scripts. */
class NativeSceneAdapterTest {
    private static final UUID PLAYER_ID=UUID.fromString("00000000-0000-0000-0000-000000000007");
    private FixtureEngine engine;
    private FixtureScript script;
    private FixtureEvaluator evaluator;
    private Player player;
    private NativeSceneAdapter adapter;
    @BeforeEach void setup(){
        engine=new FixtureEngine();script=new FixtureScript();evaluator=new FixtureEvaluator(engine.state);
        player=mock(Player.class);when(player.getUniqueId()).thenReturn(PLAYER_ID);when(player.hasPermission("starchronicle.use")).thenReturn(true);
        adapter=new NativeSceneAdapter(()->engine,id->id.equals("owned")?script:null,(id,value)->id.equals("owned")&&value==script,false,ignored->evaluator);
    }
    private FixtureNode start(){return script.nodes.get("start");}

    @Test void noSessionInactiveSessionMissingScriptAndMissingNodeReturnNoScene() throws Exception {
        engine.session=null;assertTrue(adapter.view(PLAYER_ID).isEmpty());assertEquals(0,engine.stateReads);
        engine.session=new FixtureSession();engine.session.active=false;assertTrue(adapter.view(PLAYER_ID).isEmpty());assertEquals(0,engine.stateReads);
        engine.session.active=true;engine.session.script="foreign";assertTrue(adapter.view(PLAYER_ID).isEmpty());assertEquals(0,engine.stateReads);
        engine.session.script="owned";engine.session.node="missing";assertTrue(adapter.view(PLAYER_ID).isEmpty());assertEquals(0,engine.stateReads);
    }

    @Test void foreignScriptObjectCannotBeViewedOrActedEvenWhenItsIdMatches() throws Exception {
        FixtureScript foreign=new FixtureScript();adapter=new NativeSceneAdapter(()->engine,id->foreign,(id,value)->value==script,false,ignored->{fail("foreign script evaluator was created");return evaluator;});
        assertTrue(adapter.view(PLAYER_ID).isEmpty());assertThrows(IllegalStateException.class,()->adapter.act(player,"owned","start",0));
        assertEquals(0,engine.stateReads);assertEquals(0,engine.advances);assertTrue(engine.selections.isEmpty());
    }

    @Test void nativeEvaluatorControlsVisibleDialogueAndSequentialOneBasedChoiceIndexes() throws Exception {
        FixtureChoice hidden=new FixtureChoice("hidden","隐藏"),first=new FixtureChoice("show","第一可见"),second=new FixtureChoice("show","第二可见");
        start().dialogues=List.of(new FixtureDialogue("show","史官","真实可见文本"),new FixtureDialogue("hidden","未知","不可显示文本"));
        start().choices=List.of(hidden,first,second);
        NativeScene scene=adapter.view(PLAYER_ID).orElseThrow();
        assertEquals("owned",scene.scriptId());assertEquals("start",scene.nodeId());assertEquals("原生剧情",scene.title());assertEquals("史官：真实可见文本",scene.text());
        assertEquals(List.of(new NativeScene.Choice(1,"第一可见"),new NativeScene.Choice(2,"第二可见")),scene.choices());
        assertEquals(List.of("show","hidden"),evaluator.dialogueConditions);assertEquals(List.of(hidden,first,second),evaluator.choices);
        assertThrows(UnsupportedOperationException.class,()->scene.choices().clear());
    }

    @Test void selectionDelegatesTheNativeVisibleChoiceIndexWithoutExecutingText() throws Exception {
        start().choices=List.of(new FixtureChoice("hidden","原始下标一"),new FixtureChoice("show","/op never-dispatched"));
        NativeScene next=adapter.act(player,"owned","start",1).orElseThrow();
        assertEquals(List.of(1),engine.selections);assertEquals(0,engine.advances);assertEquals("after",next.nodeId());
        verify(player,atLeastOnce()).getUniqueId();verify(player).hasPermission("starchronicle.use");verifyNoMoreInteractions(player);
    }

    @Test void noChoiceAdvanceDelegatesOnlyToNativeAdvanceNode() throws Exception {
        NativeScene next=adapter.act(player,"owned","start",0).orElseThrow();
        assertEquals(1,engine.advances);assertTrue(engine.selections.isEmpty());assertEquals("after",next.nodeId());
    }

    @Test void staleScriptNodeAndIllegalChoiceCannotReachNativeMutation() {
        start().choices=List.of(new FixtureChoice("show","有效"));
        assertThrows(IllegalStateException.class,()->adapter.act(player,"other","start",1));
        assertThrows(IllegalStateException.class,()->adapter.act(player,"owned","stale",1));
        assertThrows(IllegalArgumentException.class,()->adapter.act(player,"owned","start",0));
        assertThrows(IllegalArgumentException.class,()->adapter.act(player,"owned","start",-1));
        assertThrows(IllegalArgumentException.class,()->adapter.act(player,"owned","start",2));
        assertEquals(0,engine.advances);assertTrue(engine.selections.isEmpty());
    }

    @Test void missingUsePermissionRejectsBeforeAnyNativeRead() {
        when(player.hasPermission("starchronicle.use")).thenReturn(false);
        assertThrows(SecurityException.class,()->adapter.act(player,"owned","start",0));assertEquals(0,engine.sessionReads);assertEquals(0,engine.stateReads);assertEquals(0,engine.advances);
    }

    @Test void foliaDoesNotResolveReadOrMutateAnyNativeObject() throws Exception {
        AtomicInteger resolutions=new AtomicInteger();
        adapter=new NativeSceneAdapter(()->{resolutions.incrementAndGet();throw new AssertionError("native engine resolved on Folia");},id->{throw new AssertionError("script resolved on Folia");},(id,value)->{throw new AssertionError("ownership read on Folia");},true,ignored->{throw new AssertionError("evaluator created on Folia");});
        assertTrue(adapter.view(PLAYER_ID).isEmpty());assertThrows(SecurityException.class,()->adapter.act(player,"owned","start",0));assertEquals(0,resolutions.get());verifyNoInteractions(player);
    }

    @Test void excessiveNativeChoicesFailBeforePresentingAnUnrepresentableGui() {
        start().choices=Collections.nCopies(37,new FixtureChoice("show","选择"));
        assertThrows(IllegalStateException.class,()->adapter.view(PLAYER_ID));assertEquals(0,engine.advances);assertTrue(engine.selections.isEmpty());
    }

    public static final class FixtureEngine {
        FixtureSession session=new FixtureSession();final Object state=new Object();int sessionReads,stateReads,advances;final List<Integer> selections=new ArrayList<>();
        public FixtureSession getSession(UUID player){assertEquals(PLAYER_ID,player);sessionReads++;return session;}
        public Object getState(UUID player){assertEquals(PLAYER_ID,player);stateReads++;return state;}
        public void advanceNode(Player player){assertEquals(PLAYER_ID,player.getUniqueId());advances++;session.node="after";}
        public void selectChoice(Player player,int choice){assertEquals(PLAYER_ID,player.getUniqueId());selections.add(choice);session.node="after";}
    }
    public static final class FixtureSession {
        boolean active=true;String script="owned",node="start";
        public boolean isActive(){return active;}public String scriptId(){return script;}public String currentNodeId(){return node;}
    }
    public static final class FixtureScript {
        final Map<String,FixtureNode> nodes=new HashMap<>(Map.of("start",new FixtureNode(),"after",new FixtureNode()));
        public Optional<FixtureNode> findNode(String node){return Optional.ofNullable(nodes.get(node));}public String getTitle(){return "原生剧情";}
    }
    public static final class FixtureNode {
        List<FixtureDialogue> dialogues=List.of();List<FixtureChoice> choices=List.of();
        public List<FixtureDialogue> getDialogues(){return dialogues;}public List<FixtureChoice> getChoices(){return choices;}
    }
    public static final class FixtureDialogue {
        private final String condition,speaker,text;FixtureDialogue(String condition,String speaker,String text){this.condition=condition;this.speaker=speaker;this.text=text;}
        public String getShowCondition(){return condition;}public String getSpeakerName(){return speaker;}public String getText(){return text;}
    }
    public static final class FixtureChoice {
        private final String condition,text;FixtureChoice(String condition,String text){this.condition=condition;this.text=text;}
        public String getText(){return text;}
    }
    public static final class FixtureEvaluator {
        private final Object expectedState;final List<String> dialogueConditions=new ArrayList<>();final List<FixtureChoice> choices=new ArrayList<>();
        FixtureEvaluator(Object expectedState){this.expectedState=expectedState;}
        public boolean evaluate(String condition,Object state){assertSame(expectedState,state);dialogueConditions.add(condition);return condition.equals("show");}
        public boolean evaluateChoice(FixtureChoice choice,Object state){assertSame(expectedState,state);choices.add(choice);return choice.condition.equals("show");}
    }
}
