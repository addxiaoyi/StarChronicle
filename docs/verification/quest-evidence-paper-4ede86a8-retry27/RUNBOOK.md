# Native quest evidence Paper procedure (not a PASS report)

Default preparation starts no JVM, server or client. Compile is a separate explicitly chosen
action in the coordinator's JVM window. Each run is ROOT/run/paper-quest-evidence-<sha8>-<attempt>.
Both Chronicle and the patched StarCore JAR are SHA-bound. No old world, player data, plugin
data, SQLite database, native quest data, ops list or whitelist is copied.

Prepare:
    python tools/prepare-quest-evidence-paper.py prepare --expected-sha SHA --starcore PATCHED_JAR --expected-starcore-sha CORE_SHA
Compile (coordinator only):
    python tools/prepare-quest-evidence-paper.py compile --expected-sha SHA --expected-starcore-sha CORE_SHA --java-home JAVA21
Run serial boots using the coordinator's quest-evidence-session.py. Wait for Paper Done and
QUEST_EVIDENCE_READY before starting clients. After each client cohort starts, wait for a real
SPAWN record for every selected original player before issuing any qeverify command. Wait for
each SOURCE/SUBMITTED response before giving out its crafting kit; wait for each CRAFTED result
before capturing its native progress. Every dependent step requires its real response.
QUEST_EVIDENCE_FAILED or client ACTION_FAILED is a failure; do not silently relabel it PASS.

The three clients are PhaseTwoA/B/C, pinned offline UUIDs in preparation.json, always non-OP.
seed invokes actual NationService.createNation for A/C and addMember for ordinary B in A's
country. Later seed calls only grant basic permissions, read actual memberships and rebuild
the explicitly fixture-only crafting station. No nationality is supplied to Chronicle.

## Boot one — real acceptance and craft readiness without completion

    clients start A,B,C  # wait for SPAWN A, B, C
    server qeverify seed
    server qeverify begin player  # wait for SUBMITTED source player
    server qeverify begin nation  # wait for SUBMITTED source nation
    server qeverify begin atlas   # wait for SUBMITTED source atlas
    server qeverify craft-kit A paper
    server qeverify craft-kit B book
    server qeverify craft-kit C map
    client craft A paper  # wait for CRAFTED A paper
    client craft B book   # wait for CRAFTED B book
    client craft C map    # wait for CRAFTED C map
    server qeverify check-native A player 3 capture
    server qeverify check-native B nation 1 capture
    server qeverify check-native C atlas 1 capture
    server qeverify state A player
    server qeverify state A nation
    server qeverify state B nation
    server qeverify state C nation
    server qeverify state C atlas
    server qeverify flush
    client quit all
    server stop

Wait for actual driver admission before crafting: check-native WHO STORY 0 read can inspect
the active native identity first. PAPER is crafted from three sugar cane (one actual recipe
produces three paper), BOOK from three paper plus leather, MAP from eight paper plus compass.
The fixture only supplies those real ingredients and a physical crafting table. Clients use
the crafting inventory; the real CraftItemEvent listener updates native progress. No fixture
calls an objective setter. Progress 3/1/1 must be actual ACTIVE runs without completion IDs;
all corresponding Chronicle choices remain blocked. Other independent country runs for A/C
may remain at zero. Flush confirms real native persistence before a normal server stop.

## Boot two — late template restoration and genuine native GUI completion

    clients start A,B,C  # wait for SPAWN A, B, C
    server qeverify seed
    server qeverify check-native A player 3 restore
    server qeverify check-native B nation 1 restore
    server qeverify check-native C atlas 1 restore
    server qeverify unregister-provider
    client native-open A heir
    client native-complete A
    client native-open B siege
    client native-complete B
    client native-open C atlas
    client native-complete C
    server qeverify check-receipt A player
    server qeverify check-receipt B nation
    server qeverify check-receipt C atlas
    server qeverify check-paused
    client quit B
    server qeverify check-receipt B nation
    server qeverify flush
    client quit all
    server stop

The native templates register after StarCore initializes stored quests. Restore compares
the real run ID, start millisecond, definition fingerprint, progress and immutable accepted
evidence against boot one's observed hash. No native service is reinitialized by the fixture.
unregister-provider legally unregisters only Chronicle's real builtin QuestEvidenceProvider
from Bukkit ServicesManager and records its exact domain-bound ID. No substitute is installed.
The native QuestEvidenceService remains available and actual quest completion still works.

Native GUI path is /questgui progress -> the exact task name -> detail. Verify actual ID,
name and title; complete only via slot 32 NETHER_STAR / 完成任务 with both expected lore lines.
The native detail inventory must be the audited fixed 54 slots (old 36 + slot49 was invalid).
Fixture native-gui WHO can request opening this native command, but never clicks or completes.
check-receipt reads canonical native lookup after the actual native state and immutable journal
acknowledge, and verifies the independent native PlayerQuest.RunCompletion identity. It never
creates, supplies or submits a completion. B must really disconnect before the normal save.

## Boot three — offline canonical receipt, national GUI, exact rewards and replay

    clients start A,C  # wait for SPAWN A, C before seed or checks
    server qeverify seed
    server qeverify check-receipt A player
    server qeverify check-receipt B nation
    server qeverify check-receipt C atlas
    server qeverify check-offline-country
    client open-story A Quest evidence — personal records
    client choice A yes
    client open-story A Quest evidence — country testimonies
    client choice A yes
    client open-story C Quest evidence — caravan atlas
    client choice C yes
    server qeverify check-final-before-replay  # wait for PRE_REPLAY_CHECK_PASS
    server qeverify replay player
    server qeverify replay nation
    server qeverify replay atlas
    server qeverify check-final  # wait for exactly one FINAL_CHECK_PASS
    client quit all
    server stop

Only A/C connect. B's already pinned offline gate is discovered by canonical lookup, making
A's original country ready while C's country is blocked. A is the actual native holder and
uses the true national GUI; B remains an ordinary member and is not given a country seat.
The country quorum is one for this evidence test; existing national tests cover ordinary
member rejection. Country-story honor intentionally rewards all three recorded witnesses
through existing engine semantics: seven each, plus A's personal three and C's personal five.
Thus final online honor is A=10/C=12 once. Exactly three quest.completed histories and one
ending event of each configured type must remain. Canonical receipt hashes must be unchanged
after restart. replay submits only each previously observed original fixture source, never
a native completion DTO; the driver alone consumes canonical native evidence.

The coordinator should correlate actual clients/GUI, fixture JSONL, native service snapshots,
normal process exits, native persistent files and read-only stopped Chronicle DB inspection.
Preparation and source code are not observed validation evidence or a full upstream/Folia claim.
