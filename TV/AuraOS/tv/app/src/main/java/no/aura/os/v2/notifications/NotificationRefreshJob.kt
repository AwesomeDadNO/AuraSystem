package no.aura.os.v2.notifications

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import kotlinx.coroutines.*
import no.aura.os.v2.BuildConfig
import no.aura.os.v2.profiles.*
import no.aura.os.v2.tmdb.*
import no.aura.os.v2.watch.*

/** One process-wide center keeps foreground actions and background refresh serialized. */
object AndroidNotificationCenter {
    private var center: NotificationCenter? = null
    @Synchronized fun get(context: Context): NotificationCenter = center ?: NotificationCenter(PrefsCenterStore(context.applicationContext)).also { center = it }
}
object NotificationRefreshSchedule {
    private const val ID = 620198
    fun eligible(categories: Set<NotificationCategory>) = categories.any { it != NotificationCategory.SYSTEM }
    fun configure(context: Context, profile: String, categories: Set<NotificationCategory>): Boolean = runCatching {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return@runCatching false
        if (!eligible(categories)) { scheduler.cancel(ID); return@runCatching true }
        val extras = PersistableBundle().apply { putString("profile", profile) }
        val job = JobInfo.Builder(ID, ComponentName(context, NotificationRefreshJob::class.java))
            .setExtras(extras).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(6 * 3600_000L)
            .setBackoffCriteria(30 * 60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
        scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
    }.getOrDefault(false)
}
/** Best effort in-app refresh only; no alarms, no notification posting and no installation. Re-scheduled on app start/profile change. */
class NotificationRefreshJob : JobService() {
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val profile = params.extras.getString("profile") ?: return@launch
                val saved = PrefsProfileStore(applicationContext).load() ?: return@launch
                if (saved.activeId != profile) return@launch
                val profiles = ProfileService(MemoryProfileStore(saved))
                val center = AndroidNotificationCenter.get(applicationContext)
                if (!NotificationRefreshSchedule.eligible(center.snapshot(profile).preferences.enabled)) return@launch
                val progress = PrefsPlaybackProgress(applicationContext)
                val watch = PrefsWatchStore(applicationContext)
                val config = TmdbConfig(BuildConfig.TMDB_READ_TOKEN)
                val catalog = RealCatalogService(CachingTmdbRepository(UrlConnectionTmdbHttp(config), config), progress)
                val zone = java.time.ZoneId.systemDefault()
                val releases = TmdbWatchlistReleaseSource(watch, catalog, profiles, zone)
                val adapters = listOf(
                    ContinueWatchingNotificationAdapter(progress),
                    WatchlistReleaseNotificationAdapter(NotificationCategory.WATCHLIST_MOVIE, releases, zone),
                    WatchlistReleaseNotificationAdapter(NotificationCategory.WATCHLIST_EPISODE, releases, zone),
                    no.aura.os.v2.music.FollowedArtistNotificationAdapter(no.aura.os.v2.music.PrefsFollowedArtistStore(applicationContext),
                        no.aura.os.v2.music.MusicBrainzArtistReleaseSource(userAgent = "AuraOS-V2/0.1 (https://github.com/AwesomeDadNO/AURA-Ecosystem)", serviceUseApproved = { BuildConfig.BUILD_TYPE != "release" }),
                        { id -> saved.profiles.any { it.id == id && !it.isChild } }, zone),
                    UpdateNoticeSource(BuildConfig.AURAOS_UPDATE_MANIFEST_URL, BuildConfig.AURAOS_UPDATE_PUBLIC_KEY,
                        BuildConfig.APPLICATION_ID, if (BuildConfig.BUILD_TYPE == "perf") "private-perf" else BuildConfig.BUILD_TYPE, BuildConfig.VERSION_CODE.toLong())
                )
                center.refresh(profile, adapters)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Source-specific errors are represented by the center; never log private state. */ }
            finally { if (currentCoroutineContext().isActive) jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); work = null; return true }
    override fun onDestroy() { work?.cancel(); super.onDestroy() }
}
