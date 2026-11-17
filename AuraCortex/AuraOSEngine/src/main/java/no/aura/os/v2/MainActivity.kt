package no.aura.os.v2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import no.aura.os.v2.apps.AppsService
import no.aura.os.v2.apps.PackageManagerApps
import no.aura.os.v2.apps.PrefsModeStore
import no.aura.os.v2.demo.DemoRepositoryImpl
import android.os.SystemClock
import no.aura.os.v2.admin.AdminGate
import no.aura.os.v2.admin.AndroidBootId
import no.aura.os.v2.admin.PrefsAdminPinStore
import no.aura.os.v2.profiles.PrefsProfileStore
import no.aura.os.v2.settings.DevConnections
import no.aura.os.v2.youtube.YouTubeConfig
import no.aura.os.v2.images.AuraImageLoader
import no.aura.os.v2.shell.PrefsNavStyleStore
import no.aura.os.v2.playback.PlaybackService
import no.aura.os.v2.playback.PrefsPlaybackStore
import no.aura.os.v2.playback.UltraState
import no.aura.os.v2.ultramax.KeystoreUltraCodeStore
import no.aura.os.v2.ultramax.UltraMaxClient
import no.aura.os.v2.ultramax.UrlConnectionUltraHttp
import no.aura.os.v2.tmdb.CachingTmdbRepository
import no.aura.os.v2.tmdb.RealCatalogService
import no.aura.os.v2.tmdb.TmdbConfig
import no.aura.os.v2.tmdb.UrlConnectionTmdbHttp
import no.aura.os.v2.profiles.ProfileService
import no.aura.os.v2.settings.AndroidSettingsSources
import no.aura.os.v2.settings.PrefsModulePrefsStore
import no.aura.os.v2.settings.SettingsDeps
import no.aura.os.v2.shell.AuraShell
import no.aura.os.v2.theme.AuraTheme
import no.aura.os.v2.theme.DesignCanvas
import no.aura.os.v2.diag.Diag

/**
 * Entry point. Demo Mode (default) uses [DemoRepositoryImpl] fixtures. Real Mode (opt-in in Settings) is limited to installed-app discovery
 * and launch in V2-F1; every other page stays on the labelled demo content. [resumeTick] refreshes detection and restores focus after
 * returning from a launched app.
 */
class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)
    private var admin: AdminGate? = null
    private var f6hRuntime: no.aura.os.v2.integration.F6hRuntime? = null

    private val homeRequest = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { resumeTick++ }
    private val tvListingsRequest = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { resumeTick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        Diag.enabled = BuildConfig.DIAG     // debug + the private perf build; release never records or logs
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF) // HLS URLs may contain private query credentials.
        super.onCreate(savedInstanceState)
        Diag.section("startup.onCreate") { create() }
    }

    private fun create() {
        val runtime = no.aura.os.v2.integration.F6hRuntime(this, { homeRequest.launch(it) }, { tvListingsRequest.launch(no.aura.os.v2.watch.SystemContinueWatching.READ_TV_LISTINGS) }, androidx.lifecycle.ViewModelProvider(this)[no.aura.os.v2.simkl.SimklPairingMemory::class.java]).also { f6hRuntime = it; it.acceptResume(intent) }
        val pm = Diag.section("startup.packageManager") { PackageManagerApps(applicationContext) }
        val mediaRecents = no.aura.os.v2.apps.RecentMediaShortcuts(applicationContext)
        val sportFeed = no.aura.os.v2.sports.SportScoreFeed()
        val sportsPrefs = no.aura.os.v2.sports.SportPreferences(applicationContext)
        val apps = AppsService(pm, pm, ownPackage = packageName)
        val modeStore = Diag.section("startup.prefs.mode") { PrefsModeStore(applicationContext) }
        val sources = AndroidSettingsSources(applicationContext)
        // Monotonic clock (counts deep sleep) + boot id: lockouts and sessions never depend on the editable wall clock.
        val bootIds = AndroidBootId(applicationContext)
        val gate = Diag.section("startup.adminGate") { AdminGate(PrefsAdminPinStore(applicationContext), elapsed = SystemClock::elapsedRealtime, bootId = bootIds::current) }
        admin = gate
        // BuildConfig.YOUTUBE_API_KEY is empty unless a developer put it in git-ignored local.properties (debug builds only).
        // One TMDB repository for every page. BuildConfig.TMDB_READ_TOKEN is empty unless a developer put it in git-ignored local.properties
        // (debug builds only); empty means "not configured" and nothing is requested. The token is never logged or displayed.
        val youtubeFeed = no.aura.os.v2.youtube.YouTubePublicFeed(YouTubeConfig(BuildConfig.YOUTUBE_API_KEY))
        val tmdbConfig = TmdbConfig(BuildConfig.TMDB_READ_TOKEN)
        val tmdbRepo = CachingTmdbRepository(UrlConnectionTmdbHttp(tmdbConfig), tmdbConfig)
        val progress = no.aura.os.v2.watch.PrefsPlaybackProgress(applicationContext)
        val simklClient = no.aura.os.v2.simkl.SimklV2Client(BuildConfig.SIMKL_CLIENT_ID)
        val simklTokens = no.aura.os.v2.simkl.SimklProfileTokens(applicationContext)
        val simklRuntime = no.aura.os.v2.simkl.SimklRuntimeState()
        Diag.gauge("display.widthPx", resources.displayMetrics.widthPixels.toLong()); Diag.gauge("display.heightPx", resources.displayMetrics.heightPixels.toLong())
        Diag.gauge("display.densityDpi", resources.displayMetrics.densityDpi.toLong())
        val images = Diag.section("startup.imageLoader") { AuraImageLoader(java.io.File(cacheDir, "tmdb-images"), applicationContext) }
        // UltraMax (the F5 playback source). The 8-character code lives encrypted in the Keystore-backed store; the state is only what a real request proved.
        val ultraCodes = KeystoreUltraCodeStore(applicationContext)
        val ultra = java.util.concurrent.atomic.AtomicReference<UltraState>(if (Diag.section("startup.keystore.isSet") { ultraCodes.isSet }) UltraState.Unverified else UltraState.NoCode)
        val playback = PlaybackService(UltraMaxClient(UrlConnectionUltraHttp()), ultraCodes, gate).also { it.onState = { st -> ultra.set(st) } }
        val profileService = Diag.section("startup.profiles.load") { ProfileService(PrefsProfileStore(applicationContext), authorizer = gate) }
        // Plex / Jellyfin / Emby sign-ins: encrypted per profile; "Connected" only after a verification in this process.
        val serverIdentity = no.aura.os.v2.mediaserver.ClientIdentity(no.aura.os.v2.mediaserver.KeystoreMediaAccountStore.deviceId(applicationContext), BuildConfig.VERSION_NAME)
        val mediaHttp = no.aura.os.v2.mediaserver.UrlConnectionMediaHttp()
        val plexKeys = no.aura.os.v2.mediaserver.KeystorePlexDeviceKeys(serverIdentity.deviceId, { gate.isAuthorized() }, { profileService.state.activeId }, { id -> profileService.state.profiles.any { it.id == id } })
        val mediaServers = no.aura.os.v2.mediaserver.MediaServerService(
            no.aura.os.v2.mediaserver.KeystoreMediaAccountStore(applicationContext), mediaHttp, serverIdentity,
            canBrowse = { id -> profileService.state.profiles.any { it.id == id && !it.isChild } },
            modernPlex = { id, create -> plexKeys.open(id, create)?.let { no.aura.os.v2.mediaserver.PlexDeviceJwtClient(mediaHttp, it) } },
            forgetModernPlex = plexKeys::forget, purgeModernPlex = plexKeys::purgeDeletedProfile,
            mayManage = { id -> profileService.state.activeId == id && gate.isAuthorized() })
        val catalog = RealCatalogService(tmdbRepo, watch = no.aura.os.v2.integration.MergedWatchProgress(progress, mediaServers, profileService), art = no.aura.os.v2.tmdb.ArtSizes.forDisplayWidth(resources.displayMetrics.widthPixels))
        val settingsDeps = SettingsDeps(
            admin = gate, modules = PrefsModulePrefsStore(applicationContext), profiles = profileService,
            connections = DevConnections(YouTubeConfig(BuildConfig.YOUTUBE_API_KEY), youtubeVerifiedAt = { youtubeFeed.lastVerifiedAtMs }, simklConfigured = simklClient.configured, simklVerifiedAt = { simklRuntime.verifiedAt(profileService.state.activeId) }, auramusicConnected = { com.aura.connect.reciever.SystemPairingManager.isDeviceConnected(applicationContext) }, media = { id -> mediaServers.status(profileService.state.activeId, id) }, tmdbConfigured = tmdbConfig.isConfigured, tmdbVerifiedAt = { tmdbRepo.lastSuccessMs }, ultra = { ultra.get() }), launcher = sources, device = sources,
        )
        val navStyle = Diag.section("startup.prefs.nav") { PrefsNavStyleStore(applicationContext) }
        val playbackPrefs = Diag.section("startup.prefs.playback") { PrefsPlaybackStore(applicationContext) }
        val watchStore = Diag.section("startup.prefs.watch") { no.aura.os.v2.watch.PrefsWatchStore(applicationContext) }
        Diag.section("startup.setContent") { content(apps, modeStore, settingsDeps, catalog, tmdbRepo, images, navStyle, pm, playback, playbackPrefs, watchStore, progress, youtubeFeed, mediaRecents, simklClient, simklTokens, simklRuntime, sportFeed, sportsPrefs, mediaServers, runtime) }
    }

    private fun content(
        apps: AppsService, modeStore: PrefsModeStore, settingsDeps: SettingsDeps, catalog: RealCatalogService, tmdbRepo: CachingTmdbRepository, images: AuraImageLoader,
        navStyle: PrefsNavStyleStore, pm: PackageManagerApps, playback: PlaybackService, playbackPrefs: PrefsPlaybackStore, watchStore: no.aura.os.v2.watch.PrefsWatchStore, progress: no.aura.os.v2.watch.PrefsPlaybackProgress, youtubeFeed: no.aura.os.v2.youtube.YouTubePublicFeed, mediaRecents: no.aura.os.v2.apps.RecentMediaShortcuts,
        simklClient: no.aura.os.v2.simkl.SimklV2Client, simklTokens: no.aura.os.v2.simkl.SimklProfileTokens, simklRuntime: no.aura.os.v2.simkl.SimklRuntimeState, sportFeed: no.aura.os.v2.sports.SportScoreFeed, sportsPrefs: no.aura.os.v2.sports.SportPreferences,
        mediaServers: no.aura.os.v2.mediaserver.MediaServerService,
        runtime: no.aura.os.v2.integration.F6hRuntime,
    ) {
        setContent { androidx.compose.runtime.LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; Diag.milestone("firstComposeFrame") }
             AuraTheme { DesignCanvas { AuraShell(DemoRepositoryImpl, onExit = { finish() }, runtime = runtime, mediaServers = mediaServers, apps = apps, modeStore = modeStore, resumeTick = resumeTick, settingsDeps = settingsDeps, tmdb = catalog, tmdbVerifiedAt = { tmdbRepo.lastSuccessMs }, images = images, navStyleStore = navStyle, icons = pm, playback = playback, playbackStore = playbackPrefs, mediaRecents = mediaRecents, sportFeed = sportFeed, sportsPrefs = sportsPrefs, simklClient = simklClient, simklTokens = simklTokens, simklRuntime = simklRuntime, watch = watchStore, progress = progress, youtubeFeed = youtubeFeed, trailer = pm::openTrailer) } } }
    }

    /** Leaving the app ends any Administrator session. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        f6hRuntime?.acceptResume(intent)
    }

    /** Home-shortcut crash guard: AuraOS stayed in the foreground long enough to count as a working start. */
    private val confirmStableStart = Runnable { no.aura.os.v2.launcher.HomeShortcut(applicationContext).confirmStable() }

    override fun onPause() {
        window.decorView.removeCallbacks(confirmStableStart)
        f6hRuntime?.detach()
        super.onPause()
    }

    override fun onStop() {
        admin?.revoke()
        if (Diag.enabled) Diag.summary().forEach { android.util.Log.d("AuraDiag", it) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        window.decorView.postDelayed(confirmStableStart, no.aura.os.v2.launcher.HomeShortcutCrashGuard.STABLE_AFTER_MS)
        resumeTick++
    }
}
