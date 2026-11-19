# Proposed designated-capital authority contract

This document describes a future upstream contract. It is not a claim that the audited StarCore implements it, and no new national mutation is registered by Chronicle on its basis. Until an actual durable source exists, Chronicle's native capital scope remains incomplete; a trusted committed `SnapshotProvider` may supply an independent capital scope.

## Existing evidence and current integration

Audited commit `f769e62799e769a18fe51305b545869a17f80cd1` exposes `module.city.CityService` queries but no durable designated-capital ID. Legacy `city.CityManager.getNationCapital` selects the currently highest-level city, and `Nation.capitalLocation()` is cached/derived data. Neither can establish who authorized a move, when it committed, its stable operation ID, or whether a change in the computed location actually was a migration.

The implemented `CAPITALS` snapshot SPI therefore expects a complete detached map of `nationId → designated cityId`. A known existing city's value changing to another known value produces `CAPITAL_MOVED` with an observation sequence, before/after city IDs and explicitly unknown exact actor/time. It does not call country setters, infer a capital from city level or use a mutable Bukkit `Location`. See `HISTORY-OBSERVATION.md`.

## Smallest useful upstream read contract

A future public service needs an immutable designated-capital record containing:

* The authoritative nation UUID and durable city UUID, not display names or coordinates.
* A monotonically increasing per-nation designation revision persisted with the city ID.
* The most recent committed operation UUID and its actual commit time, canonicalized to the precision that persistence supports.
* A read-only full-scope snapshot with explicit completeness/failure status, so a temporarily unavailable city or nation service cannot look like a successful empty world.

The getter must read committed designation state under the same transaction/monitor as native mutation. Optional absence must mean no designated capital, rather than an unavailable read. A bulk snapshot should expose one consistent authority revision or otherwise document its read boundary. City membership/ownership comes from the same authority; the caller must not choose the highest-level city as a replacement for missing designation state.

When upgrading old worlds, the initial designation must be explicitly migrated/confirmed through an authoritative administrative process. Importing a baseline must not fabricate historical relocation time or attribute the present monarch as an ancient actor. A deleted capital or dissolved nation becoming absent is not itself a move to another city.

## Minimum committed event and replay contract

An upstream post-commit relocation record should include a stable operation UUID, nation UUID, previous designated city UUID, new designated city UUID, revision, actual committed timestamp and the authorized actor UUID when known. System/migration actors must be labeled explicitly rather than substituted with an arbitrary online player. The record is a committed result; pre-operation cancellable proposals are not historical proof.

The capital designation, revision and durable operation/outbox record must commit together in the **upstream authority's** transaction. Publish the event only after commit and provide a replayable committed-operation query/outbox. Replaying an operation ID must return an identical immutable payload. Two later moves with the same city pair require different operation IDs/revisions. Display names, current rulers and other mutable facts must not change the fingerprint of an already recorded operation.

This stronger replay source matters because snapshots can only observe the states they actually see. If a server observes `A`, misses committed moves `A → B → C`, then observes `C`, the current snapshot journal can honestly report an observed `A → C` difference. It cannot reconstruct the unseen intermediate `B` move or exact mutation times. A native committed outbox could supply both real operations instead.

## Mutation boundary for a later implementation

Any future relocation operation must remain in StarCore's authority service and atomically recheck its actual actor permission policy, live nation membership, target-city ownership and relocation preconditions. A plot, NPC, AI provider or cached `player.role` must not be a source of authorization. If an external operation ID is supported, its receipt must be persisted with the actual native change; a Chronicle-only receipt cannot make two different databases one transaction.

This document does not define or expose a YAML effect that performs such a move. Once a real upstream implementation is audited and tested, Chronicle can consume its read/event contract through the existing history extension points. Current native collection intentionally stays incomplete until then.
