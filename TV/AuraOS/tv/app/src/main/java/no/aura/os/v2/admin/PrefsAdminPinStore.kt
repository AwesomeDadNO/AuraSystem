package no.aura.os.v2.admin

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import no.aura.os.v2.profiles.PinCredential

/**
 * SharedPreferences-backed record in its own file (allowBackup is off, so it is not backed up or synced).
 * - Reads never throw: a wrong-typed, partial or malformed credential yields `corrupt = true` (the gate then fails closed), never "no PIN".
 * - Writes use `commit()` so a lockout cannot be lost by killing the process right after a failed attempt, and the result is reported:
 *   `false` means the data may not be on disk. Callers are off the main thread (PIN pad / sign-in dispatcher).
 */
class PrefsAdminPinStore(private val prefs: SharedPreferences) : AdminPinStore {
    constructor(context: Context) : this(context.getSharedPreferences("aura_v2_admin", Context.MODE_PRIVATE))

    override fun load(): AdminPinRecord = try { read() } catch (_: ClassCastException) { AdminPinRecord(corrupt = true) } catch (_: RuntimeException) { AdminPinRecord(corrupt = true) }

    private fun read(): AdminPinRecord {
        val salt = prefs.getString("salt", null).orEmpty(); val hash = prefs.getString("hash", null).orEmpty()
        val credential = when {
            salt.isEmpty() && hash.isEmpty() -> null
            isHex(salt, 32) && isHex(hash, 64) -> PinCredential(salt, hash)
            else -> return AdminPinRecord(corrupt = true)          // half-written or damaged credential
        }
        return AdminPinRecord(
            credential = credential, failures = prefs.getInt("failures", 0), lockoutLevel = prefs.getInt("level", 0),
            lockoutMs = prefs.getLong("lockoutMs", 0), lockedUntilElapsedMs = prefs.getLong("lockedUntilElapsed", 0), bootId = prefs.getString("boot", null).orEmpty(),
        )
    }

    override fun save(record: AdminPinRecord): Boolean = try {
        prefs.edit().putString("salt", record.credential?.salt).putString("hash", record.credential?.hash)
            .putInt("failures", record.failures).putInt("level", record.lockoutLevel)
            .putLong("lockoutMs", record.lockoutMs).putLong("lockedUntilElapsed", record.lockedUntilElapsedMs).putString("boot", record.bootId).commit()
    } catch (_: RuntimeException) { false }

    private fun isHex(s: String, len: Int) = s.length == len && s.all { it in '0'..'9' || it in 'a'..'f' }
}

/**
 * Identifies the current boot so a persisted monotonic deadline is only trusted within the boot that wrote it. Uses the system boot counter
 * (Settings.Global "boot_count"); if it is unavailable the id is "unknown", which the gate treats as "rebooted" (lockouts restart in full: safe).
 */
class AndroidBootId(private val context: Context) {
    fun current(): String = try {
        val n = Settings.Global.getInt(context.contentResolver, "boot_count", -1)
        if (n >= 0) "boot-$n" else "unknown"
    } catch (_: RuntimeException) { "unknown" }
}
