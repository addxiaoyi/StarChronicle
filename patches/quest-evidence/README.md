# Proposed StarCore native quest evidence patch

This is an actual proposed upstream implementation, not an installed StarCore capability in the audited release.

Baseline: `addxiaoyi/starcore` commit `f769e62799e769a18fe51305b545869a17f80cd1`.

Patch: `starcore-quest-evidence-v1.patch`.

Isolated source worktree: `run/upstream-starcore-quest-evidence` (detached at the baseline). The original audited checkout and both prior Chronicle worktrees were not edited. This patch has not been pushed to StarCore upstream.

## Protocol 1

Published native service type:

```java
dev.starcore.starcore.quest.evidence.QuestEvidenceService
```

It is registered in StarCore's native `ServiceRegistry` by `QuestModule`. Chronicle may discover this class reflectively and convert immutable records to its independent SPI; no shared artifact or runtime class identity is required.

```java
int protocolVersion(); // 1
boolean available();
String storageAuthorityId(); // confirmed persistent canonical UUID; not the receipt issuer
CompletionStage<Admission> beginTracked(
    UUID playerId,
    String questId,
    String bindingId,
    String expectedNation,
    boolean requireSameNation);
Snapshot lookup(String bindingId); // null means no durable/known binding
List<Snapshot> completionHints();  // replayable immutable canonical copies

record Admission(String status, Snapshot snapshot, String message) {}

record Snapshot(
    String bindingId,
    String runId,
    UUID playerId,
    String questId,
    String definitionFingerprint,
    long startedAt,
    String acceptedNationState,
    String acceptedNation,
    long acceptedNationCheckedAt,
    String completionId,
    long completedAt,
    String completedNationState,
    String completedNation,
    long completedNationCheckedAt,
    String status,
    String rewardStatus,
    String issuer,
    String acceptedEvidence,
    String completionEvidence,
    long committedAt,
    String evidence,
    String message) {}
```

All timestamps are Unix milliseconds on the native server clock. `definitionFingerprint` is lowercase SHA-256 from the versioned `starcore.quest.definition.v1` canonical definition codec. Objective progress is excluded; the objective types/targets/requirements, acceptance rules, reward definition, commands and other definition fields are included.

`storageAuthorityId()` identifies the native data domain. The provider cannot become available until `quest/evidence-authority.properties` has been atomically created and force-confirmed, then strictly reread, or a valid existing primary identity has been strictly loaded. Chronicle's provider ID must include this UUID so replacing a data directory cannot silently reuse a previously pinned backend name. This domain ID is separate from the fixed canonical receipt issuer.

The identity is never silently rotated. A corrupt primary, or a missing primary with any `.tmp`, `.bak`, `.bak.tmp` or `.delete` authority artifact, leaves those artifacts untouched and makes the provider unavailable. Missing identity with an existing tracked index/recovery artifact, journal directory, native nonempty binding, or native run-completion marker also fails closed. Legacy native quests with no tracked binding/receipt may create the first identity during upgrade. A new clean storage domain receives a new UUID. Restoring an original identity is an explicit operator action; no auto-recovery, migration or rotation endpoint is provided. The native persistence model continues to assume one server process per data folder; same-JVM initialization is synchronized.

`issuer` is always `starcore.quest.evidence.v1`. `acceptedEvidence`, `acceptedNationState`, `acceptedNation` and `acceptedNationCheckedAt` freeze at the first real accepted run and never change when completion or reward status changes. `completionEvidence` is a separate immutable completion receipt. The generic `evidence` and `message` are diagnostic fields and must not be used to reconstruct immutable acceptance identity.

`committedAt` is the native commit epoch established after the actual quest-state persistence future acknowledges, then written into the immutable completion record. `COMPLETED` and its completion ID become visible only after that completion record's file force operation also acknowledges. This timestamp is not a prediction of the future wall-clock millisecond at which the last I/O callback will run.

## Admission states and safe retries

Admission results are `ADMITTED`, `IDEMPOTENT`, `REJECTED`, `UNAVAILABLE`, `CONFLICT`, or `UNKNOWN`. Snapshot states are `ADMITTING`, `ACTIVE`, `COMPLETING`, `COMPLETED`, `REJECTED`, or `UNKNOWN`.

* A missing binding is `null`, not a fabricated uncertain run.
* Same binding and same immutable request payload is idempotent.
* Same binding with another player, quest, expected nation or nation mode is `CONFLICT` and cannot adopt another run.
* A pre-existing unrelated native active quest is never adopted as the tracked run.
* Ordinary prevalidation rejection does not reserve a binding. A persisted `REJECTED` native acceptance result is used only for a known no-mutation early return from the original native accept checks. It keeps the reserved run ID, has `startedAt == 0` and no acceptance evidence, and may safely retry the same payload after conditions improve.
* Any acceptance exception, possible partial change, interrupted admission or lost persistence confirmation becomes `UNKNOWN`; it is not automatically attempted again.
* Once a real acceptance is confirmed, its run, start and acceptance evidence are frozen. A ready objective or admission acknowledgement is never a completion receipt.

For country mode, pass the stable native nation UUID string and `requireSameNation=true`. Both acceptance and final completion reread native `NationService`; the same known nation is required. Missing/failing native nation service is `UNAVAILABLE`, never `KNOWN_NONE`. `KNOWN_NONE` is used only when a real native service successfully returns an empty membership result. Personal mode may retain such a nation observation without making it a country completion proof.

## Real native completion path

Chronicle must never call `QuestService.completeQuest` to manufacture evidence. Players reach real objectives through the existing native listeners and submit through the existing GUI/command completion path; native automatic completion, when configured, uses that same native entry point.

The patch retains `QuestService` and `PlayerQuest`'s original acceptance checks and actual objective checks. Tracked execution differs only in its durable commit and side-effect order:

1. Confirm a durable binding reservation and invoke the real acceptance mechanism with an independent native run ID.
2. Serialize the native accepted run and wait for `PersistenceService.savePropertiesAsync`'s actual atomic file-write acknowledgement, then persist accepted evidence.
3. Existing real game-event objective listeners call the original progress service, with tracked progress snapshots saved through native persistence.
4. Native completion validates the active run, exact start, original/current definition fingerprint, actual objective completion, expiry and current required nation.
5. Persist a `COMPLETING` / `PREPARED` reward execution guard before removing/completing the native active quest.
6. Invoke the existing native completion mechanism, recording a native `RunCompletion` containing run ID, binding, original definition/start, business completion time and completion ID.
7. Wait for the actual quest-state file persistence acknowledgement.
8. Append a new immutable per-completion journal record and force its file contents. Only after acknowledgement does canonical lookup expose `COMPLETED`, completion ID, completion evidence and replayable hints.
9. Attempt the native reward methods and configured completion commands once under the durable guard. Save the reward invocation checkpoint separately.

`PREPARED` is not completion. A mutable index status alone is not completion. On restart, journal records are checked against the independently persisted native `RunCompletion`; a mismatched/missing native receipt fails closed.

## Rewards and recovery boundaries

Reward states are `NOT_STARTED`, `PREPARED`, `INVOKED`, and `UNKNOWN`.

`INVOKED` means that the native reward methods returned and the invocation checkpoint was saved. It does not promise that asynchronous economy work succeeded, that inventory or downstream plugins committed, or that arbitrary console commands are transactional or strictly once.

An interrupted guard, partially failed native reward, absent owner/player after canonical completion, or lost checkpoint confirmation becomes `UNKNOWN` for manual reconciliation. No restart or replay automatically reissues native rewards or completion commands. The immutable completion receipt remains valid when its reward status is unknown, because completion proof and reward delivery are separate facts.

Start commands are attempted only after accepted state and acceptance evidence both acknowledge. They are not replayed during restoration. Arbitrary start commands also have no general exactly-once guarantee.

`QuestService.saveConfirmed()` and `QuestStateStorage.saveConfirmed(...)` expose the real persistence future. A storage implementation without an explicit durable confirmation implementation fails this operation; a void save is not silently converted to success.

## Restoration and definition changes

The old handler cloned a restored quest again and replaced its start clock, losing objective progress and timing. It also loaded before task templates were registered. The patch restores saved instances directly, keeps the exact start/run/binding/fingerprint, and defers unresolved templates without dropping their raw durable records during intervening saves.

A changed definition does not replace a historic run. Mismatched definitions or corrupt run progress remain quarantined and cannot create completion proof. A tracked active observation whose restored native run differs becomes `UNKNOWN`. Native run completion receipts load independently of the current definition registry, so a completed quest does not require today's template to preserve its historical completion evidence.

Files inside StarCore's own data directory:

* `quest/quests.properties`: native active run identity, exact progress/start and native per-run completion receipts.
* `quest/evidence-authority.properties`: immutable, durably established native storage-domain UUID.
* `quest/tracked-evidence.properties`: binding/admission and reward checkpoints.
* `quest/completion-journal/<completion UUID>.json`: immutable canonical completion records.

The native quest storage now prevents unrelated daily/commission saves from overwriting the player quest ledger with an empty `pq` slice. Other old daily/commission persistence behavior is outside this proposed patch's assurance.

## Thread and lifecycle scope

The audited native quest module is not fully ported to Folia. This proposed provider explicitly supports Paper's primary owner thread only; it returns unavailable on a detected Folia runtime. It does not advertise full native Folia compatibility. Chronicle's independent Folia rule execution should wait for an appropriate provider rather than call these native primary-thread APIs.

`beginTracked` schedules native player/quest/nation access onto the Paper owner. File writes run through native persistence and the dedicated journal executor. `lookup` and `completionHints` return immutable snapshots and are thread safe. Callers must check `available()`; after module disable, cached historical snapshots do not authorize new work.

`QuestModule.disable` closes and invalidates the evidence provider before other shutdown work. Delayed owner callbacks recheck availability. No reward is scheduled through a disabled provider; ambiguous pending work is not blindly replayed. The evidence service is also removed from the native service registry.

## Verification handoff

New native tests:

* `QuestTrackedRunPersistenceTest` (10 tests): exact/deferred run restoration, changed-template quarantine, ready-versus-complete distinction, independent persistent completion receipt, independent repeated run IDs, definition fingerprint identity, delayed durable acknowledgements/failures, protection against unrelated quest subsystem overwrites, and legacy JSON-null collection recovery without fabricated receipts.
* `QuestCompletionJournalTest` (4 tests): immutable completion round trip, idempotent/conflicting append, refusal to append acceptance/guard as completion, and corrupt-journal fail-closed behavior.
* `QuestEvidenceServiceTest` (9 tests): deterministic owner-thread admission with deferred durable acknowledgement; safe retry after a no-mutation disconnect, nation change, changed definition or real native quest limit; uncertain exception/lost checkpoint refusal; unavailable nation distinction; live rejection of another player's copied run identity; and the confirmed storage identity/availability barrier without changing the receipt issuer.
* `QuestEvidenceAuthorityTest` (10 tests): canonical/restart and concurrent initialization, corruption/recovery-artifact preservation, existing tracked index/journal/native-binding/native-receipt refusal, safe legacy upgrade, and malformed native state refusal.
* `QuestEvidenceNativeMenuTest` (3 tests, added after the 33-test success): executes actual `openQuestDetail` rendering against an inventory mock that enforces the requested frame's real bounds, for available, active-incomplete, and active-ready tasks. It also checks the complete control remains at slot 32 and opening the renderer does not complete a task.

The implementing agent did not start a JVM, Maven, Java compiler or server, respecting the single JVM validation window owned by the coordinating agent. The coordinating agent reported an initial native compile exit code 0 and a first focused run of 21 tests with zero assertion failures and one error. That run revealed a real production Gson serialization problem: the JDK anonymous immutable-map values view was emitted as JSON null. The patch now copies completion receipts into a concrete `ArrayList` before serialization and has an explicit legacy-null regression test. The original failed run remains in `run/native-quest-focused-tests.log`.

The coordinating agent then reran the current 22 native tests successfully: **22 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS, process exit 0**. Raw log: `run/native-quest-focused-tests-r2.log`; Maven reported elapsed time `02:57 min` and completion `2026-11-20T08:27:26+08:00`. The implementing agent read this original log and the three matching Surefire XML files; it did not rerun or overwrite them. Surefire evidence is under `run/upstream-starcore-quest-evidence/target/surefire-reports/` for `QuestTrackedRunPersistenceTest`, `QuestCompletionJournalTest`, and `QuestEvidenceServiceTest`.

`git diff --check` and application against the exact audited baseline passed before handoff. Native packaging, broader native regressions, real mob objective/GUI completion, and two-restart evidence/reward verification must be reported from the coordinating validation run, not inferred from these focused source tests.

After the successful 22-test run, review added the persistent storage-domain identity barrier and its 11 additional tests. The coordinating agent then ran and packaged the current source successfully: **33 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS, process exit 0**. Raw log: `run/native-quest-authority-package.log`; Maven elapsed time `02:42 min`, completion `2026-11-20T08:38:58+08:00`. The implementing agent read the original log and all four matching Surefire XML files without starting another JVM.

The intermediate, pre-GUI-fix artifact was 19,545,321 bytes, SHA-256:

```text
9b6cf3eb1480de9cf4e1df2e8dcfff2762a277ff4939955b6f5ae6094ee696cd
```

The four new native evidence classes are present in that JAR. That **33-test/package success covers the source diff with SHA-256 `6c42f8c5ce26fb2300ff2142fcbabdfae7ea475265213c5e74bc7b517de38ce7` (116,131 bytes)**. Its original source fingerprint and artifact hash are retained in the machine report.

Subsequent independent GUI review found an existing production defect: native `openQuestDetail` allocated 36 slots but unconditionally wrote the back control to slot 49 and border controls to 45–53. The patch changes the detail frame size to 54, preserves the native complete control at 32, and adds the three renderer behavior tests above.

The coordinating agent tested and packaged the exact current source through the broader native task regression selection: **180 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS, process exit 0**. That run includes all 36 new native evidence tests, including all three GUI renderer tests. Raw log: `run/native-quest-full-regression-package.log`; Maven elapsed time `03:38 min`, completion `2026-11-20T08:51:57+08:00`. The implementing agent read that log and the five relevant Surefire XML reports without starting another JVM.

Current packaged native artifact: `run/upstream-starcore-quest-evidence/target/starcore-1.0.0.jar` (19,545,320 bytes), SHA-256:

```text
1358d8e304c72f7ea575e9b30a9d6b311edd7651e132a4c66ed4bb450ef9c3a7
```

The exported patch exactly matches that tested source: 121,896 bytes, SHA-256 `37216bb55ff30f601b9e47d70a00e4ef02b0f256c99d92a6c2c2331166ae8323`. The coordinating agent prepared the paired native and Chronicle artifacts in `run/paper-quest-evidence-9fd7769f` for live Paper acceptance; preparation alone does not establish runtime results. This remains a proposed patched native fixture, not a claim that the audited upstream release already provides the protocol.

Suggested targeted test selection after the coordinating agent has resolved/used the existing native build toolchain:

```text
-Dtest=QuestTrackedRunPersistenceTest,QuestCompletionJournalTest,QuestEvidenceServiceTest,QuestEvidenceAuthorityTest,QuestEvidenceNativeMenuTest
```

Apply only against the exact baseline, review the native source, then run native formatting/compile/tests before packaging. No upstream deployment or push is included in this proposal.
