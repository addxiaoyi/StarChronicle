# Standalone Folia: copied national history and default denial

This is a procedure, not an acceptance report. Only the explicit `compile` command invokes
javac; preparation/check/inspection do not start Java or a server. The coordinator owns all
server/client sessions and the serial JVM window.

Candidate: `792a84e345c4478b3731e7bff426c30d1aa21e1f1be499a967ed3e7a4aaa4694`.
Destination: this worktree's `run/folia-national-792a84e3`; loopback port `25604`.
Sources: the normally stopped `run/paper-national-792a84e3/checkpoints/boot3/chronicle.db`
and its exact two accepted story files. The original four clients are PhaseTwoA/B/C/D.

## Preparation and compile

```text
python tools/prepare-national-council-folia.py prepare --expected-sha FULL_SHA
python tools/prepare-national-council-folia.py check --expected-sha FULL_SHA
```

Preparation requires all three real Paper server/client exits to be recorded as zero,
checkpoint3 to exist and have no nonempty WAL/journal, an intact schema5 DB, the exact
candidate JAR, and unchanged source story hashes. It refuses an existing destination.
The DB is copied byte-for-byte. Source files/sidecars are hashed before/after, while a
read-only immutable SQLite transaction captures the source baseline and actual counts,
revisions, frozen result, membership and honor. It does not rewrite old event JSON or
invent historical actors/countries. The stopped-copy provenance limitation is retained.

Only Folia, StarChronicle and the test fixture are installed. There is no StarCore, copied
native state, replacement national authority, copied world/player data or OP list. Folia
uses a fresh flat world; original offline UUIDs identify the copied Chronicle members.
The default capacity is twelve: four simultaneous clients need headroom for pending login
connections, rather than a capacity set to exactly four. A failed admission run is retained
as a preflight; change only the stopped owned runtime's capacity, never reset the database.
Default story installation and NPC hooks are disabled. The real two national stories are
copied unchanged, not regenerated with new canonical content.

In the coordinator's explicit JVM window:

```text
python tools/prepare-national-council-folia.py compile --expected-sha FULL_SHA --java-home JAVA21
```

This builds only `NationalCouncilFoliaFixture.jar`. Compile attempts and actual exits are
retained; a compiled JAR is never replaced. `preparation.json` exposes `plugin_sha256`,
`fixture_sha256`, `server_port`, `players` and `expected_states` for the coordinator's
Node/session tools. No subcommand starts Folia. Actual launch must keep persistent TTY
stdin, UTF-8 output, and wait for normal client and server exits separately.

## Before and after database snapshots

Before server start (or before clients, using a consistent actual read transaction):

```text
python tools/prepare-national-council-folia.py inspect --expected-sha FULL_SHA --label before-clients
```

Repeat as `after-actions` and, after actual client exit0 plus console `stop` and actual
server exit0, as `final-stopped`. Every label is append-only/refuse-existing. Protected
tables are compared exactly with the Paper baseline: four national vote/evidence tables,
instances, personal/shared choice log, rewards/outcomes/facts, canonical history/causes
and definition/catalog evidence. Inspection reports only database invariants; it cannot
claim GUI/protocol/process acceptance on its own. Retain full actual process/client logs.

## Actual Folia sequence

1. Start the coordinator's Folia session on `127.0.0.1:25604`. Require actual server `Done`
   and `NATIONAL_FOLIA_READY`. The fixture itself rejects StarCore, extra plugin/authority
   registrations, wrong product SHA or execution outside the prepared Folia directory.
2. Connect the four real original clients. LOWEST join permission attachments run on each
   actual entity owner and grant only `starchronicle.use=true`, `starchronicle.admin=false`.
   No OP, rank, country, PlayerContext, source event or reward is manufactured.
3. Console fixture commands (prefix `server` only when the coordinator session routes it):

   ```text
   ncfolia seed
   ncfolia instances A
   ncfolia state A primary
   ncfolia state A invalidation
   ncfolia probe A
   ```

   Repeat `instances/state/state/probe` for B/C/D. Wait for actual responses before
   dependent actions. Four identities must match the manifest. Both state queries must
   show fresh `viewerNationId=""`, revision0 and false representative/canVote/canWithdraw,
   while every copied country's original count/revision/status/actor/evidence remains exact.
   Primary's frozen result stays readable. The unfinished instance stays unfinished;
   merely lacking a native authority must not invalidate its old country ballots.
4. Use each actual client's `/chronicle national INSTANCE_ID`, where IDs come from
   `preparation.json.expected_states.primary.instanceId` and `.invalidation.instanceId`.
   Wait for a real `国家议会` inventory, retain full client inventory packets/components,
   and call `ncfolia view A` on the open page. It must display no verified current country,
   revision0, the original projection/result, and spectator/frozen text. It must expose no
   country-choice or withdrawal action. Clicking slot47 opens the real `已记录国家票`
   ledger: retain its original country UUIDs, revisions, status, actor and authority lore.
   The fixture's inventory access executes only on that player's Folia entity owner.
5. Submit actual direct-player `/chronicle vote INSTANCE_ID council 0 yes` and
   `/chronicle withdraw INSTANCE_ID council 0` attempts on the unfinished story. Retain
   real denial messages. Also exercise the trusted API boundary through the fixture:

   ```text
   ncfolia reject-vote A invalidation yes 0
   ncfolia reject-withdraw A invalidation 0
   ```

   Repeat for all four clients, and optionally primary. The fixture accepts only an actual
   SecurityException from the native-authority boundary; a completed-node/stale-token/SQL
   error is not relabeled as the expected default denial. No provider is registered by it.
6. Repeat both state queries and `probe` for A-D after attempts. `probe` requires the
   original world resolution and unchanged honor, while current-nation history,
   `past.nation.choice`, personal national ballots and NATION-scope resolutions stay
   absent for these fresh blank-country identities. No old country is supplied by the
   fixture. Inspect before/after database invariants.
7. Open actual `/chronicle history`; retain `世界编年史`, click an actual saved ending,
   and retain `历史证据`. This page's original countries/participants are historical
   evidence, not proof the viewer currently belongs to one of them.
8. Quit all clients, observe actual controller exit0, then console `stop`, observe actual
   server/launcher exit0. Capture `final-stopped`, compare with the Paper baseline, run
   `check`, and archive sanitized full server logs plus raw process/API/client evidence.

`NATIONAL_FOLIA_FAILED`, `ACTION_FAILED`, a foreign-region access, a successful vote or
withdrawal, changed protected rows, reconstructed nationality, reissued reward, or hidden
process failure is a failed acceptance. Do not erase/reset the run to make it pass.

## Command surface and boundaries

`ncfolia`: `seed | instances A | state A primary|invalidation | probe A | view A |
reject-vote A invalidation yes REV | reject-withdraw A invalidation REV | phase LABEL`.
The fixture command is console-only. It reads product APIs and logs evidence; it never
opens a DB or registers any authority. Permission and GUI entity access use only
`Player.getScheduler().execute` or the owning join event. No primary Bukkit scheduler,
nonowned player access, teleport, country service mutation or automatic source replay exists.

This verifies StarChronicle's safe standalone Folia degradation over genuine Paper-produced
national receipts. It does not certify StarCore on Folia, a full-stack national authority,
combat, external quest/reward exactly-once delivery, or human visual approval of GUI packets.
