package no.aura.os.v2.integration

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import no.aura.os.v2.launcher.HomeSetup
import no.aura.os.v2.notifications.*
import no.aura.os.v2.search.MusicBrainzSearch
import no.aura.os.v2.sports.SportPreferences

/** Android boundary constructed by the Activity; Compose receives capabilities, never grants itself permissions. */
class F6hRuntime(private val activity: Activity, private val launchHome: (Intent) -> Unit = { activity.startActivity(it) }, private val requestTvListings: () -> Unit = {}, val simklPairingMemory: no.aura.os.v2.simkl.SimklPairingMemory = no.aura.os.v2.simkl.SimklPairingMemory()) {
    val systemContinue = no.aura.os.v2.watch.SystemContinueWatching(activity.applicationContext)
    val homeShortcut = no.aura.os.v2.launcher.HomeShortcut(activity.applicationContext)
    fun homeShortcutState() = homeShortcut.state()
    fun setHomeShortcutConsent(enabled: Boolean) = homeShortcut.setConsented(enabled)
    fun openHomeShortcutSettings(): Boolean = homeShortcut.openSettings { activity.startActivity(it) }
    fun requestHome(): Boolean = home.request(launchHome)
    fun openAccessibility(): Boolean = home.openAccessibility { activity.startActivity(it) }
    fun requestSystemContinueAccess() {
        if (systemContinue.permissionAccess() == no.aura.os.v2.watch.TvListingsAccess.REQUEST_RUNTIME_PERMISSION) requestTvListings()
    }
    fun openSystemContinue(profile: no.aura.os.v2.profiles.Profile, item: no.aura.os.v2.watch.SystemContinueItem) = systemContinue.launch(profile, item) { activity.startActivity(it) }

    var pendingResume by mutableStateOf<Pair<String, String>?>(null); private set
    fun acceptResume(intent: Intent?) {
        val profile = intent?.getStringExtra("aura.watch.profile") ?: return
        val key = intent.getStringExtra("aura.watch.key") ?: return
        if (profile.length in 1..200 && no.aura.os.v2.watch.WatchKeys.isValid(key)) pendingResume = profile to key
    }
    fun consumeResume() { pendingResume = null }
    val watchNext = no.aura.os.v2.watch.WatchNextPublisher(activity.applicationContext)
    fun watchNextEnabled(profile: String) = prefs.getBoolean("watch_next:$profile", false)
    fun setWatchNext(profile: String, enabled: Boolean) { prefs.edit().putBoolean("watch_next:$profile", enabled).apply() }
    fun publishWatchNext(profile: String, records: List<no.aura.os.v2.watch.LocalPlaybackRecord>): no.aura.os.v2.watch.WatchNextPublishResult = watchNext.publish(profile, watchNextEnabled(profile), records.map { record ->
        no.aura.os.v2.watch.OwnWatchNextItem(record.key, record.title, record.positionMs, record.durationMs, record.engagedAtMs,
            Intent(activity, no.aura.os.v2.MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .putExtra("aura.watch.profile", profile).putExtra("aura.watch.key", record.key), record.artwork)
    })
    val center = AndroidNotificationCenter.get(activity.applicationContext)
    val home = HomeSetup(activity)
    val notificationAccess = AndroidNotificationAccess(activity)
    val googleVault = no.aura.os.v2.google.GoogleProfileTokens(activity.applicationContext)
    val googleClient = no.aura.os.v2.google.GoogleTvDeviceClient(no.aura.os.v2.BuildConfig.GOOGLE_TV_OAUTH_CLIENT_ID, no.aura.os.v2.BuildConfig.GOOGLE_TV_OAUTH_CLIENT_SECRET)
    val followedArtists = no.aura.os.v2.music.PrefsFollowedArtistStore(activity.applicationContext)
    val musicSearch = MusicBrainzSearch()
    val auraMusicEngine = no.aura.music.engine.AuraMusicEngine(
        isEnabledProvider = { true },
        customServerBaseProvider = { null }
    )
    private val prefs = activity.getSharedPreferences("aura_v2_accessibility", Context.MODE_PRIVATE)
    fun reduceMotion() = prefs.getBoolean("reduce_motion", false)
    fun setReduceMotion(value: Boolean) { prefs.edit().putBoolean("reduce_motion", value).apply() }
    fun liveTv(profile: String) = no.aura.os.v2.livetv.LiveTvPreferences(activity.applicationContext, profile)
    fun sports(profile: String) = SportPreferences(activity.applicationContext, profile)
    fun openYouTubeChannel(id: String) = open(no.aura.os.v2.google.YouTubeHandoff.channelIntent(activity, id))
    fun open(intent: Intent?): Boolean = intent != null && runCatching { activity.startActivity(intent); true }.getOrDefault(false)
    fun scheduleNotifications(profile: String, categories: Set<NotificationCategory>) = NotificationRefreshSchedule.configure(activity.applicationContext, profile, categories)
    fun attach(profile: String) { SystemNotificationSession.attach(center, profile); center.setSystemAccess(profile, notificationAccess.state()) }
    fun detach() { SystemNotificationSession.detach() }
}
