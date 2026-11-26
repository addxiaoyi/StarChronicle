package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.*;
import io.github.addxiaoyi.starchronicle.api.quest.*;
import io.github.addxiaoyi.starchronicle.api.quest.QuestObservation.Status;
import io.github.addxiaoyi.starchronicle.api.quest.QuestRequest.Mode;
import io.github.addxiaoyi.starchronicle.config.Condition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Full engine, causal inbox and real SQLite integration, with no fabricated native execution. */
class QuestGateEngineTest {
    @TempDir Path directory;
    final UUID a=new UUID(0,101),b=new UUID(0,102),foreign=new UUID(0,103),outsider=new UUID(0,104);
    final Instant epoch=Instant.parse("2026-11-20T12:00:00Z");
    final Set<UUID> online=Set.of(a,b,foreign);
    final String provider="starcore-native-v1",issuer="starcore.quest.evidence.v1";
    final MutableClock clock=new MutableClock(epoch);
    SqliteStore store;RuleEngine engine;List<StoryDefinition> definitions;

    static final class MutableClock extends Clock {
        Instant time;MutableClock(Instant value){time=value;}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return Clock.fixed(time,zone);}
        public Instant instant(){return time;}void set(Instant value){time=value;}
        void after(Instant value){if(time.isBefore(value))time=value;}
    }
    PlayerContext context(UUID player,String nation){return context(player,nation,"scholar",Set.of("starchronicle.use","chronicle.choose","quest.admit"));}
    PlayerContext context(UUID player,String nation,String role,Set<String> permissions){return new PlayerContext(player,nation,Map.of("player.role",role),permissions);}
    StoryDefinition story(String id,Mode mode,int quorum,VoteUnit voteUnit,Condition condition,String permission) {
        var wait=new Effect("supplies","quest.await","native-supplies",mode.name(),permission);
        var choice=new Choice("Continue","end",null,"chronicle.choose",List.of());
        var end=new Node("End",null,null,1,Map.of(),List.of(new Effect("honor","honor","player","10",null),new Effect("history","history","EPIC_FINISHED","Supplies delivered",null)),true);
        return new StoryDefinition(id,"1","Quest evidence",Scope.WORLD,List.of("ROOT"),condition,"wait",0,Map.of("wait",new Node("Supply the expedition",null,null,quorum,Map.of("yes",choice),List.of(wait),false),"end",end),voteUnit);
    }
    StoryDefinition story(Mode mode){return story("expedition",mode,1,VoteUnit.PLAYER,null,"quest.admit");}
    WorldEvent source(){return new WorldEvent("expedition:root","ROOT",epoch,online,Set.of("river","hill"),Map.of(),"native-war","committed original source");}
    String start(StoryDefinition story){return start(List.of(story));}
    String start(List<StoryDefinition> stories) {
        definitions=List.copyOf(stories);store=new SqliteStore(directory.resolve("engine.db"));engine=new RuleEngine(store,definitions,clock);
        return engine.accept(source(),List.of(context(a,"river"),context(b,"river"),context(foreign,"hill"))).instanceIds().getFirst();
    }
    void reopen(){engine.close();store=new SqliteStore(directory.resolve("engine.db"));engine=new RuleEngine(store,definitions,clock);}
    QuestGateLedger.Gate gate(UUID owner){return engine.pendingQuestGates(online,100).stream().filter(g->g.playerId().equals(owner)).findFirst().orElseThrow();}
    QuestGateLedger.Gate pinned(UUID owner){var gate=gate(owner);return engine.prepareQuestGate(gate.id(),provider,context(owner,owner.equals(foreign)?"hill":"river"));}
    QuestGateLedger.Gate saved(String id){return store.transaction(c->QuestGateLedger.find(c,id));}
    NationProof nation(String id,Instant time){return new NationProof(NationProof.State.KNOWN_NATION,id,time,"native-country","country="+id+" at "+time);}
    AcceptedRun accepted(QuestGateLedger.Gate gate) {
        Instant pin=gate.providerPinnedAt();return new AcceptedRun(gate.id(),"run:"+gate.id(),gate.playerId(),gate.questId(),"a".repeat(64),pin.plusMillis(1),nation(gate.expectedNationId(),pin),issuer,"native accepted binding="+gate.id());
    }
    CommittedCompletion completion(QuestGateLedger.Gate gate) {
        var run=accepted(gate);Instant pin=gate.providerPinnedAt();
        return new CommittedCompletion(run.bindingId(),run.runId(),"completion:"+gate.id(),run.playerId(),run.questId(),run.definitionFingerprint(),run.startedAt(),pin.plusMillis(2),pin.plusMillis(3),run.acceptedNation(),nation(gate.expectedNationId(),pin.plusMillis(2)),issuer,run.evidence(),"native durable completion binding="+gate.id());
    }
    QuestGateLedger.Mutation finish(QuestGateLedger.Gate gate) {
        var receipt=completion(gate);clock.after(receipt.committedAt().plusMillis(2));
        return engine.observeQuestGate(gate.id(),provider,new QuestObservation(Status.COMMITTED,receipt.acceptedRun(),receipt,clock.instant(),"durably completed"));
    }
    long count(String table){return store.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}});}
    void sql(String text){store.transaction(c->{try(var s=c.createStatement()){s.execute(text);}return null;});}
    long questHistory(){return engine.history(null,null,0,100).stream().filter(e->e.type().equals("quest.completed")).count();}
    StoryInstance instance(String id){return engine.instances(a).stream().filter(i->i.id().equals(id)).findFirst().orElseThrow();}
    @AfterEach void close(){if(engine!=null)engine.close();}

    @Test void anExitedMembersUnfinishedGateDoesNotCorruptOrStarveRemainingMembers() {
        String id=start(story(Mode.NATION));var exited=gate(b);var keeper=pinned(a);
        engine.exitMember(id,context(b,"river"));
        assertFalse(saved(exited.id()).active());
        assertTrue(engine.pendingQuestGates(online,100).stream().noneMatch(g->g.playerId().equals(b)));
        finish(keeper);assertTrue(engine.questGateState(id,context(a,"river")).ready());
        engine.choose(id,a,"yes",context(a,"river"));assertTrue(instance(id).completed());
        assertFalse(saved(exited.id()).active());assertEquals(1,questHistory());
    }
    @Test void exitedMembersCommittedNationalEvidenceRemainsHistoricalAfterRestart() {
        String id=start(story(Mode.NATION));var completed=pinned(a);finish(completed);
        var receipt=saved(completed.id()).completion();engine.exitMember(id,context(a,"river"));reopen();
        assertEquals(receipt,saved(completed.id()).completion());assertFalse(saved(completed.id()).active());
        assertTrue(engine.questGateState(id,context(b,"river")).ready());
        assertThrows(SecurityException.class,()->engine.prepareQuestGate(completed.id(),provider,context(a,"river")));
        assertEquals(1,questHistory());
        sql("DELETE FROM story_member_reviews WHERE player_id='"+a+"'");
        assertEquals(receipt,saved(completed.id()).completion(),"The append-only exit history remains authoritative");
        sql("DELETE FROM story_member_review_log WHERE player_id='"+a+"'");
        assertThrows(IllegalStateException.class,()->saved(completed.id()),"Missing historical membership evidence must still be treated as corruption");
    }
    @Test void personalTaskRemainsReadableButInactiveAfterItsOnlyMemberExits() {
        var template=story(Mode.PLAYER);
        var personal=new StoryDefinition(template.id(),template.version(),template.title(),Scope.PLAYER,template.triggers(),template.condition(),template.start(),template.cooldownSeconds(),template.nodes());
        start(personal);String id=engine.instances(a).getFirst().id();var gate=pinned(a);
        engine.exitMember(id,context(a,"river"));reopen();
        assertFalse(saved(gate.id()).active());assertEquals(gate.acceptedRun(),saved(gate.id()).acceptedRun());
        assertTrue(engine.instances(a).isEmpty());
    }
    @Test void upstreamExitReviewWithoutNewAppendLogStillProvesOriginalQuestMembership() {
        String id=start(story(Mode.NATION));var gate=pinned(a);
        engine.exitMember(id,context(a,"river"));sql("DELETE FROM story_member_review_log");reopen();
        assertFalse(saved(gate.id()).active());assertEquals(gate.acceptedRun(),saved(gate.id()).acceptedRun());
        sql("DELETE FROM story_member_reviews WHERE player_id='"+a+"'");
        assertThrows(IllegalStateException.class,()->saved(gate.id()));
    }
    @Test void personalScopeCanUseAppendOnlyExitProofWhenCurrentReviewIsMissing() {
        var template=story(Mode.PLAYER);
        var personal=new StoryDefinition(template.id(),template.version(),template.title(),Scope.PLAYER,template.triggers(),template.condition(),template.start(),template.cooldownSeconds(),template.nodes());
        start(personal);String id=engine.instances(a).getFirst().id();var gate=pinned(a);
        engine.exitMember(id,context(a,"river"));sql("DELETE FROM story_member_reviews WHERE player_id='"+a+"'");reopen();
        assertFalse(saved(gate.id()).active());assertEquals(gate.acceptedRun(),saved(gate.id()).acceptedRun());
        sql("DELETE FROM story_member_review_log WHERE player_id='"+a+"'");
        assertThrows(IllegalStateException.class,()->saved(gate.id()));
    }

    @Test void awaitCreatesNativeEvidenceGatesAndBlocksChoiceUntilCommittedReceipt() {
        String id=start(story(Mode.PLAYER));assertEquals(3,engine.pendingQuestGates(online,100).size());assertTrue(engine.pending().isEmpty());assertEquals(0,count("outcomes"));
        assertThrows(SecurityException.class,()->engine.choose(id,a,"yes",context(a,"river")));assertTrue(engine.availableChoices(id,context(a,"river")).isEmpty());
        var gate=pinned(a);var run=accepted(gate);clock.after(run.startedAt().plusMillis(1));
        engine.observeQuestGate(gate.id(),provider,new QuestObservation(Status.ACTIVE,run,null,clock.instant(),"objectives ready is still not committed"));
        assertThrows(SecurityException.class,()->engine.choose(id,a,"yes",context(a,"river")));assertEquals(0,questHistory());assertTrue(engine.facts(a).isEmpty());
        assertTrue(finish(gate).completionNewlyRecorded());assertTrue(engine.questGateState(id,context(a,"river")).ready());assertFalse(instance(id).completed());assertTrue(engine.facts(a).isEmpty());
        engine.choose(id,a,"wait","yes",context(a,"river"));assertTrue(instance(id).completed());assertEquals("10",engine.facts(a).get("player.honor"));assertEquals(3,engine.history(null,null,0,100).size());assertEquals(1,questHistory());
    }
    @Test void personalGateProjectionHidesOtherMembersAndNeverSharesCompletion() {
        String id=start(story(Mode.PLAYER));var ga=pinned(a);finish(ga);
        var own=engine.questGateState(id,context(a,"river"));var other=engine.questGateState(id,context(b,"river"));
        assertTrue(own.ready());assertFalse(other.ready());assertEquals(Set.of(a),own.gates().stream().map(QuestGateLedger.Gate::playerId).collect(java.util.stream.Collectors.toSet()));assertEquals(Set.of(b),other.gates().stream().map(QuestGateLedger.Gate::playerId).collect(java.util.stream.Collectors.toSet()));
        assertThrows(SecurityException.class,()->engine.choose(id,b,"yes",context(b,"river")));assertTrue(engine.pending().isEmpty());
    }
    @Test void oneOriginalCitizenReadiesOnlyItsNationWithoutGrantingNationalAuthority() {
        var national=story("national",Mode.NATION,2,VoteUnit.NATION,null,"quest.admit");String id=start(national);finish(pinned(a));
        var river=engine.questGateState(id,context(b,"river"));assertTrue(river.ready());assertEquals(Set.of(a,b),river.gates().stream().map(QuestGateLedger.Gate::playerId).collect(java.util.stream.Collectors.toSet()));
        assertFalse(engine.questGateState(id,context(foreign,"hill")).ready());assertThrows(SecurityException.class,()->engine.questGateState(id,context(b,"hill")));
        assertThrows(SecurityException.class,()->engine.nationalVote(id,b,"wait",0,"yes",context(b,"river")));assertThrows(SecurityException.class,()->engine.choose(id,b,"yes",context(b,"river")));assertFalse(instance(id).completed());assertEquals(0,count("story_nation_vote_log"));
    }
    @Test void admissionRevalidatesOwnerOriginalNationUsePermissionEffectPermissionAndStoryCondition() {
        var condition=new Condition(null,null,null,"player.role","eq","scholar");String id=start(story("restricted",Mode.PLAYER,1,VoteUnit.PLAYER,condition,"quest.admit"));var gate=gate(a);
        var invalid=List.of(context(b,"river"),context(a,"hill"),context(outsider,"river"),context(a,"river","scholar",Set.of("quest.admit")),context(a,"river","scholar",Set.of("starchronicle.use")),context(a,"river","soldier",Set.of("starchronicle.use","quest.admit")));
        for(var actor:invalid){assertThrows(SecurityException.class,()->engine.prepareQuestGate(gate.id(),provider,actor));assertNull(saved(gate.id()).providerId());}
        var pinned=engine.prepareQuestGate(gate.id(),provider,context(a,"river"));assertNotNull(pinned.providerPinnedAt());var run=accepted(pinned);clock.after(run.startedAt().plusMillis(1));engine.observeQuestGate(gate.id(),provider,new QuestObservation(Status.ACTIVE,run,null,clock.instant(),"accepted"));
        assertThrows(SecurityException.class,()->engine.prepareQuestGate(gate.id(),provider,context(a,"river","scholar",Set.of("starchronicle.use"))));assertEquals(provider,saved(gate.id()).providerId());assertEquals(run,saved(gate.id()).acceptedRun());assertFalse(engine.questGateState(id,context(a,"river")).ready());
    }
    @Test void nativeAbandonAndExpiryKeepTheGateBlockedWithoutInventingCompletion() {
        String id=start(story(Mode.PLAYER));var gate=pinned(a);var run=accepted(gate);clock.after(run.startedAt().plusMillis(1));
        engine.observeQuestGate(gate.id(),provider,new QuestObservation(Status.ABANDONED,run,null,clock.instant(),"native run abandoned"));
        assertEquals(Status.ABANDONED,saved(gate.id()).status());assertFalse(engine.questGateState(id,context(a,"river")).ready());
        assertThrows(SecurityException.class,()->engine.choose(id,a,"yes",context(a,"river")));assertEquals(0,questHistory());
    }
    @Test void currentGateProjectionRequiresMembershipOriginalNationAndUsePermission() {
        String id=start(story(Mode.PLAYER));
        assertThrows(SecurityException.class,()->engine.questGateState(id,context(outsider,"river")));
        assertThrows(SecurityException.class,()->engine.questGateState(id,context(a,"hill")));
        assertThrows(SecurityException.class,()->engine.questGateState(id,context(a,"river","scholar",Set.of("quest.admit"))));
        assertEquals(1,engine.questGateState(id,context(a,"river")).gates().size());
    }
    @Test void nativeReceiptHistoryHasStableIdentityTrueParticipantCountryAndBusinessTime() {
        start(story(Mode.PLAYER));var gate=pinned(a);var proof=completion(gate);finish(gate);
        var history=engine.history(null,null,0,100).stream().filter(e->e.type().equals("quest.completed")).findFirst().orElseThrow();
        assertEquals(RuleEngine.questCompletionEventId(issuer,proof.completionId()),history.id());assertEquals(proof.completedAt(),history.occurredAt());assertEquals(Set.of(a),history.participants());assertEquals(Set.of("river"),history.nations());assertEquals(issuer,history.source());assertEquals(gate.id(),history.facts().get("quest.gate"));assertEquals(proof.runId(),history.facts().get("quest.run"));assertTrue(history.evidence().contains("receipt_sha256="));
        assertEquals(1,engine.history("river",a,0,100).stream().filter(e->e.type().equals("quest.completed")).count());assertEquals(0,engine.history(null,b,0,100).stream().filter(e->e.type().equals("quest.completed")).count());assertEquals(0,engine.history("hill",null,0,100).stream().filter(e->e.type().equals("quest.completed")).count());
    }
    @Test void completionReplayAndProviderPinSurviveRestartWithoutRewardOrHistoryDuplication() {
        String id=start(story(Mode.PLAYER));var gate=pinned(a);var proof=completion(gate);finish(gate);long revision=saved(gate.id()).revision();reopen();
        clock.after(epoch.plusSeconds(10));var replay=engine.observeQuestGate(gate.id(),provider,new QuestObservation(Status.COMMITTED,proof.acceptedRun(),proof,clock.instant(),"canonical replay"));
        assertFalse(replay.changed());assertFalse(replay.completionNewlyRecorded());assertEquals(revision,saved(gate.id()).revision());assertEquals(gate.providerPinnedAt(),saved(gate.id()).providerPinnedAt());assertEquals(1,questHistory());assertTrue(engine.facts(a).isEmpty());assertTrue(engine.questGateState(id,context(a,"river")).ready());
        assertTrue(engine.accept(source(),List.of(context(a,"river"))).duplicate());engine.choose(id,a,"yes",context(a,"river"));assertEquals("10",engine.facts(a).get("player.honor"));assertEquals(1,questHistory());
    }
    @Test void conflictingHistoryIdRollsBackReceiptConsumptionAndGateCompletionTogether() {
        String id=start(story(Mode.PLAYER));var gate=pinned(a);var proof=completion(gate);String collision=RuleEngine.questCompletionEventId(issuer,proof.completionId());
        engine.accept(new WorldEvent(collision,"UNRELATED",epoch,Set.of(),Set.of(),Map.of(),"collision-fixture","existing different canonical source"),List.of());
        assertThrows(IllegalArgumentException.class,()->finish(gate));assertEquals(0,count("quest_gate_completions"));assertEquals(0,questHistory());assertEquals(Status.NOT_STARTED,saved(gate.id()).status());assertNull(saved(gate.id()).acceptedRun());assertFalse(engine.questGateState(id,context(a,"river")).ready());assertEquals(2,count("events"));
    }
    @Test void injectedHistoryInsertFailureRollsBackAllTaskEvidenceAndRetryCommitsOnce() {
        String id=start(story(Mode.PLAYER));var gate=pinned(a);
        sql("CREATE TRIGGER refuse_quest_history BEFORE INSERT ON events WHEN NEW.type='quest.completed' BEGIN SELECT RAISE(ABORT,'injected history failure'); END");
        assertThrows(IllegalStateException.class,()->finish(gate));assertEquals(0,count("quest_gate_completions"));assertEquals(0,questHistory());assertFalse(saved(gate.id()).completed());assertNull(saved(gate.id()).acceptedRun());assertFalse(engine.questGateState(id,context(a,"river")).ready());
        sql("DROP TRIGGER refuse_quest_history");assertTrue(finish(gate).completionNewlyRecorded());assertFalse(finish(gate).completionNewlyRecorded());assertEquals(1,count("quest_gate_completions"));assertEquals(1,questHistory());
    }
    @Test void lateCitizenCompletionAfterSharedNodeClosesCannotRewriteGateOrAddHistory() {
        String id=start(story(Mode.NATION));var ga=pinned(a);var gb=pinned(b);finish(ga);engine.choose(id,a,"yes",context(a,"river"));assertTrue(instance(id).completed());
        long revision=saved(gb.id()).revision();clock.after(epoch.plusSeconds(10));var late=finish(gb);
        assertFalse(late.changed());assertFalse(late.completionNewlyRecorded());assertEquals(revision,saved(gb.id()).revision());assertFalse(saved(gb.id()).active());assertFalse(saved(gb.id()).completed());assertEquals(1,questHistory());assertEquals(1,count("quest_gate_completions"));assertTrue(engine.pendingQuestGates(online,100).isEmpty());
        assertFalse(engine.questUnavailable(gb.id(),"late disabled provider").changed());assertThrows(SecurityException.class,()->engine.prepareQuestGate(gb.id(),provider,context(b,"river")));
    }
    @Test void unavailableCapabilityDoesNotPinABackendAndUnknownCannotEraseAcceptedIdentity() {
        String id=start(story(Mode.PLAYER));var gate=gate(a);clock.after(epoch.plusMillis(1));var unavailable=engine.questUnavailable(gate.id(),"no registered capable provider");assertEquals(Status.UNSUPPORTED,unavailable.gate().status());assertNull(unavailable.gate().providerId());assertEquals(0,questHistory());
        var pinned=engine.prepareQuestGate(gate.id(),provider,context(a,"river"));var run=accepted(pinned);clock.after(run.startedAt().plusMillis(1));engine.observeQuestGate(gate.id(),provider,new QuestObservation(Status.ACTIVE,run,null,clock.instant(),"accepted"));
        clock.after(clock.instant().plusMillis(1));engine.observeQuestGate(gate.id(),provider,new QuestObservation(Status.UNKNOWN,null,null,clock.instant(),"provider uncertain"));clock.after(clock.instant().plusMillis(1));engine.questUnavailable(gate.id(),"provider removed");
        assertEquals(run,saved(gate.id()).acceptedRun());assertEquals(provider,saved(gate.id()).providerId());assertEquals(pinned.providerPinnedAt(),saved(gate.id()).providerPinnedAt());assertFalse(engine.questGateState(id,context(a,"river")).ready());assertThrows(IllegalStateException.class,()->engine.prepareQuestGate(gate.id(),"different-provider",context(a,"river")));assertEquals(0,questHistory());
    }
    @Test void committedNativeHistoryQueuesFollowupWithoutRecursiveStoryExecution() {
        var base=story(Mode.PLAYER);var follow=new StoryDefinition("followup","1","After the expedition",Scope.PLAYER,List.of("quest.completed"),null,"end",0,Map.of("end",new Node("The chronicle remembers",null,null,1,Map.of(),List.of(),true)));
        String origin=start(List.of(base,follow));var gate=pinned(a);var proof=completion(gate);finish(gate);
        assertEquals(1,engine.instances(a).size());assertFalse(instance(origin).completed());assertEquals(1,questHistory());assertTrue(engine.facts(a).isEmpty());
        var resumed=engine.resumeTriggers(List.of(context(a,"river")));assertEquals(1,resumed.instanceIds().size());var derived=engine.instances(a).stream().filter(i->i.definition().id().equals("followup")).findFirst().orElseThrow();assertEquals(RuleEngine.questCompletionEventId(issuer,proof.completionId()),derived.sourceEventId());assertTrue(derived.completed());
        assertFalse(finish(gate).completionNewlyRecorded());assertTrue(engine.resumeTriggers(List.of(context(a,"river"))).instanceIds().isEmpty());assertEquals(2,engine.instances(a).size());assertEquals(1,questHistory());
    }
    @Test void boundedPendingGateQueryFiltersOfflineAdmissionsAndPollsPinnedOfflineReceipt() {
        start(story(Mode.PLAYER));var ga=pinned(a);assertEquals(List.of(ga.id()),engine.pendingQuestGates(Set.of(),100).stream().map(QuestGateLedger.Gate::id).toList());
        var query=engine.pendingQuestGates(Set.of(b),100);assertEquals(Set.of(a,b),query.stream().map(QuestGateLedger.Gate::playerId).collect(java.util.stream.Collectors.toSet()));
        assertThrows(IllegalArgumentException.class,()->engine.pendingQuestGates(online,0));assertThrows(IllegalArgumentException.class,()->engine.pendingQuestGates(online,101));finish(ga);assertTrue(engine.pendingQuestGates(Set.of(),100).isEmpty());
    }
    @Test void recoveryRechecksCurrentMembershipPermissionAndAttemptWithoutCreatingHistory() {
        String id=start(story(Mode.NATION));var gate=pinned(a);var run=accepted(gate);clock.after(epoch.plusSeconds(2));
        engine.observeQuestGate(gate.id(),provider,new QuestObservation(Status.FAILED,run,null,clock.instant(),"failed"));
        var proof=new QuestTermination(run,Status.FAILED,"termination",clock.instant(),clock.instant(),"native terminal receipt");
        assertThrows(SecurityException.class,()->engine.prepareQuestRecovery(gate.id(),1,context(b,"river")));
        assertThrows(SecurityException.class,()->engine.prepareQuestRecovery(gate.id(),1,context(a,"hill")));
        assertThrows(SecurityException.class,()->engine.prepareQuestRecovery(gate.id(),1,context(a,"river","scholar",Set.of("starchronicle.use"))));
        var next=engine.retryQuestGate(gate.id(),1,provider,proof,context(a,"river"));
        assertEquals(2,next.attempt());assertEquals(0,questHistory());assertEquals(0,count("outcomes"));
        assertThrows(SecurityException.class,()->engine.prepareQuestRecovery(gate.id(),1,context(a,"river")));
        engine.exitMember(id,"wait",context(a,"river"));
        assertThrows(SecurityException.class,()->engine.prepareQuestRecovery(gate.id(),2,context(a,"river")));
    }

    @Test void exitKeepsGateEvidenceReadableWithoutPollingAndRejoinCannotChangeItsNation() {
        String id=start(story(Mode.NATION));var gate=pinned(a);var run=accepted(gate);clock.after(epoch.plusSeconds(2));
        engine.observeQuestGate(gate.id(),provider,new QuestObservation(Status.ACTIVE,run,null,clock.instant(),"active"));
        engine.exitMember(id,"wait",context(a,"river"));
        assertFalse(saved(gate.id()).active());assertEquals(run,saved(gate.id()).acceptedRun());
        assertTrue(engine.pendingQuestGates(online,100).stream().noneMatch(g->g.playerId().equals(a)));
        assertFalse(engine.questGateSnapshot(id,context(b,"river")).ready());
        reopen();assertFalse(saved(gate.id()).active());
        engine.join(id,context(a,"hill"));
        assertFalse(saved(gate.id()).active());assertEquals("river",saved(gate.id()).expectedNationId());
        assertThrows(SecurityException.class,()->engine.prepareQuestRecovery(gate.id(),1,context(a,"hill")));
    }
    @Test void alreadyCompletedCountryTaskRemainsHistoricalAfterContributorExits() {
        String id=start(story(Mode.NATION));var gate=pinned(a);finish(gate);
        engine.exitMember(id,"wait",context(a,"river"));
        assertTrue(saved(gate.id()).completed());assertFalse(saved(gate.id()).active());
        assertTrue(engine.questGateState(id,context(b,"river")).ready());assertEquals(1,questHistory());
        reopen();assertTrue(engine.questGateState(id,context(b,"river")).ready());assertEquals(1,questHistory());
    }

}
