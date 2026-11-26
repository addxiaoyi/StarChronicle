package io.github.addxiaoyi.starchronicle.core;

import io.github.addxiaoyi.starchronicle.api.PlayerContext;
import io.github.addxiaoyi.starchronicle.api.quest.*;
import io.github.addxiaoyi.starchronicle.api.quest.QuestObservation.Status;
import io.github.addxiaoyi.starchronicle.api.quest.QuestRequest.Mode;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition;
import io.github.addxiaoyi.starchronicle.config.StoryDefinition.*;
import io.github.addxiaoyi.starchronicle.persistence.SqliteStore;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real, file-backed SQLite contracts. No native provider is called by this ledger. */
class QuestGateLedgerTest {
    @TempDir Path directory;
    final UUID a=UUID.fromString("00000000-0000-0000-0000-000000000001"),b=UUID.fromString("00000000-0000-0000-0000-000000000002"),other=UUID.fromString("00000000-0000-0000-0000-000000000003");
    final Instant now=Instant.parse("2026-11-20T00:00:00Z");
    final String provider="starcore-native-v1",issuer="starcore.quest.evidence.v1",fingerprint="a".repeat(64);
    Path database(){return directory.resolve("quest-gates.db");}
    PlayerContext actor(UUID id,String nation){return new PlayerContext(id,nation,Map.of(),Set.of());}
    Effect await(String id,Mode mode){return new Effect(id,"quest.await","native-"+id,mode.name(),null);}
    StoryInstance instance(String id,Map<UUID,String> members,Effect... effects) {
        var nodes=Map.of("council",new Node("Contribute",null,null,1,Map.of("continue",new Choice("Continue","done",null,null,List.of())),List.of(effects),false),"done",new Node("Done",null,null,1,Map.of(),List.of(),true));
        var definition=new StoryDefinition("heir","1","Heir",Scope.WORLD,List.of("TEST"),null,"council",0,nodes);
        return new StoryInstance(id,definition,"world","event:"+id,"council",members,Map.of(),Map.of(),now,now,false);
    }
    StoryInstance one(String id,Mode mode){return instance(id,Map.of(a,"river"),await("supply",mode));}
    StoryInstance move(StoryInstance i,boolean completed){return new StoryInstance(i.id(),i.definition(),i.scopeKey(),i.sourceEventId(),"done",i.members(),Map.of(),i.choices(),i.startedAt(),now.plusSeconds(30),completed);}
    Connection connect()throws Exception {
        Class.forName("org.sqlite.JDBC");Connection c=DriverManager.getConnection("jdbc:sqlite:"+database().toAbsolutePath());
        try(var s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");}return c;
    }
    Connection fresh()throws Exception {
        Connection c=connect();try(var s=c.createStatement()) {
            s.execute("CREATE TABLE events(id TEXT PRIMARY KEY)");
            s.execute("CREATE TABLE instances(id TEXT PRIMARY KEY,story TEXT NOT NULL,scope TEXT NOT NULL,completed INTEGER NOT NULL,updated TEXT NOT NULL,json TEXT NOT NULL)");
        }
        c.setAutoCommit(false);MemberReviewLedger.createSchema(c);QuestGateLedger.createSchema(c);return c;
    }
    void seed(Connection c,StoryInstance i)throws SQLException {try(var s=c.prepareStatement("INSERT OR IGNORE INTO events(id) VALUES(?)")){s.setString(1,i.sourceEventId());s.executeUpdate();}save(c,i);}
    void save(Connection c,StoryInstance i)throws SQLException {
        try(var s=c.prepareStatement("INSERT INTO instances VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET story=excluded.story,scope=excluded.scope,completed=excluded.completed,updated=excluded.updated,json=excluded.json")) {
            s.setString(1,i.id());s.setString(2,i.definition().id());s.setString(3,i.scopeKey());s.setInt(4,i.completed()?1:0);s.setString(5,i.updatedAt().toString());s.setString(6,SqliteStore.json(i));s.executeUpdate();
        }
    }
    QuestGateLedger.Gate gate(Connection c,StoryInstance i,UUID owner,String effect)throws SQLException {return QuestGateLedger.gates(c,i.id(),"council").stream().filter(g->g.playerId().equals(owner)&&g.effectId().equals(effect)).findFirst().orElseThrow();}
    QuestGateLedger.Gate open(Connection c,StoryInstance i)throws SQLException {seed(c,i);QuestGateLedger.open(c,i,now);return gate(c,i,a,"supply");}
    QuestGateLedger.Gate pin(Connection c,StoryInstance i)throws SQLException {var g=open(c,i);return QuestGateLedger.pinProvider(c,g.id(),provider,now);}
    NationProof nation(String id,Instant at){return new NationProof(id.isBlank()?NationProof.State.KNOWN_NONE:NationProof.State.KNOWN_NATION,id,at,"country-service","country lookup "+id+" at "+at);}
    AcceptedRun accepted(QuestGateLedger.Gate g){return accepted(g,"run:"+g.id());}
    AcceptedRun accepted(QuestGateLedger.Gate g,String run){return new AcceptedRun(g.id(),run,g.playerId(),g.questId(),fingerprint,now.plusSeconds(1),nation(g.expectedNationId(),now.plusMillis(900)),issuer,"native accepted "+run);}
    AcceptedRun change(AcceptedRun r,String binding,String run,UUID player,String quest,String hash,Instant start,NationProof nation,String source,String evidence){return new AcceptedRun(binding,run,player,quest,hash,start,nation,source,evidence);}
    CommittedCompletion completed(AcceptedRun r){return completed(r,"completion:"+r.runId());}
    CommittedCompletion completed(AcceptedRun r,String id){return new CommittedCompletion(r.bindingId(),r.runId(),id,r.playerId(),r.questId(),r.definitionFingerprint(),r.startedAt(),now.plusSeconds(2),now.plusSeconds(3),r.acceptedNation(),nation(r.acceptedNation().nationId()==null?"":r.acceptedNation().nationId(),now.plusMillis(1900)),r.issuer(),r.evidence(),"native durable completion "+id);}
    CommittedCompletion changeCompletion(CommittedCompletion r,Instant ended,Instant committed,NationProof completedNation,String acceptanceEvidence,String evidence){return new CommittedCompletion(r.bindingId(),r.runId(),r.completionId(),r.playerId(),r.questId(),r.definitionFingerprint(),r.startedAt(),ended,committed,r.acceptedNation(),completedNation,r.issuer(),acceptanceEvidence,evidence);}
    QuestObservation active(AcceptedRun r){return new QuestObservation(Status.ACTIVE,r,null,now.plusSeconds(4),"active");}
    QuestObservation committed(CommittedCompletion r){return new QuestObservation(Status.COMMITTED,r.acceptedRun(),r,now.plusSeconds(4),"completed");}
    QuestGateLedger.Mutation finish(Connection c,QuestGateLedger.Gate g)throws SQLException {return QuestGateLedger.observe(c,g.id(),provider,committed(completed(accepted(g))),now.plusSeconds(5));}
    long count(Connection c,String table)throws SQLException {try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
    String scalar(Connection c,String sql)throws SQLException {try(var s=c.createStatement();var r=s.executeQuery(sql)){return r.next()?r.getString(1):null;}}

    @Test void everyEntryPointRequiresTheEnclosingTransaction()throws Exception {
        try(var c=connect()) {
            var i=one("tx",Mode.PLAYER);
            assertThrows(IllegalStateException.class,()->QuestGateLedger.createSchema(c));
            assertThrows(IllegalStateException.class,()->QuestGateLedger.open(c,i,now));
            assertThrows(IllegalStateException.class,()->QuestGateLedger.gates(c,i.id(),i.node()));
            assertThrows(IllegalStateException.class,()->QuestGateLedger.find(c,"gate"));
            assertThrows(IllegalStateException.class,()->QuestGateLedger.pending(c,Set.of(a),1));
            assertThrows(IllegalStateException.class,()->QuestGateLedger.pinProvider(c,"gate",provider,now));
            assertThrows(IllegalStateException.class,()->QuestGateLedger.unavailable(c,"gate","offline",now));
            assertThrows(IllegalStateException.class,()->QuestGateLedger.observe(c,"gate",provider,null,now));
            assertThrows(IllegalStateException.class,()->QuestGateLedger.ready(c,i,actor(a,"river")));
        }
    }
    @Test void stableBindingsOpenEachAwaitForEveryMemberOnlyOnce()throws Exception {
        try(var c=fresh()) {
            var i=instance("stable",Map.of(a,"river",b,"river",other,"hill"),await("supply",Mode.PLAYER),await("ruins",Mode.NATION));seed(c,i);
            var first=QuestGateLedger.open(c,i,now.plusNanos(999999));var again=QuestGateLedger.open(c,i,now.plusSeconds(40));
            assertEquals(6,first.size());assertEquals(first,again);assertEquals(6,count(c,"quest_gates"));
            for(var g:first){assertEquals(RuleEngine.stable("quest-gate",i.id(),i.node(),g.effectId(),g.playerId().toString()),g.id());assertEquals(g.id(),g.request().bindingId());assertEquals(now,g.openedAt());assertEquals(1,g.revision());assertTrue(g.active());}
            assertThrows(UnsupportedOperationException.class,()->first.clear());
        }
    }
    @Test void nodesWithoutAwaitKeepLegacyReadinessAndRequireMembership()throws Exception {
        try(var c=fresh()) {
            var i=instance("legacy",Map.of(a,"river"));seed(c,i);assertTrue(QuestGateLedger.open(c,i,now).isEmpty());
            assertTrue(QuestGateLedger.ready(c,i,actor(a,"different")));assertFalse(QuestGateLedger.ready(c,i,actor(b,"river")));
        }
    }
    @Test void acceptanceAndUncertainDiagnosticsNeverCountAsCompletion()throws Exception {
        try(var c=fresh()) {
            var i=one("acceptance",Mode.PLAYER);var g=pin(c,i);var run=accepted(g);
            assertTrue(QuestGateLedger.observe(c,g.id(),provider,active(run),now.plusSeconds(5)).changed());
            assertFalse(QuestGateLedger.ready(c,i,actor(a,"river")));assertEquals(0,count(c,"quest_gate_completions"));
            QuestGateLedger.observe(c,g.id(),provider,new QuestObservation(Status.UNKNOWN,null,null,now.plusSeconds(6),"provider uncertain"),now.plusSeconds(6));
            var unavailable=QuestGateLedger.unavailable(c,g.id(),"provider disabled",now.plusSeconds(7));
            assertEquals(run,unavailable.gate().acceptedRun());assertEquals(provider,unavailable.gate().providerId());assertEquals(Status.UNSUPPORTED,unavailable.gate().status());assertFalse(unavailable.completionNewlyRecorded());
            assertFalse(QuestGateLedger.ready(c,i,actor(a,"river")));
        }
    }
    @Test void providerIsPinnedBeforeAnyObservationAndCannotSwitchAfterUnknown()throws Exception {
        try(var c=fresh()) {
            var i=one("provider",Mode.PLAYER);var g=open(c,i);
            assertThrows(IllegalStateException.class,()->QuestGateLedger.observe(c,g.id(),provider,active(accepted(g)),now.plusSeconds(5)));
            var pinned=QuestGateLedger.pinProvider(c,g.id(),provider,now);assertEquals(provider,pinned.providerId());assertEquals(now,pinned.providerPinnedAt());
            QuestGateLedger.observe(c,g.id(),provider,new QuestObservation(Status.UNKNOWN,null,null,now.plusSeconds(1),"unknown admission"),now.plusSeconds(2));
            assertThrows(IllegalStateException.class,()->QuestGateLedger.pinProvider(c,g.id(),"different-provider",now.plusSeconds(3)));
            assertThrows(IllegalStateException.class,()->QuestGateLedger.observe(c,g.id(),"different-provider",active(accepted(g)),now.plusSeconds(5)));
            assertEquals(provider,QuestGateLedger.pinProvider(c,g.id(),provider,now.plusSeconds(10)).providerId());
        }
    }
    @Test void multipleAwaitEffectsNeedOwnEvidenceAndNeverSharePersonalReadiness()throws Exception {
        try(var c=fresh()) {
            var i=instance("multi",Map.of(a,"river",b,"river"),await("supply",Mode.PLAYER),await("ruins",Mode.PLAYER));seed(c,i);QuestGateLedger.open(c,i,now);
            var supply=QuestGateLedger.pinProvider(c,gate(c,i,a,"supply").id(),provider,now);finish(c,supply);
            assertEquals(List.of("ruins"),QuestGateLedger.view(c,i,actor(a,"river")).blockedEffectIds());
            assertFalse(QuestGateLedger.ready(c,i,actor(b,"river")));
            var ruins=QuestGateLedger.pinProvider(c,gate(c,i,a,"ruins").id(),provider,now);finish(c,ruins);
            assertTrue(QuestGateLedger.ready(c,i,actor(a,"changed-country")));assertFalse(QuestGateLedger.ready(c,i,actor(b,"river")));assertEquals(2,count(c,"quest_gate_completions"));
        }
    }
    @Test void oneCitizensCommittedSameNationRunReadiesItsCountryOnlyOnce()throws Exception {
        try(var c=fresh()) {
            var i=instance("nations",Map.of(a,"river",b,"river",other,"hill"),await("supply",Mode.NATION));seed(c,i);QuestGateLedger.open(c,i,now);
            var g=QuestGateLedger.pinProvider(c,gate(c,i,a,"supply").id(),provider,now);finish(c,g);
            assertTrue(QuestGateLedger.ready(c,i,actor(a,"river")));assertTrue(QuestGateLedger.ready(c,i,actor(b,"river")));
            assertFalse(QuestGateLedger.ready(c,i,actor(other,"hill")));assertFalse(QuestGateLedger.ready(c,i,actor(b,"hill")));
            // The country has one satisfied effect even when another citizen independently finishes.
            var second=QuestGateLedger.pinProvider(c,gate(c,i,b,"supply").id(),provider,now);finish(c,second);
            assertTrue(QuestGateLedger.view(c,i,actor(b,"river")).blockedEffectIds().isEmpty());assertEquals(2,count(c,"quest_gate_completions"));
        }
    }
    @Test void rejectsWrongBindingOwnerQuestAndInvalidFingerprintWithoutSavingAcceptance()throws Exception {
        try(var c=fresh()) {
            var i=one("identity",Mode.PLAYER);var g=pin(c,i);var r=accepted(g);
            var wrong=List.of(change(r,"other-binding",r.runId(),a,r.questId(),fingerprint,r.startedAt(),r.acceptedNation(),issuer,r.evidence()),change(r,r.bindingId(),r.runId(),b,r.questId(),fingerprint,r.startedAt(),r.acceptedNation(),issuer,r.evidence()),change(r,r.bindingId(),r.runId(),a,"another-task",fingerprint,r.startedAt(),r.acceptedNation(),issuer,r.evidence()),change(r,r.bindingId(),r.runId(),a,r.questId(),"not-a-sha256",r.startedAt(),r.acceptedNation(),issuer,r.evidence()));
            for(var bad:wrong)assertThrows(IllegalArgumentException.class,()->QuestGateLedger.observe(c,g.id(),provider,active(bad),now.plusSeconds(5)));
            assertNull(gate(c,i,a,"supply").acceptedRun());assertEquals(0,count(c,"quest_gate_completions"));
        }
    }
    @Test void allAcceptanceFieldsRemainImmutableIncludingFingerprintIssuerAndEvidence()throws Exception {
        try(var c=fresh()) {
            var i=one("immutable",Mode.PLAYER);var g=pin(c,i);var r=accepted(g);QuestGateLedger.observe(c,g.id(),provider,active(r),now.plusSeconds(5));
            var changes=List.of(change(r,r.bindingId(),"new-run",a,r.questId(),fingerprint,r.startedAt(),r.acceptedNation(),issuer,r.evidence()),change(r,r.bindingId(),r.runId(),a,r.questId(),"b".repeat(64),r.startedAt(),r.acceptedNation(),issuer,r.evidence()),change(r,r.bindingId(),r.runId(),a,r.questId(),fingerprint,r.startedAt().plusMillis(1),r.acceptedNation(),issuer,r.evidence()),change(r,r.bindingId(),r.runId(),a,r.questId(),fingerprint,r.startedAt(),nation("hill",now.plusMillis(900)),issuer,r.evidence()),change(r,r.bindingId(),r.runId(),a,r.questId(),fingerprint,r.startedAt(),r.acceptedNation(),"other-issuer",r.evidence()),change(r,r.bindingId(),r.runId(),a,r.questId(),fingerprint,r.startedAt(),r.acceptedNation(),issuer,"rewritten acceptance"));
            for(var bad:changes)assertThrows(IllegalStateException.class,()->QuestGateLedger.observe(c,g.id(),provider,active(bad),now.plusSeconds(6)));
            assertEquals(r,gate(c,i,a,"supply").acceptedRun());
        }
    }
    @Test void oldNativeRunAndFutureObservationAreRejected()throws Exception {
        try(var c=fresh()) {
            var i=one("chronology",Mode.PLAYER);var g=pin(c,i);var r=accepted(g);
            var old=change(r,r.bindingId(),r.runId(),a,r.questId(),fingerprint,now.minusMillis(1),nation("river",now.minusMillis(1)),issuer,r.evidence());
            assertThrows(IllegalArgumentException.class,()->QuestGateLedger.observe(c,g.id(),provider,active(old),now.plusSeconds(5)));
            assertThrows(IllegalArgumentException.class,()->QuestGateLedger.observe(c,g.id(),provider,new QuestObservation(Status.ACTIVE,r,null,now.plusSeconds(6),"future"),now.plusSeconds(5)));
            assertThrows(IllegalArgumentException.class,()->QuestGateLedger.observe(c,g.id(),provider,new QuestObservation(Status.ACTIVE,r,null,now.minusMillis(1),"old"),now.plusSeconds(5)));
        }
    }
    @Test void gateAndNativeEpochMillisecondTimesAreCompatible()throws Exception {
        try(var c=fresh()) {
            var i=one("millis",Mode.PLAYER);seed(c,i);QuestGateLedger.open(c,i,now.plusNanos(800000));var g=QuestGateLedger.pinProvider(c,gate(c,i,a,"supply").id(),provider,now.plusNanos(900000));
            var r=new AcceptedRun(g.id(),"same-ms-run",a,g.questId(),fingerprint,now,nation("river",now),issuer,"same millisecond acceptance");
            var done=new CommittedCompletion(g.id(),r.runId(),"same-ms-completion",a,g.questId(),fingerprint,now,now,now,r.acceptedNation(),nation("river",now),issuer,r.evidence(),"all durable in same ms");
            assertTrue(QuestGateLedger.observe(c,g.id(),provider,new QuestObservation(Status.COMMITTED,r,done,now,"same ms"),now).completionNewlyRecorded());
        }
    }
    @Test void nativeRunAndItsCountryProofCannotPredateTheCommittedProviderPin()throws Exception {
        try(var c=fresh()) {
            var i=one("pre-pin-run",Mode.PLAYER);var g=open(c,i);var pinned=QuestGateLedger.pinProvider(c,g.id(),provider,now.plusSeconds(2));
            assertEquals(now.plusSeconds(2),pinned.providerPinnedAt());
            assertThrows(IllegalArgumentException.class,()->QuestGateLedger.observe(c,g.id(),provider,active(accepted(g)),now.plusSeconds(5)));
            var old=accepted(g);var proofBeforePin=change(old,old.bindingId(),old.runId(),a,old.questId(),fingerprint,now.plusSeconds(3),nation("river",now.plusMillis(1999)),issuer,old.evidence());
            assertThrows(IllegalArgumentException.class,()->QuestGateLedger.observe(c,g.id(),provider,active(proofBeforePin),now.plusSeconds(5)));
            var valid=change(old,old.bindingId(),old.runId(),a,old.questId(),fingerprint,now.plusSeconds(3),nation("river",now.plusMillis(2500)),issuer,old.evidence());
            assertTrue(QuestGateLedger.observe(c,g.id(),provider,active(valid),now.plusSeconds(5)).changed());assertEquals(now.plusSeconds(2),gate(c,i,a,"supply").providerPinnedAt());
        }
    }
    @Test void completionRequiresMatchingAcceptedRecordAndStrictDurableChronology()throws Exception {
        try(var c=fresh()) {
            var i=one("bad-completion",Mode.PLAYER);var g=pin(c,i);var done=completed(accepted(g));
            assertThrows(IllegalStateException.class,()->QuestGateLedger.observe(c,g.id(),provider,new QuestObservation(Status.COMMITTED,null,done,now.plusSeconds(4),"missing acceptance"),now.plusSeconds(5)));
            var rewritten=changeCompletion(done,done.completedAt(),done.committedAt(),done.completedNation(),"changed acceptance evidence",done.evidence());
            assertThrows(IllegalStateException.class,()->QuestGateLedger.observe(c,g.id(),provider,new QuestObservation(Status.COMMITTED,done.acceptedRun(),rewritten,now.plusSeconds(4),"changed"),now.plusSeconds(5)));
            for(var bad:List.of(changeCompletion(done,now,done.committedAt(),nation("river",now),done.acceptedEvidence(),done.evidence()),changeCompletion(done,done.completedAt(),now.plusSeconds(1),done.completedNation(),done.acceptedEvidence(),done.evidence()),changeCompletion(done,done.completedAt(),now.plusSeconds(9),done.completedNation(),done.acceptedEvidence(),done.evidence())))
                assertThrows(IllegalArgumentException.class,()->QuestGateLedger.observe(c,g.id(),provider,committed(bad),now.plusSeconds(5)));
            assertEquals(0,count(c,"quest_gate_completions"));
        }
    }
    @Test void nonCommittedStatusesCannotSmuggleCompletionReceipts()throws Exception {
        try(var c=fresh()) {
            var i=one("not-committed",Mode.PLAYER);var g=pin(c,i);var done=completed(accepted(g));
            for(Status status:Status.values())if(status!=Status.COMMITTED)assertThrows(IllegalArgumentException.class,()->QuestGateLedger.observe(c,g.id(),provider,new QuestObservation(status,done.acceptedRun(),done,now.plusSeconds(4),"not durable"),now.plusSeconds(5)));
            assertEquals(0,count(c,"quest_gate_completions"));
        }
    }
    @Test void nationalCompletionRejectsUnknownNoCountryAndChangedCountryProof()throws Exception {
        try(var c=fresh()) {
            var i=one("same-nation",Mode.NATION);var g=pin(c,i);var done=completed(accepted(g));
            var unknown=new NationProof(NationProof.State.UNAVAILABLE,"",now.plusMillis(1900),"country-service","service absent");
            for(var proof:List.of(unknown,nation("",now.plusMillis(1900)),nation("hill",now.plusMillis(1900)))) {
                var bad=changeCompletion(done,done.completedAt(),done.committedAt(),proof,done.acceptedEvidence(),done.evidence());
                assertThrows(IllegalArgumentException.class,()->QuestGateLedger.observe(c,g.id(),provider,committed(bad),now.plusSeconds(5)));
            }
            var r=accepted(g);var badAccept=change(r,r.bindingId(),r.runId(),a,r.questId(),fingerprint,r.startedAt(),nation("hill",now.plusMillis(900)),issuer,r.evidence());
            assertThrows(IllegalArgumentException.class,()->QuestGateLedger.observe(c,g.id(),provider,active(badAccept),now.plusSeconds(5)));
            assertFalse(QuestGateLedger.ready(c,i,actor(a,"river")));assertEquals(0,count(c,"quest_gate_completions"));
        }
    }
    @Test void personalCompletionCanCarryExplicitUnavailableCountryEvidence()throws Exception {
        try(var c=fresh()) {
            var i=one("personal-country-unavailable",Mode.PLAYER);var g=pin(c,i);var r=accepted(g);
            var acceptedUnknown=new NationProof(NationProof.State.UNAVAILABLE,null,now.plusMillis(900),"country-service","service unavailable");
            r=change(r,r.bindingId(),r.runId(),a,r.questId(),fingerprint,r.startedAt(),acceptedUnknown,issuer,r.evidence());var done=completed(r);
            var completedUnknown=new NationProof(NationProof.State.UNAVAILABLE,null,now.plusMillis(1900),"country-service","still unavailable");
            done=changeCompletion(done,done.completedAt(),done.committedAt(),completedUnknown,done.acceptedEvidence(),done.evidence());
            assertTrue(QuestGateLedger.observe(c,g.id(),provider,committed(done),now.plusSeconds(5)).completionNewlyRecorded());assertTrue(QuestGateLedger.ready(c,i,actor(a,"river")));
        }
    }
    @Test void emptyOriginalNationNeverBecomesKnownNoneNationalCompletion()throws Exception {
        try(var c=fresh()) {
            var i=instance("nationless",Map.of(a,""),await("supply",Mode.NATION));var g=open(c,i);
            var unavailable=QuestGateLedger.unavailable(c,g.id(),"no original country",now.plusSeconds(1));assertNull(unavailable.gate().providerId());assertEquals(Status.UNSUPPORTED,unavailable.gate().status());
            QuestGateLedger.pinProvider(c,g.id(),provider,now.plusSeconds(1));var old=accepted(g);
            var noCountry=change(old,old.bindingId(),old.runId(),a,old.questId(),fingerprint,now.plusSeconds(1),nation("",now.plusSeconds(1)),issuer,old.evidence());
            assertThrows(IllegalArgumentException.class,()->QuestGateLedger.observe(c,g.id(),provider,committed(completed(noCountry)),now.plusSeconds(5)));
            assertFalse(QuestGateLedger.ready(c,i,actor(a,"")));assertFalse(QuestGateLedger.ready(c,i,actor(a,"river")));
        }
    }
    @Test void countryProofMustBeContemporaneousAndCannotMislabelUnavailableAsCountry()throws Exception {
        try(var c=fresh()) {
            var i=one("country-proof-time",Mode.PLAYER);var g=pin(c,i);var r=accepted(g);
            for(var badProof:List.of(nation("river",now.minusMillis(1)),nation("river",now.plusMillis(1100)),new NationProof(NationProof.State.UNAVAILABLE,"river",now.plusMillis(900),"country-service","unavailable"))) {
                var bad=change(r,r.bindingId(),r.runId(),a,r.questId(),fingerprint,r.startedAt(),badProof,issuer,r.evidence());
                assertThrows(IllegalArgumentException.class,()->QuestGateLedger.observe(c,g.id(),provider,active(bad),now.plusSeconds(5)));
            }
            var done=completed(r);var bad=changeCompletion(done,done.completedAt(),done.committedAt(),nation("river",now.plusMillis(999)),done.acceptedEvidence(),done.evidence());
            assertThrows(IllegalArgumentException.class,()->QuestGateLedger.observe(c,g.id(),provider,committed(bad),now.plusSeconds(5)));
        }
    }
    @Test void completionAndRunEachHaveIndependentGlobalConsumptionKeys()throws Exception {
        try(var c=fresh()) {
            var first=one("consumer-a",Mode.PLAYER);var ga=pin(c,first);var ra=accepted(ga,"shared-run");var done=completed(ra,"shared-completion");
            QuestGateLedger.observe(c,ga.id(),provider,committed(done),now.plusSeconds(5));
            var second=one("consumer-b",Mode.PLAYER);var gb=pin(c,second);
            var sameCompletion=completed(accepted(gb,"different-run"),"shared-completion");
            assertThrows(IllegalStateException.class,()->QuestGateLedger.observe(c,gb.id(),provider,committed(sameCompletion),now.plusSeconds(5)));
            var sameRun=completed(accepted(gb,"shared-run"),"different-completion");
            assertThrows(IllegalStateException.class,()->QuestGateLedger.observe(c,gb.id(),provider,committed(sameRun),now.plusSeconds(5)));
            assertEquals(1,count(c,"quest_gate_completions"));assertFalse(gate(c,second,a,"supply").completed());
        }
    }
    @Test void receiptReplayIsAnExactNoOpAcrossDatabaseRestart()throws Exception {
        var i=one("restart",Mode.PLAYER);CommittedCompletion proof;long revision;String id;
        try(var c=fresh()) {
            var g=pin(c,i);var result=finish(c,g);assertTrue(result.changed());assertTrue(result.completionNewlyRecorded());
            proof=result.gate().completion();revision=result.gate().revision();id=g.id();c.commit();
        }
        try(var c=connect()) {
            c.setAutoCommit(false);MemberReviewLedger.createSchema(c);QuestGateLedger.createSchema(c);
            var replay=QuestGateLedger.observe(c,id,provider,committed(proof),now.plusSeconds(50));
            assertFalse(replay.changed());assertFalse(replay.completionNewlyRecorded());assertEquals(revision,replay.gate().revision());assertEquals(now.plusSeconds(5),replay.gate().lastCheckedAt());assertEquals(1,count(c,"quest_gate_completions"));assertTrue(QuestGateLedger.ready(c,i,actor(a,"river")));c.commit();
            assertThrows(IllegalArgumentException.class,()->QuestGateLedger.observe(c,id,provider,new QuestObservation(Status.COMMITTED,proof.acceptedRun(),proof,now.plusSeconds(2),"receipt observed before durable commit"),now.plusSeconds(50)));
        }
    }
    @Test void completedGateCannotBeRewrittenOrDowngradedByUnavailableOrUnknown()throws Exception {
        try(var c=fresh()) {
            var i=one("frozen",Mode.PLAYER);var g=pin(c,i);var done=finish(c,g).gate();
            assertEquals(done,QuestGateLedger.unavailable(c,g.id(),"offline",now.plusSeconds(20)).gate());
            assertFalse(QuestGateLedger.observe(c,g.id(),provider,new QuestObservation(Status.UNKNOWN,null,null,now.plusSeconds(20),"unknown"),now.plusSeconds(20)).changed());
            var bad=changeCompletion(done.completion(),done.completion().completedAt(),done.completion().committedAt(),done.completion().completedNation(),done.completion().acceptedEvidence(),"different immutable completion proof");
            assertThrows(IllegalStateException.class,()->QuestGateLedger.observe(c,g.id(),provider,committed(bad),now.plusSeconds(20)));
            assertThrows(IllegalStateException.class,()->QuestGateLedger.observe(c,g.id(),provider,new QuestObservation(Status.COMMITTED,null,done.completion(),now.plusSeconds(20),"missing original acceptance"),now.plusSeconds(20)));assertEquals(done,gate(c,i,a,"supply"));
        }
    }
    @Test void canonicalCompletionMayRecoverAfterANewerUnknownDiagnostic()throws Exception {
        try(var c=fresh()) {
            var i=one("unknown-recovery",Mode.PLAYER);var g=pin(c,i);var run=accepted(g);
            QuestGateLedger.observe(c,g.id(),provider,new QuestObservation(Status.UNKNOWN,run,null,now.plusSeconds(20),"temporarily unknown"),now.plusSeconds(21));
            var result=QuestGateLedger.observe(c,g.id(),provider,committed(completed(run)),now.plusSeconds(30));
            assertTrue(result.completionNewlyRecorded());assertTrue(result.gate().completed());assertEquals(now.plusSeconds(30),result.gate().lastCheckedAt());
        }
    }
    @Test void movedNodeAndCompletedInstanceGatesNeverConsumeLateEvidence()throws Exception {
        try(var c=fresh()) {
            for(boolean completed:List.of(false,true)) {
                var i=one("closed-"+completed,Mode.PLAYER);var g=pin(c,i);var closed=move(i,completed);save(c,closed);
                var late=QuestGateLedger.observe(c,g.id(),provider,committed(completed(accepted(g))),now.plusSeconds(40));
                assertFalse(late.changed());assertFalse(late.completionNewlyRecorded());assertFalse(late.gate().active());
                assertFalse(QuestGateLedger.unavailable(c,g.id(),"late unavailable",now.plusSeconds(50)).changed());
                assertEquals(g.revision(),QuestGateLedger.pinProvider(c,g.id(),provider,now.plusSeconds(50)).revision());
                assertThrows(IllegalStateException.class,()->QuestGateLedger.ready(c,i,actor(a,"river")));
            }
            assertEquals(0,count(c,"quest_gate_completions"));assertTrue(QuestGateLedger.pending(c,Set.of(a),50).isEmpty());
        }
    }
    @Test void closedUnpinnedGateDoesNotAcquireANewBackend()throws Exception {
        try(var c=fresh()) {
            var i=one("closed-unpinned",Mode.PLAYER);var g=open(c,i);save(c,move(i,false));
            var result=QuestGateLedger.pinProvider(c,g.id(),provider,now.plusSeconds(40));assertNull(result.providerId());assertFalse(result.active());assertEquals(1,result.revision());
        }
    }
    @Test void pendingFiltersOfflineAdmissionsBeforeLimitAndIncludesPinnedOfflineOwners()throws Exception {
        try(var c=fresh()) {
            var offline=one("offline",Mode.PLAYER);var goff=open(c,offline);
            var live=instance("online",Map.of(b,"river"),await("supply",Mode.PLAYER));seed(c,live);QuestGateLedger.open(c,live,now.plusSeconds(1));var glive=gate(c,live,b,"supply");
            assertEquals(List.of(glive.id()),QuestGateLedger.pending(c,Set.of(b),1).stream().map(QuestGateLedger.Gate::id).toList());
            QuestGateLedger.pinProvider(c,goff.id(),provider,now.plusSeconds(2));assertEquals(goff.id(),QuestGateLedger.pending(c,Set.of(b),1).getFirst().id());
            QuestGateLedger.unavailable(c,goff.id(),"provider unavailable",now.plusSeconds(3));assertEquals(glive.id(),QuestGateLedger.pending(c,Set.of(b),1).getFirst().id());
            assertEquals(List.of(goff.id()),QuestGateLedger.pending(c,Set.of(),10).stream().map(QuestGateLedger.Gate::id).toList());assertTrue(QuestGateLedger.pending(c,Set.of(a,b),0).isEmpty());
        }
    }
    @Test void unsupportedAndConflictGatesRemainEligibleAndRotate()throws Exception {
        try(var c=fresh()) {
            var i=instance("rotation",Map.of(a,"river",b,"river"),await("supply",Mode.PLAYER));seed(c,i);QuestGateLedger.open(c,i,now);
            var first=QuestGateLedger.pending(c,Set.of(a,b),1).getFirst();var remaining=QuestGateLedger.gates(c,i.id(),i.node()).stream().filter(g->!g.id().equals(first.id())).findFirst().orElseThrow();
            QuestGateLedger.unavailable(c,first.id(),"no capable provider",now.plusSeconds(1));assertNull(gate(c,i,first.playerId(),"supply").providerId());assertEquals(remaining.id(),QuestGateLedger.pending(c,Set.of(a,b),1).getFirst().id());
            QuestGateLedger.pinProvider(c,remaining.id(),provider,now.plusSeconds(1));QuestGateLedger.observe(c,remaining.id(),provider,new QuestObservation(Status.CONFLICT,null,null,now.plusSeconds(2),"known no-mutation retry"),now.plusSeconds(2));
            assertEquals(2,QuestGateLedger.pending(c,Set.of(a,b),10).size());assertEquals(first.id(),QuestGateLedger.pending(c,Set.of(a,b),1).getFirst().id());
        }
    }
    @Test void sourceAndGateRollBackTogetherWhenGateUpdateFails()throws Exception {
        var i=one("rollback",Mode.PLAYER);
        try(var c=fresh()) {
            var g=pin(c,i);c.commit();
            try(var s=c.createStatement()){s.execute("CREATE TRIGGER refuse_gate_completion BEFORE UPDATE OF status ON quest_gates WHEN NEW.status='COMMITTED' BEGIN SELECT RAISE(ABORT,'injected durable gate failure'); END");}c.commit();
            assertThrows(SQLException.class,()->finish(c,g));c.rollback();
            assertEquals(0,count(c,"quest_gate_completions"));assertEquals(Status.NOT_STARTED,gate(c,i,a,"supply").status());assertNull(gate(c,i,a,"supply").acceptedRun());assertEquals(1,count(c,"events"));
        }
        try(var c=connect()){c.setAutoCommit(false);assertEquals(0,count(c,"quest_gate_completions"));assertFalse(QuestGateLedger.ready(c,i,actor(a,"river")));}
    }
    @Test void sqliteEnforcesSourceForeignKeysAndBothUniqueReceiptKeys()throws Exception {
        try(var c=fresh()) {
            var i=one("sql-constraints",Mode.PLAYER);var g=pin(c,i);finish(c,g);
            var spare=pin(c,one("sql-constraints-spare",Mode.PLAYER));
            assertThrows(SQLException.class,()->{try(var s=c.prepareStatement("INSERT INTO quest_gate_completions SELECT issuer,'new-completion',run_id,?,provider_id,proof_hash,proof_json,received_at FROM quest_gate_completions")){s.setString(1,spare.id());s.executeUpdate();}});
            assertThrows(SQLException.class,()->{try(var s=c.prepareStatement("INSERT INTO quest_gate_completions SELECT issuer,completion_id,'new-run',?,provider_id,proof_hash,proof_json,received_at FROM quest_gate_completions")){s.setString(1,spare.id());s.executeUpdate();}});
            assertThrows(SQLException.class,()->{try(var s=c.createStatement()){s.executeUpdate("INSERT INTO quest_gate_completions SELECT issuer,completion_id,run_id,'missing-gate',provider_id,proof_hash,proof_json,received_at FROM quest_gate_completions");}});
            assertThrows(SQLException.class,()->{try(var s=c.createStatement()){s.executeUpdate("INSERT INTO quest_gate_completions SELECT 'different-issuer','new-completion','new-run','missing-gate',provider_id,proof_hash,proof_json,received_at FROM quest_gate_completions");}});
            assertEquals("1",scalar(c,"PRAGMA foreign_keys"));assertEquals(1,count(c,"quest_gate_completions"));
        }
    }
    @Test void relationalInstanceAndGateIdentityCorruptionFailsClosed()throws Exception {
        try(var c=fresh()) {
            var i=one("metadata",Mode.PLAYER);pin(c,i);
            try(var s=c.createStatement()){s.executeUpdate("UPDATE instances SET story='forged-story'");}
            assertThrows(IllegalStateException.class,()->QuestGateLedger.gates(c,i.id(),i.node()));save(c,i);
            try(var s=c.createStatement()){s.executeUpdate("UPDATE quest_gates SET quest_id='forged-task'");}
            assertThrows(IllegalStateException.class,()->QuestGateLedger.ready(c,i,actor(a,"river")));
        }
    }
    @Test void completedGateRequiresItsExactDurableSourceRow()throws Exception {
        try(var c=fresh()) {
            var i=one("missing-source",Mode.PLAYER);var g=pin(c,i);finish(c,g);
            try(var s=c.createStatement()){s.executeUpdate("UPDATE quest_gate_completions SET proof_hash='forged-proof-hash'");}
            assertThrows(IllegalStateException.class,()->QuestGateLedger.gates(c,i.id(),i.node()));
        }
    }
    @Test void staleNonCommittedStatusCannotReplaceNewerStateOrAcceptance()throws Exception {
        try(var c=fresh()) {
            var i=one("stale-observation",Mode.PLAYER);var g=pin(c,i);var run=accepted(g);QuestGateLedger.observe(c,g.id(),provider,active(run),now.plusSeconds(5));
            var result=QuestGateLedger.observe(c,g.id(),provider,new QuestObservation(Status.NOT_STARTED,null,null,now.plusSeconds(3),"stale lookup"),now.plusSeconds(6));
            assertFalse(result.changed());assertEquals(Status.ACTIVE,result.gate().status());assertEquals(run,result.gate().acceptedRun());
        }
    }
    @Test void orphanSourceCannotSilentlyRepairAnIncompleteGateAndSkipHistory()throws Exception {
        try(var c=fresh()) {
            var i=one("orphan-source",Mode.PLAYER);var g=pin(c,i);var completedGate=finish(c,g).gate();
            try(var s=c.createStatement()){s.executeUpdate("UPDATE quest_gates SET status='NOT_STARTED',completion_json=NULL");}
            assertFalse(QuestGateLedger.ready(c,i,actor(a,"river")));
            assertThrows(IllegalStateException.class,()->QuestGateLedger.observe(c,g.id(),provider,committed(completedGate.completion()),now.plusSeconds(10)));
            assertEquals(1,count(c,"quest_gate_completions"));assertFalse(gate(c,i,a,"supply").completed());assertEquals(Status.NOT_STARTED,gate(c,i,a,"supply").status());
        }
    }
}
