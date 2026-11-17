package no.aura.os.v2.modules

import no.aura.os.v2.settings.ConnectionId
import no.aura.os.v2.settings.ModuleKey
import no.aura.os.v2.shell.Route

/** What a module can contribute to shared surfaces. A capability is a contract; it is only active when its feature flag says so. */
enum class ModuleCapability { HOME_SHELF, GLOBAL_SEARCH }

/** Compile-time feature flags for contracts that are declared but not implemented yet. Each flips in the task named beside it. */
object FeatureFlags {
    const val YOUTUBE_HOME_SHELF = false     // F4c
    const val YOUTUBE_GLOBAL_SEARCH = false  // F4c
}

data class ModuleDescriptor(val key: ModuleKey, val route: Route, val connection: ConnectionId?, val declared: Set<ModuleCapability>, val active: Set<ModuleCapability> = emptySet())

/** Single list of optional modules: navigation route, Settings → Connections entry and shared-surface capabilities. */
object ModuleRegistry {
    val all: List<ModuleDescriptor> = listOf(
        ModuleDescriptor(ModuleKey.AURABOX, Route.AURABOX, null, emptySet()),
        ModuleDescriptor(ModuleKey.AURAAMP, Route.AURAAMP, null, emptySet()),
        ModuleDescriptor(
            ModuleKey.AURATUBE, Route.AURATUBE, ConnectionId.YOUTUBE, setOf(ModuleCapability.HOME_SHELF, ModuleCapability.GLOBAL_SEARCH),
            active = setOfNotNull(ModuleCapability.HOME_SHELF.takeIf { FeatureFlags.YOUTUBE_HOME_SHELF }, ModuleCapability.GLOBAL_SEARCH.takeIf { FeatureFlags.YOUTUBE_GLOBAL_SEARCH }),
        ),
        ModuleDescriptor(ModuleKey.SPORTS, Route.SPORTS, null, emptySet()),
        ModuleDescriptor(ModuleKey.LIVE_TV, Route.LIVE_TV, null, emptySet()),
    )
    fun of(key: ModuleKey) = all.first { it.key == key }
}
