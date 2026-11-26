# NPC integrations — source audit and lifecycle contract

The exact Java 21 FancyNpcs 2.10.1 artifact now has [isolated Paper combination evidence](PRODUCTION-COMBINATION-TEST-REPORT.md), including real clicks, cancellation, restart and offline maintenance. The source audit below remains separate from that tested artifact.

StarChronicle has optional, interaction-only adapters. No NPC dependency is shaded, required to compile, or instantiated on absence. Relationships remain StarChronicle facts and storyline outcomes. Existing third-party NPCs are never spawned, renamed, removed or otherwise owned by this plugin.

## Verified providers

| Provider | Observed event and stable identity | Compatibility evidence |
| --- | --- | --- |
| FancyNpcs | `de.oliver.fancynpcs.api.events.NpcInteractEvent`: `getPlayer()`, `getNpc().getData().getId()` | FancyPlugins commit `23cdba171b66c7d7eae242f8c27bba6b75f884b1`; version file `2.12.1+383`; plugin Gradle declares `foliaSupported = true`. Current API build uses release 25 / Folia API 1.21.11. Do not assume this newest artifact can run on a Java 21 server. |
| Citizens | `net.citizensnpcs.api.event.NPCRightClickEvent`: `getClicker()`, `getNPC().getUniqueId()` | Citizens2 commit `e81fdf7f0027f023d7e22e5ea0746b4601ae87f1`: plugin descriptor declares `folia-supported: true`. Older statements that Citizens never supports Folia are no longer reliable. |
| Adyeshach | `ink.ptms.adyeshach.core.event.AdyeshachEntityInteractEvent`: `getPlayer()`, `getEntity().getUniqueId()` | Branch 2.1 commit `e3a9a09d8c1840dcca74fa72799aad944fab35a3`; `EntityBase.uniqueId` is a stable String, while `id` is a mutable name. Includes FoliaSupport and FoliaEntityTicker. Do not mix its new core API with old `ink.ptms.adyeshach.api.*` classes. |

The adapters inspect event classes and methods through the provider instance's class loader when enabled, and register normal Bukkit listeners at MONITOR with `ignoreCancelled=true`. A bound, authorized interaction becomes an observed `NPC_INTERACT` event: `npc.id` is the logical catalog profile, `npc.binding` is `provider:stable-id`, and `time_basis=OBSERVED`. The timestamp is captured at the interaction callback, before entity or state-worker queuing. IDs must be nonblank Strings or UUIDs, at most 256 characters; nulls and arbitrary objects never become bindings. Unbound interactions can open the story menu after the same player permission check, but create no NPC history.

Each installed bridge has an instance-specific lease. Start is idempotent; close is terminal. Disable unregisters and invalidates only that exact provider instance, so a delayed disable from an old instance cannot remove a replacement. Queued observations remain invalid after re-enable. A failed optional API lookup or partial listener registration cleans up that bridge and leaves other providers available. The bridge never uses another plugin's class loader to borrow a missing API.

The event's exact Player reference is checked on its owning scheduler before reading context. A disconnect or replacement before that callback rejects the observation. `starchronicle.use` is required, including when the player has administrator permission. The bridge returns this capture directly rather than reading it back through the shared UUID cache. Once the owner snapshot is obtained, its immutable context is retained; an audience refresh cannot replace it with a new login or nation. The state worker checks the provider lease and plugin shutdown flag again immediately before admission. A callback already admitted may finish; disabling a provider does not roll back committed history. Owned memory-site veterans use the same observation path; refreshing memory-site configuration invalidates their pending observations.

GUI work uses player schedulers. Observation-driven NPC views and unbound-story fallbacks carry the original player and provider lease through their asynchronous query and recheck both at final display, including query-error messages. Disabling a provider unregisters only its bridge listeners; StarChronicle close unregisters all its bridge listeners. Missing/incompatible APIs produce a warning while the core history engine continues running. The legacy two-argument callback constructor remains available; external callers using it must manage their own downstream queuing and player-session checks.

This is source-level API compatibility, not a claim of completed end-to-end testing on all provider/server combinations. Install the correct Java/Minecraft/provider build and run the manual matrix in TEST-REPORT before production.

The 2026-11-26 regression tests use explicitly labeled API-shaped doubles, real Bukkit `RegisteredListener`/`HandlerList`, and queued entity/state callbacks. They are not copies of installed provider binaries. Current build counts, retained failures, and fingerprints are in [the lifecycle source report](NPC-LIFECYCLE-SOURCE-20261126.md). This interaction path is not a durable third-party outbox: it cannot recover clicks lost before admission or prove events that occurred while the plugin was disabled. Protection checks rely on cancellation observed by the MONITOR callback; actual provider/protection order and later nonconforming listeners still require the target server matrix.

## Official sources

- FancyNpcs event: https://github.com/FancyInnovations/FancyPlugins/blob/23cdba171b66c7d7eae242f8c27bba6b75f884b1/plugins/fancynpcs-v2/fn-v2-api/src/main/java/de/oliver/fancynpcs/api/events/NpcInteractEvent.java
- FancyNpcs managers: https://github.com/FancyInnovations/FancyPlugins/blob/23cdba171b66c7d7eae242f8c27bba6b75f884b1/plugins/fancynpcs-v2/fn-v2-api/src/main/java/de/oliver/fancynpcs/api/FancyNpcsPlugin.java
- FancyNpcs build/Folia declaration: https://github.com/FancyInnovations/FancyPlugins/blob/23cdba171b66c7d7eae242f8c27bba6b75f884b1/plugins/fancynpcs-v2/build.gradle.kts
- FancyNpcs API Java baseline: https://github.com/FancyInnovations/FancyPlugins/blob/23cdba171b66c7d7eae242f8c27bba6b75f884b1/plugins/fancynpcs-v2/fn-v2-api/build.gradle.kts
- Citizens descriptor: https://github.com/CitizensDev/Citizens2/blob/e81fdf7f0027f023d7e22e5ea0746b4601ae87f1/main/src/main/resources/plugin.yml
- Citizens API: https://github.com/CitizensDev/CitizensAPI/blob/master/src/main/java/net/citizensnpcs/api/event/NPCRightClickEvent.java
- Adyeshach event: https://github.com/TabooLib/adyeshach/blob/e3a9a09d8c1840dcca74fa72799aad944fab35a3/project/common/src/main/kotlin/ink/ptms/adyeshach/core/event/AdyeshachEntityInteractEvent.kt
- Adyeshach stable identity: https://github.com/TabooLib/adyeshach/blob/e3a9a09d8c1840dcca74fa72799aad944fab35a3/project/common/src/main/kotlin/ink/ptms/adyeshach/core/entity/EntityBase.kt
- Adyeshach region scheduling: https://github.com/TabooLib/adyeshach/blob/e3a9a09d8c1840dcca74fa72799aad944fab35a3/project/common/src/main/kotlin/ink/ptms/adyeshach/core/util/FoliaSupport.kt
