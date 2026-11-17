package no.aura.os.v2.shell

/**
 * Every destination the shell can show. [implemented] = one of the seven approved-mockup pages; the rest are minimal secondary demo views
 * (Services, All Apps, Search, Settings, Profile, Saved, Library) built from the shared components.
 */
enum class Route(val title: String, val implemented: Boolean = false) {
    HOME("Home", implemented = true),
    MOVIES("Movies", true), TV_SHOWS("TV Shows", true), SPORTS("Sports", true), LIVE_TV("Live TV", true),
    AURABOX("AuraBox", true), AURAAMP("AuraAmp", true), AURATUBE("AuraTube"), SERVICES("Services"),
    ALL_APPS("All Apps"), NOTIFICATIONS("Notifications"), SEARCH("Search"), SETTINGS("Settings"), PROFILE("Profile"),
    SAVED("Favorites / Watchlist"), LIBRARY("Library"),
}

/** Optional modules. Disabled modules disappear from navigation (no empty or broken destinations). Production values come from real enablement state. */
data class ModuleFlags(
    val sports: Boolean = true, val liveTv: Boolean = true,
    val auraBox: Boolean = true, val auraAmp: Boolean = true, val auraTube: Boolean = true, val services: Boolean = true,
)

/** Pure navigation structure: `Home Movies TV Shows Sports Live TV | AuraBox AuraAmp AuraTube Services`, then the right-hand actions. */
object NavModel {
    /** Only the current destination is selected; module pages never inherit Home or TV selection. */
    fun isSelected(destination: Route, current: Route): Boolean = destination == current

    val rightActions: List<Route> = listOf(Route.SEARCH, Route.NOTIFICATIONS, Route.SETTINGS, Route.PROFILE)
    val rail: List<Route> = listOf(Route.HOME, Route.AURAAMP, Route.AURATUBE, Route.SAVED, Route.ALL_APPS)
    const val MAX_SHORTCUTS = 5
    /** Main navigation destinations that get an icon in the "Icons + text" style. */
    val iconRoutes: Set<Route> = setOf(Route.HOME, Route.MOVIES, Route.TV_SHOWS, Route.SPORTS, Route.LIVE_TV, Route.AURABOX, Route.AURAAMP, Route.AURATUBE, Route.SERVICES)

    /** Navigation groups (rendered with a divider between groups). Home is always first. */
    fun groups(flags: ModuleFlags): List<List<Route>> {
        val media = listOfNotNull(Route.HOME, Route.MOVIES, Route.TV_SHOWS, Route.SPORTS.takeIf { flags.sports }, Route.LIVE_TV.takeIf { flags.liveTv })
        val modules = emptyList<Route>() // AuraBox is a toggleable engine, not a duplicate destination; music/video apps live in the icon rail
        return listOf(media, modules).filter { it.isNotEmpty() }
    }
    fun items(flags: ModuleFlags): List<Route> = groups(flags).flatten()
    /** Routes reachable with these flags (navigation + right actions + rail). */
    fun reachable(flags: ModuleFlags): Set<Route> = (items(flags) + rightActions + rail.filter { when(it) { Route.AURAAMP -> flags.auraAmp; Route.AURATUBE -> flags.auraTube; else -> true } }).toSet()
}

/**
 * The three official top-navigation styles (Settings → General → Navigation style). Icon-only navigation is intentionally not a style.
 * One shared item model ([NavItemModel]) feeds a single renderer; the style only decides which items carry an icon.
 */
enum class NavStyle(val label: String) {
    TEXT_ONLY("Text only"), ICONS_TEXT("Icons + text"), AURA_APPS_ICONS("Aura apps: icons + text");

    fun showsIcon(route: Route): Boolean = false // Owner contract: all destination labels text only.
    companion object { fun parse(name: String?): NavStyle = values().firstOrNull { it.name == name } ?: TEXT_ONLY }
}

/** An installed external streaming app offered as an icon-only shortcut. [label] is for accessibility only; it is never drawn. */
data class AppShortcut(val packageName: String, val label: String)

sealed interface NavItemModel {
    data class Destination(val route: Route, val showIcon: Boolean) : NavItemModel
    data class App(val shortcut: AppShortcut) : NavItemModel
}

/** Persisted navigation style (device-wide, ordinary preference: no Administrator PIN needed). */
interface NavStyleStore { fun load(): NavStyle; fun save(style: NavStyle) }
class MemoryNavStyleStore(private var style: NavStyle = NavStyle.TEXT_ONLY) : NavStyleStore {
    override fun load() = style
    override fun save(style: NavStyle) { this.style = style }
}

/**
 * Navigation layout for a style. When AuraBox, AuraAmp and AuraTube are ALL disabled, up to [MAX_SHORTCUTS] installed external streaming
 * apps replace that module area (icon only); the core destinations (Home, Movies, TV Shows, Sports, Live TV, Services) always remain.
 */
fun NavModel.layout(flags: ModuleFlags, style: NavStyle, shortcuts: List<AppShortcut>): List<List<NavItemModel>> {
    val media = groups(flags).first().map<Route, NavItemModel> { NavItemModel.Destination(it, style.showsIcon(it)) }
    // The divider separates built-in destinations from installed media apps. Keep only actual installed launchables.
    val external = shortcuts.take(MAX_SHORTCUTS).map<AppShortcut, NavItemModel> { NavItemModel.App(it) }
    return if (external.isEmpty()) listOf(media) else listOf(media, external)
}
