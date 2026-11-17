package com.aura.connect.reciever

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

object SystemPairingManager {
    private const val PREFS_NAME = "aura_system_pairing"
    private const val KEY_CODE = "system_pairing_code"
    private val random = SecureRandom()

    fun getOrGenerateSystemCode(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var code = prefs.getString(KEY_CODE, null)
        if (code == null || !code.matches(Regex("[0-9]{6}"))) {
            code = String.format(java.util.Locale.US, "%06d", random.nextInt(1_000_000))
            prefs.edit().putString(KEY_CODE, code).apply()
        }
        return code
    }

    fun resetSystemCode(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = String.format(java.util.Locale.US, "%06d", random.nextInt(1_000_000))
        prefs.edit().putString(KEY_CODE, code).apply()
        return code
    }

    fun validateCode(context: Context, input: String?): Boolean {
        if (input == null) return false
        return input.trim() == getOrGenerateSystemCode(context)
    }
}
