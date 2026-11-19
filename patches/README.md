# Optional StarCore persistence repair

`starcore-quest-deferred-restore.patch` targets audited StarCore commit `f769e62799e769a18fe51305b545869a17f80cd1`. It is a proposed upstream source patch, not an automatically installed runtime modification. Chronicle's default scenes remain available without it; default native custom quest installation/binding remains disabled.

The patch fixes three related restart problems discovered while auditing real integration paths:

1. `QuestModule` restores player quests before dependent plugins can register their templates. Unknown active quest JSON is now retained through autosaves/restarts and restored only when the matching template becomes available. Registration does not globally reload player state or run acceptance commands/rewards.
2. The old `deserializeQuest → PlayerQuest.acceptQuest → createInstance` path cloned restored objective progress away and restarted time limits. A package-private persistence restore method keeps the saved instance's progress and original start time; expired late tasks are not granted a new timer.
3. Quest/daily/commission handlers share a persistence namespace. Their old partial snapshots each overwrote all three files, including the final `saveCommissionLeaderboard` shutdown call. Backwards-compatible scoped storage overloads now isolate player, daily, commission and leaderboard files, including intentionally empty snapshots. Async commission saves split their nonempty leaderboard into its own file.

The old full-snapshot `QuestStateStorage.save(Properties)` and `saveAsync(Properties)` contracts remain available; legacy callers can still intentionally replace a full snapshot. All eight production handler call sites in the audited quest package now use explicit sections. The new default interface methods preserve source/binary compatibility for custom single-section storage implementations. `QuestService.supportsDeferredQuestRestore()` identifies the running patched persistence capability; it does not assert that external reward grants are transactional.

Apply in a separate StarCore checkout after reviewing the patch:

```powershell
git -C C:\path\to\starcore apply --check C:\path\to\StarChronicle\patches\starcore-quest-deferred-restore.patch
git -C C:\path\to\starcore apply C:\path\to\StarChronicle\patches\starcore-quest-deferred-restore.patch
```

The audited original checkout and running plugin JAR were not modified during development. `git apply --check` against an unmodified copy is part of patch delivery validation.

For a low-memory targeted reproduction against the actual upstream classes, use `tools/test-starcore-quest-patch.ps1`. It copies only five patched production sources and three test classes into a separate harness and compiles all other upstream classes from the supplied original verified JAR. This avoids compiling/running the full upstream test suite and does not build a replacement server plugin. Java 21 and Maven 3.9.x are required:

```powershell
.\tools\test-starcore-quest-patch.ps1 `
  -StarCoreRoot C:\path\to\patched-starcore `
  -VerifiedStarCoreJar C:\path\to\original-starcore.jar `
  -MavenExecutable C:\path\to\mvn.cmd
```

The supplied verification run passed **33 tests, 0 failures, 0 errors, 0 skipped** on 2026-11-20 at 00:35:22 +08:00: 13 deferred restore tests, 14 existing persistence handler contracts and 6 storage-isolation tests. One test uses the actual `PersistenceService`/atomic Properties files through two restarts, preserving player, daily and commission data while templates are initially absent. Other tests cover the real shutdown leaderboard call, nonempty async leaderboard, original progress/start times, unknown JSON, unrelated live players and idempotent restoration. See `docs/verification/starcore-quest-patch-verify.log` and `docs/verification/starcore-quest-patch-summary.json`.

This targeted verification is not a full patched StarCore Maven build or a full server acceptance test. Review/build the upstream plugin with its normal checks and validate a backup of the production world before enabling optional native quests. This patch does not provide an external outcome-ID-aware reward transaction, change country authorization or repair upstream Folia scheduling.
