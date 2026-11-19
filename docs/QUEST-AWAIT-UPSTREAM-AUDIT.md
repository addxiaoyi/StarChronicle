# StarCore quest completion gate: fixed-source audit

Audited source root: `C:/Users/Administrator/Documents/StarChronicle-audit/starcore`.
Verified HEAD: `f769e62799e769a18fe51305b545869a17f80cd1`.
Method: read-only source inspection and repository searches; no JVM, Maven, server, upstream modification or runtime acceptance was performed.

## Decision

This StarCore revision exposes useful quest definitions, active progress and aggregate completion state. It does **not** expose a durable, uniquely identified quest run or a committed completion receipt/event suitable for an exact-run automatic `awaitQuest` gate.

`acceptQuest == SUCCESS` or `ALREADY_ACTIVE` means acceptance only. `isAllObjectivesCompleted()` and `READY_TO_COMPLETE` mean objectives are ready; the separate completion operation still has to occur. A previously true `hasCompletedQuest(questId)` does not prove any new run completed for the current story.

Do not synthesize completion, grant a reward, or advance an exact-run gate from acceptance. A gate requiring exact-run committed evidence should stay `WAITING`/`UNSUPPORTED` on this revision until a capable, explicitly trusted completion provider is installed. Missing evidence is not success.

## Reusable upstream surfaces

All paths and line numbers below refer to the fixed source root above.

| Capability | Actual source | Reuse and limit |
| --- | --- | --- |
| Obtain native service | `src/main/java/dev/starcore/starcore/quest/QuestModule.java:380-385` | `QuestService` is registered in StarCore's `ServiceRegistry`. Prefer this existing service over creating a second quest implementation. The retained service is not automatically evidence that the module remains enabled: lines 388-390 explain that it is kept after disable. |
| Definition existence | `quest/QuestService.java:116-123` | `getQuest(questId)` and `getPlayerQuest(UUID)` exist. The latter creates an empty mutable player record if absent; this is not a committed-history query. |
| Accept existing task | `quest/QuestService.java:127-171` | Validates registered definition, limits, active state, cooldown and requirements; calls native `PlayerQuest.acceptQuest`, executes start commands, then requests asynchronous save. Reuse for execution, not completion proof. |
| Objectives and readiness | `quest/Quest.java:81-85`; `quest/PlayerQuest.java:195-216` | Objectives can be complete while the quest is still active with status `READY_TO_COMPLETE`. Do not equate this with claimed/committed completion. |
| Actual completion operation | `quest/QuestService.java:185-217` | Requires an active quest and completed objectives, then calls `PlayerQuest.completeQuest`. Returns `QuestCompleteResult`; it is a synchronous return value, not a durable receipt. |
| Aggregate completion state | `quest/PlayerQuest.java:128-135,219-226,282-300` | Boolean completed-ever, most recent completion timestamp, completion count, active tasks and start-time maps are available. Useful for UI, diagnostics and explicit aggregate observations; not enough to bind a unique run to a story. |
| Existing persistence | `quest/QuestStateStorage.java:9-26`; `quest/PersistenceQuestStateStorage.java:12-15,48-90` | Player quests use `quests.properties`, with separate daily and commission files. `QuestStateStorage.saveAsync` returns `void`. |
| Actual file writer completion | `core/persistence/PersistenceService.java:112-145,151-171` | Generic persistence has a `CompletableFuture<Void>` and serialized per-file asynchronous saves. This useful acknowledgement is discarded by the quest storage/service layers. The writer uses atomic file replacement; that is not a cross-service completion/reward transaction. |

Paths abbreviated as `quest/...` and `core/...` in this table are beneath `src/main/java/dev/starcore/starcore/`.

## Exact attempt identity and start-time audit

1. `PlayerQuest` is keyed by `playerId` plus reusable `questId`. Active quests, latest completion time, cooldown, start time and completion count are separate maps (`quest/PlayerQuest.java:33-39`). There is no quest-run UUID, durable attempt sequence, acceptance correlation ID or completion ID in these fields.
2. Accepting clones the quest template using the **same definition ID**, stores it by `quest.getId()`, and sets `System.currentTimeMillis()` in `questStartTimes` (`quest/PlayerQuest.java:52-66`; `quest/Quest.java:107-139`). Cloning is progress isolation, not unique run identity.
3. Completion removes both the active object and start time, writes only the latest completion time for the definition ID, and increments the aggregate count (`quest/PlayerQuest.java:79-100`). Earlier per-run completions are overwritten in the time map. Abandon/fail also remove the start time (`69-77,103-109`).
4. Start times are serialized (`quest/QuestPersistenceHandler.java:49-58,126-134`), but **not restored**. Load calls `pq.acceptQuest(quest)` (`226-235`), which generates a fresh wall-clock start time. The `startTimes` load section merely reads a string and contains no restoration (`242-246`). A saved start timestamp therefore cannot be treated as a stable resumed run key.
5. Active quest serialization contains definition ID/name/type/difficulty and objective progress, not run identity, definition version/hash or country (`quest/QuestPersistenceHandler.java:642-660`). Restore looks up the current registered template (`666-690`), so a changed upstream quest definition is not an immutable pinned run definition.
6. The separate `QuestProgress` class has start/complete fields (`quest/QuestProgress.java:15-31,52-55`), but searches found no construction/use by the native `QuestService`/`PlayerQuest` completion and persistence path. It also has no run ID. Do not mistake that model's getters for evidence emitted by the actual quest service.

## Persistence, events and completion logs

- The concrete completion order is: mutate in-memory completed state; give reward; execute completion commands; call `saveAsync`; return success (`quest/QuestService.java:202-214`). The return value can precede durable save, and reward/command failure can occur after the in-memory completion mutation but before the save request.
- `QuestCompleteResult` stores only `success`, `message` and `QuestReward` (`quest/QuestService.java:396-422`). It has no player/run/completion ID, timestamp, country, persistence acknowledgement or authoritative source evidence.
- The quest serializer writes latest completion entries and aggregate counts (`quest/QuestPersistenceHandler.java:60-69,93-105,136-177`); the loader restores those aggregates (`248-264,284-297`). These are useful durable snapshots after successful saving, **not an append-only completion log**.
- `QuestService.saveAsync()` returns `void` (`73-78`); handler `savePlayerQuestsAsync()` ends with `stateStorage.saveAsync(properties)` (`111-177`); the concrete storage issues generic save futures without returning/combining them (`quest/PersistenceQuestStateStorage.java:71-90`). There is no quest-level completion-commit acknowledgement to subscribe to.
- No `QuestCompletedEvent`, `QuestCompleteEvent`, quest completion event-bus publication, completion outbox or sequence-query API was found in the native quest implementation. `QuestService`'s complete method has no event publication, and `QuestProgressService.checkAutoComplete` just calls it (`quest/QuestProgressService.java:279-287`).
- `TitleTrigger.QUEST_COMPLETE`, `NotificationType.QUEST_COMPLETE` and `AchievementTrigger.CUSTOM_QUEST_COMPLETED` are enum values, not evidence that a quest completion event is actually emitted. The title checker polls aggregate statistics (`title/checker/QuestCompleteChecker.java:20-31`). Repository searches found no dispatch of those quest trigger constants from the completion flow.
- Native `QuestProgressService` messages saying a task is complete can occur when objectives become complete (for example `quest/QuestProgressService.java:243-248`), before the actual completion call. Chat/GUI messages are not completion receipts.
- Typewriter integration is a different backend: `integration/typewriter/TypewriterIntegration.java:81-88` dispatches `tw quest complete ...`. Its command-dispatch boolean is not a native StarCore quest completion receipt and must not be used as one.

## Reward replay and crash boundary

The native code prevents a simple sequential duplicate completion while the same in-memory quest is no longer active (`quest/QuestService.java:193-203`; `quest/PlayerQuest.java:80-100`). That is not a durable exactly-once reward guarantee:

- Money is deposited without an outcome ID (`quest/QuestService.java:235-247`); items are added/dropped directly (`251-265`); reputation/title calls and console commands follow (`268-306`). There is no quest-run reward journal or caller idempotency token in this path.
- A crash after reward delivery but before asynchronous quest persistence can restore an old active quest and allow another completion/reward. Conversely, a reward exception after in-memory removal can leave partial external delivery and no queued completion save. These are crash-window deductions from the actual ordering, not claims of an observed runtime failure.
- Per-player state uses ordinary `HashMap`s and the accept/complete methods are not synchronized (`quest/PlayerQuest.java:33-49,53,80`). `QuestService`'s outer map is concurrent, but the complete transition has no per-player CAS/lock (`quest/QuestService.java:31-55,185-217`). Do not add asynchronous polling/mutation of these mutable objects and assume thread safety.
- Generic atomic Properties replacement cannot atomically include Bukkit inventory changes, economy transactions, commands or Chronicle's independent SQLite commit.

A future gate must not call `completeQuest` to manufacture proof, and Chronicle should not reissue the native quest reward after receiving completion evidence. Its own post-gate outcomes still need stable IDs and the existing local/outbox delivery policy.

## Player-to-country attribution

The relevant native quest state stores player UUID and quest definition ID. There is no acceptance-country or completion-country field in `Quest`, `PlayerQuest`, active serialization, completion result or aggregate completion snapshots (fields cited above; targeted searches for `nation`, `nationId`, `runId`, `instanceId` and `completionId` in these classes found none).

Looking up the player's country later proves only the **current** country. It cannot reconstruct the country at task acceptance/completion. A Chronicle country snapshot taken when its gate is opened is Chronicle's gate context, not upstream proof of the country in which the quest was completed. Preserve those meanings separately. A player transfer must not retroactively rewrite an earlier task's historical country or allow one old aggregate completion to qualify a new country.

## Smallest safe implementation route

### Without changing this upstream revision

1. Reuse the native registry/acceptance mechanism and keep its delivery result labelled `ACCEPTED` only. StarChronicle's existing bridge likewise marks `SUCCESS`/`ALREADY_ACTIVE` as delivery `APPLIED`, not quest completion (`StarChronicle` isolated tree, `src/main/java/io/github/addxiaoyi/starchronicle/integration/StarCoreBridge.java:151-155`).
2. Introduce a capability boundary such as `QuestCompletionEvidenceProvider`. Default to unsupported; expose a waiting reason in GUI/API. Do not silently downgrade an exact-run requirement to completed-ever, ready-to-complete, chat text, command success or a reused acceptance.
3. Persist the gate's stable ID, pinned story/version/node, player UUID, target quest definition ID, expected country policy, creation time and evidence state. `UNKNOWN` and unavailable providers remain waiting after restart. Do not invent a run ID from the unstable restored start time.
4. Aggregate completion count/time may be shown as **observed aggregate state** or attached for administrator review. It must not be described as an exact task run, committed reward delivery or historical country proof. A legacy observation mode would require explicitly weaker semantics and a separate acceptance decision; it is not the default exact-run gate.
5. A manually reconciled trusted receipt must be explicitly marked as administrator attestation with its actual source, stable receipt ID, reviewer and evidence. It must not impersonate an automatic StarCore completion event.

### Minimum upstream capability for automatic exact-run completion

1. Persist an immutable acceptance/run ID and start time once, keyed by player + quest + run, and restore both exactly. Pin enough upstream definition identity to detect incompatible task changes. Use a per-player serialization/transaction boundary for accept/complete/revoke.
2. Persist an append-only completion record with stable completion ID, run ID, player UUID, quest ID, original start time and actual completion time. If nation policy needs country-at-acceptance/completion, capture native authoritative IDs at those moments and keep their provenance; do not infer them later.
3. Provide a committed receipt query API plus replayable sequence/outbox. Publish an immutable `QuestCompletionCommitted` hint only after that record is durable. Consumers reread the canonical record and verify exact identity. A live event alone is insufficient for restart recovery.
4. Expose the actual persistence future/commit acknowledgement through the quest service. Keep reward-delivery status separate: completion committed does not imply all rewards delivered. For exactly-once native rewards, add per-effect stable outcome IDs and reconciliation for external uncertainty; generic commands cannot safely be blindly retried.
5. In Chronicle, consume only a matching canonical committed record in the same SQLite transaction as gate completion and downstream outcome insertion. Deduplicate by stable source completion ID and the gate's bound attempt/consumer policy. A replay may refresh evidence but cannot pay or advance twice. Do not reconstruct the original event using current time, current players or current nationality.
6. Keep player/Bukkit/native reads in an audited appropriate scheduler/authority scope; send only immutable evidence into the Chronicle worker transaction. The audited quest module still uses Bukkit scheduler tasks (`quest/QuestModule.java:295-347`), so this audit does not establish full upstream Folia safety.

## Required tests before claiming the gate complete

- Acceptance/`ALREADY_ACTIVE`/objective-ready state never completes the gate.
- A completion before gate creation or belonging to another run/player/quest/version/country policy is rejected.
- Duplicate/out-of-order receipt, event replay and restart do not duplicate transitions, history or rewards.
- Crash after upstream completion commit but before event delivery is recovered by canonical receipt replay/query.
- Upstream save failure, provider absence, malformed proof and reward uncertainty remain waiting/unknown, not success.
- Start/run identity survives restart unchanged; changed templates do not silently become the old pinned task.
- Repeatable tasks produce distinct durable run/completion IDs; abandonment/reacceptance cannot reuse an old receipt.
- Country transfer cannot reattribute old completion evidence, and overlapping gates follow an explicit receipt-consumption policy.
- Failure during Chronicle outcome insertion rolls back the receipt consumption and gate transition together.

## Reproducible negative searches

Within the fixed checkout, inspect the complete native quest directory and run searches including:

```text
rg -n 'Quest.*Event|Event.*Quest|callEvent|eventBus|publish\(' src/main/java/dev/starcore/starcore/quest
rg -n 'runId|run_id|instanceId|completionId|UUID.randomUUID|nationId|\bnation\b' src/main/java/dev/starcore/starcore/quest/{QuestService,PlayerQuest,Quest,QuestPersistenceHandler}.java
rg -n 'QUEST_COMPLETE|CUSTOM_QUEST_COMPLETED' src/main/java
rg -n 'new QuestProgress|QuestProgress[ >]' src/main/java/dev/starcore/starcore/quest
```

The brace expression in the second example is a shell convenience; in PowerShell pass the four explicit file paths. Negative search results are bounded to this exact commit and do not promise what another StarCore revision or an external quest plugin provides.
