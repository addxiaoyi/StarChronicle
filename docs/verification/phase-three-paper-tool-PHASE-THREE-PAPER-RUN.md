# Phase-three Paper operator sequence

This run uses a fresh clone of the actually stopped phase-two Paper/StarCore data.
Never point these tools at the old run, edit any database to pass, or substitute a new
source timestamp/actors. Every tool requires the complete final `--expected-sha`.
Run JVM work serially in the coordinator's explicit window. A failure is retained;
read the actual state before deciding any repair. Do not reset the clone after a failure.

The frozen product for this prepared run is
`bfa80b9ca62b1f28fdd37a2f1d7244b9b94cb8c34814273fda742ff8c6f28154`.
The prepared directory is `run/phase-three-paper-bfa80b9c`.

This actual run retains two extra pre-client preflight attempts separately: the first
Python console owner failed while printing Unicode through GBK; the second exec omitted
tty=true and had no usable interactive stdin. Both products reached READY, and the first
migrated the clone. The exact owned PIDs 4188 and 6636 were normally shut down with a
bounded, world-directory/product-hash checked Attach agent scheduled through the real
fixture; held Win32 handles observed both server exits 0. The second launcher also
observed its actual server exit 0 and returned 1 because no controller had started.
The database/world were not reset. The three complete acceptance boots below follow
those retained preflights. Original failed process tables, complete server logs,
agent sources/commands and recovery markers remain in evidence.
Future CLI invocations explicitly set `PYTHONIOENCODING=UTF-8` in addition to the launcher's
stdout/stderr UTF-8 reconfiguration. `recover-phase-three-paper.py` is a one-shot tool
restricted to that exact recorded PID; it is not a generic process killer.

## Preparation and identity

Preparation/build are separate from server startup:

```text
python tools/prepare-phase-three-paper.py --expected-sha <FULL_SHA> --run-name phase-three-paper-<suffix>
python tools/build-phase-three-paper-fixture.py --run-dir run/phase-three-paper-<suffix> --expected-sha <FULL_SHA> --java-home <JDK21>
```

Preparation opens the actual old Chronicle database read-only, saves the source table
snapshot, creates a consistent backup in a new directory, and copies the real StarCore
state/worlds/configuration. It preserves all source file hashes. It does not start a JVM.
The clone explicitly sets `install-default-stories: false`; otherwise the default installer
would reintroduce the retired A1 YAML on a later restart. The source config stays untouched.

The fixture is compiled only in an explicit JVM window. Its manifest retains actual
compiler attempts; the initial VM-option-in-args-file tooling rejection and successful
repair remain in this run's evidence. No server was started by either compiler attempt.

The controller connects the same four original non-operator accounts `PhaseTwoA` through
`PhaseTwoD`. `phase3 seed` grants use but explicitly denies admin and alliance permission,
then reads their actual existing native nations/founder status. It never creates a country,
adds membership, constructs a PlayerContext, writes SQLite or grants rewards.

The historical source used for explicit registration is the saved original ending:

```text
chronicle:975377c7394a74dbaafc65237cf43cdccda38990fe1b2ef3f46b428f6464ebe7
```

The fixture reads canonical sources through the real history API for replay. It never
reconstructs the receipt. That ending retains the actual four old actors/countries and
its original cause rooted at `phase-two:heir-discovery:1`.

A1 is `heir-legacy/1`: A testimony, C witness, D reconciliation are completed; B remains
at its original archive node. A2 is `heir-legacy/2`, title `旧都档案·第二卷`, with one
`annotate` choice (+2 honor). B is the new `phase-three-catalog-reader/1`, title
`旧档中的版本坐标`, whose choices read the exact retired reference
`past.player.choice.heir-legacy/1/archive` (+3 honor). The current directory never enables
both A1 and A2. Old pinned A1 remains playable. The fixtures assert current narrative
choices; they do not restore unrecorded past eligibility or perform national mutations.

## Session input and stage capture

Start exactly one session for each boot:

```text
python tools/phase-three-paper-session.py --run-dir run/phase-three-paper-<suffix> --expected-sha <FULL_SHA> --java-home <JDK21> --boot 1
```

The next boots use `--boot 2` and `--boot 3`. The launcher refuses out-of-order boots,
fingerprint drift, an uncompiled fixture, or an existing server log. It starts clients
only after explicit `clients start`, and records their real exits separately.

Routes:

```text
server <real console command>
clients start
client <real controller action>
stage <new-stage-name>
```

`stage` uses the read-only inspector. It refuses overwriting a prior stage. After the
actual session exits, capture the final stage and sanitized full server log externally:

```text
python tools/inspect-phase-three-paper.py boot1-final --archive-log --run-dir <RUN> --expected-sha <FULL_SHA>
```

Use corresponding `boot2-final`/`boot3-final`. Never assume exit code zero from a saving
message; require the process table's actual observed result. Quit clients before `stop`.

Commands are asynchronous. Wait for each stated fixture response / validation result /
actual WINDOW before dependent work. In particular, do not inspect instances or open
story GUIs before capturing the automatic join recovery stages. `facts` does not drain
instances; `inspect` and `/chronicle stories` intentionally do.

## Boot one: migrate, validate, publish and register offline

1. Wait for actual `PHASE3_READY` and Paper `Done`. No clients yet:
   `stage boot1-migrated-offline`.
2. `clients start`; wait for four actual SPAWN records. `server phase3 seed`; require
   four original UUIDs/founders, distinct expected native nations, op=false/admin=false.
   Run `server phase3 facts A` (and B/C/D), wait for all fate/recollection responses.
   `server phase3 runtime migrated`; `stage boot1-baseline`.
3. `server phase3 install`; wait for CONFIG_INSTALLED_NOT_RELOADED. This preserves
   the exact old YAML as `.phase-three-retired` and copies only prepared A2/B files.
   `server phase3 runtime before-validate`; `stage boot1-before-validate`.
4. `server chronicle validate`; wait for actual success. `server phase3 runtime validated`;
   `stage boot1-validated`. The catalog must still contain no A2/B rows and runtime remains A1.
5. `server chronicle reload`; wait for success. `server phase3 runtime published`;
   `stage boot1-published`. Require A2/2 + reader/1 current, A1/1 retired but archived.
6. Send as actual non-op A:
   `client chat A /chronicle evaluate <ENDING> phase-three-catalog-reader 1`.
   Wait for actual denial; `client assert-message A 管理权限`;
   `stage boot1-nonop-denied`. No new registered triggers or instances.
7. `server phase3 replay ending`; wait for SOURCE_REPLAY duplicate=true.
   `stage boot1-normal-replay`. This ordinary redelivery must not register A2/B.
   `server phase3 reject-evaluate heir-legacy 1` and
   `server phase3 reject-evaluate phase-three-catalog-reader 2`; wait for two actual rejections.
8. `client quit`; require the actual controller exit 0 before continuing. Offline console:

   ```text
   server chronicle evaluate <ENDING> heir-legacy 2
   server chronicle evaluate <ENDING> phase-three-catalog-reader 1
   ```

   Wait separately for each QUEUED response (8 seeds per story, no instances/rewards).
   Then `server phase3 evaluate heir-legacy 2` and
   `server phase3 evaluate phase-three-catalog-reader 1`; require structured API
   ALREADY_REGISTERED/newSeeds=0. `stage boot1-explicit-queued`.
9. `server stop`; wait for actual server exit 0 and session exit 0. Capture
   `boot1-final --archive-log` using the external inspector.

## Boot two: automatic join, rejected reload and three real completions

1. Start boot 2, wait READY/Done; `stage boot2-offline` before clients. Registration
   remains pinned with no new instances.
2. `clients start`; wait all four actual SPAWN; `server phase3 seed`. Run only `facts`
   for A/B/C/D and wait for all responses. `stage boot2-joined` **before any instance
   query or story GUI**. Require A/C/D to have A2+B (six new instances); B still waits
   on old active A1, with neither new receipt consumed.
3. `server phase3 runtime before-bad`; `stage boot2-before-bad-reload`.
   `server phase3 bad-config`; wait for BAD_CONFIG_WRITTEN_NOT_RELOADED.
   `server chronicle reload`; require actual same-version conflict failure.
   `server phase3 runtime bad-reload`; `stage boot2-bad-reload`.
   Catalog and actual enabled fingerprints must match before-bad.
4. `server phase3 restore-config`; `server chronicle reload`; wait success.
   `server phase3 runtime restored`; `stage boot2-restored`.
5. Actual GUI sequence for each of A, C, D (controller open-story waits for real windows):

   ```text
   client open-story A 旧都档案·第二卷
   client assert-choices A annotate
   client inspect A
   client choice A annotate
   client open-story A 旧档中的版本坐标
   client assert-choices A testimony
   client inspect A
   client choice A testimony
   ```

   C's reader choice is `witness`; D's is `reconcile`. Keep B's original A1 untouched.
   Inspect all four using `server phase3 inspect A` etc. Require A/C/D honor 22 and
   B honor 15, with B's new registrations still waiting. `stage boot2-completed`.
6. `client quit`, observe actual 0; `server stop`, observe actual 0. Capture
   `boot2-final --archive-log` externally.

## Boot three: old retired GUI, new version and stable replay

1. Start boot 3, wait READY/Done; `stage boot3-offline`.
   `clients start`, actual SPAWN, `server phase3 seed`, facts A/B/C/D only; wait replies.
   `stage boot3-joined`. B's old A1 still active and its two new receipts unconsumed.
2. Actual B old GUI:

   ```text
   client open-story B 旧都档案：每个人的那一票
   client assert-choices B reconcile,testimony
   client inspect B
   client choice B testimony
   ```

   Wait completion and returned real menu. Run `server phase3 facts B`; the old
   retired reference now returns testimony. `stage boot3-old-b-completed`.
3. B completes A2 `annotate`, then reader `testimony`, using actual open-story,
   assert-choices, inspect and choice actions as above. Require honor exactly 22.
4. Retain actual current history/evidence/fate GUI components:
   `client chat A /chronicle history`, `client wait-window A 世界编年史`, `client inspect A`,
   `client click A 0`, wait for history evidence, `client inspect A`.
   Optionally open `/chronicle fate` and inspect the real honor display.
   `server phase3 inspect A` (B/C/D too); `server phase3 runtime boot3-final`;
   `stage boot3-completed`.
5. Repeat both exact console `chronicle evaluate <ENDING> ...` commands; require
   ALREADY_REGISTERED, no new seeds. Repeat structured `phase3 evaluate` for both.
   `server phase3 replay ending` and `server phase3 replay discovery`; wait actual duplicates.
   For every actual player, run:

   ```text
   server phase3 complete-replay A heir-legacy 2 annotate
   server phase3 complete-replay A phase-three-catalog-reader 1 testimony
   ```

   Use B testimony, C witness, D reconcile for the reader calls. Wait for eight actual
   IllegalStateException COMPLETED_REJECTED records. `stage boot3-replayed`.
6. `client quit`, actual 0; `server stop`, actual 0. Capture `boot3-final --archive-log`.

## Complete report

Only after all three actual server/controller exits and all 22 stages:

```text
python tools/report-phase-three-paper.py --run-dir <RUN> --expected-sha <FULL_SHA>
```

The reporter fails closed on missing evidence and evaluates boolean checks over actual
SQLite rows, Java runtime/catalog fingerprints, native identities, GUI packets, and
observed process exits. A passing report is published at
`docs/verification/phase-three-paper-summary.json` and `phase-three-paper-report.md`,
with prefixed raw stage evidence and sanitized full server logs. The completed frozen
run passes 43 checks, including third-boot-scoped console/API retries, both source
duplicates, all eight completed-choice rejections, and the exact new event types with
each original player-country pairing. `phase-three-paper-preparation.json` retains
the source hashes and real compile attempts. All preflight/compile logs and the exact
new tool sources, including both bounded recovery scripts and the Attach agent, are
published as `phase-three-paper-*` evidence. `phase-three-paper-evidence-inventory.json`
records SHA-256 values for the untouched source files, all raw run evidence, current
tool sources and published evidence; it excludes itself to avoid a self-hash cycle.
It does not substitute
JUnit counts or old artifact reports for this actual run. Release the exclusive JVM
window only after checking all owned processes have actually exited.
