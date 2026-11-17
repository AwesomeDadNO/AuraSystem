package no.aura.os.v2.simkl

import android.app.job.*
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import kotlinx.coroutines.*
import no.aura.os.v2.BuildConfig
import no.aura.os.v2.profiles.PrefsProfileStore
import no.aura.os.v2.watch.PrefsWatchStore

/** No tokens or server response payloads are persisted in sync status. */
class SimklSyncPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("aura_v2_simkl_sync", Context.MODE_PRIVATE)
    fun enabled(profile: String) = prefs.getBoolean("auto:$profile", false)
    fun setEnabled(profile: String, enabled: Boolean) { prefs.edit().putBoolean("auto:$profile", enabled).apply() }
    fun lastSuccess(profile: String) = prefs.getLong("success:$profile", 0)
    fun lastStatus(profile: String) = prefs.getString("status:$profile", null)
    fun record(profile: String, result: SimklImportResult) {
        if (result == SimklImportResult.Skipped) return
        prefs.edit().apply {
            putString("status:$profile", if (result is SimklImportResult.Success) "Updated ${result.inserted} new titles" else "Sync failed; retry when connected or reconnect Simkl")
            if (result is SimklImportResult.Success) putLong("success:$profile", System.currentTimeMillis())
        }.apply()
    }
    fun clear(profile: String) { prefs.edit().remove("auto:$profile").remove("success:$profile").remove("status:$profile").apply() }
}

object SimklAutoSync {
    private const val HOURLY_ID = 620199
    private const val CONNECT_ID = 620200
    fun eligible(context: Context, profile: String): Boolean {
        val state = PrefsProfileStore(context).load() ?: return false
        return state.activeId == profile && state.find(profile)?.isChild == false && SimklProfileTokens(context).has(profile)
    }
    /** Reconcile on startup, resume and profile changes. Android chooses execution time. */
    fun configure(context: Context, profile: String): Boolean = runCatching {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return@runCatching false
        if (!eligible(context, profile) || !SimklSyncPreferences(context).enabled(profile) || !SimklV2Client(BuildConfig.SIMKL_CLIENT_ID).configured) {
            scheduler.cancel(HOURLY_ID); scheduler.cancel(CONNECT_ID); return@runCatching true
        }
        // Do not reset the periodic window on each foreground recomposition/resume.
        if (scheduler.getPendingJob(HOURLY_ID)?.extras?.getString("profile") == profile) return@runCatching true
        scheduler.cancel(CONNECT_ID)
        scheduler.schedule(builder(context, HOURLY_ID, profile).setPeriodic(3_600_000L).build()) == JobScheduler.RESULT_SUCCESS
    }.getOrDefault(false)
    /** Called only after a new grant is securely saved. Enables autosync and requests first import. */
    fun connected(context: Context, profile: String) {
        SimklSyncPreferences(context).setEnabled(profile, true)
        configure(context, profile)
        if (eligible(context, profile)) runCatching {
            context.getSystemService(JobScheduler::class.java)?.schedule(builder(context, CONNECT_ID, profile).setMinimumLatency(0).build())
        }
    }
    fun clear(context: Context, profile: String) {
        SimklSyncPreferences(context).clear(profile)
        context.getSystemService(JobScheduler::class.java)?.let { scheduler ->
            for (id in listOf(HOURLY_ID, CONNECT_ID)) if (scheduler.getPendingJob(id)?.extras?.getString("profile") == profile) scheduler.cancel(id)
        }
    }
    private fun builder(context: Context, id: Int, profile: String) = JobInfo.Builder(id, ComponentName(context, SimklSyncJob::class.java))
        .setExtras(PersistableBundle().apply { putString("profile", profile) })
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setBackoffCriteria(30 * 60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)

    suspend fun sync(context: Context, profile: String, automatic: Boolean = false): SimklImportResult {
        val tokens = SimklProfileTokens(context)
        val prefs = SimklSyncPreferences(context)
        val watch = PrefsWatchStore(context)
        return SimklSyncCoordinator(
            eligible = { eligible(context, profile) && (!automatic || prefs.enabled(profile)) },
            credentials = { tokens.get(profile) },
            fetch = { SimklV2Client(BuildConfig.SIMKL_CLIENT_ID).watchlist(it) },
            apply = { initial, result ->
                // Serial with clear/save: disconnect/deletion/reconnect cannot resurrect credentials or data.
                synchronized(SimklProfileTokens.mutationLock) {
                    if (!eligible(context, profile) || tokens.get(profile) != initial || (automatic && !prefs.enabled(profile))) null
                    else if (result.refreshedCredentials != null && !tokens.save(profile, result.refreshedCredentials)) null
                    else {
                        var inserted = 0
                        for (key in result.tmdbKeys) if (!watch.inWatchlist(profile, key) && watch.setWatchlist(profile, key, true)) inserted++
                        inserted
                    }
                }
            },
            status = { if (eligible(context, profile)) prefs.record(profile, it) },
            preserveRefresh = { initial, refreshed -> synchronized(SimklProfileTokens.mutationLock) {
                eligible(context, profile) && tokens.get(profile) == initial && tokens.save(profile, refreshed)
            } },
        ).sync()
    }
}

/** Best effort hourly read-only import; active adult profile only. No alarms or exact timing promise. */
class SimklSyncJob : JobService() {
    private val work = java.util.concurrent.ConcurrentHashMap<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        val task = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch(start = CoroutineStart.LAZY) {
            try { params.extras.getString("profile")?.let { SimklAutoSync.sync(applicationContext, it, automatic = true) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* No credential-bearing exceptions are logged. Next periodic run can retry. */ }
            finally {
                val ownJob = currentCoroutineContext()[Job]
                if (ownJob != null) work.remove(params.jobId, ownJob)
                if (currentCoroutineContext().isActive) jobFinished(params, false)
            }
        }
        work.put(params.jobId, task)?.cancel()
        task.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work.remove(params.jobId)?.cancel(); return true }
    override fun onDestroy() {
        work.values.forEach { it.cancel() }
        work.clear()
        super.onDestroy()
    }
}
