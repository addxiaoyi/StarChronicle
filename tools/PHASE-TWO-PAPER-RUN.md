# Phase-two real Paper / StarCore acceptance

These are preparation instructions, not executed evidence. Wait for the coordinator's final
JAR fingerprint and exclusive JVM permission. Do not compile this fixture, start Java or
reuse an earlier run before that permission. All resulting evidence uses `phase-two-paper-*`;
the earlier `expanded-paper-*` reports retain their own artifact fingerprints.

Prepare a fresh directory after final `clean verify`:

```text
python tools/prepare-phase-two-paper.py --expected-plugin-sha256 THE_EXACT_FROZEN_SHA256
```

The preparation script requires the real 64-character SHA-256 value, refuses an existing
run directory and compiles the test-only fixture against that exact product JAR. It binds
`127.0.0.1:25589`, disables RCON/query, allows six slots and sets polling to 12,000 ticks.
The first poll still runs shortly after startup; subsequent polls are ten minutes apart.
Do not let boot one run past that later poll before its first shutdown.

Start each of the three boots from `run/phase-two-paper-release-smoke`, one JVM at a time:

```text
java -Xms256M -Xmx768M -XX:MaxMetaspaceSize=256M -Dfile.encoding=UTF-8 -jar paper.jar --nogui
```

Start the controller from the repository root, using the actual boot number and the same
append-only evidence log on each boot:

```text
node tools/phase-two-paper-clients.cjs phase-two-paper-clients.log boot1
```

Four actual clients A/B/C/D (`PhaseTwoA` through `PhaseTwoD`) connect by default. E does
not connect until the controller receives `spawn E`. The normal `quit` command disconnects
every connected client and lets the controller process exit. Record each actual exit code.
Wait for every command's actual window/fixture completion output before the next dependent
action; sending a chat command does not synchronously open its GUI.

## Boot one: actual epic, native departure and a durable unactivated inbox

1. Wait for `Done`, `PHASE2_READY` and four client `SPAWN` records. On the console run
   `phase2 phase BOOT1`, `phase2 seed`, then `phase2 begin`. Require four distinct native
   nations, actual founder membership, non-operator permission attachments and the product's
   installed `chronicle_last_heir` native script.
2. In the controller run `chat A /chronicle scene`; wait for its native window. Run
   `inspect A`, then `click A 9` to reach evidence. Wait for the new window, then `click A 31`
   to actually reach departure. Query `phase2 native A` on the console and require the
   real engine session `chronicle_last_heir/departure`, routing true and matching owned
   flag/evidence state. **Leave this native scene unfinished.**
3. Through the console fixture, perform the shipped quorum choices one phase at a time;
   every choice uses `ChronicleService.choose` with the actual connected actor:

   ```text
   phase2 vote A summons convene
   phase2 vote B summons convene
   phase2 vote D council oppose
   phase2 vote A council support
   phase2 vote B council support
   phase2 vote A preparations restoration
   phase2 vote B preparations restoration
   ```

4. After the final committed ending, **do not call `phase2 inspect`, `/chronicle stories`,
   an inventory link to stories, `api.instances`, or any other story-drain operation**.
   Those reads intentionally resume deferred triggers. `phase2 facts D` and
   `phase2 native A` are safe read-only projections and do not drain story triggers.
   Do not complete A's native scene here: its completion GUI links to stories.
5. Use controller `quit`, observe its normal exit and capture, read-only:

   ```text
   python tools/inspect-phase-two-paper.py before-first-stop
   ```

   Require exactly the original WORLD epic, no `heir-legacy` instance, the committed
   ending plus its choice log/causal trace, and pinned `heir-legacy` inbox rows referencing
   that ending. Do not resubmit the ending event.
6. Issue normal console `stop`, wait for world saving and the actual process exit code,
   then archive the first boot before another server starts:

   ```text
   python tools/inspect-phase-two-paper.py boot1-final --archive-log
   ```

## Boot two: automatic personal recovery, real late membership and GUI branches

1. Restart the same artifacts and databases. Before clients connect, mark
   `phase2 phase BOOT2_OFFLINE`, then capture `python tools/inspect-phase-two-paper.py boot2-offline`.
2. Start the controller with `boot2`. Wait for A-D actual logins and the join handlers.
   Query only `phase2 native A` to confirm the actual native departure recovery; this
   does not drain story triggers. Capture **before any `phase2 inspect`/stories query**:

   ```text
   python tools/inspect-phase-two-paper.py boot2-recovered
   ```

   There must already be four distinct PLAYER `heir-legacy` instances, one per actual
   original actor, all with the exact original ending source ID. Then mark
   `phase2 phase AUTOMATIC_RECOVERY_CAPTURED` and use `phase2 inspect A/B/C/D` as needed.
3. Use `spawn E` in the controller. After its actual `SPAWN`, run console
   `phase2 late-member`, then `phase2 inspect E`. This must perform one actual
   `NationService.addMember` into A's real nation; E remains a non-founder and non-operator.
   Fresh API recollections must show world support, national ending history count 1,
   personal ending history count 0 and no personal council ballot. E must get no archive.
   Capture `python tools/inspect-phase-two-paper.py late-member`.
4. Finish A's restored native scene: controller `chat A /chronicle scene`, wait for the
   departure GUI, then `click A 31`. Query `phase2 native A`; require actual session absent,
   routing false and durable native completion. This legitimate GUI may now open stories.
5. Query `phase2 facts D` and `phase2 facts A`; require actual default NPC dialogues
   `personal-dissent` and `trusted-restoration`. Open `chat D /chronicle npc ancient-heir`
   and inspect its actual components. Also send `chat D /chronicle past last-heir/1/council`
   and the same command for C; D's personal oppose and world support must stay distinct,
   while C has only the world resolution.
6. For each A/C/D open `chat X /chronicle stories`, wait, then
   `click-name X 旧都档案：每个人的那一票`. Inspect the resulting actual story GUI.
   Verify visible choices through the controller:

   ```text
   assert-choices A reconcile,testimony
   assert-choices C reconcile,witness
   assert-choices D reconcile,testimony
   ```

   Console `phase2 invalid A witness` and `phase2 invalid C testimony` must produce
   `PHASE2_INVALID_REJECTED` with a real SecurityException and no result delivery.
7. Complete through the real filtered GUI, not a fixture reward setter:

   ```text
   choice A testimony
   choice C witness
   choice D reconcile
   ```

   Reopen each stories GUI first if a previous action changed it. Inspect A/C/D via the
   fixture and require honor 17, distinct titles and one personal history per branch;
   B must remain at archive with honor 15. NPC relationship values remain canonical.
   Open actual `/chronicle fate`, `/chronicle history`, `/chronicle history player me`
   and click a history entry for its evidence window; retain protocol components.
8. Run console `phase2 invite-config`, wait for the actual configuration reload, then
   `phase2 invite-begin`. Wait for `PHASE2_INVITE_SUBMITTED` before running
   `phase2 invite-choose`; the begin operation is asynchronous. The actual A founder still explicitly
   lacks `starcore.nation.ally.add`: require a SecurityException and no authority outcome.
   Capture `python tools/inspect-phase-two-paper.py invite-denied`. Then `phase2 allow`,
   wait for its actual permission attachment, and run `phase2 invite-choose` again.
   After authority delivery run `phase2 invite-check`: require the real native pending
   A→B invitation, NEUTRAL relation, one DONE outcome and a same-boot retained APPLIED
   response for that exact already-committed outcome ID. Capture
   `python tools/inspect-phase-two-paper.py invite-permitted`. This provider call checks
   coalescing only after the formal permission-gated story path has already mutated the
   native service; it never substitutes for that path.
9. Capture `python tools/inspect-phase-two-paper.py boot2-completed`. Normally `quit`
   all five clients and record the actual controller exit. Normally console `stop`, record
   the server exit and archive `python tools/inspect-phase-two-paper.py boot2-final --archive-log`.

## Boot three: durable completion and original-source replay

1. Restart the exact same artifact. Before clients connect capture
   `python tools/inspect-phase-two-paper.py boot3-offline`.
2. Start the controller with `boot3`; wait for A-D. `phase2 seed` may refresh permission
   attachments and must reuse the same four native nations. Spawn E explicitly and use
   `phase2 late-member` again; it must read its persisted A-nation membership and report
   `addedByNativeService=false`, without inventing a second membership operation.
3. `phase2 inspect A/B/C/D/E` must retain completed A/C/D, unfinished B and no E archive.
   `phase2 native A` must remain absent/routing false; A's original native start must not replay.
4. Run `phase2 replay` to resubmit **only** the original captured test source. Require
   duplicate true. Do not create a replacement source ID and do not submit the ending.
   Console `phase2 complete-replay A testimony`, `phase2 complete-replay C witness`,
   `phase2 complete-replay D reconcile` must reject completed choices without changing
   history, choice IDs, outcomes or honor.
5. Normally quit all five clients and stop the server; retain both actual exits. Archive
   `python tools/inspect-phase-two-paper.py boot3-final --archive-log`. Release the JVM
   window before another agent starts a server or compiler.

Run the report only after observing all three actual server/client exits, using the frozen
fingerprint and their actual codes (the zero values below are expected outcomes, not assumed):

```text
python tools/report-phase-two-paper.py --expected-plugin-sha256 THE_EXACT_FROZEN_SHA256 --server-exit-code 0 --server-exit-code 0 --server-exit-code 0 --client-exit-code 0 --client-exit-code 0 --client-exit-code 0
```

Missing or failed evidence aborts reporting. Never remove warnings, reset UNKNOWN states,
rewrite plugin databases or substitute an older artifact's successful paths. GUI protocol
coverage does not claim human visual approval.
