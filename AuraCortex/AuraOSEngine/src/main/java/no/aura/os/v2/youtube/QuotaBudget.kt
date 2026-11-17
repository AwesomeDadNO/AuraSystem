package no.aura.os.v2.youtube

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Local guard for the daily quota (default 10,000 units; YouTube resets it at midnight Pacific time). Stops calls before they are sent once
 * the budget is spent, and stays exhausted after Google reports the quota as exceeded. In memory: a restart starts a fresh count, and a quota
 * error from Google re-establishes the exhausted state.
 */
class QuotaBudget(private val clock: () -> Long = System::currentTimeMillis, private val dailyLimit: Int = DEFAULT_DAILY_LIMIT) {
    private var day: LocalDate = today()
    private var used = 0

    @Synchronized fun tryCharge(cost: Int): Boolean { rollover(); if (used + cost > dailyLimit) return false; used += cost; return true }
    @Synchronized fun markExhausted() { rollover(); used = dailyLimit }
    @Synchronized fun remaining(): Int { rollover(); return dailyLimit - used }

    private fun rollover() { val t = today(); if (t != day) { day = t; used = 0 } }
    private fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(PACIFIC).toLocalDate()

    companion object { const val DEFAULT_DAILY_LIMIT = 10_000; private val PACIFIC: ZoneId = ZoneId.of("America/Los_Angeles") }
}
