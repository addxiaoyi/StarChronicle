# Final Paper / real StarCore acceptance procedure

This procedure uses a fresh isolated run and the final build fingerprint. Do not reuse the candidate server database or present the candidate report as final-JAR coverage. Start only during the root coordinator's assigned JVM window, with at most one server process.

Prepare after the final `clean verify` succeeds:

```text
python tools/prepare-expanded-paper-smoke.py --run-name expanded-paper-release-smoke
```

The preparation tool copies the verified Paper and real StarCore baseline, the final product JAR and test-only fixture to a new directory. It records artifact hashes, binds only `127.0.0.1:25579`, uses new world/plugin databases and refuses to overwrite a run. The fixture is not shipped in the production plugin. Every national mutation below uses an actual public authority service and actual connected player UUIDs; no database writes or fabricated player contexts are permitted.

Run from `run/expanded-paper-release-smoke` using the assigned JDK:

```text
java -Xms256M -Xmx768M -XX:MaxMetaspaceSize=256M -Dfile.encoding=UTF-8 -jar paper.jar --nogui
```

Run actual clients from the repository root, retaining the same append-only client log across reconnects:

```text
node tools/expanded-paper-clients.cjs expanded-paper-final-clients.log
```

The client controller accepts `chat A /chronicle scene`, `click A 9`, `close A`, `inspect A`, and `quit A`. The `quit` action normally disconnects both clients and exits; restart the same command to reconnect the same real offline-mode UUIDs.

## Stage 1: normal disconnect resumes the real native node

1. Wait for the server `Done`/`EXPANDED_READY` markers and both actual `SPAWN` messages. On the server console, run `expanded phase STAGE1`, `expanded seed`, then `expanded begin`. Confirm the fixture reports real founder membership for both non-operator clients and all three native scenes were automatically installed by the product.
2. Query `expanded scene A` and `expanded scene B`: both must be `chronicle_last_heir/arrival`. Through the client controller, open `/chronicle scene` on both clients. Mark `expanded phase GUI_REFRESH_POLL_OVERLAP`, submit 20 pairs of concurrent `expanded scene A/B` refreshes while the one-second background poll remains enabled, and concurrently select A slot 9 and B slot 10 through the real clients. Require the first legal clicks to advance without a busy rejection, then mark `expanded phase GUI_REFRESH_POLL_OVERLAP_DONE`. Query again: actual native nodes must be `evidence` and `civilians`. `expanded native-state A/B` records the real engine routing and owned collection state. Capture read-only `final-branch-before-quit` evidence with the inspector and `--run-name expanded-paper-release-smoke`.
3. Normally quit both clients while these nodes are unfinished. Reconnect the same two clients, then query `expanded scene A/B` and `expanded native-state A/B`. The script and node must match the pre-quit checkpoint; merely showing a saved presentation is insufficient. Capture `final-branch-after-reconnect` read-only evidence.
4. Open each real native GUI and click slot 31. Both native sessions must actually advance to `departure`. Leave the departure nodes unfinished for Stage 2. The retained collection state must not multiply and foreign state must not be reset.
5. Submit `expanded begin` again while the original source audience still exists. It must report duplicate history, preserve the existing shared campaign ID/membership, and not replace or restart either native session.

## Stage 2: pending offline source and unfinished native sessions survive server restart

1. Run `expanded offline-arm` while both actual players are connected. This captures their service-verified UUIDs/nations and writes a separate test WORLD story **without a native script**, so its recovery cannot mask the unfinished main native-session restoration. Wait for successful story reload.
2. Normally quit both clients. On the console run `expanded offline-submit` once, then `expanded offline-state`. The source must be durably recorded without inventing an online audience. Capture read-only evidence:

   ```text
   python tools/inspect-expanded-paper-smoke.py final-before-restart --run-name expanded-paper-release-smoke
   ```

3. Issue normal console `stop`, wait for world saving and the actual process exit code, and archive the first-boot log before starting again:

   ```text
   python tools/inspect-expanded-paper-smoke.py final-first --run-name expanded-paper-release-smoke --archive-log
   ```

4. Restart the same server with the exact same JARs and databases. Before clients reconnect, run `expanded phase STAGE2_RESTART` and capture `final-restarted-offline` evidence. The original source/history IDs and committed local rewards must be unchanged; an offline deferred source must not be falsely acknowledged as an exhausted historical trigger.
5. Reconnect A and B. Run `expanded offline-state`: there must be exactly one shared recovered instance, original source `expanded:offline-source:1`, and two real members. The second login must retain eligibility even if the first player recovered the instance first. Capture `final-recovered` evidence.
6. Query `expanded scene A/B`: both original native sessions must recover at `departure`. Reopen each GUI and click slot 31 to actually finish through the native engine. Require both `getSession == null` and native routing `false` (`expanded native-state A/B`), and corresponding durable native journal completion. Capture `final-native-completed` evidence. No `UNKNOWN` entry may be reset or blindly replayed to make this pass.
7. Finish the separate shared offline story with `expanded offline-vote A` and `expanded offline-vote B`. The same instance must complete with two members; the permanent source event remains one row.

## Stage 3: shared outcomes, permission boundary and actual national services

1. Finish the main shared campaign via actual API snapshots, one vote per real actor at each stage:

   ```text
   expanded vote A convene
   expanded vote B convene
   expanded vote A support
   expanded vote B support
   expanded vote A restoration
   expanded vote B restoration
   ```

   Wait for each quorum transition before sending the next pair. Verify one restoration history record, each player's honor 15, NPC reputation 25/trust 12, and global NPC growth 20. Use actual client commands to open NPC, fate, world history, personal history and event evidence windows; retain protocol windows/components and clicks in the client log.

2. Run `expanded seed` again only to explicitly restore permission attachments on the reconnected, still-real non-operator clients; existing native nations must be reused. Run `expanded invite-config`, wait for reload, then `expanded invite-begin` and `expanded invite-choose`. The missing explicit native alliance permission must reject the choice before authority delivery. Capture `final-denied` read-only evidence showing no authority outcome.
3. Run `expanded allow`, then `expanded invite-choose` again. After the native service returns, run `expanded invite-check`. Require a real pending native invitation, relationship still `NEUTRAL`, one DONE authority outcome, and the same already-admitted outcome coalescing to `APPLIED` without another native mutation. Capture `final-permitted` evidence.
4. Run `expanded tech`; require an actual previously locked technology to be unlocked through `TechnologyService`, then observed once via the committed observation journal. After its baseline is confirmed, run `expanded disband` for real founder B and wait for one `NATION_DESTROYED` observation. These history entries must keep `OBSERVED` time basis, interval/evidence and unknown historical actor rather than pretending the fixture's actor was exposed by a post-commit event.
5. Inspect actual GUI/API results and the read-only database, then normally quit clients and stop the server. Record the observed process exit code. Archive `final` log/database evidence using the same inspector. Release the JVM window before any Folia verification begins.

The final report must assert the tested JAR's exact fingerprint, compare source/outcome IDs across the restart, list actual process exits, and separate any failed or uncertain result. Do not substitute the older candidate's successful paths for a changed final artifact, and do not claim human visual acceptance from protocol windows alone.

After observing both actual server exits, generate the report with their observed exit codes and the frozen artifact fingerprint:

```text
python tools/report-expanded-paper-final.py --first-exit-code 0 --final-exit-code 0 --client-exit-code 0 --client-exit-code 0 --client-exit-code 0 --run-name expanded-paper-release-smoke --expected-plugin-sha256 abaadf4c4dd318d518687bafe2596f43e32c9f9a115b4074deeae9f37abc22df
```
