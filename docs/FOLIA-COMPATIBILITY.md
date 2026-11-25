# Folia compatibility and upstream boundary

Status recorded on 2026-11-19. The audited upstream is StarCore commit `f769e62799e769a18fe51305b545869a17f80cd1`; Folia is the official 1.21.11 build 14 artifact.

Exploration acceptance was updated on 2026-11-20 for StarChronicle SHA-256 `4ede86a8bb6315776184c612a488f0c9ebc0321ccef73ce84ddfb097c1f374ca`. This records the bounded standalone exploration scope below and does not change the upstream full-stack boundary.

| Combination | Recorded result |
| --- | --- |
| Paper + audited StarCore + StarChronicle | Real-server startup, authoritative founded event import and restart replay passed; see `verification/paper-smoke-report.md`. |
| Folia + StarChronicle standalone | Two real protocol clients, shared instance voting across restart, local history/title receipts and repeated event replay passed 18/18 bounded checks; see `verification/folia-smoke-report.md`. |
| Folia 1.21.11-14 + StarChronicle configured-region exploration, standalone | Two normal boots passed physical walking, actual async teleport arrival, actual GUI choices, canonical first-visit persistence and repeated-arrival idempotency. Cancellable teleport coverage is `UNSUPPORTED` / `null`; see [the exact-build report](verification/exploration-folia-4ede86a8-report.md). |
| Folia + unmodified audited StarCore + StarChronicle with `require-starcore: true` | Full stack does not start. Real Folia loader rejected StarCore because its descriptor does not declare Folia support, then StarChronicle correctly refused absent authority services. See `verification/folia-upstream-loader-probe.log`. |
| Folia + patched StarCore | Not certified or shipped. The included owner-thread contract patch is a prerequisite, not a complete port. |

The separate negative loader probe has no observed normal-stop/exit-code result: its PTY handle became unavailable during host resource pressure, and a later process inventory contained no `folia.jar` JVM. The two successful standalone runs were independently observed to stop normally with exit 0. These outcomes are recorded separately.

The exploration runs used `require-starcore:false`; both server and client exited normally with code 0 on both boots. In the preserved old-build platform probe, `Player.teleportAsync` moved the player without dispatching `PlayerTeleportEvent`, so this platform could not exercise cancellation through that event. The current build confirms actual position on the player's owning entity scheduler during regular context polling, in addition to post-event confirmation. Its callbacks retain the scheduled player entity instead of resolving a replacement session by UUID. It records an `OBSERVED` time and does not infer a visit that ended before any observation. The five supported checks passed; cancellation remains unavailable, not a passed check. Both stopped SQLite checkpoints retained two canonical discoveries and receipts, two completed stories and honor 5. The [matching Paper report](verification/exploration-paper-4ede86a8-report.md) includes a real cancelled-teleport check; the [build summary](verification/owner-consumers-build-summary.json) separately records 800 passing tests.

The current runs used a detached controller with exact PID/creation identity, append-only sequenced commands and post-command response cursors. Each platform passed nine offline evidence regressions after strict inspection. The previous `8fdcf8d3` [Paper](verification/exploration-paper-8fdcf8d3-report.md) and [Folia](verification/exploration-folia-8fdcf8d3-report.md) reports remain separate historical evidence.

## Reproducible upstream patch

`patches/starcore-folia-narrative-owner-contract.patch` applies cleanly to the pinned upstream commit. It changes only `StarCoreScheduler`, `NarrativeEngine`, and two focused test classes. It does not edit the upstream descriptor, redirect arbitrary `Runnable` callbacks globally, replace upstream authority services, or enable StarCore on Folia.

The new `StarCoreScheduler.callForEntity(Entity, Supplier<T>)` runs the whole operation on that entity's owning scheduler, following entity migration. Its future completes after the operation returns. Before a task starts, retirement, a refused schedule, or scheduler shutdown produces a precise `EntityTaskNotAppliedException`. A task that already started retains its actual result or body exception; its exception is not relabelled as definitely unapplied. No global-thread, stale-location, offline-player, or retired-entity fallback is provided. Pending cancelled futures are not executed. `requireEntityOwner` checks actual ownership rather than assuming a Folia thread is safe.

`NarrativeEngine.startScript` and `rollbackCheckpoint` now reject the wrong owner before loading or mutating the session/state. `startScriptAsync` and `rollbackCheckpointAsync` dispatch the whole operation and return its actual completion/result. Other player-bound void entry points dispatch with the explicit player. UUID-only APIs still expose mutable state; callers must respect per-player ownership or a quiescent offline lifecycle.

The focused harness compiles the two changed production classes against the verified StarCore JAR and the Paper 1.21.11 API. Thirteen tests cover inline owner execution, queued completion, retirement, schedule refusal, shutdown, pending cancellation, body errors, incorrect scheduler ownership, pre-mutation start/rollback guards, and rejected async starts. `git apply --check` passed. This is not a claim that the entire upstream Maven release or its runtime modules were ported. Evidence is in `verification/starcore-folia-contract-maven.log` and `verification/starcore-folia-contract-summary.json`.

Reproduce with a separate, unchanged checkout of the pinned upstream commit and its verified JAR installed as `dev.starcore:starcore:1.0.0`:

```text
python tools/prepare-starcore-folia-contract.py <upstream-checkout>
mvn -f run/starcore-folia-contract/pom.xml test
git -C <upstream-checkout> apply --check <absolute-path-to-patch>
python tools/report-starcore-folia-contract.py <upstream-checkout>
```

The preparation script writes only under this project's ignored `run/` directory and the reviewable `patches/` artifact. It never edits the audit checkout.

## Remaining upstream work before full-stack certification

These are observed boundaries in the pinned source, not permission to bypass Folia's loader:

| Source | Required change or contract |
| --- | --- |
| `core/scheduler/StarCoreScheduler.java` synchronous and timer APIs | Audit each caller and assign player/entity, fixed region, truly global, or I/O ownership. Preserve cancellation/completion. A generic `runSync(Runnable)` has insufficient ownership information and cannot be safely translated to a global task. |
| `core/scheduler/FoliaCompatScheduler.java:isMainThread` | Remove its unconditional `true` Folia ownership claim. Its mere existence does not prove other callers safe. Entity retirement and cancellation need explicit results. |
| `module/narrative/engine/TypewriterController.java:64` | Port per-player repeating work to EntityScheduler, retain a task handle, clear state on retirement, and define callback ownership. Its current timer is a BukkitTask. |
| `module/narrative/gui/NarrativeDialogGui.java:292` | Port the repeating inventory refresh to the owning player scheduler and cancel safely on close, replacement, quit, retirement and disable. |
| `module/narrative/listener/NarrativeGuiListener.java:212` | Transfer asynchronous chat input to the player's owner with session-staleness checks. The current legacy Bukkit task is not valid on Folia. |
| `module/narrative/NarrativeModule.java:126` and `state/PlayerNarrativeStateManager.java:141` | Replace bulk traversal of mutable per-player state with immutable snapshots captured under each player's ownership, then persist snapshots on I/O. A copied map of mutable values is insufficient. Disable must drain this process without blocking a region on itself. |
| `module/narrative/boss/NarrativeBossIntegration.java:95-153` | Distinguish boss-entity and player ownership. Current hooks inspect player online/name state and may start a player script from a boss callback; use explicit player handoff and completion. |
| `module/narrative/event/NarrativeEventBus.java` | Document that synchronous listeners execute on the publisher's thread. A thread-safe subscriber registry does not make listener world/entity access safe. Audit downstream consumers. |
| Remaining StarCore modules and boot/disable | Port all enabled legacy tasks, GUI, entity/world access, event handlers and persistence lifecycles before declaring plugin-wide support. Re-run real cross-region, teleport, disconnect, retirement, concurrent-player, disable, restart and authority tests. |

The upstream `EffectExecutor` reviewed here handles narrative state flags/variables, not an unrestricted administrator command dispatcher. StarChronicle continues to route actual nation/task/reward authority through audited services and refuses unavailable integrations. The patch does not expand AI or script authority.

Only after this remaining port and full acceptance run should upstream maintainers add `folia-supported: true`. StarChronicle keeps upstream narrative execution unavailable on Folia while these conditions remain unmet.

Primary platform references: [Paper's Folia support guide](https://docs.papermc.io/paper/dev/folia-support/), [Folia ownership overview](https://docs.papermc.io/folia/reference/overview/), and [Folia's upstream project](https://github.com/PaperMC/Folia). Source findings refer to [the pinned StarCore tree](https://github.com/addxiaoyi/starcore/tree/f769e62799e769a18fe51305b545869a17f80cd1).
