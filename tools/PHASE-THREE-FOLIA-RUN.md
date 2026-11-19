# Phase-three Folia retired-version acceptance

Only run Java after the parent explicitly hands over the serial JVM window. The
approved product is `bfa80b9ca62b1f28fdd37a2f1d7244b9b94cb8c34814273fda742ff8c6f28154`.
All phase-two files and original source database/worlds remain unchanged.
The immediate-recovery acceptance uses the independent runtime
`run/phase-three-folia-bfa80b9c-r2`; do not run preparation again over this name.

The earlier `run/phase-three-folia-bfa80b9c` remains untouched after two normal
boots. Its test v2 retained the production seven-day same-story cooldown, which
correctly blocked immediate re-entry despite a true login permission. Its full
database, worlds, configurations, logs and failure snapshot remain preserved;
copied evidence is in `docs/verification/phase-three-folia-cooldown-attempt/`.
The r2 runtime was copied afresh from the unchanged phase-two source, with only
test v2 authored as `cooldownSeconds: 0` before its first definition acceptance.
Never edit the already archived v2 definition or database in the earlier run.
Use the r2 path in the commands below for the observed three-boot sequence.

Use the bundled Python at
`C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe`
in place of `python` below. Set `PYTHONIOENCODING=UTF-8` in the calling environment;
the session also explicitly configures UTF-8 streams with replacement handlers.
The selected JDK may be supplied with `--java-home` and `--java`; the verified
example path below is the actual JDK used for phase two.

## Prepared source and current definitions

The source is the normally stopped real phase-two run, product `d765e0d1...943b`.
Preparation opens its fully checkpointed schema-three database with
`mode=ro&immutable=1`, uses SQLite backup into a new database, and verifies the
original main database and WAL/SHM state before/after. The complete source rows
are retained in `evidence/phase-three-folia-source-baseline.json`. Thirteen old
choice rows, seventeen old outcome rows and three completed old instances are
never rewritten, deleted or reconstructed.

The same actual offline accounts **PhaseTwoFoliaA** and **PhaseTwoFoliaB** retain
their real UUIDs, world/player files, old testimony/reconcile choices, honor 17,
NPC relationships and owned memory. New player names must not be substituted.

Current content consists of the twelve default story IDs with heir-legacy
explicitly authored as test version 2, plus `phase-three-archive/1`. Version 1 is
retired from files and is migrated from real pinned snapshots. Default story
installation is false to avoid reinstalling the old file. B reads the exact
`past.player.choice.heir-legacy/1/archive`; v2 requires completed
`past.player.choice.phase-three-archive/1/desk`. B's history results naturally
queue v2. Only test definitions in the new runtime change; no production resource
or old definition archive is edited.

Because a GUI vote automatically refreshes the story list, a ten-minute poll
alone cannot keep a newly derived story pending. Version 2's starting memory
effect therefore explicitly requires real permission `phase-three.v2.ready`.
The test fixture installs a **real Bukkit permission attachment**, false on boot
one and true on boots two/three, at LOWEST PlayerJoinEvent priority on the actual
owner region before the production NORMAL-priority fresh snapshot. It supplies
no PlayerContext, history fact, identity, nation or reward.

## Build only after JVM handoff

```text
python tools/build-phase-three-folia-fixture.py --run-dir run/phase-three-folia-bfa80b9c-r2 --expected-sha bfa80b9ca62b1f28fdd37a2f1d7244b9b94cb8c34814273fda742ff8c6f28154 --java-home C:\Users\Administrator\Documents\starworld-tools\jdk-21.0.13.1+2
```

For each boot start this launcher, changing only `--boot 1`, `2`, `3`:

The orchestration `exec_command` invocation **must use `tty: true`**, as the
successful phase-two runs did. If input ends, the launcher treats EOF as a setup
failure and immediately enters normal owned-child cleanup instead of waiting or
leaving a server orphan. Preserve that failure evidence rather than relabelling
it a successful acceptance boot.

```text
python tools/phase-three-folia-session.py --run-dir run/phase-three-folia-bfa80b9c-r2 --expected-sha bfa80b9ca62b1f28fdd37a2f1d7244b9b94cb8c34814273fda742ff8c6f28154 --boot 1 --java C:\Users\Administrator\Documents\starworld-tools\jdk-21.0.13.1+2\bin\java.exe
```

Server and protocol-client commands use `server` and `client` prefixes. Clients
**do not automatically start** at Done; a bare `clients` line is an explicit
operator acknowledgement. The launcher records actual PIDs, UTC times, hashes and
exits; it does not accept invented success codes. On unexpected errors it attempts
normal client quit/server stop for its own Popen children, records the failure,
and only kills after a bounded wait. Any forced cleanup prevents a passing report.

## Boot one: offline historical registration, then the real B GUI

Wait for Done with zero online players. In a separate read-only invocation:

```text
python tools/inspect-phase-three-folia.py --run-dir run/phase-three-folia-bfa80b9c-r2 --expected-sha bfa80b9ca62b1f28fdd37a2f1d7244b9b94cb8c34814273fda742ff8c6f28154 --stage migrated-offline
```

Require schema 4, the same old game rows/rewards and a real retired first-edition
definition in story_definitions. Then route only this console command:

```text
server phase3folia evaluate
```

Wait for `PHASE_THREE_EVALUATED` with online 0/status QUEUED. This registers only
the real saved canonical ending:
`chronicle:5ad9905c3336c82bef3538e5b469a5ae23e6031566a1d9f3af9d4ed07a78b492`.
It never submits a new event. Take the mandatory subsequent snapshot:

```text
python tools/inspect-phase-three-folia.py --run-dir run/phase-three-folia-bfa80b9c-r2 --expected-sha bfa80b9ca62b1f28fdd37a2f1d7244b9b94cb8c34814273fda742ff8c6f28154 --stage offline-evaluated
```

Require two B inbox rows, still only the three old completed instances,
seventeen outcomes and honor 17. Now type the bare launcher acknowledgement:

```text
clients
```

The launcher refuses this until it has observed the successful offline callback
and the matching snapshot. Its process metadata proves API callback < snapshot <
operator client-start acknowledgement < actual login timestamps with UTC
subsecond values. This avoids relying on same-second server log inference.
After both real logins and LOWEST/owned/granted=false gate records, capture
`--stage b-recovered` before fixture inspect or story GUI queries. Require two
personal B desk instances, no v2 instance and no new rewards.

```text
server phase3folia separation PhaseTwoFoliaA
server phase3folia sites PhaseTwoFoliaA
server phase3folia inspect PhaseTwoFoliaA
server phase3folia inspect PhaseTwoFoliaB
server phase3folia probe PhaseTwoFoliaA
server phase3folia probe PhaseTwoFoliaB
client chat A /chronicle past heir-legacy/1/archive
client chat B /chronicle past heir-legacy/1/archive
client chat A /chronicle stories
client chat B /chronicle stories
```

Wait for actual windows, then:

```text
client select A Phase Three Archive Review
client select B Phase Three Archive Review
```

After both story windows arrive, capture `client inspect A/B`, `server phase3folia
view PhaseTwoFoliaA/B`, and `client shift A 9`. Wait for unchangedInventory and
slotStillPresent. Complete only the actually visible allowed choices:

```text
client choice A b-preserve
client choice B b-annotate
```

Require real GUI_CHOICE records and completion messages. The production GUI's
refresh is allowed; the real false permission must keep v2 pending. Capture
`--stage after-b`: both B completed, honor 19 each, two v2 derived inboxes and no
v2 instances. No live SQL or manual reward/choice repair is permitted.
Quit then stop normally, waiting for the observed client exit before stop:

```text
client quit
server stop
```

## Boot two: true permission and derived v2 login recovery

Start launcher `--boot 2`, wait for Done, type bare `clients`. Require actual
LOWEST/owner/granted=true records. Before any instance query take
`--stage v2-recovered`: two unvoted v2 archive instances sourced from each B's
derived history, completed B proof, honor still 19. Do not evaluate again.

```text
server phase3folia separation PhaseTwoFoliaA
server phase3folia sites PhaseTwoFoliaA
client chat A /chronicle stories
client chat B /chronicle stories
client select A Phase Three Legacy v2
client select B Phase Three Legacy v2
```

Wait for actual story windows and inspect them on the real player owner, then:

```text
client choice A v2-testimony
client choice B v2-reconcile
server phase3folia probe PhaseTwoFoliaA
server phase3folia probe PhaseTwoFoliaB
client chat A /chronicle npc ancient-heir
```

Wait for the NPC window; inspect and close before `/chronicle fate`, then inspect
and close before `/chronicle history` and `/chronicle book`. Run
`/chronicle past phase-three-archive/1/desk` and
`/chronicle past heir-legacy/2/archive` for both players. Do not physically click
the memory veteran during this phase; that correctly creates another observed
NPC_INTERACT source, which would be unrelated to the retained-result comparison.
Honor must be 21 and first-edition personal decisions remain A testimony/B
reconcile. Capture `--stage after-complete`, then normal `client quit`/`server stop`.

## Boot three: restore completed states and repeat the natural key

Start `--boot 3`, acknowledge `clients`, and query both instance/past/fate views:

```text
server phase3folia separation PhaseTwoFoliaA
server phase3folia sites PhaseTwoFoliaA
server phase3folia inspect PhaseTwoFoliaA
server phase3folia inspect PhaseTwoFoliaB
server phase3folia probe PhaseTwoFoliaA
server phase3folia probe PhaseTwoFoliaB
server phase3folia npc PhaseTwoFoliaA
server phase3folia npc PhaseTwoFoliaB
server phase3folia scene PhaseTwoFoliaA
server phase3folia sceneact PhaseTwoFoliaA
```

Capture `--stage restored`. Repeat `server phase3folia evaluate` several times,
waiting for each callback. Each must be ALREADY_REGISTERED; it must not submit the
source or apply its old NPC reaction again. Query/reopen completed GUIs without
voting. Quit both clients normally and stop normally. Capture `--stage after-replay`.
Use the same read-only inspector command template for every named stage.

```text
python tools/report-phase-three-folia.py --run-dir run/phase-three-folia-bfa80b9c-r2 --expected-sha bfa80b9ca62b1f28fdd37a2f1d7244b9b94cb8c34814273fda742ff8c6f28154
```

The report and summary default to the new runtime/evidence directory. After all
checks pass, preserve the exact `phase-three-folia-*` evidence in
`docs/verification/`, including `phase-three-folia-summary.json` and
`phase-three-folia-report.md`, without overwriting old prefixes. Retain any setup
failure logs separately. The parent must receive the actual three server/client
exit arrays and an explicit JVM-window release only after all owned processes exit.

This is actual Folia standalone acceptance, not a StarCore full-stack claim.
Native StarCore scenes remain unavailable and action requests must be rejected.
