# StarCore integration audit

Audited on 2026-11-19 from a shallow clone of `https://github.com/addxiaoyi/starcore`, commit `f769e62799e769a18fe51305b545869a17f80cd1`.

## Build and plugin identity

- Maven: `dev.starcore:starcore:1.0.0`, JAR.
- Java release: 21; upstream enforcer permits JDK 21 through 25 and Maven 3.9.6 through 3.9.x.
- Paper API: `1.21.11-R0.1-SNAPSHOT`.
- Plugin name: `StarCore`; main: `dev.starcore.starcore.StarCorePlugin`.
- MIT licensed. Preserve attribution if copying upstream code. Prefer calling existing services.

## Production entry and service discovery

`StarCorePlugin.context()` returns `dev.starcore.starcore.core.StarCoreContext`. Its record accessors include `serviceRegistry()`, `eventBus()`, `scheduler()`, `moduleManager()`, `permissionService()`.

`dev.starcore.starcore.core.service.ServiceRegistry` provides `<T> Optional<T> find(Class<T>)`, `<T> T require(Class<T>)`, `Map<Class<?>,Object> snapshot()`.

`NarrativeEngine.getInstance()` must NOT be used in production: it creates an engine with null dependencies explicitly intended for UUID-only tests. Obtain the registered `NarrativeEngine` service from `context().serviceRegistry()`.

## Reuse: narrative execution

Package: `dev.starcore.starcore.module.narrative.engine.NarrativeEngine`.

- `NarrativeScript startScript(Player player, String scriptId)`.
- `NarrativeScript startScript(Player player, String scriptId, String startNodeId)`.
- `advanceNode(Player)` and `selectChoice(Player,int)`; choice indices are one-based.
- `endScript(Player)`, `saveCheckpoint(Player)`, `rollbackCheckpoint(Player)`.
- `getState(UUID)`, `getSession(UUID)`, `saveState(UUID)`.
- `isInSession(UUID)`, `hasActiveSession(UUID)`, `getActiveSessions()`.
- `eventBus()` returns the live narrative event bus.

`dev.starcore.starcore.module.narrative.event.NarrativeEventBus.subscribe(String, Consumer<NarrativeEvent>)` returns a `Runnable` unsubscribe token. Subscribe to `narrative.*` to receive all events explicitly published by the engine. The `addListener(NarrativeEngineListener)` list exists but engine code never invokes its listeners; it is unsuitable for a production bridge.

The sealed `NarrativeEvent` interface exposes `UUID playerId()`, `String scriptId()`, `long timestamp()`. Relevant nested records include `ScriptStartedEvent`, `NodeAdvancedEvent` (fromNode/toNode), `DialogueDisplayedEvent` (nodeId/text), `ChoiceSelectedEvent` (nodeId/choiceId), `ScriptCompletedEvent` (endingId).

`ScriptLoader` is **not** registered as a service by `NarrativeModule`. Locate `NarrativeModule` in `context().moduleManager().modules()` and call `scriptLoader()`. Its public `register(NarrativeScript)`, `unregister(String)`, `load(String)`, `loadFromFile(Path)` and `loadFromStream(InputStream,String)` can install Chronicle-owned scripts without replacing StarCore's engine. Namespace every installed ID and unregister only IDs owned by Chronicle at disable.

`ScriptLoader` YAML format uses top-level `id`, `title`, `startNode`, and a `nodes` mapping. Each node supports `dialogues` (strings or mappings), `choices` mapping with `text`, `targetNode`, `condition`, `effect`, and `nextNode`. Native `EffectExecutor` supports flags, variables and evidence, not authoritative national state mutations.

Observed implementation gaps:

1. `NarrativeEngine.renderNode(Player,...,String dialogue)` is a no-op in the actual instantiated base engine. A companion Chronicle GUI or chat listener is necessary to show the received dialogue.
2. Normal traversal passes a null endingId to `endScriptInternal`; `ScriptNode.isEnding/endingId` are not consulted. Do not depend solely on `ScriptCompletedEvent` to record orchestration outcomes. Map a committed Chronicle stage/outcome to known node or choice callbacks.
3. A player has a single active StarCore narrative session, while a world/nation campaign can have many players and stages. Chronicle should own only campaign membership, national alignment, shared choices, durable orchestration and outcome receipts; StarCore remains the per-player scene executor.
4. Starting an already-active player's scene can overwrite their active state. Detect unrelated active scenes and defer delivery.

## Reuse: quests

`dev.starcore.starcore.quest.QuestService` is registered by `QuestModule`.

- `Quest getQuest(String)` validates referenced IDs.
- `QuestAcceptResult acceptQuest(Player,String)` starts an existing quest. SUCCESS and ALREADY_ACTIVE are recognizable results.
- `PlayerQuest getPlayerQuest(UUID)` exposes `hasActiveQuest(String)`, `hasCompletedQuest(String)`, `getCompletedQuestIds()` and `getCompletedQuests()`.
- `registerQuest(Quest)` exists for namespaced example quest installation. `CoreContentInstaller` validates the actual builder/objective APIs and can explicitly opt into three no-command/no-reward preparation quests, but defaults to scenes only because upstream restores active quests before dependent plugins can register their templates. The original handler also cloned restored objective progress away and shared partial storage writers could clear other quest files. A separately proposed source patch and its 33 passing targeted tests are documented in `patches/README.md`; it is not applied automatically or enabled by default.
- `completeQuest(Player,String)` is the existing reward mechanism. Chronicle must not reimplement objective processing, entity rewards or boss execution.

Important external transaction boundary: `completeQuest` updates in-memory completion, grants money/items/experience/titles and possibly console commands, then calls asynchronous save. No outcome id or external transaction receipt is accepted. Chronicle cannot honestly promise cross-plugin exactly-once rewards if it calls this operation and the server fails between effect and its own receipt. Default Chronicle effects should start quests, leave reward completion to StarCore, and avoid replaying non-idempotent grants.

## Reuse: events and actual commit semantics

`context().eventBus()` is `dev.starcore.starcore.core.event.StarCoreEventBus`. `subscribe(Class<T>,Consumer<T>)` returns a removal Runnable. It dispatches only the concrete event class; subscribing to `Object.class` does not receive subclasses. Explicitly subscribe to known event classes and remove all subscriptions on disable.

| Event | Evidence / identifiers | Caution |
| --- | --- | --- |
| `.module.season.NationClaimedEvent` | record: claimId, playerId, nationId, claimedChunks, worldName | Published after successful claim; stable claimId supports deduplication. |
| `.module.war.event.WarStartedEvent` | getWar() exposes authoritative war UUID | Cancellable and both Bukkit/internal bridging exist; verify committed WarService state and deduplicate. |
| `.module.war.event.WarEndedEvent` | getWar(), getReason() | Verify committed end/outcome; do not double-count bridge publication. |
| `.module.split.event.NationSplitEvent` | source nation, splitter, requested new name | PRE operation and cancellable; **not history proof**. |
| `.module.split.event.SplitRequestApprovedEvent` | getRequestId(), getApproverId(), getSourceNation(), getNewNation(), getTransferredChunks() | Correct committed split evidence. |
| `.module.merge.event.NationsMergedEvent` | getResult(): MergeResult.id(), resultNationId(), mergedNationIds(), initiatorId(), mergedAt() | Committed merge result has stable operation UUID. |
| `.technology.event.ResearchCompleteEvent` | record: researchId, techId, researcherId, nationId, completeTime, era | Record is not a Bukkit Event. Subscribe to the internal event bus where the installed research implementation publishes it. |
| `.module.diplomacy.event.DiplomacyRelationChangedEvent` | left/right nations, previous/new relation | Cancellable, no durable event ID. Verify relation after commit; timestamp-only deduplication is not sufficient. |

`NationModule` publishes claims but provides no dedicated committed nation-created, nation-disbanded or capital-relocated event. A robust phase-one implementation can persist nation snapshots and diff them during bounded polling. First observation must be marked baseline/import rather than falsely assigning the current time as a founding time. Use `Nation.foundedAt()` for actual available foundation evidence. Snapshot-detected events must identify observation source and observation time and must not claim precise disappearance time.

## Reuse: national read and authority APIs

`dev.starcore.starcore.module.nation.NationService` offers `nationOf(UUID)`, `nationById(NationId)`, `nations()`, `claimsOf(NationId)` and `isFounder(UUID,NationId)`. `NationId` wraps a UUID (`value()`, `uuid()`, `toString()`). Nation model has `id()`, `name()`, `founderId()`, `foundedAt()`, `members()`, `capitalLocation()`, cached treasury/tech/war counters. Prefer authority services for balances and status rather than cached model counters.

Read services:

- `.module.treasury.TreasuryService.balance(NationId)`.
- `.module.technology.TechnologyService.unlockedTechnologies(NationId)` and `hasTechnology(NationId,String)`.
- `.module.war.WarService.atWar(NationId,NationId)`, `activeWarsOf(NationId)`, `allWars()`, `outcome(UUID)`.
- `.module.diplomacy.DiplomacyRelationService.relationBetween(NationId,NationId)`.

Authoritative mutations must never call model setters. Existing services are the only allowed backend and still require explicit actor authorization:

- `WarService.declareWar(NationId actorId,NationId aggressor,NationId defender)` / `endWar(NationId actorId,NationId left,NationId right)`. Actorless overloads default to denied.
- `DiplomacyRelationService.setRelation(NationId,NationId,DiplomacyRelation)` has no player actor; the caller must enforce permission and intended principals.
- `TreasuryService.withdraw/deposit/transfer` have no actor or outcomeId. Do not expose them through an arbitrary YAML command effect.
- `NationPermissionChecker.hasPermission(Player,NationPermission,PermissionLevel,NationRank[,boolean strictMode])` is a three-layer policy implementation. Obtain actual level/rank from authoritative services; never invent a supplied rank from client facts.

No upstream mutation API audited here accepts Chronicle's stable operationId. A Chronicle effect SPI should require idempotent receipts, fail closed when no authorized implementation is registered, and keep uncertain delivery visibly unresolved rather than blindly retrying a side effect.

## Existing history and persistence

`.module.event.EventService` supports `record(NationId,type,message[,context])`, `eventsOf(NationId)`, and clear. Its `NationEventRecord` includes UUID id, one nationId, Instant occurredAt, type, message, context. It has existing persistence and a nation log GUI but lacks structured participants, multiple nations, stable external source identity, evidence and world-wide/person timeline indexes. Chronicle's durable historical event store should add these missing fields rather than replace the old module. Optional mirroring into the old log must itself be deduplicated because `record` generates a fresh ID.

## Folia finding

`FoliaCompatScheduler` already offers entity/location/global/async methods, but NarrativeEngine uses `StarCoreScheduler` directly. Its `runSync` calls `Bukkit.getScheduler().runTask`, timers use Bukkit scheduler, and `NarrativeModule.enable` creates a Bukkit auto-flush timer. Upstream plugin.yml lacks `folia-supported: true`. Thus full StarCore integration on Folia cannot be asserted for this audited upstream build.

Chronicle can keep all its state/IO away from Bukkit threads and use the player's entity scheduler for UI. On detected Folia, block the unsafe upstream scene delivery or require an audited Folia-enabled StarCore adapter. Do not advertise a passing Folia server smoke test without actually running that stack.

## Implemented bridge capability contract

`integration.StarCoreBridge` uses public Java reflection only, avoiding a hard compile dependency on unpublished StarCore artifacts. It accesses `StarCorePlugin.context()` and the exact production service registry and explicitly fails closed when a required capability is missing. It never reads private fields, calls management commands, creates an independent narrative engine, issues rewards, or changes national models.

The bridge installs only `chronicle_` scene IDs through `NarrativeModule.scriptLoader().loadFromFile/register` and removes only currently owned script objects on shutdown. It accepts `narrative.start` and `quest.accept` through StarCore's existing executors. The three default sample campaigns bind successfully installed native scenes. `NativeSceneAdapter` supplies an owned-scene presenter using the actual native condition evaluator and traversal methods. Other side effects require the explicit `authority` adapter. A custom registered `AuthorityProvider` takes precedence; the built-in conservative fallback supports only a real founder-authorized `diplomacy.invite` through the native invitation service. It rechecks actual nationality, Bukkit permissions and `NationPermissionChecker`; unknown external results remain unresolved and are not blindly retried. Upstream lacks a cross-store outcome-ID transaction, so this is not a promise of exactly-once national changes across a crash.

`refreshContext(Player,Collection<String>)` runs on the player's entity thread. `context(UUID)` returns only its immutable cached snapshot and makes no Bukkit entity calls. Missing war, treasury, resource or technology services leave those facts absent, rather than inventing zero/false state. Core facts include:

- `player.name`, `player.world`, `player.rank`; `player.role` is lower case and `player.identity` is upper case.
- A verified StarCore founder has role `monarch`; exact standard rank names may map to the other seven roles.
- Administrators can explicitly grant `starchronicle.identity.soldier`, `.general`, `.merchant`, `.scholar`, `.explorer`, `.diplomat`, `.mayor`, `.monarch`. These nodes default to false in plugin.yml; operator status alone should not assign a role.
- `nation.id`, `nation.name`, `nation.treasury`, `nation.war`, `war`, `nation.technology.count`, `nation.technology.<key>`, `nation.resource.<key>`, `diplomacy.<otherNationUuid>` come from available StarCore read services.
- `TechnologyFacts` derives current `tech.stage` and `tech.era` from the highest real unlocked definition era via `TechnologyEra.fromId/getOrder`, under the native technology mutation monitor. Empty, unknown or incomplete capability reads omit these facts rather than guessing a stage from a count or technology name.
- `ContextContributor` registrations may contribute missing `player.*`, `region.*`, or `tech.stage` facts on the entity thread. They cannot replace core nation/resource/war/diplomacy facts, change the nation ID, or inject permissions. Only allow trusted plugins to register such a contributor.

`scanCommittedSources()` repeatedly imports actual `Nation.foundedAt`, authoritative `War.startedAt/endedAt`, `WarOutcomeSnapshot.battles` and `territoryCaptures` under stable source IDs. War lifecycle payloads use the immutable principal aggressor/defender pair, because late-joining allies must not change the fingerprint of an existing event ID. This supplements live subscriptions and recovers these replayable source records after a restart.

Live claim/split events have stable UUIDs but no source timestamp. Their evidence explicitly identifies `occurredAt` as an observation time. Merge and research events use their actual supplied completion times. Current upstream has a ResearchCompleteEvent model but no built-in publisher, so the bridge cannot promise that the base technology module will emit it without a trusted upstream integration.

`SnapshotCollector` and `ObservationJournal` now implement transactional native nation/technology observations: the first complete scope establishes a baseline; later removals/unlocks enqueue immutable observed history and advance the baseline in one SQLite transaction. Pending rows are acknowledged only after `RuleEngine.accept` commits, so accept-before-ack crashes safely redeliver the same event to its ID/fingerprint dedupe. Repeated capital transitions or technology revoke/reunlock receive distinct durable scope sequences. Incomplete scopes never advance baselines, and successful emptiness requires an explicit assertion. A trusted capital `SnapshotProvider` can supply real designated-city IDs; the native capital scope remains incomplete because the audited CityService has no durable designated-capital getter. See `HISTORY-OBSERVATION.md` and the proposed-only `CAPITAL-AUTHORITY-CONTRACT.md`.

Not implemented as purported facts: exact nation disappearance dates/actors, exact capital migration or technology unlock times inferred only from current-state snapshots, generic commerce thresholds, and city siege consequences without a committed producer. Observed changes explicitly carry `time_basis=OBSERVED`, source/before/after/sequence and an observation interval; they are not exact mutation evidence. Trusted producers with actual durable source operations can submit stronger `WorldEvent`s through the public extension API.

## Restart precision regression found by real Paper testing

The first real StarCore/Paper restart revealed that newly created entities may hold nanosecond-resolution `Instant` values in memory while StarCore persists/reloads the same fields as epoch milliseconds. For example, `2026-11-19T13:15:17.052129600Z` becomes `2026-11-19T13:15:17.052Z`. Reusing the correct stable source ID without canonicalizing this unsupported extra precision caused Chronicle's strict fingerprint check to report a collision after restart. The check correctly detected different payloads; weakening event integrity globally would hide the integration defect.

The bridge now canonicalizes all source timestamps to milliseconds at its input boundary. Nation-founding and war-lifecycle builders canonicalize before constructing both `occurredAt` and textual evidence. Merge, battle and territory-capture builders use the same timestamp helper; research already supplies epoch milliseconds. Observation timestamps also pass through the final common boundary. No known source time is replaced with the current time or omitted from integrity checks. Regression tests compare the complete first-publication and post-reload `WorldEvent`, including its evidence, for founding and both war lifecycle cases.

## SenLuoPlugin code audit

Repository: `https://github.com/addxiaoyi/SenLuo_Plugin`; audited main commit `7a676978f9c4a825275a9f905c3f860ac05a6827`, authored `2026-11-19T09:12:22Z`. The current repository is **KaleidoscopeCookeryPlugin**, with additional CraftEngine content modules under `series/common`, `doll`, `tavern`, `deco` and `music`. It is not a national RPG/narrative service. No StarCore, NarrativeEngine, national authority, diplomacy, quest or NPC provider API was found in its main Java code.

- `src/main/resources/plugin.yml`: plugin `KaleidoscopeCookeryPlugin`, main `net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin`, API 1.21, Folia support declaration, hard dependency CraftEngine, optional Dominion/PlaceholderAPI/UltimateAdvancementAPI.
- `build.gradle` and `gradle.properties`: Gradle Java 21, Paper `1.21.11-R0.1-SNAPSHOT`, CraftEngine `26.9.1`; project group `net.kaleidoscope`, version `1.3.0`. There is no inspected Maven publication to depend on as a published national API coordinate.
- Public entry point `net.kaleidoscope.cookery.api.KaleidoscopeCookeryAPI` exposes cooking/food/tag/menu registries, such as `foodRecipes()`, `applianceFoods()`, `itemTags()`, `potCookConditions()` and `recipeMenuHooks()`.

Optional content events in `net.kaleidoscope.cookery.api.event`:

| Event | Actual accessors | Commit boundary |
| --- | --- | --- |
| `PotExtractDishEvent` / `StockpotExtractDishEvent` | `getPlayer()`, `location()`, `dish()`, `setDish(ItemStack)` | Cancellable; fired before serving the dish. |
| `MillstoneGrindCompleteEvent` | `player()`, `location()`, `products()` | Cancellable before outputs; player can be null for animal-powered grinding. |
| `PotStirFryEvent` | `getPlayer()`, `location()`, `count()` | Cancellable; cancelling prevents progress increment. |
| `SickleHarvestEvent` | `player()`, `sickle()`, `block()`, `costDurability()`, `setCostDurability(boolean)` | Per-candidate cancellable harvest event, not a committed agricultural production log. |

`PotBehavior` constructs `PotExtractDishEvent` and invokes `EventUtils.fireAndCheckCancel` before handing out food. `EventUtils` directly dispatches through Bukkit's plugin manager. Monitoring an uncancelled event is useful for optional gameplay signals but does not create a durable committed production receipt; the API provides no stable event ID/outbox. A trustworthy long-term production integration needs an upstream post-commit producer with a stable operation ID, rather than a random callback UUID or timestamp heuristic.

Therefore StarChronicle keeps SenLuo optional. The first-stage artifact does **not** claim an automatic major-trade/civilization history adapter from single cooking or harvest actions. It does not use cooking APIs for treasury deductions, state mutations, diplomacy or rewards. Future trusted adapters can aggregate durable production contributions and submit real `WorldEvent` records when a meaningful threshold is committed.

`net.kaleidoscope.cookery.util.FoliaUtil` delegates to CraftEngine's `AbstractBukkitExecutor` and exposes entity/location `run`, `runLater`, `runEntity` and async teleport helpers. This is evidence of SenLuo's own scheduling approach; StarChronicle does not take an implementation dependency on this cooking utility and does not infer StarCore compatibility from SenLuo's support declaration.

## Narrow invitation authority and lock review

The built-in `StarCoreAuthorityProvider` supports only the native `DiplomacyService.sendAllianceInvite` operation. It verifies the currently online actor, explicit Bukkit permission, current national membership, both extant nation IDs, actual founder status and the upstream `NationPermissionChecker`. It holds the actual `NationModule` monitor across that verification and the invitation call, matching the monitor used by audited nation membership, ownership and disband mutations. It does not write a national model or turn an invitation into an accepted alliance.

An independent read-through of the pinned upstream implementation found this lock order: `NationModule` monitor → `DiplomacyModule.statePersistenceLock` → `MergingDiplomacyStateStorage.stateLock` → SQL enqueue lock or persistence file stripe. The diplomacy combined-state codec only projects its four maps. SQL snapshot work accesses the database pool and legacy properties, while file persistence writes properties; neither path calls back into `NationModule`. No reverse acquisition of the national monitor was found in this specific invitation/storage call chain. This is a source review of commit `f769e62799e769a18fe51305b545869a17f80cd1`, not a promise about later upstream versions or third-party replacement services.

Upstream `sendInvite` checks diplomatic relations before writing its invitation map; unrelated upstream diplomacy/war changes do not share the national monitor. A concurrent relation change can therefore leave a pending invitation that is no longer eligible for acceptance. Upstream `acceptInvite` rechecks hostile/war relations. Chronicle deliberately retains the narrow invitation operation and does not claim this monitor makes arbitrary diplomacy or war transitions atomic.

The provider's in-process duplicate-attempt registry is capped at **100,000 admitted outcome IDs**. Identical repeated deliveries coalesce to the retained result, while changed payloads under a reused ID fail closed. Already admitted results, including `UNKNOWN`, are not expired or evicted with an LRU policy, because removing them would permit the same outcome to invoke a native operation again. At capacity, a previously unseen outcome is rejected before native mutation; old known IDs remain readable. New invitation attempts require a controlled plugin/server restart after this exceptional capacity limit is reached. Chronicle's durable outbox remains the cross-restart protection: claim before calling, never replay `RUNNING`/`UNKNOWN`, and reconcile uncertain effects explicitly. No upstream API accepts the Chronicle outcome ID, so cross-plugin exactly-once mutation is not asserted.
