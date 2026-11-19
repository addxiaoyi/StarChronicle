# Phase-two standalone Folia acceptance procedure

This procedure runs only after the parent agent releases the serial JVM window,
supplies the final approved product SHA-256, and confirms Paper acceptance has
finished. Preparing these tools is not permission to compile or start a server.
The scope is **StarChronicle standalone on real Folia**, never StarCore full-stack
Folia certification. Do not edit the product JAR, its twelve bundled stories, NPC
catalog, SQLite database, or any retained old evidence to obtain a pass.

All commands below run from the repository root. Substitute the approved full
SHA-256 for `APPROVED_SHA` and its first eight characters for `SHA8`. The new run
directory must not already exist. These scripts write only their isolated runtime
and `evidence/`; they never overwrite `docs/verification` or older reports.
In the command templates, `python` means the installed interpreter; on this host
its verified bundled path is
`C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe`
because the short `python` command is not on PATH.

## Prepare after the final fingerprint arrives

```text
python tools/prepare-phase-two-folia.py --expected-sha APPROVED_SHA
```

That step copies the approved artifact, retained official Folia 1.21.11 build 14,
and the twelve unmodified default story files. It sets two region tick threads,
an isolated loopback listener on 25578, explicit standalone mode, and a 12,000-tick
(ten-minute) poll interval. It only prepares files. Once the parent explicitly
hands over the serial JVM window, compile the clearly named test-only fixture:

```text
python tools/build-phase-two-folia-fixture.py --run-dir run/phase-two-folia-SHA8 --expected-sha APPROVED_SHA --java-home C:\Users\Administrator\Documents\starworld-tools\jdk-21.0.13.1+2
```

The fixture never creates a `PlayerContext`, nation, role, reward or authority
provider. `ChronicleService` obtains the actual online players' fresh contexts.
The single test-only source is `phase-two-folia:heir-discovery-1`, type
`ANCIENT_HEIR_DISCOVERED`. Its evidence explicitly identifies an acceptance
fixture, and its original timestamp/cohort/payload are persisted for one unchanged
third-boot replay. No additional story definition or external NPC plugin is added.

## Boot one: finish the default shared story and preserve its derived inbox

```text
python tools/phase-two-folia-session.py --run-dir run/phase-two-folia-SHA8 --expected-sha APPROVED_SHA --boot 1 --java C:\Users\Administrator\Documents\starworld-tools\jdk-21.0.13.1+2\bin\java.exe
```

The launcher records the actual server PID, starts two real Minecraft protocol
clients only after the server reports `Done`, and routes input by `server` or
`client` prefix. Wait for both `SPAWN` records, then submit each console command
and wait for its success marker before continuing:

```text
server op PhaseTwoFoliaA
server phase2folia edge PhaseTwoFoliaA
server phase2folia far PhaseTwoFoliaB
server phase2folia separation PhaseTwoFoliaA
server phase2folia begin
```

Require `SEPARATION.playerOwned=true`, `otherOwned=false`, exactly two evidenced
source actors, `SOURCE.duplicate=false`, and no asserted nation. A is at block
15,15 on the chunk edge; B is over 4,000 blocks away on a different owner region.
Perform memory and GUI checks before the final shared vote:

```text
server phase2folia bystander PhaseTwoFoliaA
client chat A /chronicle memory create phase-two-live phase-two-folia:heir-discovery-1 ancient-heir
client chat A /chronicle memory create phase-two-removed phase-two-folia:heir-discovery-1 ancient-heir
server phase2folia sites PhaseTwoFoliaA
client chat A /chronicle memory remove phase-two-removed
client chat A /chronicle memory create phase-two-invalid nonexistent-phase-two-event ancient-heir
client chat B /chronicle memory create phase-two-forbidden phase-two-folia:heir-discovery-1 ancient-heir
server phase2folia sites PhaseTwoFoliaA
client chat A /chronicle history
client inspect A
server phase2folia view PhaseTwoFoliaA
client shift A 0
client select A ANCIENT_HEIR_DISCOVERED
client inspect A
server phase2folia view PhaseTwoFoliaA
client click A 48
client close A
client interact A monument
client inspect A
client close A
client interact A veteran
client inspect A
client close A
client chat A /chronicle scene
server phase2folia scene PhaseTwoFoliaA
server phase2folia sceneact PhaseTwoFoliaA
```

Wait for the asynchronous memory refreshes and actual windows before their next
dependent action. The live site must have exactly monument/interaction,
text/text-display and veteran/villager entities, the removed site's entities must
be absent, and the unrelated armor stand must remain. The invalid source warning,
non-admin denial and native scene-action rejection are intentional negative
checks. A real `BOOK` packet must be observed. The monument's evidence window
must include the exact test source ID and its explicitly test-only evidence.

Complete all three default `last-heir` quorum stages through the production API:

```text
server phase2folia choose PhaseTwoFoliaA last-heir summons convene
server phase2folia choose PhaseTwoFoliaB last-heir summons convene
server phase2folia choose PhaseTwoFoliaA last-heir council support
server phase2folia choose PhaseTwoFoliaB last-heir council support
server phase2folia choose PhaseTwoFoliaA last-heir preparations restoration
server phase2folia choose PhaseTwoFoliaB last-heir preparations restoration
```

After the last `CHOSEN` marker, **do not issue `inspect`, `stories`, another source
submission, or any API that queries instances**. GUI choice callbacks would query
stories automatically, so the final first-boot vote deliberately uses the direct
API fixture. The derived `HEIR_RESTORATION_SUPPORTED` and both `heir-legacy`
recipient inbox rows must still be pending when the server normally stops. Keep
this entire boot under ten minutes from plugin enable, since the first periodic
poll is scheduled at startup; stop and diagnose if the
periodic poll consumed the test early. Shut down in this order:

```text
client quit
server stop
```

Wait for the launcher to observe both client and server exit code 0. Capture the
stopped database using only the read-only inspector:

```text
python tools/inspect-phase-two-folia.py --run-dir run/phase-two-folia-SHA8 --expected-sha APPROVED_SHA --stage before-restart
```

Require completed `last-heir`, no `heir-legacy` instances yet, the two pending
derived recipient triggers, 15 honor for both participants, and one restoration
event. Do not write SQL to create, consume or repair a trigger.

## Boot two: real login recovery and different actual GUI choices

```text
python tools/phase-two-folia-session.py --run-dir run/phase-two-folia-SHA8 --expected-sha APPROVED_SHA --boot 2 --java C:\Users\Administrator\Documents\starworld-tools\jdk-21.0.13.1+2\bin\java.exe
```

The same two actual clients log in. Production login recovery must create their
two independent PLAYER `heir-legacy` instances from the retained derived inbox;
**never run `begin` or replay the source on this boot**. Inspect them, verify the
world/player history and actual past choices, and exercise the default UI:

Before any fixture `inspect` command or GUI that queries instances, capture the
intermediate state in a separate command invocation. This preserves evidence
that actual login recovery already created both unvoted personal instances:

```text
python tools/inspect-phase-two-folia.py --run-dir run/phase-two-folia-SHA8 --expected-sha APPROVED_SHA --stage restored --output run/phase-two-folia-SHA8/evidence/phase-two-folia-login-recovered.json
```

```text
server phase2folia inspect PhaseTwoFoliaA
server phase2folia inspect PhaseTwoFoliaB
server phase2folia probe PhaseTwoFoliaA
server phase2folia probe PhaseTwoFoliaB
client chat A /chronicle past last-heir/1/council
client chat B /chronicle past last-heir/1/preparations
client chat A /chronicle stories
client select A 旧都档案：每个人的那一票
client inspect A
server phase2folia view PhaseTwoFoliaA
client choice A testimony
client chat B /chronicle stories
client select B 旧都档案：每个人的那一票
client inspect B
server phase2folia view PhaseTwoFoliaB
client choice B reconcile
```

Wait for each window before selecting and each successful server transition before
continuing. The `choice` helper only clicks an actual open heir-legacy window with
its unique bundled label, and logs `GUI_CHOICE` independently from the database
outcome. It does not call a server API or type a choose command.

```text
server phase2folia inspect PhaseTwoFoliaA
server phase2folia inspect PhaseTwoFoliaB
server phase2folia probe PhaseTwoFoliaA
server phase2folia probe PhaseTwoFoliaB
server phase2folia npc PhaseTwoFoliaA
server phase2folia npc PhaseTwoFoliaB
server phase2folia sites PhaseTwoFoliaA
server phase2folia separation PhaseTwoFoliaA
client chat A /chronicle npc ancient-heir
client inspect A
server phase2folia view PhaseTwoFoliaA
client close A
client chat A /chronicle fate
client inspect A
server phase2folia view PhaseTwoFoliaA
client close A
client chat A /chronicle book
client chat A /chronicle past heir-legacy/1/archive
client chat B /chronicle past heir-legacy/1/archive
```

Require A's terminal node `personal`, B's `reconciled`, both honor 17 and the base
`old-capital-guardian` title, plus A's testimony title and B's archive-reader title.
The original players' council ballots are `support`, shared council resolution is
`support`, and preparations resolution is `restoration`. The supported-event
legacy/world/player count is 1 for each. Both requested national scopes must be
absent and both roles must remain `unassigned`. Ancient-heir should select
`trusted-restoration`, reputation 25, trust 12, growth 20 and council-speaker.
No role or nationality may be fabricated to make a check pass.

While the server is idle, take a consistent read-only snapshot. Then quit and
stop normally so the final snapshot can also prove no settlement changed:

```text
python tools/inspect-phase-two-folia.py --run-dir run/phase-two-folia-SHA8 --expected-sha APPROVED_SHA --stage after-complete
client quit
server stop
```

## Boot three: completed-state recovery and unchanged source replay

```text
python tools/phase-two-folia-session.py --run-dir run/phase-two-folia-SHA8 --expected-sha APPROVED_SHA --boot 3 --java C:\Users\Administrator\Documents\starworld-tools\jdk-21.0.13.1+2\bin\java.exe
```

After both actual logins, inspect/probe/NPC/site/GUI reads without casting another
vote. They must preserve completed state, 17 honor, titles, NPC state and memory
ownership. Take `restored`, replay once through the fixture using the saved
original source payload, and require `SOURCE.duplicate=true`:

```text
server phase2folia inspect PhaseTwoFoliaA
server phase2folia inspect PhaseTwoFoliaB
server phase2folia probe PhaseTwoFoliaA
server phase2folia probe PhaseTwoFoliaB
server phase2folia npc PhaseTwoFoliaA
server phase2folia sites PhaseTwoFoliaA
python tools/inspect-phase-two-folia.py --run-dir run/phase-two-folia-SHA8 --expected-sha APPROVED_SHA --stage restored
server phase2folia replay
client quit
server stop
python tools/inspect-phase-two-folia.py --run-dir run/phase-two-folia-SHA8 --expected-sha APPROVED_SHA --stage after-replay
python tools/report-phase-two-folia.py --run-dir run/phase-two-folia-SHA8 --expected-sha APPROVED_SHA
```

The `python` inspection lines run in a separate read-only command invocation, not
as routed launcher input. Compare all result tables while ignoring only the
documented trigger `checked` liveness counter. Verify ChoiceLedger's thirteen
actual rows, exact world/personal actor attribution, stable receipts, and the
five-event heir causal subtree with two additional legitimate PLAYER_JOIN roots.
The report fails closed for missing evidence, wrong hashes, unexpected process
exits, missing normal quit/stop requests, thread violations or duplicated results.

Each launcher captures real PIDs and exit codes, never supplied success values.
All three server and all three client processes must have actually exited before
the JVM window is handed back. Any external whole-process audit is the parent's
responsibility; the launcher only claims that its own observed children exited.
Report paths are printed under the isolated runtime's `evidence/` directory.
