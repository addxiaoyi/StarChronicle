package io.github.addxiaoyi.starchronicle.integration;

import io.github.addxiaoyi.starchronicle.api.NativeScene;
import io.github.addxiaoyi.starchronicle.narrative.NativeSceneJournal.Checkpoint;
import io.github.addxiaoyi.starchronicle.narrative.NativeSceneJournal.Request;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.github.addxiaoyi.starchronicle.integration.NativeSceneRecoveryAdapter.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Public native method fixtures exercise receipt boundaries and immutable getter snapshots. */
class NativeSceneRecoveryAdapterTest {
    private static final UUID PLAYER=UUID.fromString("00000000-0000-0000-0000-000000000019");
    private static final String SCRIPT="chronicle_last_heir",HASH="a".repeat(64);
    private FixtureScript script;
    private AtomicReference<FixtureEngine> engine;
    private AtomicBoolean owned,safe;
    private AtomicReference<String> hash;
    private Player player;
    private NativeSceneAdapter scenes;
    private NativeSceneRecoveryAdapter adapter;
    @BeforeEach void setup(){
        script=new FixtureScript(SCRIPT);engine=new AtomicReference<>(new FixtureEngine(script));owned=new AtomicBoolean(true);safe=new AtomicBoolean(true);hash=new AtomicReference<>(HASH);
        player=mock(Player.class);when(player.getUniqueId()).thenReturn(PLAYER);when(player.isOnline()).thenReturn(true);when(player.hasPermission("starchronicle.use")).thenReturn(true);
        configure(false);
    }
    private void configure(boolean folia){
        Function<String,Object> scripts=id->owned.get()&&id.equals(script.id)?script:null;
        scenes=new NativeSceneAdapter(engine::get,scripts,(id,value)->scripts.apply(id)==value,folia,ignored->new FixtureEvaluator());
        adapter=new NativeSceneRecoveryAdapter(engine::get,scripts,id->hash.get(),id->safe.get(),scenes);
    }
    private Request request(){return new Request("outcome","instance",PLAYER,"river",script.id,HASH);}
    private Request request(String node){return new Request("outcome","instance",PLAYER,"river",script.id,HASH,node);}
    private void session(String node){engine.get().session=new FixtureSession(script.id,node);engine.get().routing=true;}
    private Checkpoint checkpoint(String node){return new Checkpoint(node,Map.of("chronicle_heir_score",7L),Set.of("chronicle_heir_trusted"),Set.of("chronicle_heir_testimony"),new NativeScene(SCRIPT,node,"owned","saved",List.of()));}

    @ParameterizedTest @ValueSource(strings={"chronicle_last_heir","chronicle_siege_embers","chronicle_silk_road"})
    void captureIncludesOnlyTheExactScriptsOwnScalarPrefixAndDetachesSnapshots(String id)throws Exception{
        script=new FixtureScript(id);engine.set(new FixtureEngine(script));configure(false);session("evidence");
        String prefix=switch(id){case "chronicle_last_heir"->"chronicle_heir_";case "chronicle_siege_embers"->"chronicle_siege_";default->"chronicle_caravan_";};
        var state=engine.get().state;Object foreign=new Object();state.variables.putAll(Map.of(prefix+"score",7,prefix+"label","saved","foreign",foreign,"chronicle_someone_else",99));
        state.flags.addAll(Set.of(prefix+"trusted","chronicle_other_flag","foreign_flag"));state.evidence.addAll(Set.of(prefix+"source","chronicle_other_evidence"));
        Checkpoint captured=adapter.capture(PLAYER,id);
        assertEquals(Map.of(prefix+"score",7L,prefix+"label","saved"),captured.variables());assertEquals(Set.of(prefix+"trusted"),captured.flags());assertEquals(Set.of(prefix+"source"),captured.evidence());
        state.variables.put(prefix+"score",99);state.flags.clear();assertEquals(7L,captured.variables().get(prefix+"score"));assertEquals(1,captured.flags().size());
        assertThrows(UnsupportedOperationException.class,()->captured.variables().clear());assertSame(foreign,state.variables.get("foreign"));assertEquals(0,engine.get().starts);
    }

    @Test void captureRejectsForeignSessionMissingNodeWrongStateOwnerAndOwnedNonScalar()throws Exception{
        assertThrows(IllegalStateException.class,()->adapter.capture(PLAYER,SCRIPT));session("evidence");engine.get().session.script="foreign";
        assertThrows(IllegalStateException.class,()->adapter.capture(PLAYER,SCRIPT));session("deleted");assertThrows(IllegalStateException.class,()->adapter.capture(PLAYER,SCRIPT));
        session("evidence");engine.get().state.player=UUID.randomUUID();assertThrows(SecurityException.class,()->adapter.capture(PLAYER,SCRIPT));
        engine.get().state.player=PLAYER;engine.get().state.variables.put("chronicle_heir_object",new Object());assertThrows(IllegalArgumentException.class,()->adapter.capture(PLAYER,SCRIPT));
    }

    @Test void freshStartPassesNullOverrideAndExplicitStartPreservesItsActualNativeNode(){
        var first=adapter.startOrResume(player,request(),null);assertEquals(READY,first.status());assertEquals("arrival",first.checkpoint().nodeId());assertNull(engine.get().lastOverride);assertEquals(1,engine.get().starts);
        engine.set(new FixtureEngine(script));var explicit=adapter.startOrResume(player,request("evidence"),null);
        assertEquals(READY,explicit.status());assertEquals("evidence",engine.get().lastOverride);assertEquals("evidence",explicit.checkpoint().nodeId());
        engine.set(new FixtureEngine(script));assertEquals(NOT_INVOKED,adapter.startOrResume(player,request("deleted"),null).status());assertEquals(0,engine.get().starts);
    }

    @Test void existingMatchingSessionIsCapturedWithoutRestartingOrResettingIt()throws Exception{
        session("evidence");engine.get().state.variables.put("chronicle_heir_score",7);
        var checkpoint=adapter.capture(PLAYER,SCRIPT);var activeSession=engine.get().session;
        engine.get().state.variables.put("chronicle_heir_score",99);
        var result=adapter.startOrResume(player,request(),checkpoint);assertEquals(READY,result.status());assertEquals(99L,result.checkpoint().variables().get("chronicle_heir_score"));assertEquals(0,engine.get().starts);assertSame(activeSession,engine.get().session);assertEquals(7L,checkpoint.variables().get("chronicle_heir_score"));
    }

    @Test void freshOutcomeCannotAdoptAnAlreadyActiveSessionEvenWhenScriptAndPlayerMatch(){
        session("evidence");var activeSession=engine.get().session;var state=engine.get().state;
        state.variables.put("chronicle_heir_score",99);state.flags.add("chronicle_heir_trusted");state.evidence.add("chronicle_heir_testimony");
        Request fresh=new Request("different-outcome","different-instance",PLAYER,"river",SCRIPT,HASH);
        var result=adapter.startOrResume(player,fresh,null);
        assertEquals(NOT_INVOKED,result.status());assertNull(result.checkpoint());
        assertEquals(0,engine.get().starts);assertEquals(0,engine.get().advances);assertTrue(engine.get().selections.isEmpty());
        assertSame(activeSession,engine.get().session);assertTrue(engine.get().routing);assertEquals("evidence",activeSession.node);
        assertEquals(Map.of("chronicle_heir_score",99),state.variables);assertEquals(Set.of("chronicle_heir_trusted"),state.flags);assertEquals(Set.of("chronicle_heir_testimony"),state.evidence);
    }

    @Test void normalQuitStaleRoutingCanResumeVerifiedCheckpointAndTouchesNoForeignState(){
        var nativeEngine=engine.get();nativeEngine.routing=true;Object foreign=new Object();
        nativeEngine.state.variables.putAll(Map.of("foreign",foreign,"chronicle_caravan_foreign",8L,"chronicle_heir_obsolete",99L));
        nativeEngine.state.flags.addAll(Set.of("foreign_flag","chronicle_siege_other","chronicle_heir_obsolete"));
        nativeEngine.state.evidence.addAll(Set.of("foreign_evidence","chronicle_caravan_source","chronicle_heir_obsolete"));
        nativeEngine.onStart=()->{nativeEngine.state.variables.put("chronicle_heir_entry",123L);nativeEngine.state.flags.add("chronicle_heir_entry");nativeEngine.state.evidence.add("chronicle_heir_entry");};
        var result=adapter.startOrResume(player,request(),checkpoint("evidence"));
        assertEquals(READY,result.status());assertEquals("evidence",nativeEngine.lastOverride);assertEquals(1,nativeEngine.starts);assertTrue(nativeEngine.routing);
        assertEquals(Map.of("chronicle_heir_score",7L),result.checkpoint().variables());assertEquals(Set.of("chronicle_heir_trusted"),result.checkpoint().flags());assertEquals(Set.of("chronicle_heir_testimony"),result.checkpoint().evidence());
        assertSame(foreign,nativeEngine.state.variables.get("foreign"));assertEquals(8L,nativeEngine.state.variables.get("chronicle_caravan_foreign"));
        assertTrue(nativeEngine.state.flags.containsAll(Set.of("foreign_flag","chronicle_siege_other")));assertTrue(nativeEngine.state.evidence.containsAll(Set.of("foreign_evidence","chronicle_caravan_source")));
        assertFalse(nativeEngine.state.variables.containsKey("chronicle_heir_obsolete"));assertFalse(nativeEngine.state.flags.contains("chronicle_heir_entry"));
    }

    @Test void restartRecoveryRebuildsNativeRoutingAndCanActuallyAdvanceToConclusiveCompletion(){
        var result=adapter.startOrResume(player,request(),checkpoint("evidence"));assertEquals(READY,result.status());assertTrue(engine.get().routing);
        assertEquals(READY,adapter.act(player,SCRIPT,"evidence",0).status());assertEquals("departure",engine.get().session.node);
        assertEquals(COMPLETED,adapter.act(player,SCRIPT,"departure",0).status());assertNull(engine.get().session);assertFalse(engine.get().routing);assertEquals(2,engine.get().advances);
    }

    @Test void changedFingerprintUnsafeScriptMissingOwnershipAndMissingCheckpointNodeNeverInvoke(){
        hash.set("b".repeat(64));assertEquals(NOT_INVOKED,adapter.startOrResume(player,request(),checkpoint("evidence")).status());hash.set(HASH);
        safe.set(false);assertEquals(NOT_INVOKED,adapter.startOrResume(player,request(),checkpoint("evidence")).status());safe.set(true);
        owned.set(false);assertEquals(NOT_INVOKED,adapter.startOrResume(player,request(),checkpoint("evidence")).status());owned.set(true);
        assertEquals(NOT_INVOKED,adapter.startOrResume(player,request(),checkpoint("deleted")).status());assertEquals(0,engine.get().starts);
    }

    @Test void checkpointCannotRestoreAnotherScriptsPrefixOrIdentity(){
        var wrongPrefix=new Checkpoint("evidence",Map.of("chronicle_siege_foreign",99),Set.of(),Set.of(),new NativeScene(SCRIPT,"evidence","owned","",List.of()));
        var wrongScript=new Checkpoint("evidence",Map.of(),Set.of(),Set.of(),new NativeScene("chronicle_siege_embers","evidence","other","",List.of()));
        assertEquals(NOT_INVOKED,adapter.startOrResume(player,request(),wrongPrefix).status());assertEquals(NOT_INVOKED,adapter.startOrResume(player,request(),wrongScript).status());assertEquals(0,engine.get().starts);
    }

    @Test void realForeignSessionsAndUnsafeStaleRoutingBlockButSafeNullSessionRebuilds(){
        session("arrival");engine.get().session.script="foreign";assertEquals(NOT_INVOKED,adapter.startOrResume(player,request(),checkpoint("evidence")).status());
        session("");assertEquals(NOT_INVOKED,adapter.startOrResume(player,request(),checkpoint("evidence")).status());
        engine.get().session=null;safe.set(false);assertEquals(NOT_INVOKED,adapter.startOrResume(player,request(),null).status());assertEquals(0,engine.get().starts);
        safe.set(true);var restarted=adapter.startOrResume(player,request(),null);assertEquals(READY,restarted.status());assertEquals("arrival",restarted.checkpoint().nodeId());assertNull(engine.get().lastOverride);assertEquals(1,engine.get().starts);
    }

    @Test void offlineMismatchedOrUnpermittedPlayersFailBeforeInvocation(){
        when(player.isOnline()).thenReturn(false);assertEquals(NOT_INVOKED,adapter.startOrResume(player,request(),null).status());when(player.isOnline()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());assertEquals(NOT_INVOKED,adapter.startOrResume(player,request(),null).status());when(player.getUniqueId()).thenReturn(PLAYER);
        when(player.hasPermission("starchronicle.use")).thenReturn(false);assertEquals(NOT_INVOKED,adapter.startOrResume(player,request(),null).status());assertEquals(0,engine.get().starts);
    }

    @Test void nativeStartExceptionNullResultAndPostStartCaptureFailureAreUnknown(){
        engine.get().onStart=()->{throw new IllegalStateException("native failed after entry");};assertEquals(UNKNOWN,adapter.startOrResume(player,request(),null).status());assertEquals(1,engine.get().starts);
        engine.set(new FixtureEngine(script));engine.get().nullStart=true;assertEquals(UNKNOWN,adapter.startOrResume(player,request(),null).status());assertEquals(1,engine.get().starts);
        engine.set(new FixtureEngine(script));engine.get().onStart=()->owned.set(false);assertEquals(UNKNOWN,adapter.startOrResume(player,request(),null).status());assertEquals(1,engine.get().starts);
    }

    @Test void mergeFailureAfterStartIsUnknownAndNeverClearsForeignState(){
        engine.get().state.variables.putAll(Map.of("foreign",7,"chronicle_heir_old",8));engine.get().state.failRemove=true;
        var result=adapter.startOrResume(player,request(),checkpoint("evidence"));assertEquals(UNKNOWN,result.status());assertEquals(1,engine.get().starts);assertEquals(7,engine.get().state.variables.get("foreign"));
    }

    @Test void invalidStaleOrUnpermittedActionsStayNotInvoked(){
        session("arrival");assertEquals(NOT_INVOKED,adapter.act(player,SCRIPT,"stale",1).status());assertEquals(NOT_INVOKED,adapter.act(player,SCRIPT,"arrival",0).status());
        assertEquals(NOT_INVOKED,adapter.act(player,SCRIPT,"arrival",2).status());when(player.hasPermission("starchronicle.use")).thenReturn(false);assertEquals(NOT_INVOKED,adapter.act(player,SCRIPT,"arrival",1).status());
        assertEquals(0,engine.get().advances);assertTrue(engine.get().selections.isEmpty());
    }

    @Test void validActionDelegatesTheNativeVisibleIndexAndCapturesItsActualState(){
        session("arrival");engine.get().onSelect=()->engine.get().state.flags.add("chronicle_heir_choice_executed");
        var result=adapter.act(player,SCRIPT,"arrival",1);assertEquals(READY,result.status());assertEquals(List.of(1),engine.get().selections);assertEquals("evidence",result.checkpoint().nodeId());assertTrue(result.checkpoint().flags().contains("chronicle_heir_choice_executed"));
    }

    @Test void emptyViewWithStaleRoutingOrInactiveSessionIsUnknownRatherThanCompleted(){
        session("evidence");engine.get().afterAdvance=()->engine.get().session=null;
        assertEquals(UNKNOWN,adapter.act(player,SCRIPT,"evidence",0).status());assertTrue(engine.get().routing);
        session("evidence");engine.get().afterAdvance=()->{engine.get().session.node="";engine.get().routing=false;};
        assertEquals(UNKNOWN,adapter.act(player,SCRIPT,"evidence",0).status());
    }

    @Test void nativeActionExceptionAndReadBackFailureAreUnknownAfterTheInvocationMarker(){
        session("evidence");engine.get().afterAdvance=()->{throw new IllegalStateException("native action failed");};assertEquals(UNKNOWN,adapter.act(player,SCRIPT,"evidence",0).status());assertEquals(1,engine.get().advances);
        engine.get().afterAdvance=()->engine.get().failSessionReads=true;session("evidence");assertEquals(UNKNOWN,adapter.act(player,SCRIPT,"evidence",0).status());assertEquals(2,engine.get().advances);
    }

    @Test void presenterInvocationCallbackRunsAfterValidationAndImmediatelyBeforeNativeCall()throws Exception{
        session("arrival");AtomicBoolean marked=new AtomicBoolean();
        assertThrows(IllegalArgumentException.class,()->scenes.act(player,SCRIPT,"arrival",2,()->marked.set(true)));assertFalse(marked.get());
        engine.get().onSelect=()->assertTrue(marked.get());scenes.act(player,SCRIPT,"arrival",1,()->marked.set(true));assertEquals(List.of(1),engine.get().selections);
        session("arrival");assertThrows(IllegalStateException.class,()->scenes.act(player,SCRIPT,"arrival",1,()->{throw new IllegalStateException("not admitted");}));assertEquals(1,engine.get().selections.size());
    }

    @Test void foliaNeverResolvesAnyNativeObjectOrMetadata()throws Exception{
        scenes=new NativeSceneAdapter(()->{throw new AssertionError("Folia native engine read");},id->{throw new AssertionError("Folia script read");},(id,value)->false,true,ignored->null);
        adapter=new NativeSceneRecoveryAdapter(()->{throw new AssertionError("Folia native engine read");},id->{throw new AssertionError("Folia script read");},id->{throw new AssertionError("Folia fingerprint read");},id->{throw new AssertionError("Folia capability read");},scenes);
        assertEquals(NOT_INVOKED,adapter.startOrResume(player,request(),checkpoint("evidence")).status());assertEquals(NOT_INVOKED,adapter.act(player,SCRIPT,"arrival",1).status());assertThrows(SecurityException.class,()->adapter.capture(PLAYER,SCRIPT));verifyNoInteractions(player);
    }

    public static final class FixtureEngine {
        final FixtureScript script;final FixtureState state=new FixtureState();FixtureSession session;boolean routing,nullStart,failSessionReads;int starts,advances;String lastOverride;
        Runnable onStart=()->{},onSelect=()->{},afterAdvance=()->{};final List<Integer> selections=new ArrayList<>();
        FixtureEngine(FixtureScript script){this.script=script;}
        public FixtureSession getSession(UUID player){assertEquals(PLAYER,player);if(failSessionReads)throw new IllegalStateException("read failed");return session;}
        public boolean hasActiveSession(UUID player){assertEquals(PLAYER,player);return routing;}
        public FixtureState getState(UUID player){assertEquals(PLAYER,player);return state;}
        public FixtureScript startScript(Player player,String id,String node){assertEquals(PLAYER,player.getUniqueId());assertEquals(script.id,id);starts++;lastOverride=node;session=new FixtureSession(id,node==null?"arrival":node);routing=true;onStart.run();return nullStart?null:script;}
        public void selectChoice(Player player,int index){assertEquals(PLAYER,player.getUniqueId());selections.add(index);onSelect.run();session.node="evidence";}
        public void advanceNode(Player player){assertEquals(PLAYER,player.getUniqueId());advances++;if(session.node.equals("departure")){session=null;routing=false;}else session.node="departure";afterAdvance.run();}
    }
    public static final class FixtureState {
        UUID player=PLAYER;boolean failRemove;final Map<String,Object> variables=new HashMap<>();final Set<String> flags=new HashSet<>(),evidence=new HashSet<>();
        public UUID getPlayerId(){return player;}public Map<String,Object> getVariables(){return Map.copyOf(variables);}public Set<String> getFlags(){return Set.copyOf(flags);}public Set<String> getEvidence(){return Set.copyOf(evidence);}
        public void setVariable(String key,Object value){variables.put(key,value);}public void removeVariable(String key){if(failRemove)throw new IllegalStateException("merge failed");variables.remove(key);}
        public void setFlag(String key){flags.add(key);}public void unsetFlag(String key){flags.remove(key);}public void addEvidence(String key){evidence.add(key);}public void removeEvidence(String key){evidence.remove(key);}
    }
    public static final class FixtureSession {
        String script,node;FixtureSession(String script,String node){this.script=script;this.node=node;}
        public boolean isActive(){return node!=null&&!node.isBlank();}public String scriptId(){return script;}public String currentNodeId(){return node;}
    }
    public static final class FixtureScript {
        final String id;final Map<String,FixtureNode> nodes=new HashMap<>(Map.of("arrival",new FixtureNode("arrival",true),"evidence",new FixtureNode("evidence",false),"departure",new FixtureNode("departure",false)));
        FixtureScript(String id){this.id=id;}public String getId(){return id;}public String getTitle(){return "Owned native scene";}public FixtureNode getStartNode(){return nodes.get("arrival");}public Optional<FixtureNode> findNode(String node){return Optional.ofNullable(nodes.get(node));}
    }
    public static final class FixtureNode {
        final String id;final boolean choice;FixtureNode(String id,boolean choice){this.id=id;this.choice=choice;}public String getId(){return id;}
        public List<Object> getDialogues(){return List.of();}public List<FixtureChoice> getChoices(){return choice?List.of(new FixtureChoice()):List.of();}
    }
    public static final class FixtureChoice {public String getText(){return "Native choice";}}
    public static final class FixtureEvaluator {public boolean evaluateChoice(FixtureChoice choice,FixtureState state){return true;}}
}
