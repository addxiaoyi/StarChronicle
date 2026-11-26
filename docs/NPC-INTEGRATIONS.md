# NPC integrations — source audit 2026-11-19

StarChronicle has optional, interaction-only adapters. No NPC dependency is shaded, required to compile, or instantiated on absence. Relationships remain StarChronicle facts and storyline outcomes. Existing third-party NPCs are never spawned, renamed, removed or otherwise owned by this plugin.

## Verified providers

| Provider | Observed event and stable identity | Compatibility evidence |
| --- | --- | --- |
| FancyNpcs | `de.oliver.fancynpcs.api.events.NpcInteractEvent`: `getPlayer()`, `getNpc().getData().getId()` | FancyPlugins commit `23cdba171b66c7d7eae242f8c27bba6b75f884b1`; version file `2.12.1+383`; plugin Gradle declares `foliaSupported = true`. Current API build uses release 25 / Folia API 1.21.11. Do not assume this newest artifact can run on a Java 21 server. |
| Citizens | `net.citizensnpcs.api.event.NPCRightClickEvent`: `getClicker()`, `getNPC().getUniqueId()` | Citizens2 commit `e81fdf7f0027f023d7e22e5ea0746b4601ae87f1`: plugin descriptor declares `folia-supported: true`. Older statements that Citizens never supports Folia are no longer reliable. |
| Adyeshach | `ink.ptms.adyeshach.core.event.AdyeshachEntityInteractEvent`: `getPlayer()`, `getEntity().getUniqueId()` | Branch 2.1 commit `e3a9a09d8c1840dcca74fa72799aad944fab35a3`; `EntityBase.uniqueId` is a stable String, while `id` is a mutable name. Includes FoliaSupport and FoliaEntityTicker. Do not mix its new core API with old `ink.ptms.adyeshach.api.*` classes. |

The adapters inspect event classes and methods when providers are enabled and register normal Bukkit listeners at MONITOR with `ignoreCancelled=true`. An uncancelled interaction becomes an evidenced `NPC_INTERACT` event with the configured logical `npc.id` and the original `npc.binding=provider:stable-id`. Narrative GUI actions and context reads are dispatched to the player's entity scheduler. Disabling a provider unregisters only its bridge listeners; StarChronicle disable unregisters all its bridge listeners. Missing/incompatible APIs produce a warning while the core history engine continues running.

This is source-level API compatibility, not a claim of completed end-to-end testing on all provider/server combinations. Install the correct Java/Minecraft/provider build and run the manual matrix in TEST-REPORT before production.

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
