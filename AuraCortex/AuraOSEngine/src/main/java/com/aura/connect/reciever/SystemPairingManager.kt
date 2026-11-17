package com.aura.connect.reciever

import android.content.Context
import java.security.SecureRandom
import java.util.Locale

object SystemPairingManager {
    private const val PREFS_NAME = "aura_system_pairing"
    private const val KEY_CODE = "system_pairing_code"
    private const val KEY_TIME = "system_pairing_time"
    private const val KEY_CONNECTED = "system_pairing_connected"
    private const val EXPIRY_MS = 5 * 60 * 1000L // 5 minutes duration
    private val random = SecureRandom()

    fun getOrGenerateSystemCode(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var code = prefs.getString(KEY_CODE, null)
        val time = prefs.getLong(KEY_TIME, 0L)
        val now = System.currentTimeMillis()

        if (code == null || !code.matches(Regex("[0-9]{6}")) || (now - time > EXPIRY_MS)) {
            code = String.format(Locale.US, "%06d", random.nextInt(1_000_000))
            prefs.edit()
                .putString(KEY_CODE, code)
                .putLong(KEY_TIME, now)
                .putBoolean(KEY_CONNECTED, false)
                .apply()
        }
        return code
    }

    fun startVisibility(context: Context): String {
        return resetSystemCode(context)
    }

    fun resetSystemCode(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = String.format(Locale.US, "%06d", random.nextInt(1_000_000))
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_CODE, code)
            .putLong(KEY_TIME, now)
            .putBoolean(KEY_CONNECTED, false)
            .apply()
        return code
    }

    fun getRemainingSeconds(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val time = prefs.getLong(KEY_TIME, 0L)
        val now = System.currentTimeMillis()
        val elapsedMs = now - time
        val remainingMs = EXPIRY_MS - elapsedMs
        return if (remainingMs > 0) (remainingMs / 1000).toInt() else 0
    }

    fun isDeviceConnected(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (getRemainingSeconds(context) <= 0) return false
        return prefs.getBoolean(KEY_CONNECTED, false)
    }

    fun setDeviceConnected(context: Context, connected: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_CONNECTED, connected).apply()
    }

    fun validateCode(context: Context, input: String?): Boolean {
        if (input == null) return false
        val valid = input.trim() == getOrGenerateSystemCode(context) && getRemainingSeconds(context) > 0
        if (valid) {
            setDeviceConnected(context, true)
        }
        return valid
    }
}
