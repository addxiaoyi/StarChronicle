# Phase-two Paper / real StarCore verification

Result: **PASS** for the exact tested JAR below. This report is generated only after every required read-only snapshot, protocol log, native service assertion and observed process exit passes.

- SHA-256: `d765e0d14b9a9122e0ba1304c9c1b0d3ef844d4458295b1f4847ecb213bd943b`.
- Fresh isolated `run/phase-two-paper-release-smoke`; Paper 1.21.11 build 132, Java 21, audited real StarCore baseline.
- Listener `127.0.0.1:25589`; query/RCON disabled; all clients remained non-operators.
- Actual server exit codes: `[0, 0, 0]`; actual client controller exit codes: `[0, 0, 0]`.

## Verified checks

- all snapshots and copied jar match frozen artifact: PASS.
- published stories and npc file match jar resources: PASS.
- three actual server processes stopped normally: PASS.
- three actual client controllers exit normally: PASS.
- four actual non operator founders and four distinct nations: PASS.
- source and ending preserve exact four historical members: PASS.
- real epic api votes keep dissent and unvoted member distinct: PASS.
- ending queues pinned followup before any personal instance: PASS.
- first restart keeps choice rows and source causes: PASS.
- four real logins recover four individual followups: PASS.
- automatic join recovery is captured before fixture instance queries: PASS.
- late client only connects after the first boot: PASS.
- late membership is committed by real native service: PASS.
- late citizen reads world and national history but not personal choice: PASS.
- late citizen gets no personal story reward or fabricated actor: PASS.
- dissent and support select actual default npc dialogues: PASS.
- actual gui exposes only eligible archive choices: PASS.
- actual api rejects voter witness and unvoted testimony: PASS.
- three actual gui branches complete with correct personal history: PASS.
- three completed archives award 17 and unfinished b stays 15: PASS.
- actual story permission denial has no authority outcome: PASS.
- explicit actual permission allows one done native invitation: PASS.
- already committed authority outcome coalesces in same boot: PASS.
- native a really advances and recovers its own checkpoint: PASS.
- native a completes through gui and never restarts on boot3: PASS.
- native start outcome ids do not replay: PASS.
- third boot and source replay do not duplicate stories choices rewards: PASS.
- real past command npc fate story and history gui packets: PASS.
- schema3 integrity and foreign keys pass at every stage: PASS.
- no uncertain native or causal blocks are hidden: PASS.
- no unhandled fixture failure source collision or chronicle runtime warning: PASS.

## Durable narrative evidence

Four real native national founders completed the shipped WORLD epic. D’s actual opposing council ballot remained separate from the supporting world resolution; C had no council ballot. The server was normally stopped after the actual ending was committed and its PLAYER archive triggers were durable, before any archive instance was activated. No ending event was resubmitted to make the recovery work. After restart, four real logins recovered four personal archives from that original ending.

E joined later and was added to A’s actual nation through NationService.addMember. Its persisted native membership and fresh recollections API responses proved access to the relevant world decision and national history count, while the personal ending count remained zero and no personal archive or reward was fabricated.

Actual GUI branches completed A’s testimony, C’s witness account and D’s reconciliation. Their histories each retain the correct single actor and the cause path last-heir -> heir-legacy rooted at the original discovery receipt. B’s untouched archive remains active with honor 15; the other three retain honor 17 and their distinct titles. A’s actual native session was advanced, normally quit, recovered after restart and actually completed through its GUI. A third normal boot and replay of only the original source did not repeat stories, choices or outcomes.

A separate isolated story first rejected the actual founder without its explicit alliance permission and created no authority outcome. After a real player permission attachment, the formal story choice committed one authority request, and the real StarCore service created one pending invitation while the relationship remained NEUTRAL. Only after confirming that mutation did the same already-admitted outcome return retained APPLIED in the same boot. This does not claim provider in-memory coalescing is a cross-restart exactly-once contract.

## Evidence and limits

- `phase-two-paper-*.json`: read-only Chronicle tables, causal traces, choice logs, native nation properties and integrity checks.
- `phase-two-paper-boot1-final.log`, `boot2-final.log`, `boot3-final.log`: sanitized actual server logs.
- `phase-two-paper-clients.log`: actual clients, late E connection, inventory components, visible choice guards, commands and successful clicks.
- `tools/PHASE-TWO-PAPER-RUN.md`: operator sequence and the boot-one no-drain boundary.

The discovery and authority signals are explicitly isolated test receipts, not naturally occurring production-world discoveries. Protocol GUI evidence does not claim human visual acceptance. This run does not establish full Folia StarCore support, external quest/reward exactly-once delivery, real combat or other national authority operations. Older reports remain tied to their own artifact fingerprints and are not substituted for this run.


## Retained fixture timing observation

The boot-two log retains one premature invite-choose command issued before the asynchronous invite-begin operation completed. The fixture could not yet find an invitation instance and caught NoSuchElementException; this is not counted as a production permission rejection. After PHASE2_INVITE_SUBMITTED, a separate valid choice produced the required SecurityException denial and no authority outcome. The subsequent grant, real service delivery and coalescing assertions use that independently verified sequence. No database or product state was reset to remove the earlier observation.
