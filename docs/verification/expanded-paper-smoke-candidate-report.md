# Expanded Paper / real StarCore candidate evidence

This run verifies the listed capabilities on the candidate artifact and records an unfinished-native-session regression. It is **not final release acceptance** and cannot cover a subsequently changed JAR.

- Tested UTC interval: 2026-11-19 16:41–16:54 (2026-11-20 00:41–00:54 Asia/Shanghai).
- Paper 1.21.11 build 132, Java 21.0.13, real audited StarCore f769e627 baseline.
- Only `127.0.0.1:25579`, isolated `run/expanded-paper-smoke`, fresh world and databases. Two actual mineflayer offline loopback clients, both non-operators.
- Nation creation, technology unlock and founder disband use the real public services. The fixture grants explicit Bukkit permission attachments for the invitation test and never edits either plugin database or supplies a fabricated `PlayerContext`.
- The ancient-heir test signal is explicitly labeled test-only, with actual connected player UUIDs and service-read national membership. The invitation definition is isolated test YAML populated with real nation IDs.
- Tested plugin SHA-256: `a644eaf835bc08b62aad096da078198d55ac8fcb8ad864d22eeec3868963e39a`.
- Server console `stop` completed world saving and the observed process exit code was `0`. Both client batches quit normally.

## Checked capabilities

- artifact manifest matches tested jar: PASS.
- server stopped normally exit zero: PASS.
- two actual non op nation founders: PASS.
- all three native scenes auto registered: PASS.
- both native sessions started at actual arrival: PASS.
- gui selected distinct native branches: PASS.
- native sessions reached departure and ended: PASS.
- source replay was deduplicated: PASS.
- shared campaign completed with two members: PASS.
- exactly one shared restoration history: PASS.
- per player honor and npc relationships persist: PASS.
- npc global growth applied once: PASS.
- no authority outcome when permission denied: PASS.
- one native invitation outcome committed: PASS.
- same admitted outcome coalesced after commit: PASS.
- two native foundations imported: PASS.
- two real unlocks observed without fake actor: PASS.
- real disband observed once without fake time: PASS.
- observation journal fully acked: PASS.
- real native npc fate history and evidence gui packets: PASS.
- readonly database integrity ok: PASS.
- normal stop preserved committed events: PASS.
- no chronicle runtime warning or collision: PASS.

## Reproduced regression

After the first two native scenes completed normally, a second real technology unlock triggered a separate test story referencing the already automatically installed `chronicle_siege_embers` script. The actual native session was visible at `witness`. Both clients then disconnected normally. StarCore logged the unfinished session destroyed with `saved=false`; after the same UUID reconnected, `ChronicleService.scene` returned empty. This is retained as defect evidence. It is not reclassified as passing restoration, and the fixture did not restart the scene or rewrite native state to hide it.

The root implementation is being revised separately for deferred audience handling and safe native continuation. Any new artifact requires its own fingerprints and affected-path verification.

## Evidence

- `expanded-paper-smoke-candidate.log`: sanitized actual server log; complete credential-related lines are redacted.
- `expanded-paper-clients.log`: real login, commands, inventory windows, item components, GUI clicks and normal disconnect/reconnect packets. Protocol evidence is not a claim of human visual inspection.
- `expanded-paper-smoke-denied.json`: read-only SQLite capture after the denied choice; no authority outcome exists.
- `expanded-paper-smoke-permitted.json`: read-only capture after actual invitation, showing the unique DONE outcome.
- `expanded-paper-smoke-before-stop.json` and `expanded-paper-smoke-candidate.json`: committed event/fact/instance/outcome/journal evidence and integrity checks.
- `tools/prepare-expanded-paper-smoke.py`, `ExpandedPaperFixture.java`, `expanded-paper-clients.cjs`, `inspect-expanded-paper-smoke.py` reproduce the isolated test setup and evidence capture.

## Boundaries

This candidate run does not test a whole server restart, actual war combat, third-party NPC plugin packets, designated-capital authority, real money/item rewards, or AI services. Those must not be inferred from these results. NPC state and all descriptions worked without an AI provider. Independent upstream/platform warnings include Windows dungeon-directory durability failure, optional ProtocolLib absence, native-profiler fallback, offline-mode notices and leaderboard refresh timing. They remain in the sanitized evidence; they did not block the verified national/narrative services.
