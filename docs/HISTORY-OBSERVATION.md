# Transactional history observation

The 2026-11-26 candidate adds [committed native technology replay](COMMITTED-TECHNOLOGY-HISTORY.md). When StarCore exposes that durable protocol, its contiguous log owns technology history and the fallback snapshot/research callbacks below do not emit duplicates. Initial catch-up archives old records without replaying story effects. The following snapshot contract continues to describe older authorities and the other observation scopes.

StarCore does not publish every committed national change. `SnapshotCollector` and `ObservationJournal` recover selected changes from complete, detached authority snapshots without treating missing services as an empty world. This supplements the stable-ID committed event/source bridge; it does not manufacture exact dates or actors from present state.

## Actual source coverage

| Scope | Native source | Derived history |
| --- | --- | --- |
| `starcore.nations` | Audited `NationModule.nations()`, copied under its native mutation monitor | A previously present nation that disappears produces `NATION_DESTROYED`, explicitly described as authority disappearance with unknown cause and actor. |
| `starcore.technologies` | Legacy fallback: audited `TechnologyModule.unlockedTechnologies()` plus real definition era | Used only without the durable technology log. A newly observed unlocked key produces `TECHNOLOGY_BREAKTHROUGH`; observed unlock is not necessarily research completion. |
| `starcore.capitals` | Legacy snapshot unavailable; new candidate has a separate committed capital log | The committed adapter records real actors and designated cities. Snapshot mode never guesses from city levels or cache locations. |

The collector accepts native nation/technology implementations only when their exact audited concrete class is present. Native nation membership lists are copied while holding `NationModule`'s monitor. Technology snapshots use the same monitor as synchronized unlock/revoke and their synchronous save, so a failed write's intermediate unlock is not observed. Failure to obtain a full native nation scope also makes its dependent technology scope incomplete; it never silently deletes technologies from omitted nations.

`Nation.capitalLocation()` is a mutable derived cache and `city.CityManager.getNationCapital` chooses the currently highest-level city. Neither proves a committed designated-capital migration. The registered `module.city.CityService` has no durable designated-capital getter, so the built-in snapshot scope deliberately remains unavailable. The newer candidate now exposes an independent transactional `NationService.capitalChangesAfter` authority connected through `StarCoreCapitalHistory`; see [committed capital history](COMMITTED-CAPITAL-HISTORY.md). Its built-in snapshot scope remains unavailable to avoid duplicate observations, including when the pinned source fails.

Nation foundations still use the actual available `Nation.foundedAt()` through the stable committed-source bridge, rather than assigning the installation date as the nation's founding date. War lifecycle, battle and territory outcomes use their committed upstream IDs/timestamps. The observation journal does not replace these stronger sources. Claim and split callbacks lack source timestamps and are separately marked observed by the bridge.

## Snapshot contract

`ObservationSnapshot(namespace, source, kind, complete, allowEmpty, entries, evidence)` is immutable and bounded. Each entry has a stable map key, `nationId`, current `value`, and detached string facts.

* `namespace` identifies one durable, bounded authority scope. Its `source` and `kind` cannot change after a baseline exists; use a new namespace for a different authority.
* `complete=false` never advances a baseline, even if its entries are empty.
* An empty successful scope requires `complete=true, allowEmpty=true`. This explicit assertion lets the last nation's genuine disappearance be observed while preventing an unavailable service from destroying the entire baseline.
* The first complete scope establishes a baseline and emits no changes. The collector does not know what happened before the first observation.
* Identical entries neither enqueue an event nor advance the sequence. Any actual entry change increments the scope sequence, including changes such as technology revocation that have no corresponding historical event type here.
* `NATIONS` emits only removals; `CAPITALS` emits an existing nation's designated value changing to another existing value; `TECHNOLOGIES` emits additions. Initial capital assignment or a missing capital property is not reported as a migration.

Every derived event has an immutable ID, `time_basis=OBSERVED`, namespace, sequence, entry key, before/after values, nation ID and evidence. Its participants are empty because a snapshot cannot identify the responsible player. `occurredAt` is the millisecond observation time. Evidence gives the interval between the prior baseline write and the current observation, explicitly says the exact mutation time/actor are unknown, and names the authority. Unchanged scans do not rewrite the baseline time, so this interval can be conservative rather than the narrowest possible mutation interval.

The stable ID includes the scope's durable sequence. Therefore capital `A → B → A → B`, or technology unlock → revoke → unlock, produces distinct changes even when an old value pair repeats. Reopening the same database does not reset that sequence or regenerate pending event payloads.

## Durable handoff and recovery

Two SQLite tables share the Chronicle store's transaction boundary:

* `observation_baselines` stores the namespace's current authority, sequence, observed timestamp and full immutable snapshot JSON.
* `observation_ingress` stores each derived immutable event JSON with `PENDING` or `ACKED` status.

Advancing the baseline and inserting **all** derived pending events is one SQLite transaction. If this write fails, both the old baseline and ingress remain unchanged. There is no intermediate state where a new baseline hides an event that has not been durably enqueued.

The plugin runs this flow on its serialized persistence worker:

```java
for (ObservationSnapshot snapshot : collector.collect()) {
    journal.observe(snapshot);
}
for (WorldEvent event : journal.pending(100)) {
    engine.accept(event);                 // must commit successfully first
    journal.acknowledge(event.id());      // only then acknowledge ingress
}
```

Do not call `engine.accept` from inside the observation transaction, and do not acknowledge before the consumer commits. Fetching a pending batch does not mark it sent or move a cursor. A crash before delivery leaves a pending row. A crash after the engine commits but before acknowledgement deliberately redelivers the **same immutable event**. The engine's stable event ID/fingerprint dedupe prevents a second history record or repeated campaign outcome, after which the pending row can be acknowledged. Failed consumer acceptance leaves the row pending for investigation/retry; an acknowledgement is not proof that an external national mutation occurred.

`pending(limit)` accepts 1–1000 records and returns detached immutable records. `acknowledge` changes only a currently pending row. Repeating an acknowledgement does not create history. `pendingCount` is an operational backlog count. ACKED rows remain available in the database; no time-based deletion here erases source receipts.

## Trusted capital and custom authority provider

Register an `api.SnapshotProvider` with Bukkit's services manager. Its `snapshots()` callback runs on the Chronicle persistence worker, so it must return detached reads of committed authority and must not inspect Bukkit entities, `Location` or world state from that worker. A provider may supply independent nation, technology or capital scopes with explicit evidence. Providers cannot use the reserved `starcore.*` namespace and duplicate authority namespaces are rejected.

For example, a separately implemented durable capital service can report:

```java
new ObservationSnapshot(
    "myplugin.designated-capitals", "MyCapitalService.committedCityId",
    ObservationSnapshot.Kind.CAPITALS, true, true,
    Map.of(nationId, new ObservationSnapshot.Entry(nationId, cityId, Map.of())),
    "Designated city IDs read after the capital transaction committed"
);
```

This is a source contract, not a permission bypass: the provider observes committed changes; it does not authorize or execute a migration. A trusted source that already has durable mutation IDs and exact timestamps should submit real `WorldEvent`s through `ChronicleService` instead of weakening its evidence into snapshot observations.

## Verification evidence

`ObservationJournalTest` exercises the real SQLite transaction store, reopening it to test baseline persistence, pending ingress recovery, acknowledgement persistence and accept-before-ack redelivery dedupe. Crash injection at the actual pre-commit boundary verifies rollback of both the baseline and multiple pending events. Additional cases cover unchanged scans, capital repeated transitions, technology revoke/reunlock without an upstream publisher, incomplete or unconfirmed empty snapshots, last-nation disappearance, authority namespace changes and invalid batch sizes.

`SnapshotCollectorTest` verifies unavailable native capabilities fail incomplete, a trusted designated-capital provider is accepted without guessing from caches, and provider namespace impersonation is rejected. These are deterministic contract tests; they do not claim that upstream has a missing capital getter or research publisher at runtime.
