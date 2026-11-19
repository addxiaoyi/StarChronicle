# Native StarCore content

The audited upstream commit is `f769e62799e769a18fe51305b545869a17f80cd1`.

`CoreContentInstaller.install()` copies three native YAML scenes into the Chronicle data folder's `starcore-scenes` directory only when the corresponding file is missing. It validates their IDs and edges, then calls `StarCoreBridge.installScript(Path)`, which uses the live `NarrativeModule.scriptLoader().loadFromFile/register` API. It does not create a replacement narrative engine. Existing user files are preserved, external registrations with the same ID are never intentionally overwritten, and an unconfirmed registration is never attached to a story definition.

| Chronicle sample | Owned native scene | Optional native quest |
| --- | --- | --- |
| `last-heir` | `chronicle_last_heir` | `chronicle_heir_records` |
| `siege-embers` | `chronicle_siege_embers` | `chronicle_siege_testimonies` |
| `silk-road-scholar` | `chronicle_silk_road` | `chronicle_caravan_atlas` |

After successful registration, `bind(stories)` adds the owned scene reference to the start node of these three known samples if its YAML did not already specify a scene. It creates a new immutable definition; it does not write the user's story files or alter other nodes. Standalone mode leaves definitions unchanged. Administrators can edit the native files using StarCore's actual parser format: `startNode`, `dialogues`, `choices.<id>.text`, `choices.<id>.targetNode`, singular `effect`, and `nextNode`. Legacy example keys `effects` and `onEnd` are not parsed by the live `ScriptLoader` and are rejected for installed Chronicle scenes.

Native scene effects are restricted to narrative flags, variables and evidence. They contain no management commands, national mutations or rewards. `markEnding` is explicit because upstream normal traversal does not consult `ScriptNode.isEnding/endingId` when publishing a completion result. National campaign choices and history receipts remain owned by Chronicle's durable rule engine.

## Rendering and Folia boundary

The production upstream base `NarrativeEngine.renderNode(Player,...)` is a no-op. Chronicle supplies a thin `NativeSceneAdapter` and `/chronicle scene` GUI to display the live native session. It reuses the actual native `ConditionEvaluator` for visible text/choice filtering and calls `NarrativeEngine.advanceNode` or one-based `selectChoice` for execution. It never interprets native effects or traverses the graph itself. Only currently owned script objects are exposed; player permissions and expected script/node tokens are rechecked on every action. Node-level `script` references start at the native script's own `startNode`, not the unrelated national campaign node ID.

Native scene choice/state ownership remains distinct from the Chronicle shared campaign: a native choice does not vote for a national result. Audited native node-render effects and automatic ending publication are incomplete, so the Chronicle durable campaign/history receipts remain authoritative for shared results. This presenter does not claim to repair those upstream engine internals.

The audited StarCore uses the Bukkit main-thread scheduler in its narrative implementation. Chronicle disables `narrative.start` on Folia; installing text files or executing Chronicle's own GUI/state engine does not fix this upstream scheduling limitation.

## Native quests: explicit opt-in and restart blocker

The installer can construct three real `Quest.Builder` definitions through public reflection and register them using the live `QuestService.registerQuest`. Their single actual `CRAFT_ITEM` objective is PAPER ×3, BOOK ×1 or MAP ×1, respectively. Upstream's existing `QuestProgressService.onCraft` advances these objectives. They have no start commands, completion commands or rewards; Chronicle never bypasses StarCore's objective or reward implementation. `install(true)` explicitly opts in. `bind(stories,true)` independently opts into binding confirmed owned quests. Default `install()` and `bind(stories)` do neither.

**Known upstream restart blocker:** `QuestModule.enable` constructs an empty `QuestService`, calls `QuestService.initialize()`, then registers the service. It has no public before-restore template hook and does not load the bundled `quest/quests.yml` into that registry. `QuestPersistenceHandler.deserializeQuest` skips a saved active quest when its template is absent. As a dependent plugin, Chronicle can only register templates after that initialization has completed, so active native custom quests can be discarded during the next upstream startup. Calling global `initialize()` again or editing the upstream internal persistence database is unsafe and is not done here. Do not enable optional native quests in a production world until this upstream restoration order is repaired. Chronicle's own campaign instances, history, outcomes and observations retain their independent tested SQLite restart recovery.

A separately delivered proposed upstream patch, `patches/starcore-quest-deferred-restore.patch`, preserves unknown-template JSON, original objective progress/start times and isolated player/daily/commission/leaderboard storage sections. Its targeted source harness passed 33 tests, including actual-file recovery through two restarts; `git apply --check` passed against the original audited commit. The patch adds `QuestService.supportsDeferredQuestRestore()` for future capability detection. It has not been automatically applied to any running StarCore installation, does not constitute a full patched upstream/server verification, and does not turn this round's optional native quests on by default. See `patches/README.md` and the archived verification evidence.

## Native diplomatic initiative

The built-in authority provider accepts exactly:

```yaml
type: authority
target: diplomacy.invite
value: "22222222-2222-2222-2222-222222222222" # existing target nation's canonical UUID
permission: starcore.nation.ally.add
```

The delivery must include its stable outcome/instance IDs and the actor's original nation UUID. The provider rechecks the online actor, `starchronicle.use`, the explicit Bukkit permission, live `NationService.nationOf/isFounder`, existence of both nations and the actual strict `NationPermissionChecker` with `ALLY_ADD`. This initial adapter conservatively supports true founders only; role labels and custom rank names do not grant national authority. It calls `DiplomacyService.sendAllianceInvite`, which performs native relation/cooldown/pending checks and synchronously persists a successful invitation. It does not force the other nation to ally.

The upstream API accepts no external outcome ID and offers no cross-store transaction. The Chronicle outbox durably claims an attempt before dispatch, and an uncertain mutation is `UNKNOWN` rather than blindly retried. A process crash can leave the invitation's actual result unresolved; reconciliation must inspect native authority. This is an explicit external transaction boundary, not a promise of exactly-once changes across two databases.
