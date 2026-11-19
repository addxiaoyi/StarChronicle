# Expanded Paper / real StarCore final verification

Result: **PASS** for the precise tested artifact below. Evidence comes from two real loopback protocol clients, actual public native services, read-only SQLite captures, normal client disconnects and two normally stopped server processes.

- Plugin SHA-256: `abaadf4c4dd318d518687bafe2596f43e32c9f9a115b4074deeae9f37abc22df`.
- Paper 1.21.11 build 132; Java 21.0.13; audited real StarCore f769e627 baseline.
- Fresh isolated `run/expanded-paper-release-smoke`, listener only `127.0.0.1:25579`, query/RCON disabled. Both clients were non-operators and national founders were established through real NationService calls.
- Both observed console-stop process exit codes: `0`, `0`.

## Verified checks

- all stages use identical final jar: PASS.
- both server processes exit normally: PASS.
- all three client processes exit normally: PASS.
- actual non operator clients and authoritative nations: PASS.
- native distinct branches checkpointed before quit: PASS.
- concurrent scene refresh and poll do not reject legal gui clicks: PASS.
- normal reconnect preserves native node and owned state: PASS.
- native gui actually advanced after reconnect: PASS.
- pending offline world source was durable: PASS.
- restart offline preserves source and native checkpoints: PASS.
- both logins recover one shared instance from original source: PASS.
- server restart recovers actual native departure: PASS.
- restored native sessions actually complete without unknown: PASS.
- native start outcome ids do not replay on restart: PASS.
- historical source records unchanged across restart: PASS.
- recovered shared instance completes with two real members: PASS.
- main two player campaign and local outcomes commit once: PASS.
- npc personal and global feedback persists: PASS.
- permission denial creates no authority delivery: PASS.
- actual native invitation commits one done receipt: PASS.
- native invitation same outcome returns retained applied: PASS.
- real technology and disband are observed with honest provenance: PASS.
- observation journal is acknowledged: PASS.
- protocol contains native npc fate history and evidence gui: PASS.
- all readonly integrity checks pass: PASS.
- no unknown or running native scene left: PASS.
- no chronicle source collision or runtime warning: PASS.

The three actual client controller processes exited with codes `0, 0, 0`; the append-only protocol log records six spawns and six normal disconnects without a client timeout, error, kick or failed action. The first branch actions were sent concurrently with 40 scene refresh queries while background polling remained enabled; both legal GUI actions advanced on their first attempt without a busy rejection.

## Native recovery and offline audience evidence

The first native sessions reached different real branch nodes. Normal client reconnect preserved each actor’s node and owned state; actual GUI advances then reached departure. The server was normally stopped with both departure checkpoints and one source event whose two captured real participants were offline. After the same artifact restarted, the historical source remained one immutable record. Both real logins joined one shared recovered instance, while the original native sessions resumed at departure. Further GUI actions actually removed both native sessions and active routes, and the journal recorded completion without resetting unknown effects. The two original native start outcome IDs remained unchanged.

## Authority and history evidence

An invitation choice without its explicit native permission produced no authority outcome. After a real permission attachment, StarCore created one pending native alliance invitation, leaving the relation neutral until recipient acceptance. The same admitted outcome returned its retained APPLIED result. A real technology unlock and native founder disband were separately observed with intervals, evidence and unknown historical actor; the fixture never wrote plugin databases or invented authoritative PlayerContext values.

The ancient-heir discovery and offline test source are explicitly labeled isolated test signals, not claims of natural production-world discoveries. Their actor UUIDs and nations were captured from real connected clients and native membership services.

## Reproduction and artifacts

- `tools/EXPANDED-PAPER-FINAL-RUN.md`: three-stage procedure and expected real outcomes.
- `expanded-paper-smoke-final-first.log` / `expanded-paper-smoke-final.log`: sanitized actual server logs.
- `expanded-paper-final-clients.log`: login/reconnect, real commands, GUI inventory packets/components and clicks.
- `expanded-paper-smoke-final-*.json`: read-only snapshots at branch, reconnect, offline source, restart, recovery, native completion and permission boundary stages.
- `expanded-paper-smoke-summary.json`: machine-readable assertions against this exact JAR.

## Limits

Protocol GUI verification does not claim human visual acceptance. This run does not establish real combat/boss outcomes, external money/item reward delivery, designated-capital authority, third-party NPC-provider packet compatibility or AI service availability. Independent upstream Windows/platform notices remain in the sanitized logs, including optional-plugin/profiler and dungeon-directory durability notices where emitted. The older candidate report retains the original normal-disconnect regression as historical evidence and does not replace the final-artifact checks.
