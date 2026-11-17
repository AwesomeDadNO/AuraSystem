package no.aura.os.v2.admin

import no.aura.os.v2.profiles.PinCredential
import no.aura.os.v2.profiles.PinHasher
import no.aura.os.v2.profiles.PinPolicy
import no.aura.os.v2.profiles.Pbkdf2PinHasher

/** What protected operations ask. Implemented by [AdminGate]; the profile layer only knows this interface. */
interface Authorizer {
    /** True while a verified System Administrator session is open (and not expired). */
    fun isAuthorized(): Boolean
    /** Extends an open session on activity. No effect when no session is open. */
    fun touch()
}

/**
 * Persisted credential and lockout state. Lockouts are stored as a DURATION plus a monotonic deadline tied to the boot they were made in
 * ([bootId]): the wall clock is never used (it can be changed by the user), and monotonic timestamps are never trusted across a reboot.
 * [corrupt] = stored data could not be read consistently; the gate then fails closed (see [AdminGate]).
 */
data class AdminPinRecord(
    val credential: PinCredential? = null, val failures: Int = 0, val lockoutLevel: Int = 0,
    val lockoutMs: Long = 0, val lockedUntilElapsedMs: Long = 0, val bootId: String = "", val corrupt: Boolean = false,
)

interface AdminPinStore {
    fun load(): AdminPinRecord
    /** True when the record is durably stored. A false return must never be treated as "saved". */
    fun save(record: AdminPinRecord): Boolean
}
class MemoryAdminPinStore(private var record: AdminPinRecord = AdminPinRecord(), var failWrites: Boolean = false) : AdminPinStore {
    var writes = 0; private set
    override fun load() = record
    override fun save(record: AdminPinRecord): Boolean { if (failWrites) return false; writes++; this.record = record; return true }
}

enum class AdminDenial { NOT_SET_UP, ALREADY_SET_UP, INVALID_PIN, WRONG_PIN, LOCKED, STORAGE_ERROR, STORAGE_CORRUPT }
sealed interface AdminResult {
    object Ok : AdminResult
    data class Denied(val reason: AdminDenial, val retryInSeconds: Long = 0) : AdminResult
}

/**
 * The System Administrator PIN: one credential for the whole device, independent of every profile and Google account.
 *
 * - Created once (first-run setup); there is no default PIN and no bypass. Forgotten PIN = clear AuraOS app data in Android settings
 *   (which also removes the profiles); see docs/V2_F3B_ADMIN_AUTHORIZATION.md.
 * - A correct PIN opens a temporary in-memory session ([sessionTtlMs] of inactivity, extended by [touch]). Expiry is checked when a protected
 *   action is attempted (no timers or polling). It never changes any profile.
 * - **Time:** only the monotonic [elapsed] clock (SystemClock.elapsedRealtime on Android, which keeps counting in deep sleep) is used, for the
 *   session and for lockouts, so changing the system date/time neither extends a session nor shortens a lockout.
 * - **Reboot:** elapsed time restarts at boot, so a stored deadline from an earlier boot is meaningless. If the persisted [AdminPinRecord.bootId]
 *   differs from the current boot (or the boot id is unknown), a pending lockout is re-armed for its FULL duration from the current boot:
 *   a reboot can never shorten a lockout. Failure counters and the escalation level persist across restarts and reboots.
 * - **Storage failures fail closed:** if setup or a PIN change cannot be saved, nothing changes and no session opens ([AdminDenial.STORAGE_ERROR]);
 *   if a failed attempt cannot be saved, the lockout is applied at once in memory instead of relying on the counter. Unreadable or inconsistent
 *   stored data ([AdminPinRecord.corrupt]) denies everything ([AdminDenial.STORAGE_CORRUPT]), and in particular never reads as "no PIN yet",
 *   so damaging the file cannot be used to create a new PIN.
 * - Wrong attempts: [MAX_ATTEMPTS] failures lock for 30 s, doubling per lockout up to 15 min. Verification (slow PBKDF2) runs off the main thread;
 *   state read by the UI is volatile so rendering never waits for a verification in progress.
 */
class AdminGate(
    private val store: AdminPinStore, private val hasher: PinHasher = Pbkdf2PinHasher(iterations = ADMIN_ITERATIONS),
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }, private val bootId: () -> String = { "unknown" },
    private val sessionTtlMs: Long = SESSION_TTL_MS,
) : Authorizer {
    @Volatile private var sessionUntil = 0L
    @Volatile private var hasSession = false
    @Volatile private var setUpFlag = false
    @Volatile private var corruptFlag = false
    @Volatile private var lockUntil = 0L          // monotonic deadline of the lockout currently in force (0 = none)
    private var record: AdminPinRecord

    init {
        val loaded = store.load()
        record = if (loaded.corrupt || !plausible(loaded)) AdminPinRecord(corrupt = true) else rearm(loaded)
        corruptFlag = record.corrupt; setUpFlag = record.credential != null; lockUntil = if (record.lockoutMs > 0) record.lockedUntilElapsedMs else 0
    }

    /** Boot-aware restore of a pending lockout; see class doc. */
    private fun rearm(r: AdminPinRecord): AdminPinRecord {
        if (r.lockoutMs <= 0) return r
        val now = elapsed(); val boot = bootId()
        val sameBoot = boot != "unknown" && boot == r.bootId
        val deadline = if (sameBoot) minOf(r.lockedUntilElapsedMs, now + r.lockoutMs) else now + r.lockoutMs   // clamp tampered deadlines too
        val fixed = r.copy(lockedUntilElapsedMs = deadline, bootId = boot)
        if (fixed != r) store.save(fixed)   // best effort: in memory it is already correct
        return fixed
    }

    private fun plausible(r: AdminPinRecord) = r.failures in 0 until MAX_ATTEMPTS && r.lockoutLevel in 0..MAX_LEVEL && r.lockoutMs in 0..MAX_LOCKOUT_MS &&
        r.lockedUntilElapsedMs >= 0 && (r.credential != null || (r.failures == 0 && r.lockoutMs == 0L))

    val isSetUp: Boolean get() = setUpFlag
    /** True when stored security data is unreadable; the only recovery is clearing app data. */
    val isCorrupt: Boolean get() = corruptFlag

    override fun isAuthorized(): Boolean = hasSession && elapsed() < sessionUntil
    override fun touch() { if (isAuthorized()) sessionUntil = elapsed() + sessionTtlMs }
    fun revoke() { hasSession = false; sessionUntil = 0L }

    /** Seconds the session has left (0 when locked). Display only. */
    fun secondsLeft(): Long = if (!hasSession) 0 else (sessionUntil - elapsed()).coerceAtLeast(0).let { (it + 999) / 1000 }

    /** Seconds of lockout left, without attempting anything. Never blocks. */
    fun lockedForSeconds(): Long = (lockUntil - elapsed()).takeIf { lockUntil > 0 && it > 0 }?.let { (it + 999) / 1000 } ?: 0

    /** First-run setup. Only possible while no PIN exists (and storage is readable), so it can never replace one. Opens a session. */
    @Synchronized fun setUp(pin: String): AdminResult {
        if (record.corrupt) return AdminResult.Denied(AdminDenial.STORAGE_CORRUPT)
        if (record.credential != null) return AdminResult.Denied(AdminDenial.ALREADY_SET_UP)
        if (!PinPolicy.isValid(pin)) return AdminResult.Denied(AdminDenial.INVALID_PIN)
        if (!persist(AdminPinRecord(credential = hasher.create(pin), bootId = bootId()))) return AdminResult.Denied(AdminDenial.STORAGE_ERROR)
        open()
        return AdminResult.Ok
    }

    @Synchronized fun unlock(pin: String): AdminResult = verified(pin) { open(); AdminResult.Ok }

    /** Explicit change workflow: needs the current PIN (counted against the lockout), then replaces it. A failed save changes nothing. */
    @Synchronized fun changePin(current: String, new: String): AdminResult {
        if (!PinPolicy.isValid(new)) return AdminResult.Denied(AdminDenial.INVALID_PIN)
        return verified(current) {
            if (!persist(record.copy(credential = hasher.create(new)))) AdminResult.Denied(AdminDenial.STORAGE_ERROR) else { open(); AdminResult.Ok }
        }
    }

    private inline fun verified(pin: String, onOk: () -> AdminResult): AdminResult {
        if (record.corrupt) return AdminResult.Denied(AdminDenial.STORAGE_CORRUPT)
        val cred = record.credential ?: return AdminResult.Denied(AdminDenial.NOT_SET_UP)
        val now = elapsed()
        if (record.lockoutMs > 0) {
            if (record.lockedUntilElapsedMs > now) return AdminResult.Denied(AdminDenial.LOCKED, (record.lockedUntilElapsedMs - now + 999) / 1000)
            persist(record.copy(lockoutMs = 0, lockedUntilElapsedMs = 0))   // expired: clear it so a later reboot cannot resurrect it
        }
        if (!hasher.verify(pin, cred)) {
            val n = record.failures + 1
            if (n >= MAX_ATTEMPTS) return lockOut(now)
            // If the counter cannot be stored, a restart would forget it: fail closed by locking now instead.
            if (!persist(record.copy(failures = n))) return lockOut(now)
            return AdminResult.Denied(AdminDenial.WRONG_PIN)
        }
        // Success: reset counters. Only write when something actually changes (no disk write on the common path).
        if (record.failures != 0 || record.lockoutLevel != 0 || record.lockoutMs != 0L) persist(record.copy(failures = 0, lockoutLevel = 0, lockoutMs = 0, lockedUntilElapsedMs = 0))
        return onOk()
    }

    private fun lockOut(now: Long): AdminResult {
        val ms = lockoutMs(record.lockoutLevel)
        persist(record.copy(failures = 0, lockoutLevel = (record.lockoutLevel + 1).coerceAtMost(MAX_LEVEL), lockoutMs = ms, lockedUntilElapsedMs = now + ms, bootId = bootId()))
        return AdminResult.Denied(AdminDenial.LOCKED, ms / 1000)
    }

    private fun open() { hasSession = true; sessionUntil = elapsed() + sessionTtlMs }

    /** Applies [r] in memory only when the store accepted it for credential changes; lockout/counter updates are always kept in memory. */
    private fun persist(r: AdminPinRecord): Boolean {
        val ok = store.save(r)
        if (ok || r.credential == record.credential) { record = r; lockUntil = if (r.lockoutMs > 0) r.lockedUntilElapsedMs else 0; setUpFlag = r.credential != null }
        return ok
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val SESSION_TTL_MS = 5 * 60_000L
        const val ADMIN_ITERATIONS = 120_000
        private const val BASE_LOCKOUT_MS = 30_000L
        private const val MAX_LOCKOUT_MS = 900_000L
        private const val MAX_LEVEL = 50
        fun lockoutMs(level: Int): Long = if (level >= 5) MAX_LOCKOUT_MS else (BASE_LOCKOUT_MS shl level).coerceAtMost(MAX_LOCKOUT_MS)
    }
}

/** Session state for the UI. */
enum class AdminStatus { NOT_SET_UP, LOCKED, UNLOCKED }
fun AdminGate.status(): AdminStatus = when { isCorrupt -> AdminStatus.LOCKED; !isSetUp -> AdminStatus.NOT_SET_UP; isAuthorized() -> AdminStatus.UNLOCKED; else -> AdminStatus.LOCKED }

/** Deny-all authorizer for contexts with no gate wired in (fails closed). */
object NoAuthorizer : Authorizer { override fun isAuthorized() = false; override fun touch() {} }
