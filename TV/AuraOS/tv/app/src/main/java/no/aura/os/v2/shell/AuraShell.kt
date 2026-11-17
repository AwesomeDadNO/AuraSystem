package no.aura.os.v2.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import no.aura.os.v2.cards.auraFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.aura.os.v2.apps.AppsService
import no.aura.os.v2.apps.AppsSnapshot
import no.aura.os.v2.apps.LaunchFocusTracker
import no.aura.os.v2.apps.LaunchFeedback
import no.aura.os.v2.apps.LaunchResult
import no.aura.os.v2.apps.ModeStore
import no.aura.os.v2.data.DataMode
import no.aura.os.v2.data.DemoCatalog
import no.aura.os.v2.data.DemoRepository
import no.aura.os.v2.focus.LocalRailRequester
import no.aura.os.v2.pages.*
import no.aura.os.v2.admin.status
import no.aura.os.v2.settings.AccessPolicy
import no.aura.os.v2.settings.AdminFlow
import no.aura.os.v2.settings.ModuleSwitches
import no.aura.os.v2.settings.ModuleToggle
import no.aura.os.v2.settings.ConnectionId
import no.aura.os.v2.settings.Sheet
import no.aura.os.v2.settings.SheetOption
import no.aura.os.v2.settings.UsersFlow
import no.aura.os.v2.youtube.PlaybackHandoff
import no.aura.os.v2.settings.SettingsCatalog
import no.aura.os.v2.settings.SettingsDeps
import no.aura.os.v2.settings.SettingsPresenter
import no.aura.os.v2.settings.SettingsState
import no.aura.os.v2.settings.LauncherStatus
import no.aura.os.v2.settings.DeviceInfo
import no.aura.os.v2.overlay.*
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraDimensions
import no.aura.os.v2.theme.AuraTypography
import no.aura.os.v2.apps.AppIconSource
import no.aura.os.v2.apps.ShortcutPicker
import no.aura.os.v2.data.HeroAction
import no.aura.os.v2.data.HeroEntry
import no.aura.os.v2.data.MediaItem
import no.aura.os.v2.images.AuraImageLoader
import no.aura.os.v2.diag.Diag
import no.aura.os.v2.images.LocalAuraImageLoader
import no.aura.os.v2.tmdb.*
import no.aura.os.v2.playback.*
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * Root composable. Layers are composed bottom to top in [AuraLayer] order:
 * BASE_CONTENT (page) < INTERACTIONS (rail, then top navigation ABOVE it so the AURA wordmark is never covered) < COMPACT_PLAYER
 * < DIALOGS (own window, traps focus) < SYSTEM_OVERLAYS (Demo Mode badge).
 */
@Composable
fun AuraShell(
    repository: DemoRepository, onExit: () -> Unit,
    apps: AppsService? = null, modeStore: ModeStore? = null, resumeTick: Int = 0, settingsDeps: SettingsDeps = SettingsDeps(),
    /** Real Mode catalogue (TMDB) and artwork loader. Null = this host has no TMDB source. */
    tmdb: RealCatalogService? = null, tmdbVerifiedAt: () -> Long? = { null }, images: AuraImageLoader? = null,
    navStyleStore: NavStyleStore = MemoryNavStyleStore(), icons: AppIconSource? = null,
    /** F5: UltraMax sources + playback preferences. Null = no playback in this host. */
    playback: no.aura.os.v2.playback.PlaybackService? = null, playbackStore: no.aura.os.v2.playback.PlaybackPrefsStore = no.aura.os.v2.playback.MemoryPlaybackPrefsStore(),
    mediaRecents: no.aura.os.v2.apps.RecentMediaShortcuts? = null,
    sportFeed: no.aura.os.v2.sports.SportScoreFeed? = null,
    sportsPrefs: no.aura.os.v2.sports.SportPreferences? = null,
    /** Plex / Jellyfin / Emby sign-ins of the profiles. Null = this host has no media-server support. */
    mediaServers: no.aura.os.v2.mediaserver.MediaServerService? = null,
    runtime: no.aura.os.v2.integration.F6hRuntime? = null,
    simklClient: no.aura.os.v2.simkl.SimklV2Client? = null,
    simklTokens: no.aura.os.v2.simkl.SimklProfileTokens? = null,
    simklRuntime: no.aura.os.v2.simkl.SimklRuntimeState? = null,
    watch: no.aura.os.v2.watch.WatchStore = no.aura.os.v2.watch.MemoryWatchStore(), progress: no.aura.os.v2.watch.PrefsPlaybackProgress? = null, youtubeFeed: no.aura.os.v2.youtube.YouTubePublicFeed? = null, trailer: ((String) -> Boolean)? = null,
) {
    SideEffect { Diag.count("shell.recompose") }
    val appContext=androidx.compose.ui.platform.LocalContext.current.applicationContext
    var settingsTarget by remember { mutableStateOf<String?>(null) }
    var settingsStart by remember { mutableStateOf(no.aura.os.v2.settings.SettingsCategory.GENERAL) }
    var route by rememberSaveable { mutableStateOf(Route.HOME) }
    // Mode is read before anything else: a Real Mode TMDB page must not wait for (or build) the demo fixtures.
    var mode by remember { mutableStateOf(if (apps != null) modeStore?.load() ?: DataMode.DEMO else DataMode.DEMO) }
    val realMode = apps != null && mode == DataMode.REAL
    val tmdbOn = realMode && tmdb != null
    val activePage = when (route) { Route.HOME -> RealPage.HOME; Route.MOVIES -> RealPage.MOVIES; Route.TV_SHOWS -> RealPage.TV; Route.AURABOX -> RealPage.BOX; else -> null }
    val tmdbPage = tmdbOn && activePage != null
    val realLibraryPage = realMode && (route == Route.SAVED || route == Route.LIBRARY)
    val realYouTubePage = realMode && route == Route.AURATUBE && youtubeFeed != null
    val standalonePage = route in setOf(Route.LIVE_TV, Route.AURAAMP) || (realMode && route in setOf(Route.SEARCH, Route.NOTIFICATIONS))
    val demoPreviewPage = route == Route.SPORTS
    // Demo fixtures are built off the main thread, and only when a page that shows them is opened. The shell (navigation, rail) never waits:
    // [c] is null until they exist and the page area stays empty for that moment.
    var catalog by remember { mutableStateOf<DemoCatalog?>(null) }
    LaunchedEffect(repository, tmdbPage, realLibraryPage, realYouTubePage, demoPreviewPage, standalonePage) { if ((demoPreviewPage || (!standalonePage && !tmdbPage && !realLibraryPage && !realYouTubePage)) && catalog == null) catalog = withContext(Dispatchers.Default) { Diag.timed("demo.catalogue.load") { repository.load() } } }
    val c = catalog
    val contentReady = if (demoPreviewPage) c != null else standalonePage || tmdbPage || realLibraryPage || realYouTubePage || c != null
    val queueSize = c?.queue?.size ?: 0

    val homeUi = rememberShelfPageUiState(c?.home?.shelves?.size ?: 0)
    val moviesUi = rememberShelfPageUiState(c?.movies?.shelves?.size ?: 0)
    val tvUi = rememberShelfPageUiState(c?.tvShows?.shelves?.size ?: 0)
    val boxUi = remember { AuraBoxUiState() }; val liveUi = remember { LiveTvUiState() }; val secUi = remember { SecondaryUiState() }
    var player by remember { mutableStateOf(MiniPlayerState()) }
    var snapshot by remember { mutableStateOf<AppsSnapshot?>(null) }
    val launchTracker = remember { LaunchFocusTracker() }
    // Module switches (persisted). Disabling only hides navigation and releases the module's runtime; content and configuration are kept.
    val profiles = settingsDeps.profiles
    var profileState by remember { mutableStateOf(profiles.state) }
    var ampPlayback by remember(profileState.activeId,realMode) { mutableStateOf<no.aura.os.v2.amp.AmpPlayback?>(null) }
    val ampVault=remember { no.aura.os.v2.amp.AmpArlVault(appContext) }
    var ampCheck by remember(profileState.activeId) { mutableStateOf<Pair<no.aura.os.v2.amp.AmpVerification, Long>?>(null) }   // result + time of the last Save/Verify in this process
    var ampCredentialTick by remember { mutableIntStateOf(0) }
    val ampDemoSession = remember(profileState.activeId) { AuraAmpDemoSession() }
    val searchScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val searchUi = remember(profileState.active) { no.aura.os.v2.pages.GlobalSearchUiState(searchScroll) }
    var reduceMotion by remember { mutableStateOf(runtime?.reduceMotion() == true) }
    var centerState by remember(profileState.active) { mutableStateOf(if (profileState.active.isChild) no.aura.os.v2.notifications.CenterSnapshot() else runtime?.center?.snapshot(profileState.activeId) ?: no.aura.os.v2.notifications.CenterSnapshot()) }
    var centerTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(runtime, profileState.activeId, centerState.preferences.enabled) { runtime?.scheduleNotifications(profileState.activeId, centerState.preferences.enabled) }
    val notificationRevision = runtime?.center?.changes?.collectAsState()?.value ?: 0L
    DisposableEffect(runtime, profileState.activeId, profileState.active.isChild, resumeTick) {
        if (!profileState.active.isChild) runtime?.attach(profileState.activeId)
        else { runtime?.detach(); runtime?.center?.setSystemAccess(profileState.activeId, no.aura.os.v2.notifications.SystemAccess.UNAVAILABLE) }
        onDispose { runtime?.detach() }
    }
    LaunchedEffect(runtime, profileState.active, notificationRevision, centerTick) {
        if (runtime != null) {
            val owner = profileState.active
            val snapshot = runtime.center.snapshot(owner.id)
            centerState = if (!owner.isChild) snapshot else snapshot.copy(items = snapshot.items.filter { row ->
                if (row.event.category == no.aura.os.v2.notifications.NotificationCategory.AURA_UPDATE) true
                else {
                    val ref = TitleRef.parse("tmdb:" + row.event.destinationKey?.substringBefore(":s").orEmpty())
                    ref != null && tmdb?.details(ref, owner) is DetailsResult.Ok
                }
            })
        }
    }
    var serverAccount by remember(profileState.activeId) { mutableStateOf<no.aura.os.v2.mediaserver.ServerAccount?>(null) }
    var serverItem by remember(profileState.activeId) { mutableStateOf<no.aura.os.v2.mediaserver.MediaEntry?>(null) }
    var serverPlayback by remember(profileState.activeId) { mutableStateOf<no.aura.os.v2.mediaserver.ServerPlayback?>(null) }
    var categoryCard by remember(profileState.activeId) { mutableStateOf<no.aura.os.v2.data.CategoryCard?>(null) }
    var musicHit by remember(profileState.activeId) { mutableStateOf<no.aura.os.v2.search.SearchHit?>(null) }
    var showGoogle by remember { mutableStateOf(false) }
    var googleState by remember(profileState.activeId) { mutableStateOf(no.aura.os.v2.google.GoogleFlowState()) }
    var googleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val googleFlow = remember(runtime, profileState.activeId) { runtime?.let {
        no.aura.os.v2.google.GoogleDeviceFlow(profileState.activeId, it.googleClient, it.googleVault, profiles, { profiles.state.activeId })
    } }
    DisposableEffect(googleFlow, route, showGoogle) { onDispose { googleJob?.cancel() } }
    LaunchedEffect(route) { if (route != Route.AURATUBE) showGoogle = false }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    LaunchedEffect(profileState.activeId) { sheet = null; googleJob?.cancel() }
    var adminTick by remember { mutableIntStateOf(0) }
    val flowScope = rememberCoroutineScope()
    val changed: () -> Unit = { profileState = profiles.state; adminTick++ }
    val adminFlow = remember { AdminFlow(settingsDeps.admin, show = { sheet = it }, changed = changed) }
    val usersFlow = remember {
        UsersFlow(profiles, settingsDeps.modules, adminFlow, settingsDeps.google, background = { job -> flowScope.launch(Dispatchers.Default) { job() } }, show = { sheet = it }, changed = changed,
            onProfileDeleted = { id -> no.aura.os.v2.amp.AmpArlVault(appContext).clear(id); no.aura.os.v2.amp.AmpLibrary(appContext,id).clear(); simklTokens?.clear(id); no.aura.os.v2.simkl.SimklAutoSync.clear(appContext,id); watch.purge(id); progress?.clear(id); mediaRecents?.clear(id); simklRuntime?.clear(id); mediaServers?.purge(id); runtime?.googleVault?.clear(id); runtime?.followedArtists?.purge(id); runtime?.systemContinue?.purge(id) })
    }
    val moduleSwitches = remember { ModuleSwitches(settingsDeps.modules, settingsDeps.admin) }
    // The Administrator session ends when the person leaves Settings/Profile, switches profile, or the app stops (MainActivity.onStop).
    LaunchedEffect(route) { if (route != Route.SETTINGS && route != Route.PROFILE) { settingsDeps.admin.revoke(); adminTick++ } }
    var modules by remember { mutableStateOf(settingsDeps.modules.load(profileState.activeId)) }
    // Switching profile loads that profile's own module switches; nothing is shared between profiles.
    LaunchedEffect(profileState.activeId) { modules = settingsDeps.modules.load(profileState.activeId); settingsDeps.admin.revoke() }   // a different person starts locked
    val flags = modules.toFlags()
    LaunchedEffect(route,realMode,profileState.activeId,flags.auraAmp) {
        if(!realMode || profileState.active.isChild || !flags.auraAmp) {ampPlayback?.close();ampPlayback=null}
        else if(route==Route.AURAAMP && ampPlayback==null) ampPlayback=no.aura.os.v2.amp.AmpPlayback(appContext,profileState.activeId)
    }
    val ampLifecycle=androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(ampPlayback,ampLifecycle) {
        val owned=ampPlayback
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event -> if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP)owned?.pause() }
        ampLifecycle.addObserver(observer)
        onDispose {ampLifecycle.removeObserver(observer);owned?.close()}
    }
    // ---- "Connect a phone" (AuraConnect): one pairing, then control + sync + credential transfer over the pinned channel ---------------------
    // The receiver listens PERSISTENTLY while an adult profile is active and the app is foregrounded (not just while its sheet is open), so a
    // paired phone can control playback and sync any time. Trust is durable (a file-backed store): a paired phone reconnects on its pinned key
    // without a new code, and shows up in "Paired phones". A pair grants only the everyday controller rights (playback, queue, playlist sync);
    // a credential (Deezer ARL or a media-server sign-in) is accepted only after the person arms the window on the TV, never by the pair alone.
    // The pairing CODE is opened only while the "Connect a phone" sheet is up. Off unless the build opts in (BuildConfig.AURACONNECT_PAIRING).
    val pairingBuild = no.aura.os.v2.BuildConfig.AURACONNECT_PAIRING
    var cortexUp by remember { mutableStateOf(false) }   // AuraCortex (coretex.kt) is running: paired phones can reach this TV
    val pairWindow = remember { no.aura.os.v2.connect.CredentialGrantWindow() }
    var pairedTick by remember { mutableIntStateOf(0) }
    var connTick by remember { mutableIntStateOf(0) }   // bumps after an UltraMax verify / save / remove / resolve, or a received media-server sign-in
    // Closing any sheet cancels a pending "receive a sign-in" arm and closes an open pairing code.
    LaunchedEffect(sheet == null) { if (sheet == null) { pairWindow.armedDevice()?.let(pairWindow::disarm); if (cortexUp) no.aura.cortex.AuraCortex.connect.closePairing() } }

    // The live AmpPlayback wrapped as a remote-control target; every call is marshalled to the main thread (the player and its state live there).
    fun playbackTargetFor(pb: no.aura.os.v2.amp.AmpPlayback): no.aura.os.v2.connect.PlaybackTarget = object : no.aura.os.v2.connect.PlaybackTarget {
        fun <T> onMain(block: () -> T): T = kotlinx.coroutines.runBlocking(Dispatchers.Main) { block() }
        override fun resume() { onMain { pb.resume() } }
        override fun pause() { onMain { pb.pause() } }
        override fun next() { onMain { pb.next() } }
        override fun previous() { onMain { pb.previous() } }
        override fun setShuffle(on: Boolean?) { onMain { if (on == null) pb.toggleShuffle() else pb.applyShuffle(on) } }
        override fun setRepeat(on: Boolean?) { onMain { if (on == null) pb.toggleRepeat() else pb.applyRepeat(on) } }
        override fun seek(positionMs: Long) { onMain { pb.seek(positionMs) } }
        override fun enqueue(track: no.aura.os.v2.connect.SyncTrack) { onMain { pb.enqueue(no.aura.os.v2.amp.AmpTrack(track.id, track.title, track.artist, (track.durationMs / 1000L).toInt(), track.cover, null, null, null, track.isrc)) } }
        override fun removeFromQueue(index: Int) { onMain { pb.removeQueuedAt(index) } }
        override fun moveInQueue(from: Int, to: Int) { onMain { pb.moveQueued(from, to) } }
        override fun clearQueue() { onMain { pb.clearQueue() } }
        override fun snapshot() = onMain { no.aura.os.v2.connect.PlaybackSnapshot(pb.playing, pb.loading, pb.shuffle, pb.repeat, pb.positionMs, pb.durationMs, pb.selected?.id, pb.selected?.title, pb.selected?.artist, pb.queue.size) }
    }

    fun buildPairHandlers(owner: String, isChild: Boolean): Map<String, (no.aura.os.v2.connect.CommandContext) -> Map<String, Any?>> {
        val stillAllowed = { profiles.state.activeId == owner && !profiles.state.active.isChild && settingsDeps.admin.isAuthorized() }
        val playlistsAllowed = { profiles.state.activeId == owner && !profiles.state.active.isChild }
        val playlistPull = no.aura.os.v2.connect.PlaylistSyncHandler { no.aura.os.v2.connect.AmpPlaylistSource(appContext, owner, isChild) }
        // A pushed playlist is written on the main thread into the live player (or the library when AuraAmp is idle), so the visible library stays fresh.
        val playlistPush = no.aura.os.v2.connect.PlaylistPushHandler {
            no.aura.os.v2.connect.AmpPlaylistSink(appContext, owner, isChild, onImport = { meta, tracks ->
                kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                    require(profiles.state.activeId == owner && !profiles.state.active.isChild)
                    ampPlayback?.importPlaylist(meta, tracks) ?: no.aura.os.v2.amp.AmpLibrary(appContext, owner).importPlaylist(meta, tracks)
                }
            })
        }
        val playback = no.aura.os.v2.connect.PlaybackCommandHandler { kotlinx.coroutines.runBlocking(Dispatchers.Main) { ampPlayback }?.let { playbackTargetFor(it) } }
        val arl = no.aura.os.v2.connect.ArlOfferHandler(owner, stillAllowed,
            store = { a -> kotlinx.coroutines.runBlocking(Dispatchers.Main) { ampPlayback?.invalidateSource() }; ampVault.save(owner, a) },
            verify = { kotlinx.coroutines.runBlocking { no.aura.os.v2.amp.AmpProvider(ampVault, owner).verifyAccess() } },
            onOutcome = { outcome -> flowScope.launch {
                if (profiles.state.activeId == owner) { ampCredentialTick++; ampCheck = outcome.check?.let { it to System.currentTimeMillis() } }
                sheet = Sheet.Message("Eclipse", listOf("Deezer sign-in received from the phone.") + no.aura.os.v2.amp.AmpConnectionPresenter.resultLines(outcome.stored, outcome.check))
            } })
        val media = mediaServers?.let { svc -> no.aura.os.v2.connect.MediaServerOfferHandler(owner, stillAllowed,
            receive = { acc -> kotlinx.coroutines.runBlocking {
                if (!svc.add(owner, acc)) no.aura.os.v2.connect.MediaServerOutcome(false, false, "The TV could not store it (it may already have the maximum number of servers).")
                else when (val v = svc.verify(owner, acc)) {
                    is no.aura.os.v2.mediaserver.MediaResult.Ok -> no.aura.os.v2.connect.MediaServerOutcome(true, true, null)
                    is no.aura.os.v2.mediaserver.MediaResult.Failure -> no.aura.os.v2.connect.MediaServerOutcome(true, false, v.error.message())
                }
            } },
            onOutcome = { outcome -> flowScope.launch {
                if (profiles.state.activeId == owner) connTick++
                sheet = Sheet.Message("Media server", (if (outcome.stored) listOf("Server sign-in received from the phone.") else listOf("The sign-in could not be stored.")) +
                    (if (outcome.stored && outcome.verified) listOf("Checked against the server: connected.") else listOfNotNull(outcome.error)))
            } }) }
        val credentials = no.aura.os.v2.connect.CredentialReceiveHandler(pairWindow, arl, media)
        // Playlist pull/push are bound to the paired profile (refuse if the TV switched profiles since the pair).
        val boundPull = no.aura.os.v2.connect.ProfileBoundPlaylistHandler(owner, playlistsAllowed, playlistPull)
        val boundPush = no.aura.os.v2.connect.ProfileBoundPlaylistHandler(owner, playlistsAllowed, playlistPush)
        return buildMap {
            put(no.aura.os.v2.connect.PlaylistSync.OP) { ctx -> boundPull.handle(ctx) }
            put(no.aura.os.v2.connect.PlaylistSync.PUSH_OP) { ctx -> boundPush.handle(ctx) }
            no.aura.os.v2.connect.PlaybackCommandHandler.OPS.forEach { op -> put(op) { ctx -> playback.handle(ctx) } }
            put(no.aura.os.v2.connect.ArlOffer.OP) { ctx -> credentials.handle(ctx) }
        }
    }

    // Bring AuraCortex up/down with the active (adult) profile and the app's foreground state. Pairing, trust, encryption and
    // reconnection all live in coretex.kt; this shell only shows the prompts and plugs in the TV's own handlers.
    DisposableEffect(profileState.activeId, pairingBuild, ampLifecycle) {
        val owner = profileState.activeId
        var ops: Set<String> = emptySet()
        var startJob: kotlinx.coroutines.Job? = null
        fun bringUp() {
            if (!pairingBuild || profiles.state.active.isChild || cortexUp || startJob?.isActive == true) return
            startJob = flowScope.launch {
                val started = withContext(Dispatchers.IO) { runCatching {
                    no.aura.cortex.AuraCortex.start(no.aura.cortex.AuraCortex.Config(
                        dataDir = appContext.filesDir, deviceName = "AuraOS TV", deviceType = no.aura.cortex.AuraConnect.DeviceType.TV,
                        policy = no.aura.cortex.AuraGuard.GuardPolicy(pairingCodeLifetime = with(kotlin.time.Duration.Companion) { 2.minutes }),
                        callbackExecutor = androidx.core.content.ContextCompat.getMainExecutor(appContext)))
                }.isSuccess }
                if (!started || profiles.state.activeId != owner) return@launch
                val connect = no.aura.cortex.AuraCortex.connect
                connect.onApprovePairing = { req, decide ->
                    if (profiles.state.activeId != owner || profiles.state.active.isChild) decide(no.aura.cortex.Approval.Deny)
                    else sheet = Sheet.Choice("Allow " + req.name.take(40) + "?", listOf(
                        SheetOption("Allow") { decide(no.aura.cortex.Approval.Approve(owner, no.aura.cortex.Permission.DEFAULT_CONTROLLER)); pairedTick++
                            sheet = Sheet.Message("Connect a phone", listOf("Allowed. The phone can now control and sync \"" + profiles.state.active.name + "\".")) },
                        SheetOption("Deny") { decide(no.aura.cortex.Approval.Deny); sheet = null }),
                        note = "This phone entered the code. Allow it to connect to the profile \"" + profiles.state.active.name + "\"? Check the fingerprint matches on the phone: " + req.shortFingerprint)
                }
                connect.onShowSas = { sas -> sheet = Sheet.Message("Connect a phone", listOf("Check that the phone shows the same number:", sas.chunked(3).joinToString(" "))) }
                connect.onDeviceUnpaired = { pairedTick++ }
                val handlers = buildPairHandlers(owner, profiles.state.active.isChild)
                no.aura.os.v2.connect.TvCommands.register(handlers)
                ops = handlers.keys
                cortexUp = true
            }
        }
        fun tearDown() {
            startJob?.cancel(); startJob = null
            pairWindow.armedDevice()?.let(pairWindow::disarm)
            no.aura.os.v2.connect.TvCommands.unregister(ops); ops = emptySet()
            if (cortexUp) no.aura.cortex.AuraCortex.stop()
            cortexUp = false
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) { androidx.lifecycle.Lifecycle.Event.ON_START -> bringUp(); androidx.lifecycle.Lifecycle.Event.ON_STOP -> tearDown(); else -> {} }
        }
        ampLifecycle.addObserver(observer)
        bringUp()
        onDispose { ampLifecycle.removeObserver(observer); tearDown() }
    }

    fun phoneDevicesSheet(owner: String, removed: String? = null) {
        val rows = no.aura.cortex.AuraCortex.connect.pairedDevices().filter { it.deviceId != removed }
        sheet = Sheet.Choice("Paired phones", buildList {
            rows.forEach { d -> add(SheetOption(d.name + "  ·  remove") { adminFlow.ensure { no.aura.cortex.AuraCortex.connect.unpair(d.deviceId); pairedTick++; phoneDevicesSheet(owner, d.deviceId) } }) }
            if (rows.isEmpty()) add(SheetOption("No phones are paired yet") {})
        }, note = "Phones you have paired with this TV. They can control playback and sync; a sign-in still needs your explicit go-ahead. Removing one ends its access at once, also on the phone.")
    }
    fun armCredential(owner: String, label: String) {
        val target = no.aura.cortex.AuraCortex.connect.pairedDevices().firstOrNull()?.deviceId
        if (target == null) sheet = Sheet.Message("Receive a sign-in", listOf("Pair a phone first, then choose this again."))
        else { pairWindow.arm(target); sheet = Sheet.Message("Receive a $label sign-in", listOf("Ready. In AuraAmp on the paired phone, send the $label sign-in to this TV.", "This stays open for two minutes and accepts one sign-in.")) }
    }

    fun startPhoneLink(owner: String) {
        pairedTick
        val title = "Connect a phone"
        if (!pairingBuild) { sheet = Sheet.Message(title, listOf(no.aura.os.v2.connect.ArlReceiveText.DISABLED)); return }
        if (!cortexUp) { sheet = Sheet.Message(title, listOf("Starting the connection… try again in a moment, or check this TV has a home-network address.")); return }
        val session = no.aura.cortex.AuraCortex.connect.startAdvertisingAsReceiver()
        val host = Regex("[?&]host=([^&]+)").find(session.qrData)?.groupValues?.get(1)
        val port = Regex("[?&]port=([0-9]+)").find(session.qrData)?.groupValues?.get(1)?.toIntOrNull()
        if (host == null || port == null) { no.aura.cortex.AuraCortex.connect.closePairing(); sheet = Sheet.Message(title, listOf(no.aura.os.v2.connect.ArlReceiveText.NO_NETWORK)); return }
        val isChild = profiles.state.active.isChild
        val (heading, note) = no.aura.os.v2.connect.ArlReceiveText.waiting(host, port, session.code)
        sheet = Sheet.Choice(heading, buildList {
            add(SheetOption("Show a new code") { startPhoneLink(owner) })
            add(SheetOption("Paired phones") { phoneDevicesSheet(owner) })
            if (!isChild) {
                add(SheetOption("Receive a Deezer sign-in from a phone") { armCredential(owner, "Deezer") })
                if (mediaServers != null) add(SheetOption("Receive a media-server sign-in from a phone") { armCredential(owner, "media server") })
            }
        }, note = note)
    }

    fun showAmpConnection():Unit {
        if(profileState.active.isChild) {sheet=Sheet.Message("AuraAmp",listOf("Eclipse is available on adult profiles only."));return}
        val owner=profileState.activeId
        sheet=Sheet.Choice("Eclipse / Deezer",buildList {
            add(SheetOption(if(ampVault.has(owner)) "Replace Deezer ARL" else "Enter Deezer ARL") {
                adminFlow.ensure {sheet=Sheet.TextEntry("Deezer ARL",hint="Paste ARL or Eclipse manifest URL",note="Stored encrypted for this profile. Address: https://eclipse.ultramax.vip/ + ARL + /manifest.json",keyboard=Sheet.Keyboard.PASSWORD,maxLength=512) {raw ->
                    if(profiles.state.activeId==owner && !profiles.state.active.isChild && settingsDeps.admin.isAuthorized()) {
                        sheet=Sheet.Message("Eclipse",listOf("Saving and checking connection…"))
                        flowScope.launch {
                            ampPlayback?.invalidateSource()
                            val saved=withContext(Dispatchers.IO){ampVault.save(owner,raw)}
                            val provider=no.aura.os.v2.amp.AmpProvider(ampVault,owner)
                            val check=if(saved) provider.verifyAccess() else null
                            if(profiles.state.activeId==owner) {ampCredentialTick++;ampCheck=check?.let{it to System.currentTimeMillis()}
                                sheet=Sheet.Message("Eclipse",no.aura.os.v2.amp.AmpConnectionPresenter.resultLines(saved,check))}
                        }
                    }
                }}
            })
            if(ampVault.has(owner)) {
                add(SheetOption("Verify connection") {
                    sheet=Sheet.Message("Eclipse",listOf("Checking connection…"))
                    flowScope.launch {val provider=no.aura.os.v2.amp.AmpProvider(ampVault,owner);val check=provider.verifyAccess();if(profiles.state.activeId==owner){ampCheck=check to System.currentTimeMillis();ampCredentialTick++;sheet=Sheet.Message("Eclipse",no.aura.os.v2.amp.AmpConnectionPresenter.resultLines(true,check))}}
                })
                add(SheetOption("Disconnect this profile") {adminFlow.ensure {ampPlayback?.invalidateSource();ampVault.clear(owner);ampCheck=null;ampCredentialTick++;sheet=Sheet.Message("Eclipse",listOf("Local ARL removed for this profile."))}})
            }
            if(no.aura.os.v2.BuildConfig.AURACONNECT_PAIRING) add(SheetOption("Connect a phone (pair, playlists, sign-in)") {adminFlow.ensure {startPhoneLink(owner)}})
            add(SheetOption("Close"){sheet=null})
        },note="The host, ARL and manifest path are kept separate. No Deezer SSO is required for this connection.")
    }
    var launcherStatus by remember { mutableStateOf(LauncherStatus(null, null)) }
    var deviceInfo by remember { mutableStateOf(DeviceInfo("unknown", 0, "unknown", "unknown", 0)) }
    LaunchedEffect(resumeTick) { withContext(Dispatchers.Default) { launcherStatus = settingsDeps.launcher.status(); deviceInfo = settingsDeps.device.info() } }
    LaunchedEffect(flags) { if (route !in NavModel.reachable(flags)) route = Route.HOME }
    LaunchedEffect(flags.auraAmp) { if (!flags.auraAmp) player = MiniPlayerState() }   // AuraAmp off: stop playback, hide its player
    var tmdbTick by remember { mutableIntStateOf(0) }   // bumps when a TMDB request completes, so Settings shows a verified connection
    // Plex / Jellyfin / Emby: the connect flow (sheets), and a re-check of saved sign-ins whenever the app returns to the foreground or the
    // profile changes, so "Connected" always reflects a verification made in this process. Nothing runs for a profile without sign-ins.
    val mediaFlow = remember(mediaServers) {
        mediaServers?.let { svc ->
            no.aura.os.v2.mediaserver.MediaServerFlow(svc, profileId = { profiles.state.activeId }, show = { sheet = it }, launch = { step -> flowScope.launch { step() } },
                ensureAdmin = { then -> adminFlow.ensure(then) }, isAdmin = { settingsDeps.admin.isAuthorized() }, changed = { connTick++ }, browse = { account ->
                    if (!profiles.state.active.isChild) { sheet = null; serverAccount = account; serverItem = null }
                    else sheet = Sheet.Message("Restricted profile", listOf("This server does not provide verified child content ratings."))
                })
        }
    }
    DisposableEffect(mediaFlow, profileState.activeId) { onDispose { mediaFlow?.cancel() } }
    LaunchedEffect(mediaServers, resumeTick, profileState.activeId) { if (mediaServers?.verifyStale(profileState.activeId) == true) connTick++ }
    val connectionStatuses = remember(profileState.activeId, resumeTick, tmdbTick, connTick, route == Route.SETTINGS) { settingsDeps.connections.statuses() }   // read once per resume / result, not on every recomposition
    var navStyle by remember { mutableStateOf(navStyleStore.load()) }
    var pb by remember { mutableStateOf(playbackStore.load()) }
    val setPb: (no.aura.os.v2.playback.PlaybackPrefs) -> Unit = { pb = it; playbackStore.save(it) }
    val settingsState = SettingsState(modules, mode, realModeAvailable = apps != null, launcher = launcherStatus, device = deviceInfo, connections = connectionStatuses.map { status -> if(status.id==ConnectionId.ECLIPSE) {
        ampCredentialTick
        // Facts only: stored entry (presence check, no Keystore work in composition), the last check, and what the player proved or was told.
        no.aura.os.v2.amp.AmpConnectionPresenter.status(no.aura.os.v2.amp.AmpConnection(ampVault.isSet(profileState.activeId), ampCheck?.first, ampCheck?.second, ampPlayback?.audioConfirmedAt, ampPlayback?.providerFailure))
    } else status }, profiles = profileState, admin = run { adminTick; settingsDeps.admin.status() }, navStyle = navStyle, playback = pb,
        simklAutoSync = simklTokens?.has(profileState.activeId)==true && no.aura.os.v2.simkl.SimklSyncPreferences(appContext).enabled(profileState.activeId),
        simklSyncStatus = no.aura.os.v2.simkl.SimklSyncPreferences(appContext).lastStatus(profileState.activeId) ?: "Connect Simkl to enable autosync")
    // Module switches and the Demo/Real switch are protected: ask for the Administrator PIN (or first-time setup), then apply.
    val onSettingsToggle: (String) -> Unit = { id ->
        val protectedItem = SettingsCatalog.all.firstOrNull { it.id == id }?.let(AccessPolicy::requiresAdmin) ?: true   // unknown ids fail closed
        (if (protectedItem) adminFlow::ensure else { then: () -> Unit -> then() }) {
            if (id == "simkl.auto_sync") {
                if (profileState.active.isChild || simklTokens?.has(profileState.activeId)!=true) sheet=Sheet.Message("Simkl autosync",listOf("Connect Simkl on an adult profile first."))
                else { val prefs=no.aura.os.v2.simkl.SimklSyncPreferences(appContext); prefs.setEnabled(profileState.activeId,!prefs.enabled(profileState.activeId)); no.aura.os.v2.simkl.SimklAutoSync.configure(appContext,profileState.activeId); connTick++ }
            }
            else if (id == SettingsCatalog.PB_AUTOSTART) setPb(pb.copy(autoStart = !pb.autoStart))
            else if (id == SettingsCatalog.PB_SUBS) setPb(pb.copy(subtitlesOn = !pb.subtitlesOn))
            else if (id == SettingsCatalog.DATA_MODE) { mode = if (mode == DataMode.REAL) DataMode.DEMO else DataMode.REAL; modeStore?.save(mode) }
            else (moduleSwitches.toggle(profileState.activeId, modules, id) as? ModuleToggle.Changed)?.let { modules = it.prefs }
            adminTick++
        }
    }
    LaunchedEffect(realMode, resumeTick) { snapshot = if (realMode && apps != null) withContext(Dispatchers.Default) { apps.snapshot() } else null }
    val clock = remember { DemoClock() }
    var dialog by remember { mutableStateOf<DemoDialog?>(null) }

    // ---- Real Mode: TMDB catalogue (one shared service for Home, Movies, TV Shows and AuraBox) ----
    val realStates = remember(profileState.active, tmdbOn) { mutableStateMapOf<RealPage, RealState>() }
    var retryTick by remember { mutableIntStateOf(0) }
    var detailsEpisode by remember(profileState.activeId) { mutableStateOf<Pair<Int, Int>?>(null) }
    var detailsRef by remember(profileState.active, tmdbOn) { mutableStateOf<TitleRef?>(null) }
    LaunchedEffect(runtime?.pendingResume, profileState.activeId) {
        val target = runtime?.pendingResume
        if (target != null) {
            if (target.first == profileState.activeId) {
                detailsRef = TitleRef.parse(target.second.substringBefore(":s"))
                val season = target.second.substringAfter(":s", "").substringBefore(":e").toIntOrNull()
                val episode = target.second.substringAfter(":e", "").toIntOrNull()
                detailsEpisode = if (season != null && episode != null) season to episode else null
            }
            else sheet = Sheet.Message("Choose the correct profile", listOf("Switch to the profile that shared this Continue Watching item, then open it again."))
            runtime.consumeResume()
        }
    }
    var youtubeVideos by remember { mutableStateOf(emptyList<no.aura.os.v2.youtube.PublicYouTubeVideo>()) }
    var youtubeLoading by remember { mutableStateOf(false) }
    LaunchedEffect(realYouTubePage, youtubeFeed) {
        if (realYouTubePage && youtubeFeed != null && youtubeFeed.configured) {
            youtubeLoading = true
            val result = youtubeFeed.popular()
            youtubeVideos = (result as? no.aura.os.v2.youtube.PublicYouTubeResult.Ready)?.videos.orEmpty()
            youtubeLoading = false
            connTick++
        } else youtubeVideos = emptyList()
    }
    var liveApps by remember(profileState.active) { mutableStateOf(emptyList<no.aura.os.v2.apps.LaunchableApp>()) }
    var liveAppsLoading by remember { mutableStateOf(false) }
    LaunchedEffect(route, apps, resumeTick, profileState.active) {
        liveApps = emptyList()
        if (route == Route.LIVE_TV && apps != null && !profileState.active.isChild) {
            liveAppsLoading = true
            val owner = profileState.activeId
            val found = withContext(Dispatchers.IO) { apps.snapshot().apps }
            if (profiles.state.activeId == owner) liveApps = found
            liveAppsLoading = false
        } else liveAppsLoading = false
    }
    var libraryTick by remember { mutableIntStateOf(0) }
    var savedTitles by remember(profileState.active) { mutableStateOf(emptyList<MediaItem>()) }
    var watchlistTitles by remember(profileState.active) { mutableStateOf(emptyList<MediaItem>()) }
    var watchingItems by remember(profileState.active) { mutableStateOf(emptyList<no.aura.os.v2.data.ContinueItem>()) }
    LaunchedEffect(realLibraryPage, route, profileState.active, libraryTick) {
        if (realLibraryPage) {
            val active = profileState.active
            savedTitles = withContext(Dispatchers.Default) { tmdb?.savedTitles(watch.favorites(active.id), active).orEmpty() }
            watchlistTitles = withContext(Dispatchers.Default) { tmdb?.savedTitles(watch.watchlist(active.id), active).orEmpty() }
            watchingItems = progress?.continueWatching(active.id).orEmpty()
        }
    }
    LaunchedEffect(runtime, profileState.active, resumeTick, libraryTick) {
        val owner = profileState.active
        if (runtime != null && progress != null) withContext(Dispatchers.IO) {
            runtime.watchNext.clearOwn()
            val allowed = eligibleWatchNext(owner, progress, tmdb)
            if (profiles.state.activeId == owner.id) runtime.publishWatchNext(owner.id, allowed)
        }
    }
    var systemContinueTick by remember { mutableIntStateOf(0) }
    var systemContinue by remember(profileState.active) { mutableStateOf(no.aura.os.v2.watch.SystemContinueResult(emptyList(), "Android TV Continue Watching is off.")) }
    LaunchedEffect(runtime, profileState.active, resumeTick, systemContinueTick) {
        val owner = profileState.active
        systemContinue = no.aura.os.v2.watch.SystemContinueResult(emptyList(), "Checking Android TV Continue Watching…")
        val result = withContext(Dispatchers.IO) { runtime?.systemContinue?.read(owner) }
        if (profiles.state.activeId == owner.id && result != null) systemContinue = result
    }
    val systemWatching = systemContinue.items.map { row -> no.aura.os.v2.data.ContinueItem(
        MediaItem("android-watch-next:${row.id}", row.title, no.aura.os.v2.data.Artwork(imageUrl = row.thumbnailArtUri ?: row.posterArtUri), subtitle = row.packageName),
        if (row.durationMs > 0) (row.positionMs.toFloat() / row.durationMs).coerceIn(0f, 1f) else 0f,
        "Resume in app", showProgress = row.durationMs > 0) }
    val homeState = (realStates[RealPage.HOME] as? RealState.Ready)?.let { ready ->
        if (systemWatching.isEmpty()) ready else RealState.Ready(ready.content.copy(content = ready.content.content.copy(
            shelves = listOf(no.aura.os.v2.data.Shelf("android-continue", "Android TV · Continue Watching", systemWatching, true)) + ready.content.content.shelves)))
    } ?: realStates[RealPage.HOME]
    val moreInFlight = remember { HashSet<String>() }
    // A different profile or leaving Real Mode drops every page: nothing from one profile's (possibly filtered) view is reused for another.
    LaunchedEffect(tmdbOn, profileState.active) { realStates.clear(); moreInFlight.clear() }
    // Load the visible page once; a Retry removes its failed state and bumps retryTick. Cancelled with the effect when the route changes.
    LaunchedEffect(tmdbOn, activePage, profileState.active, retryTick) {
        val page = activePage
        if (tmdbOn && tmdb != null && page != null && realStates[page] == null) {
            realStates[page] = RealState.Loading
            val profile = profileState.active
            val r = withContext(Dispatchers.Default) { tmdb.load(page, profile) }
            realStates[page] = when (r) { is CatalogResult.Ready -> RealState.Ready(r.value); CatalogResult.Empty -> RealState.Empty; is CatalogResult.Failed -> RealState.Failed(r.error) }
            if (r is CatalogResult.Ready) Diag.milestone("firstCatalogueShown")
            tmdbTick++
        }
    }
    val onShelfEnd: (RealPage) -> (String) -> Unit = { page -> { shelfId ->
        val cur = (realStates[page] as? RealState.Ready)?.content
        val key = "${profileState.activeId}/$page/$shelfId"
        if (tmdb != null && cur != null && shelfId in cur.more && moreInFlight.add(key)) {
            val profile = profileState.active
            flowScope.launch {
                val next = withContext(Dispatchers.Default) { tmdb.more(page, shelfId, cur, profile) }
                if (realStates[page] is RealState.Ready && profile.id == profileState.activeId) realStates[page] = RealState.Ready(next)
                moreInFlight.remove(key); tmdbTick++
            }
        }
    } }
    // Simkl V2 device PIN pairing. The user_code is public; the device_code and user tokens are never exposed.
    var simklPairing by remember(profileState.activeId) { mutableStateOf<no.aura.os.v2.simkl.SimklPairingController?>(null) }
    fun showSimklPin(pin: no.aura.os.v2.simkl.SimklPin, ownerProfile: String) {
        if (profiles.state.activeId != ownerProfile) return
        simklPairing?.close()
        runtime?.simklPairingMemory?.pending = no.aura.os.v2.simkl.SimklPairingMemory.Pending(ownerProfile, pin)
        sheet = null
        lateinit var session: no.aura.os.v2.simkl.SimklPairingController
        session = no.aura.os.v2.simkl.SimklPairingController(pin,
            poll = { simklClient?.check(pin) ?: no.aura.os.v2.simkl.SimklPinStatus.Failed("Simkl is unavailable.") },
            save = { token -> withContext(Dispatchers.IO) { session.state.value.visible && profiles.state.activeId == ownerProfile && simklTokens?.save(ownerProfile, token) == true } },
            currentProfile = { profiles.state.activeId == ownerProfile },
            approved = { if (profiles.state.activeId == ownerProfile && simklPairing === session) {
                runtime?.simklPairingMemory?.pending = null
                simklRuntime?.markVerified(ownerProfile); connTick++; simklPairing = null
                no.aura.os.v2.simkl.SimklAutoSync.connected(appContext,ownerProfile)
                sheet = Sheet.Message("Simkl connected", listOf("Automatic Plan-to-Watch import is enabled for this profile.","First import is scheduled now when network is available, then approximately hourly. Change this in Connections."))
            } })
        simklPairing = session
    }

    LaunchedEffect(runtime, profileState.activeId) {
        val pending = runtime?.simklPairingMemory?.pending
        if (pending != null) {
            if (pending.profileId != profileState.activeId) runtime.simklPairingMemory.pending = null
            else if (simklPairing == null) showSimklPin(pending.pin, pending.profileId)
        }
    }

    fun showSimklMenu() {
        val client = simklClient
        val tokens = simklTokens
        val ownerProfile = profileState.activeId
        if (client == null || !client.configured || tokens == null) {
            sheet = Sheet.Message("Simkl", listOf("No Simkl test client is configured in this private build.", "SIMKL_CLIENT_ID must be set in git-ignored local.properties."))
            return
        }
        sheet = Sheet.Choice("Simkl · " + profileState.active.name, buildList {
            if (!tokens.has(ownerProfile)) {
                add(SheetOption("Connect using TV PIN") {
                    sheet = Sheet.Message("Simkl", listOf("Requesting a pairing code…"))
                    flowScope.launch {
                        val pin = client.begin()
                        if (profileState.activeId != ownerProfile) { sheet = null; return@launch }
                        if (pin == null) sheet = Sheet.Message("Simkl", listOf("Could not start pairing. Check your client ID and internet access.", "Verify this client ID is registered as a Simkl AUTH V2 TV/device app."))
                        else showSimklPin(pin, ownerProfile)
                    }
                })
            } else {
                add(SheetOption("Sync Watchlist now") {
                    sheet = Sheet.Message("Simkl", listOf("Reading your Simkl Watchlist…"))
                    flowScope.launch {
                        val result=withContext(Dispatchers.IO) { no.aura.os.v2.simkl.SimklAutoSync.sync(appContext,ownerProfile) }
                        if(profiles.state.activeId!=ownerProfile) { sheet=null; return@launch }
                        when(result) {
                            is no.aura.os.v2.simkl.SimklImportResult.Success -> {
                                simklRuntime?.markVerified(ownerProfile); connTick++; libraryTick++
                                realStates.remove(RealPage.HOME); retryTick++
                                sheet=Sheet.Message("Simkl Watchlist updated",listOf("Imported ${result.inserted} new Plan-to-Watch titles.","No remote lists or history were changed."))
                            }
                            is no.aura.os.v2.simkl.SimklImportResult.Failed -> sheet=Sheet.Message("Simkl sync failed",listOf(result.reason,"Existing lists are unchanged."))
                            no.aura.os.v2.simkl.SimklImportResult.Skipped -> sheet=Sheet.Message("Simkl",listOf("Sync skipped because profile or connection changed."))
                        }
                    }
                })
                add(SheetOption("Disconnect this profile") {
                    sheet = Sheet.Message("Simkl", listOf("Revoking the device grant and clearing its local credentials…"))
                    flowScope.launch {
                        val raw = withContext(Dispatchers.IO) { tokens.get(ownerProfile) }
                        withContext(Dispatchers.IO) { tokens.clear(ownerProfile); no.aura.os.v2.simkl.SimklAutoSync.clear(appContext,ownerProfile) }
                        val revoked = if (raw != null) client.revoke(raw) else false
                        simklRuntime?.clear(ownerProfile); connTick++
                        sheet = Sheet.Message("Simkl disconnected", listOf("Local credentials for this profile were cleared.",
                            if (revoked) "The Simkl device authorization was revoked." else "Revoke the device authorization from Simkl Connected Apps if still listed."))
                    }
                })
            }
            add(SheetOption("Cancel") { sheet = null })
        }, note = "AUTH V2 TV PIN pairing with PKCE and read-only media access.")
    }

    LaunchedEffect(showGoogle, googleFlow, profileState.activeId) {
        if (showGoogle && googleFlow != null && runtime?.googleVault?.has(profileState.activeId) == true) googleFlow.browse { googleState = it }
    }
    LaunchedEffect(profileState.activeId,resumeTick) {
        withContext(Dispatchers.IO) { no.aura.os.v2.simkl.SimklAutoSync.configure(appContext,profileState.activeId) }
        libraryTick++
    }
    val settingsOnlySearch=remember { no.aura.os.v2.search.GlobalSearchService(emptyMap()) }
    val globalSearch = remember(tmdb, apps, mediaServers, runtime) {
        no.aura.os.v2.search.GlobalSearchService(no.aura.os.v2.search.searchSources(tmdb, profiles, apps, runtime?.musicSearch, mediaServers), no.aura.os.v2.search.serverSearchSource(mediaServers, profiles))
    }
    val showTrailer: (HeroEntry) -> Unit = { hero ->
        val owner = profileState.active
        val ref = TitleRef.parse(hero.id)
        if (ref != null && tmdb != null) flowScope.launch {
            val details = (tmdb.details(ref, owner) as? DetailsResult.Ok)?.details
            if (profiles.state.activeId == owner.id) {
                val key = details?.trailerKey
                if (key == null) sheet = Sheet.Message("Trailer unavailable", listOf("No official YouTube trailer is available for this title."))
                else if (trailer?.invoke(key) != true) sheet = Sheet.Message("YouTube unavailable", listOf("Install or enable the official YouTube TV app to play this trailer."))
            }
        }
    }
    fun refreshNotifications() {
        val owner = profileState.active
        val center = runtime?.center ?: return
        flowScope.launch {
            val adapters = buildList<no.aura.os.v2.notifications.NotificationSourceAdapter> {
                add(no.aura.os.v2.notifications.UpdateNoticeSource(no.aura.os.v2.BuildConfig.AURAOS_UPDATE_MANIFEST_URL,
                    no.aura.os.v2.BuildConfig.AURAOS_UPDATE_PUBLIC_KEY, no.aura.os.v2.BuildConfig.APPLICATION_ID,
                    if (no.aura.os.v2.BuildConfig.BUILD_TYPE == "perf") "private-perf" else no.aura.os.v2.BuildConfig.BUILD_TYPE,
                    no.aura.os.v2.BuildConfig.VERSION_CODE.toLong()))
                progress?.let { add(no.aura.os.v2.notifications.ContinueWatchingNotificationAdapter(it)) }
                if (tmdb != null) {
                    val zone = java.time.ZoneId.systemDefault()
                    val source = no.aura.os.v2.notifications.TmdbWatchlistReleaseSource(watch, tmdb, profiles, zone)
                    add(no.aura.os.v2.notifications.WatchlistReleaseNotificationAdapter(no.aura.os.v2.notifications.NotificationCategory.WATCHLIST_MOVIE, source, zone))
                    add(no.aura.os.v2.notifications.WatchlistReleaseNotificationAdapter(no.aura.os.v2.notifications.NotificationCategory.WATCHLIST_EPISODE, source, zone))
                }
                runtime?.let { env -> add(no.aura.os.v2.music.FollowedArtistNotificationAdapter(env.followedArtists,
                    no.aura.os.v2.music.MusicBrainzArtistReleaseSource(userAgent = "AuraOS-V2/0.1 (https://github.com/AwesomeDadNO/AURA-Ecosystem)", serviceUseApproved = { no.aura.os.v2.BuildConfig.BUILD_TYPE != "release" }),
                    profileAllowed = { id -> profiles.state.profiles.any { it.id == id && !it.isChild } }, zone = java.time.ZoneId.systemDefault())) }
            }
            center.refresh(owner.id, adapters)
            if (profiles.state.activeId == owner.id) centerTick++
        }
    }
    fun showHomeShortcut() {
        val activeRuntime = runtime ?: return
        if (profileState.active.isChild) { sheet = Sheet.Message("Restricted profile", listOf("Home shortcut setup is unavailable on child profiles.")); return }
        val status = activeRuntime.homeShortcutState()
        sheet = Sheet.Choice("Home button shortcut", buildList {
            if (status.consented) add(SheetOption("Turn shortcut off") { activeRuntime.setHomeShortcutConsent(false); sheet = Sheet.Message("Home shortcut", listOf("Shortcut is off. You can also revoke AuraOS Home shortcut in Android Accessibility settings.")) })
            else add(SheetOption("Allow optional shortcut and open Android settings") { adminFlow.ensure {
                activeRuntime.setHomeShortcutConsent(true)
                val opened = activeRuntime.openHomeShortcutSettings()
                sheet = if (opened) null else Sheet.Message("Android settings unavailable", listOf("Consent was saved, but Android Accessibility settings could not be opened. No Android access was granted automatically."))
            } })
            add(SheetOption("Open Android Accessibility settings") { sheet = null; if (!activeRuntime.openHomeShortcutSettings()) sheet = Sheet.Message("Settings unavailable", listOf("This TV did not expose Android Accessibility settings.")) })
            add(SheetOption("Choose default Home app instead") { sheet = null; if (!activeRuntime.requestHome()) sheet = Sheet.Message("Home setup unavailable", listOf("The TV could not open Home setup.")) })
        }, note = status.message + ". " + no.aura.os.v2.launcher.HomeShortcut.DISCLOSURE)
    }

    fun editQuietHours() {
        val owner = profileState.activeId
        val center = runtime?.center ?: return
        val quiet = center.snapshot(owner).preferences.quietHours
        fun save(value: no.aura.os.v2.notifications.QuietHours) {
            if (profiles.state.activeId == owner) { center.configure(owner, center.snapshot(owner).preferences.copy(quietHours = value)); centerTick++ }
        }
        fun editTime(start: Boolean) {
            sheet = Sheet.TextEntry(if (start) "Quiet hours start (HH:mm)" else "Quiet hours end (HH:mm)", hint = "22:00", maxLength = 5) { text ->
                if (!Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(text)) sheet = Sheet.Message("Invalid time", listOf("Use 24-hour HH:mm."))
                else {
                    val minute = text.take(2).toInt() * 60 + text.takeLast(2).toInt()
                    save(if (start) quiet.copy(startMinute = minute) else quiet.copy(endMinute = minute)); sheet = null
                }
            }
        }
        sheet = Sheet.Choice("Quiet hours", listOf(
            SheetOption(if (quiet.enabled) "Turn off quiet hours" else "Turn on quiet hours") { save(quiet.copy(enabled = !quiet.enabled)); sheet = null },
            SheetOption("Set start time") { editTime(true) }, SheetOption("Set end time") { editTime(false) },
            SheetOption("Set timezone") { sheet = Sheet.TextEntry("Quiet hours timezone", hint = quiet.zone) { value ->
                val zone = runCatching { java.time.ZoneId.of(value).id }.getOrNull()
                if (zone == null) sheet = Sheet.Message("Invalid timezone", listOf("Use an IANA timezone such as Europe/Oslo."))
                else { save(quiet.copy(zone = zone)); sheet = null }
            } }
        ), note = "Quiet hours suppress presentation, while the in-app center retains new notifications. Playback is never interrupted.")
    }
    val openItem: (MediaItem) -> Unit = { item ->
        if (item.id.startsWith("android-watch-next:")) {
            val row = systemContinue.items.firstOrNull { "android-watch-next:${it.id}" == item.id }
            if (row == null || runtime?.openSystemContinue(profileState.active, row) != true) sheet = Sheet.Message("Continue Watching unavailable", listOf("The app link is no longer available. Refresh Android TV Continue Watching in Settings."))
        } else if (item.id.startsWith("server:") && mediaServers != null && !profileState.active.isChild) {
            val owner = profileState.activeId
            flowScope.launch {
                val key = item.id.removePrefix("server:").substringBefore('|')
                val id = item.id.substringAfter('|', "")
                val account = mediaServers.accounts(owner).firstOrNull { it.key == key }
                val entry = account?.let { a -> (mediaServers.libraryPlayback.resume(owner, a) as? no.aura.os.v2.mediaserver.MediaResult.Ok)?.value?.firstOrNull { it.id == id } }
                if (profiles.state.activeId == owner && account != null && entry != null) { serverAccount = account; serverItem = entry }
            }
        } else TitleRef.parse(item.id)?.let { detailsRef = it }
    }
    val onHero: (HeroEntry, HeroAction) -> Unit = { e, a ->
        TitleRef.parse(e.id)?.let { detailsRef = it } // let the real Details resolver handle Play/UltraMax status, never show fake playback
    }

    // ---- External streaming shortcuts (icon only) replace the Aura module area while AuraBox, AuraAmp and AuraTube are ALL off ----
    var installed by remember { mutableStateOf(emptyList<AppShortcut>()) }
    LaunchedEffect(realMode, resumeTick) {   // lightweight, off the main thread, only when it can be shown
        installed = if (apps != null && realMode) withContext(Dispatchers.Default) { ShortcutPicker.pick(apps.launchableApps()) } else emptyList()
    }
    var recentTick by remember { mutableIntStateOf(0) }
    val shortcuts = if (!profileState.active.isChild && realMode) {
        recentTick; mediaRecents?.order(profileState.activeId, installed) ?: installed
    } else emptyList()   // hidden on Child profiles
    val navTracker = remember { LaunchFocusTracker() }
    val shortcutRequesters = remember { HashMap<String, FocusRequester>() }
    LaunchedEffect(resumeTick, shortcuts) { navTracker.consumeOnResume()?.removePrefix("nav:")?.let { k -> withFrameNanos { }; runCatching { shortcutRequesters[k]?.requestFocus() } } }
    val navLayout = remember(flags, navStyle, shortcuts) { NavModel.layout(flags, navStyle, shortcuts) }
    val navPadding = remember(flags, navStyle, shortcuts) { NavMetrics.itemPadding(flags, style = navStyle, shortcuts = shortcuts.size).dp }
    var chromeFocused by remember { mutableStateOf(false) }
    val entry = remember(route) { FocusRequester() }          // the page's primary control (hero Play, first tile ...)
    val railEntry = remember { FocusRequester() }
    val homeNav = remember { FocusRequester() }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }

    // Initial focus goes to the page's primary control once it exists. Ordinary navigation never steals focus afterwards.
    LaunchedEffect(contentReady) { if (contentReady && !initialFocusDone) { withFrameNanos { }; runCatching { entry.requestFocus() }; initialFocusDone = true } }

    // Back policy: collapse expanded player -> chrome focus returns to the page's primary control (Home) -> other page goes Home and focuses
    // the Home nav item -> otherwise the system exits. Dialogs handle their own Back (Dialog onDismissRequest).
    // The compact player is the Demo Mode simulation (see below): in Real Mode it does not exist, so it can never be "expanded".
    val demoPlayer = flags.auraAmp && !realMode && c != null && route != Route.AURAAMP
    val expanded = demoPlayer && player.visibility == PlayerVisibility.EXPANDED
    BackHandler(enabled = dialog == null && sheet == null && simklPairing == null && (expanded || (route == Route.HOME && chromeFocused) || route != Route.HOME)) {
        when {
            expanded -> player = MiniPlayerReducer.reduce(player, PlayerAction.Collapse, queueSize)
            route == Route.HOME -> runCatching { entry.requestFocus() }
            else -> route = Route.HOME
        }
    }
    LaunchedEffect(route) { if (initialFocusDone && route == Route.HOME && !chromeFocused) { /* returning Home keeps the focus the user had; nothing to force */ } }

    val realUi = if (apps == null) null else RealAppsUi(
        mode = mode, snapshot = snapshot, tracker = launchTracker, resumeTick = resumeTick,
        onLaunchApp = { app ->
            val r = apps.launchApp(app.packageName)
            if (r == LaunchResult.LAUNCHED) {
                launchTracker.onLaunched("app:" + app.packageName)
                mediaRecents?.launched(profileState.activeId, app.packageName)
                recentTick++
            } else LaunchFeedback.message(r, app.label)?.let { dialog = DemoDialog(app.label, listOf(it), footer = null) }
        },
        appBanner = { pkg -> (icons as? no.aura.os.v2.apps.AppBannerSource)?.banner(pkg) },
        onLaunchService = { svc ->
            val r = apps.launchService(svc.name)
            if (r == LaunchResult.LAUNCHED) launchTracker.onLaunched("svc:" + svc.name)
            else LaunchFeedback.message(r, svc.name)?.let { dialog = DemoDialog(svc.name, listOf(it), footer = null) }
        },
    )

    val playTrack: (String) -> Unit = { title ->
        if (realMode) dialog = DemoDialog("Playback is not available yet", listOf("\"$title\" cannot be played: AuraAmp has no music source connected in this build.", "The AuraAmp page shows illustrative content."), footer = null)
        else {
            player = MiniPlayerReducer.reduce(player, PlayerAction.Show, queueSize)
            if (!player.playing) player = MiniPlayerReducer.reduce(player, PlayerAction.TogglePlay, queueSize)
        }
    }

    val widgetContext = androidx.compose.ui.platform.LocalContext.current
    val sportWidgetPrefs = remember(profileState.activeId, route, resumeTick) { no.aura.os.v2.sports.AuraSportPrefsStore(widgetContext, profileState.activeId).load() }
    val sportWidgetEntry = remember(profileState.activeId) { FocusRequester() }
    val homeWidgets: (@Composable (FocusRequester, FocusRequester)->Unit)? =
        if(!flags.sports || no.aura.os.v2.sports.AuraSportDemo.widgets(sportWidgetPrefs).isEmpty()) null
        else { up,down -> SportWidgetsRow(sportWidgetPrefs,sportWidgetEntry,up,down) { route=Route.SPORTS } }
    val chromeScroll = remember(route) { ChromeScrollState() }
    CompositionLocalProvider(LocalChromeScroll provides chromeScroll, no.aura.os.v2.theme.LocalReduceMotion provides reduceMotion, LocalRailRequester provides railEntry, LocalAuraImageLoader provides images) {
        Box(Modifier.fillMaxSize().background(AuraColors.Background)) {
            // BASE_CONTENT. A demo page waits for its fixtures (c); a Real Mode TMDB page never does.
            if (c == null && !standalonePage && !tmdbPage && !realLibraryPage && !realYouTubePage) Unit else when (route) {
                Route.HOME -> if (tmdbOn) RealShelves(RealPage.HOME, ShelfLayouts.Home, homeState, entry, dialog != null || detailsRef != null, { retryTick++; realStates.remove(RealPage.HOME) }, openItem, onHero, onShelfEnd(RealPage.HOME), onCategory = { categoryCard = it }, onTrailer = showTrailer, extraContent = homeWidgets, extraEntry = sportWidgetEntry)
                    else ShelfPage(c!!.home, ShelfLayouts.Home, homeUi, entry, rotationPaused = dialog != null, onDialog = { dialog = it }, statusContent = homeWidgets, statusEntry = if(homeWidgets!=null) sportWidgetEntry else null, statusExit = if(homeWidgets!=null) sportWidgetEntry else null)
                Route.MOVIES -> if (tmdbOn) RealShelves(RealPage.MOVIES, ShelfLayouts.Movies, realStates[RealPage.MOVIES], entry, dialog != null || detailsRef != null, { retryTick++; realStates.remove(RealPage.MOVIES) }, openItem, onHero, onShelfEnd(RealPage.MOVIES), onCategory = { categoryCard = it }, onTrailer = showTrailer)
                    else ShelfPage(c!!.movies, ShelfLayouts.Movies, moviesUi, entry, rotationPaused = dialog != null, onDialog = { dialog = it })
                Route.TV_SHOWS -> if (tmdbOn) RealShelves(RealPage.TV, ShelfLayouts.TvShows, realStates[RealPage.TV], entry, dialog != null || detailsRef != null, { retryTick++; realStates.remove(RealPage.TV) }, openItem, onHero, onShelfEnd(RealPage.TV), onCategory = { categoryCard = it }, onTrailer = showTrailer)
                    else ShelfPage(c!!.tvShows, ShelfLayouts.TvShows, tvUi, entry, rotationPaused = dialog != null, onDialog = { dialog = it })
                Route.AURABOX -> if (tmdbOn) RealShelves(RealPage.BOX, ShelfLayouts.Movies, realStates[RealPage.BOX], entry, dialog != null || detailsRef != null, { retryTick++; realStates.remove(RealPage.BOX) }, openItem, onHero, onShelfEnd(RealPage.BOX), onCategory = { categoryCard = it }, onTrailer = showTrailer)
                    else AuraBoxPage(c!!.auraBox, boxUi, entry, onDialog = { dialog = it })
                Route.SAVED, Route.LIBRARY -> if (realLibraryPage) RealLibraryPage(route, savedTitles, watchlistTitles, watchingItems, entry, openItem, systemWatching = systemWatching, systemStatus = systemContinue.message)
                    else SecondaryPage(route, c!!, secUi, entry, onNavigate = { settingsTarget=null; route = it }, onDialog = { dialog = it }, realUi = realUi)
                Route.AURAAMP -> if(realMode && !profileState.active.isChild && ampPlayback!=null) AuraAmpPage(profileState.activeId,entry,ampPlayback!!,onConnect={showAmpConnection()},rotationPaused=sheet!=null || dialog!=null,
                    mediaServers=mediaServers, auraMusicEngine = runtime?.auraMusicEngine,
                    onImport={submit -> sheet=Sheet.TextEntry("Import public Deezer playlist",hint="https://www.deezer.com/playlist/… or playlist ID",keyboard=Sheet.Keyboard.URL){value->sheet=null;submit(value)}},
                    onAuraConnect={
                        val owner=profiles.state.activeId
                        adminFlow.ensure {
                            if(profiles.state.activeId==owner && !profiles.state.active.isChild)startPhoneLink(owner)
                        }
                    })
                    else AuraAmpDemoPage(entry = entry, session = ampDemoSession,
                    onSearch = { route = Route.SEARCH }, onMessage = { message -> sheet = Sheet.Message("AuraAmp demo", listOf(message)) },
                    externalTitle = musicHit?.title, externalSubtitle = musicHit?.subtitle)
                Route.SPORTS -> SportsPage(c!!.sports, entry, onDialog = { dialog = it }, profileId = profileState.activeId, onSettings = { settingsTarget=null; settingsStart = no.aura.os.v2.settings.SettingsCategory.AURASPORT; route = Route.SETTINGS })
                Route.LIVE_TV -> LiveHomePage(liveApps, liveAppsLoading, profileState.active.isChild, entry,
                    banner = { pkg -> (icons as? no.aura.os.v2.apps.AppBannerSource)?.banner(pkg) },
                    onLaunch = { app ->
                        if (profileState.active.isChild) sheet = Sheet.Message("Restricted profile", listOf("External live apps are unavailable for this profile."))
                        else {
                            val result = apps?.launchApp(app.packageName)
                            if (result != LaunchResult.LAUNCHED) sheet = Sheet.Message("App unavailable", listOf("This live TV app could not be opened."))
                            else mediaRecents?.launched(profileState.activeId, app.packageName)
                        }
                    }, onMessage = { message -> sheet = Sheet.Message("Live TV", listOf(message)) }, rotationPaused = sheet != null || dialog != null)
                Route.AURATUBE -> if (showGoogle && googleFlow != null) GoogleYouTubePage(googleState, entry,
                    onConnect = { adminFlow.ensure { googleJob?.cancel(); googleJob = flowScope.launch { googleFlow.connect { googleState = it; changed() } } } },
                    onCancel = { googleJob?.cancel(); googleState = no.aura.os.v2.google.GoogleFlowState("Sign-in cancelled.") },
                    onRefresh = { googleJob?.cancel(); googleJob = flowScope.launch { googleFlow.browse { googleState = it } } },
                    onDisconnect = { adminFlow.ensure { googleJob?.cancel(); googleJob = flowScope.launch { googleFlow.disconnect { googleState = it; changed() } } } },
                    onPlaylist = { id -> googleJob?.cancel(); googleJob = flowScope.launch { googleFlow.openPlaylist(id) { googleState = it } } },
                    onChannel = { id -> if (runtime?.openYouTubeChannel(id) != true) sheet = Sheet.Message("YouTube unavailable", listOf("The official YouTube app could not open this channel.")) },
                    onVideo = { id -> if (trailer?.invoke(id) != true) sheet = Sheet.Message("YouTube unavailable", listOf("The official YouTube TV app could not be opened.")) }) else AuraTubePage(
                    AuraTubeState(youtubeFeed?.configured == true, verifiedPublic = youtubeVideos.isNotEmpty()),
                    entry, onAction = { action ->
                        when (action) {
                            AuraTubeActions.OPEN_YOUTUBE ->
                                if (apps != null && realMode) {
                                    LaunchFeedback.message(apps.launchApp(PlaybackHandoff.YOUTUBE_TV_PACKAGE), "YouTube")?.let { dialog = DemoDialog("YouTube", listOf(it), footer = null) }
                                } else dialog = DemoDialog("YouTube", listOf("The official YouTube app is not available."), footer = null)
                            AuraTubeActions.STATUS -> { showGoogle = true; googleState = no.aura.os.v2.google.GoogleFlowState() };
                            "legacy-status" -> dialog = DemoDialog("YouTube", listOf(
                                if (youtubeVideos.isNotEmpty()) "YouTube public catalogue verified via Data API v3." else "YouTube public catalogue is not verified.",
                                "Google account connection requires OAuth and has not been implemented."), footer = null)
                        }
                    },
                    videos = if (realMode) youtubeVideos else emptyList(),
                    loading = youtubeLoading,
                    onVideo = { id ->
                        if (trailer?.invoke(id) != true) dialog = DemoDialog("YouTube", listOf("Could not open the official YouTube TV app."), footer = null)
                    },
                )
                Route.SEARCH -> GlobalSearchPage(if(realMode) globalSearch else settingsOnlySearch, profileState.active, entry, searchUi) { hit ->
                    when (hit.kind) {
                        no.aura.os.v2.search.SearchKind.SETTINGS -> {
                            val id=hit.target.removePrefix("settings:")
                            SettingsCatalog.all.firstOrNull { it.id==id }?.let { item -> settingsStart=item.category; settingsTarget=item.id; route=Route.SETTINGS }
                        }
                        no.aura.os.v2.search.SearchKind.APPS -> {
                            val result = apps?.launchApp(hit.target.removePrefix("app:"))
                            if (result != LaunchResult.LAUNCHED) sheet = Sheet.Message("App unavailable", listOf("This app could not be launched."))
                        }
                        no.aura.os.v2.search.SearchKind.MUSIC -> { musicHit = hit; route = Route.AURAAMP
                            sheet = Sheet.Choice(hit.title, buildList {
                                hit.artistId?.let { artist -> add(SheetOption("Follow / unfollow ${hit.subtitle.orEmpty()}") {
                                    val store = runtime?.followedArtists
                                    if (store != null) {
                                        val existing = store.artists(profileState.activeId).any { it.id == artist }
                                        store.follow(profileState.activeId, no.aura.os.v2.music.FollowedArtist(artist, hit.subtitle.orEmpty()), !existing)
                                        sheet = Sheet.Message("Followed artists", listOf(if (existing) "Artist unfollowed." else "Artist followed. Enable Followed artists in Notifications for release alerts."))
                                    }
                                }) }
                            }, note = "MusicBrainz music metadata · audio playback is not connected.")
                        }
                        else -> if (hit.target.startsWith("server:") && mediaServers != null && !profileState.active.isChild) {
                            val owner = profileState.activeId
                            flowScope.launch {
                                val found = mediaServers.search(owner, searchUi.query).firstOrNull { (a, e) -> "server:${a.key}|${e.id}" == hit.target }
                                if (profiles.state.activeId == owner && found != null) { serverAccount = found.first; serverItem = found.second }
                            }
                        } else TitleRef.parse(hit.target)?.let { detailsRef = it }
                    }
                }
                Route.NOTIFICATIONS -> NotificationCenterPage(centerState, entry,
                    onToggle = { category -> if (category == no.aura.os.v2.notifications.NotificationCategory.SYSTEM && profileState.active.isChild) sheet = Sheet.Message("Restricted profile", listOf("System notifications are unavailable for child profiles.")) else runtime?.center?.configure(profileState.activeId, centerState.preferences.copy(enabled = if (category in centerState.preferences.enabled) centerState.preferences.enabled - category else centerState.preferences.enabled + category)); centerTick++; refreshNotifications() },
                    onQuietHours = { editQuietHours() },
                    onSystemSettings = { if (profileState.active.isChild) sheet = Sheet.Message("Restricted profile", listOf("Android/system notifications are unavailable for child profiles.")) else sheet = Sheet.Choice("Optional Android notification access", listOf(SheetOption("Open Android access settings") { val opened = runtime?.open(runtime.notificationAccess.settingsIntent()) == true; sheet = if (opened) null else Sheet.Message("Notification access unavailable", listOf("This device does not expose supported Android notification access settings.")) }), note = "Only public notifications supported by this device are copied. Accessibility is not used. You can revoke access in Android settings.") },
                    onRefresh = { refreshNotifications() }, onRead = { runtime?.center?.markRead(profileState.activeId, it); centerTick++ },
                    onDismiss = { runtime?.center?.dismiss(profileState.activeId, it); centerTick++ },
                    onReadAll = { runtime?.center?.markAllRead(profileState.activeId); centerTick++ },
                    onDismissAll = { runtime?.center?.dismissAll(profileState.activeId); centerTick++ },
                    profileKey = profileState.activeId, onOpen = { key ->
                        TitleRef.parse("tmdb:" + key.substringBefore(":s"))?.let { detailsRef = it }
                        val season = key.substringAfter(":s", "").substringBefore(":e").toIntOrNull(); val episode = key.substringAfter(":e", "").toIntOrNull()
                        detailsEpisode = if (season != null && episode != null) season to episode else null
                    })
                Route.SETTINGS -> SettingsPage(settingsState, entry, initialCategory = settingsStart, initialItemId = settingsTarget, onToggle = onSettingsToggle, onAction = { id ->
                    val pick: (String, List<Pair<String, () -> Unit>>) -> Unit = { title, opts -> sheet = Sheet.Choice(title, opts.map { (l, f) -> SheetOption(l) { f(); sheet = null } }) }
                    when (id) {
                        "general.android_tv" -> {
                            if(profileState.active.isChild) sheet=Sheet.Message("Restricted profile",listOf("Device settings require an adult profile."))
                            else if(runtime?.open(no.aura.os.v2.settings.AndroidTvSettings.intent())!=true) sheet=Sheet.Message("Android TV settings",listOf("This device did not expose a supported settings activity."))
                        }
                        "general.watch_next" -> {
                            val owner = profileState.activeId
                            sheet = Sheet.Choice("Android TV Continue Watching", listOf(
                                SheetOption(if (runtime?.watchNextEnabled(owner) == true) "Stop sharing my progress" else "Share this profile's progress") {
                                    if (profiles.state.activeId == owner && runtime != null) {
                                        val enabled = !runtime.watchNextEnabled(owner); runtime.setWatchNext(owner, enabled)
                                        flowScope.launch { val result = withContext(Dispatchers.IO) { runtime.publishWatchNext(owner, profiles.state.profiles.firstOrNull { it.id == owner }?.let { eligibleWatchNext(it, progress, tmdb) }.orEmpty()) }; sheet = Sheet.Message("Android TV Continue Watching", listOf(result.message)) }
                                    }
                                }), note = "Optional: title and viewing progress may be visible outside AuraOS on this shared device. Only AuraOS-owned rows are written. Device support/certification vary. This is separate from your Google account Watchlist.")
                        }
                        "general.notifications" -> route = Route.NOTIFICATIONS
                        "general.motion" -> { reduceMotion = !reduceMotion; runtime?.setReduceMotion(reduceMotion); sheet = Sheet.Message("Reduce motion", listOf(if (reduceMotion) "On. Poster and focus transitions are immediate." else "Off. System animation settings are also respected.")) }
                        "general.set_default" -> { sheet = null; if (runtime?.requestHome() != true) sheet = Sheet.Message("Default Home unavailable", listOf("This device could not open its Home chooser or Android settings.")) }
                        "general.accessibility", "general.home_button" -> showHomeShortcut()
                        "general.system_continue" -> {
                            val owner = profileState.activeId
                            if (profileState.active.isChild) sheet = Sheet.Message("Restricted profile", listOf("Device-wide viewing history cannot be safely attributed to a child profile."))
                            else sheet = Sheet.Choice("Android TV Continue Watching", listOf(SheetOption(if (runtime?.systemContinue?.enabled(owner) == true) "Turn off shared Android TV history" else "Enable and request TV listings access") {
                                if (profiles.state.activeId == owner && runtime != null) {
                                    val enabled = !runtime.systemContinue.enabled(owner)
                                    runtime.systemContinue.setEnabled(owner, enabled)
                                    systemContinue = no.aura.os.v2.watch.SystemContinueResult(emptyList(), "Refreshing…")
                                    sheet = null; systemContinueTick++
                                    if (enabled) runtime.requestSystemContinueAccess()
                                }
                            }, SheetOption("Refresh / check availability") {
                                val active = profileState.active
                                sheet = Sheet.Message("Android TV Continue Watching", listOf("Checking availability…"))
                                flowScope.launch {
                                    val result = withContext(Dispatchers.IO) { runtime?.systemContinue?.read(active) }
                                    if (profiles.state.activeId == active.id && result != null) {
                                        systemContinue = result
                                        sheet = Sheet.Message("Android TV Continue Watching", listOf(result.message))
                                    }
                                }
                            }), note = systemContinue.message + " Optional device-wide viewing history, visible in Home and Library. Only rows Android exposes can be read. This does not sync the private Google TV account Watchlist. No Accessibility access is needed.")
                        }
                        "general.media_libraries" -> { if (mediaServers != null && !profileState.active.isChild) flowScope.launch {
                            val accounts = mediaServers.accounts(profileState.activeId)
                            sheet = Sheet.Choice("Connected media libraries", accounts.map { a -> SheetOption("${a.kind.title} · ${a.serverName}") { serverAccount = a; serverItem = null; sheet = null } }, note = if (accounts.isEmpty()) "Connect a server in Settings → Connections first." else null)
                        } }
                        "users.google", SettingsCatalog.CONNECTION_PREFIX + ConnectionId.YOUTUBE.name -> { showGoogle = true; route = Route.AURATUBE; googleState = no.aura.os.v2.google.GoogleFlowState() }
                        SettingsCatalog.PB_QUALITY -> pick("Preferred quality", PlaybackPrefs.SELECTABLE.map { q -> ((if (q == pb.quality) "✓ " else "") + q.label) to { setPb(pb.copy(quality = q)) } })
                        SettingsCatalog.PB_AUDIO -> pick("Audio language", Lang.values().map { l -> ((if (l == pb.audio) "✓ " else "") + l.label) to { setPb(pb.copy(audio = l)) } })
                        SettingsCatalog.PB_AUDIO2 -> pick("Secondary audio language", listOf(((if (pb.audioSecondary == null) "✓ " else "") + "None") to { setPb(pb.copy(audioSecondary = null)) }) + Lang.values().map { l -> ((if (l == pb.audioSecondary) "✓ " else "") + l.label) to { setPb(pb.copy(audioSecondary = l)) } })
                        SettingsCatalog.PB_SUBLANG -> pick("Preferred subtitle language", Lang.values().map { l -> ((if (l == pb.subtitleLang) "✓ " else "") + l.label) to { setPb(pb.copy(subtitleLang = l)) } })
                        SettingsCatalog.CONNECTION_PREFIX + ConnectionId.ECLIPSE.name -> showAmpConnection()
                        SettingsCatalog.CONNECTION_PREFIX + ConnectionId.SIMKL.name -> showSimklMenu()
                        SettingsCatalog.CONNECTION_PREFIX + ConnectionId.PLEX.name -> mediaFlow?.open(no.aura.os.v2.mediaserver.ServerKind.PLEX) ?: Unit
                        SettingsCatalog.CONNECTION_PREFIX + ConnectionId.JELLYFIN.name -> mediaFlow?.open(no.aura.os.v2.mediaserver.ServerKind.JELLYFIN) ?: Unit
                        SettingsCatalog.CONNECTION_PREFIX + ConnectionId.EMBY.name -> mediaFlow?.open(no.aura.os.v2.mediaserver.ServerKind.EMBY) ?: Unit
                        SettingsCatalog.ULTRAMAX_ITEM -> {
                            val st = connectionStatuses.first { it.id == ConnectionId.ULTRAMAX }
                            val svc = playback
                            if (svc == null) sheet = Sheet.Message("UltraMax", listOf("Playback sources are not available in this build."))
                            else sheet = Sheet.Choice("UltraMax · " + st.state.label, buildList {
                                add(SheetOption(if (svc.hasCode) "Change code" else "Enter code") {
                                    sheet = null
                                    adminFlow.ensure {   // credential changes need the Administrator PIN; saveCode re-checks it
                                        sheet = Sheet.CodeEntry("UltraMax code (8 letters/digits)", no.aura.os.v2.ultramax.UltraCode.LENGTH) { raw -> flowScope.launch {
                                            // Keystore encryption + a synchronous commit(): never on the main thread. The Administrator check is still made inside saveCode.
                                            val r = withContext(Dispatchers.Default) { svc.saveCode(raw) }; connTick++
                                            sheet = when (r) {
                                                PlaybackService.SaveResult.SAVED -> Sheet.Message("UltraMax code saved", listOf("The code is stored encrypted on this device.", "Choose Verify to check the connection."))
                                                PlaybackService.SaveResult.INVALID -> Sheet.Message("Invalid code", listOf("The code must be exactly 8 letters or digits."))
                                                PlaybackService.SaveResult.NEEDS_ADMIN -> Sheet.Message("Administrator PIN needed", listOf("The Administrator session ended. Try again."))
                                                PlaybackService.SaveResult.STORAGE_FAILED -> Sheet.Message("Could not store the code", listOf("Secure storage on this device is unavailable."))
                                            }
                                        } }
                                    }
                                })
                                if (svc.hasCode) add(SheetOption("Verify connection") {
                                    sheet = Sheet.Message("UltraMax", listOf("Checking the connection…"))
                                    flowScope.launch { val r = withContext(Dispatchers.Default) { svc.verify() }; connTick++
                                        sheet = Sheet.Message("UltraMax · " + (if (r is UltraState.Verified) "Connected" else "Not connected"), listOf(when (r) { is UltraState.Verified -> "UltraMax answered. Movies and series can now use it as a source."; is UltraState.Failed -> r.error.message(); else -> "No code is saved." }) + (if (r is UltraState.Failed) listOf("Choose Verify again to retry.") else emptyList())) }
                                })
                                if (svc.hasCode) add(SheetOption("Remove code") { sheet = null; adminFlow.ensure { svc.clearCode(); connTick++; sheet = Sheet.Message("UltraMax code removed", listOf("Playback from UltraMax is off until a new code is entered.")) } })
                            }, note = st.message)
                        }
                        else -> Unit
                    }
                    if (id == SettingsCatalog.NAV_STYLE) sheet = Sheet.Choice("Navigation style", NavStyle.values().map { st -> SheetOption((if (st == navStyle) "✓ " else "") + st.label) { navStyle = st; navStyleStore.save(st); sheet = null } }, note = "Applies immediately and is remembered.")
                    else if (id.startsWith("users.") && id != "users.google") usersFlow.start(id)
                }, onDialog = { dialog = it })
                else -> SecondaryPage(route, c!!, secUi, entry, onNavigate = { settingsTarget=null; route = it }, onDialog = { dialog = it }, realUi = realUi, onProfileAction = usersFlow::start)
            }

            // INTERACTIONS: rail first, navigation after it, so the navigation (and the AURA wordmark) is drawn on top of the rail.
            Box(Modifier.align(Alignment.TopStart).onFocusChanged { if (it.hasFocus) chromeFocused = true }) { AuraSideRail(route, railEntry, entry, onNavigate = { settingsTarget=null; route = it }, flags = flags) }
            Box(Modifier.align(Alignment.TopStart).onFocusChanged { chromeFocused = it.hasFocus }) {
                AuraTopNavigation(
                    route, navLayout, navPadding, homeNav, entry, onNavigate = { settingsTarget=null; route = it },
                    unreadNotifications = centerState.items.count { !it.read }, appIcon = { sc, m -> AppIcon(sc.packageName, icons, m) },
                    shortcutRequester = { pkg -> shortcutRequesters.getOrPut(pkg) { FocusRequester() } },
                    onShortcut = { sc ->
                        if (apps != null) {
                            val r = apps.launchApp(sc.packageName)
                            if (r == LaunchResult.LAUNCHED) { navTracker.onLaunched("nav:" + sc.packageName); mediaRecents?.launched(profileState.activeId, sc.packageName); recentTick++ }
                            else LaunchFeedback.message(r, sc.label)?.let { dialog = DemoDialog(sc.label, listOf(it), footer = null) }
                        }
                    },
                )
            }

            // COMPACT_PLAYER (belongs to AuraAmp: absent while the module is off).
            // What exists today is the Demo Mode SIMULATION (fixture queue + a 250 ms demo clock). It is composed in Demo Mode only: Real Mode
            // must not show simulated playback or keep its timer running. A real AuraAmp mini-player (Eclipse playback, migration phase 4)
            // takes this same overlay slot later, driven by a real playback state instead of [DemoClock].
            if (demoPlayer && c != null) {
                CompactPlayer(player, c.queue, clock, onAction = { player = MiniPlayerReducer.reduce(player, it, queueSize) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = PlayerSpec.MarginEnd, bottom = PlayerSpec.MarginBottom))
                if (player.visibility == PlayerVisibility.HIDDEN) Box(Modifier.align(Alignment.BottomEnd).padding(end = PlayerSpec.MarginEnd, bottom = PlayerSpec.MarginBottom)) {
                    ShowPlayerChip { player = MiniPlayerReducer.reduce(player, PlayerAction.Show, queueSize) }
                }

            }

            // SYSTEM_OVERLAYS
            if(route!=Route.AURAAMP || !realMode) DemoBadge(realMode, tmdbOn, Modifier.align(Alignment.BottomStart).padding(start = 137.dp, bottom = 6.dp), previewLabel = when (route) { Route.SPORTS -> "Sports · Demo content"; Route.LIVE_TV -> "Live apps · Demo EPG"; Route.AURAAMP -> if(realMode && !profileState.active.isChild) "AuraAmp · Deezer catalog · Eclipse audio" else "AuraAmp · Demo · No audio"; else -> null })
        }
    }

    // DIALOGS: they are composed outside the base-content provider above. Re-provide the same image loader
    // BEFORE constructing dialog compositions, otherwise LocalAuraImageLoader is null for all details artwork.
    CompositionLocalProvider(LocalChromeScroll provides chromeScroll, no.aura.os.v2.theme.LocalReduceMotion provides reduceMotion, LocalAuraImageLoader provides images) {
        dialog?.let { DemoDialogHost(it) { dialog = null } }
        categoryCard?.let { category -> if (tmdb != null) no.aura.os.v2.overlay.CategoryCatalogHost(category, tmdb, profileState.active, { item -> openItem(item) }, { categoryCard = null },
            topNavigation = { categoryEntry -> val categoryHome = remember { FocusRequester() }; AuraTopNavigation(route, navLayout, navPadding, categoryHome, categoryEntry,
                onNavigate = { destination -> settingsTarget=null; categoryCard = null; route = destination }, unreadNotifications = centerState.items.count { !it.read },
                appIcon = { sc, m -> AppIcon(sc.packageName, icons, m) }, onShortcut = { sc -> apps?.launchApp(sc.packageName) }) },
            onTrailer = showTrailer) }
        detailsRef?.let { ref -> if (tmdb != null) TitleDetailsHost(ref, profileState.active, tmdb, playback, pb, watch, progress, trailer,
            initialEpisode = detailsEpisode, topNavigation = { detailEntry -> val detailHome = remember { FocusRequester() }; AuraTopNavigation(route, navLayout, navPadding, detailHome, detailEntry, onNavigate = { destination -> settingsTarget=null; detailsRef = null; categoryCard = null; route = destination },
                unreadNotifications = centerState.items.count { !it.read }, appIcon = { sc, m -> AppIcon(sc.packageName, icons, m) }, onShortcut = { sc -> apps?.launchApp(sc.packageName) }) },
            onNavigate = { destination ->
                detailsRef = null
                categoryCard = null
                route = destination
                connTick++
                libraryTick++
            },
        ) { detailsRef = null; detailsEpisode = null; connTick++; realStates.remove(RealPage.HOME); retryTick++; libraryTick++ } }
        serverAccount?.let { account -> if (mediaServers != null && !profileState.active.isChild) {
            val activePlayback = serverPlayback
            if (activePlayback == null) no.aura.os.v2.overlay.MediaServerLibraryHost(mediaServers, profileState.activeId, account, { serverPlayback = it }, { serverAccount = null; serverItem = null }, serverItem)
            else no.aura.os.v2.overlay.MediaServerPlayerHost(mediaServers, profileState.activeId, account, activePlayback, prefs = pb) { serverPlayback = null }
        } }
        simklPairing?.let { session -> no.aura.os.v2.overlay.SimklPairingHost(session) { session.close(); if (simklPairing === session) { runtime?.simklPairingMemory?.pending = null; simklPairing = null } } }
        sheet?.let { SheetHost(it) { mediaFlow?.cancel(); sheet = null } }
    }
}

@Composable
private fun DemoBadge(real: Boolean, tmdb: Boolean, modifier: Modifier, previewLabel: String? = null) {
    Text(previewLabel ?: if (real && tmdb) "Real mode · connected sources only" else if (real) "Real mode · connect sources in Settings" else "Demo Mode · illustrative content", style = AuraTypography.Label, color = AuraColors.TextSecondary,
        modifier = modifier.clip(RoundedCornerShape(AuraDimensions.RadiusBadge)).background(AuraColors.BackgroundDeep.copy(alpha = 0.8f)).padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
private fun ShowPlayerChip(onClick: () -> Unit) {
    val shape = RoundedCornerShape(AuraDimensions.RadiusContainer)
    Text("Show player", style = AuraTypography.Label, color = AuraColors.TextPrimary,
        modifier = Modifier.auraFocusable(shape, onClick = onClick).clip(shape).background(AuraColors.SurfaceElevated).padding(horizontal = 16.dp, vertical = 8.dp))
}


/** Per-page Real Mode state. Never holds demo content: a failure is shown as a failure. */
private sealed interface RealState {
    object Loading : RealState
    object Empty : RealState
    data class Failed(val error: TmdbError) : RealState
    data class Ready(val content: RealContent) : RealState
}

@Composable
private fun RealShelves(
    page: RealPage, layout: ShelfLayout, state: RealState?, entry: FocusRequester, paused: Boolean, onRetry: () -> Unit,
    onItem: (MediaItem) -> Unit, onHero: (HeroEntry, HeroAction) -> Unit, onShelfEnd: (String) -> Unit,
    onCategory: ((no.aura.os.v2.data.CategoryCard) -> Unit)? = null, onTrailer: ((HeroEntry) -> Unit)? = null,
    extraContent: (@Composable (FocusRequester,FocusRequester)->Unit)? = null, extraEntry: FocusRequester? = null,
) {
    when (state) {
        null, RealState.Loading -> RealStatusPage("Loading from TMDB…", null, entry, null)
        RealState.Empty -> RealStatusPage("Nothing to show", "TMDB returned no titles that this profile may see.", entry, onRetry)
        is RealState.Failed -> RealStatusPage("Could not load from TMDB", state.error.message(), entry, onRetry)
        is RealState.Ready -> {
            val ui = rememberShelfPageUiState(state.content.content.shelves.size)
            ShelfPage(state.content.content, layout, ui, entry, rotationPaused = paused, onDialog = {}, onItem = onItem, onHeroAction = onHero, onShelfEnd = onShelfEnd, onCategory = onCategory, onTrailer = onTrailer, statusContent = extraContent, statusEntry = if(extraContent!=null) extraEntry else null, statusExit = if(extraContent!=null) extraEntry else null)
        }
    }
}

/** An installed app's own launcher icon (PackageManager), loaded off the main thread; a neutral square until it arrives. */
@Composable
private fun AppIcon(packageName: String, source: AppIconSource?, modifier: Modifier) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, packageName, source) {
        value = source?.let { withContext(Dispatchers.Default) { (it as? no.aura.os.v2.apps.AppBannerSource)?.banner(packageName)?.asImageBitmap() ?: it.icon(packageName, 96)?.asImageBitmap() } }
    }
    val b = bmp
    if (b != null) Image(b, null, modifier, contentScale = ContentScale.Fit) else Box(modifier.clip(RoundedCornerShape(6.dp)).background(AuraColors.Surface))
}

/** Common gate for both explicit opt-in and refresh, so changed child restrictions cannot leak stored titles. */
private suspend fun eligibleWatchNext(profile: no.aura.os.v2.profiles.Profile,
    progress: no.aura.os.v2.watch.PrefsPlaybackProgress?, catalog: RealCatalogService?): List<no.aura.os.v2.watch.LocalPlaybackRecord> {
    val records = progress?.records(profile.id).orEmpty()
    if (!profile.isChild) return records
    if (catalog == null) return emptyList()
    return records.filter { record -> TitleRef.parse(record.key.substringBefore(":s"))?.let { catalog.details(it, profile) is DetailsResult.Ok } == true }
}
