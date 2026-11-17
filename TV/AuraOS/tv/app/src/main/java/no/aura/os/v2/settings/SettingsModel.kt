package no.aura.os.v2.settings

import no.aura.os.v2.admin.AdminGate
import no.aura.os.v2.admin.AdminStatus
import no.aura.os.v2.admin.MemoryAdminPinStore
import no.aura.os.v2.data.DataMode
import no.aura.os.v2.identity.GoogleIdentityProvider
import no.aura.os.v2.identity.UnavailableGoogleProvider
import no.aura.os.v2.profiles.ProfileService
import no.aura.os.v2.profiles.ProfileState
import no.aura.os.v2.profiles.MemoryProfileStore
import no.aura.os.v2.shell.ModuleFlags

enum class SettingsCategory(val title: String) { GENERAL("General"), MODULES("Modules"), AURASPORT("AuraSport"), CONNECTIONS("Connections"), USERS("Users"), STATUS("Status"), ABOUT("About") }

/** AVAILABLE = works today. COMING_LATER = visible but disabled; it never pretends to work. */
enum class Availability { AVAILABLE, COMING_LATER }
enum class ItemKind { TOGGLE, VALUE, INFO, ACTION }

data class SettingsItem(
    val id: String, val title: String, val category: SettingsCategory, val kind: ItemKind,
    val availability: Availability = Availability.AVAILABLE, val plannedIn: String? = null,
)

/** Optional modules. Disabling hides the navigation entry only; content and configuration are kept. Home is the core shell and cannot be disabled. */
enum class ModuleKey(val title: String) { AURABOX("AuraBox"), AURAAMP("AuraAmp"), AURATUBE("AuraTube"), SPORTS("Sports"), LIVE_TV("Live TV") }

data class ModulePrefs(val auraBox: Boolean = true, val auraAmp: Boolean = true, val sports: Boolean = true, val liveTv: Boolean = true, val auraTube: Boolean = true) {
    fun toFlags() = ModuleFlags(sports = sports, liveTv = liveTv, auraBox = auraBox, auraAmp = auraAmp, auraTube = auraTube)
    fun isEnabled(m: ModuleKey) = when (m) { ModuleKey.AURABOX -> auraBox; ModuleKey.AURAAMP -> auraAmp; ModuleKey.SPORTS -> sports; ModuleKey.LIVE_TV -> liveTv; ModuleKey.AURATUBE -> auraTube }
    fun toggled(m: ModuleKey) = when (m) {
        ModuleKey.AURABOX -> copy(auraBox = !auraBox); ModuleKey.AURAAMP -> copy(auraAmp = !auraAmp)
        ModuleKey.SPORTS -> copy(sports = !sports); ModuleKey.LIVE_TV -> copy(liveTv = !liveTv); ModuleKey.AURATUBE -> copy(auraTube = !auraTube)
    }
}

/** Module switches are per profile: nothing is shared between profiles. */
interface ModulePrefsStore { fun load(profileId: String): ModulePrefs; fun save(profileId: String, prefs: ModulePrefs); fun clear(profileId: String) }
class MemoryModulePrefsStore : ModulePrefsStore {
    private val map = mutableMapOf<String, ModulePrefs>()
    override fun load(profileId: String) = map[profileId] ?: ModulePrefs()
    override fun save(profileId: String, prefs: ModulePrefs) { map[profileId] = prefs }
    override fun clear(profileId: String) { map.remove(profileId) }
}

/** Platform information read through interfaces so the presenter stays pure. */
data class LauncherStatus(val isDefault: Boolean?, val currentHome: String?)
interface LauncherStatusSource { fun status(): LauncherStatus }
/** [buildSha] is the exact git commit the APK was built from ("-dirty" = uncommitted changes); [buildVariant] is the Gradle build type. */
data class DeviceInfo(val versionName: String, val versionCode: Long, val model: String, val androidRelease: String, val sdk: Int, val buildSha: String = "unknown", val buildVariant: String = "unknown")
interface DeviceInfoSource { fun info(): DeviceInfo }

object UnknownLauncherStatus : LauncherStatusSource { override fun status() = LauncherStatus(null, null) }
object UnknownDeviceInfo : DeviceInfoSource { override fun info() = DeviceInfo("unknown", 0, "unknown", "unknown", 0) }

/** Everything Settings needs from the outside. Defaults are inert so the demo and tests need no Android. */
class SettingsDeps(
    val admin: AdminGate = AdminGate(MemoryAdminPinStore()),
    val modules: ModulePrefsStore = MemoryModulePrefsStore(), val profiles: ProfileService = ProfileService(MemoryProfileStore(), authorizer = admin),
    val connections: ConnectionRegistry = NotConfiguredConnections,
    val launcher: LauncherStatusSource = UnknownLauncherStatus, val device: DeviceInfoSource = UnknownDeviceInfo,
    val google: GoogleIdentityProvider = UnavailableGoogleProvider,
)

data class SettingsState(
    val modules: ModulePrefs, val mode: DataMode, val realModeAvailable: Boolean, val launcher: LauncherStatus, val device: DeviceInfo,
    val connections: List<ConnectionStatus>, val profiles: ProfileState = ProfileState.initial(), val admin: AdminStatus = AdminStatus.NOT_SET_UP,
    val navStyle: no.aura.os.v2.shell.NavStyle = no.aura.os.v2.shell.NavStyle.TEXT_ONLY,
    val playback: no.aura.os.v2.playback.PlaybackPrefs = no.aura.os.v2.playback.PlaybackPrefs(),
    val simklAutoSync: Boolean = false, val simklSyncStatus: String = "Connect Simkl to enable autosync",
)
