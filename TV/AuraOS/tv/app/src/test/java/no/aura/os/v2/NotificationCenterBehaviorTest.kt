package no.aura.os.v2

import no.aura.os.v2.notifications.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class NotificationCenterBehaviorTest {
    private class Memory : CenterStore {
        val data = mutableMapOf<String, CenterSnapshot>()
        override fun load(profile: String) = data[profile] ?: CenterSnapshot()
        override fun save(profile: String, value: CenterSnapshot) { data[profile] = value }
    }
    @Test fun bulk_actions_are_profile_local_and_dismissed_events_stay_seen() {
        val store=Memory(); val center=NotificationCenter(store) { 1000L }
        listOf("a","b").forEach { profile ->
            center.configure(profile,CenterPreferences(enabled=setOf(NotificationCategory.AURA_UPDATE)))
            center.ingest(profile,CenterEvent("update",NotificationCategory.AURA_UPDATE,"feed","Update","Body",900))
        }
        center.markAllRead("a")
        assertEquals(0,center.unread("a")); assertEquals(1,center.unread("b"))
        center.dismissAll("a")
        assertTrue(center.snapshot("a").items.isEmpty())
        assertFalse(center.ingest("a",CenterEvent("update",NotificationCategory.AURA_UPDATE,"feed","Update","Body",900)))
        assertEquals(1,center.snapshot("b").items.size)
    }
    @Test fun dismissedEventsStayDeduplicatedAndProfilesAreIsolated() {
        val store = Memory(); val center = NotificationCenter(store, { 1000L })
        center.configure("a", CenterPreferences(enabled = setOf(NotificationCategory.CONTINUE_WATCHING)))
        val event = CenterEvent("episode:1", NotificationCategory.CONTINUE_WATCHING, "Local playback", "Resume", "A title", 900)
        assertTrue(center.ingest("a", event))
        center.dismiss("a", "episode:1")
        assertFalse(center.ingest("a", event))
        assertEquals(0, center.unread("b"))
        assertTrue(NotificationCenter(store, { 1000L }).snapshot("a").items.isEmpty())
    }
    @Test fun disablingCategoryClearsPendingAndReadStatePersists() {
        val store = Memory(); val center = NotificationCenter(store, { 1000L })
        center.configure("a", CenterPreferences(enabled = setOf(NotificationCategory.AURA_UPDATE)))
        center.ingest("a", CenterEvent("update", NotificationCategory.AURA_UPDATE, "Verified feed", "Update", "Details", 900))
        center.markRead("a", "update")
        assertEquals(0, NotificationCenter(store, { 1000L }).unread("a"))
        center.configure("a", CenterPreferences())
        assertTrue(center.snapshot("a").items.isEmpty())
    }
    @Test fun quietHoursCrossMidnightInProfileTimezone() {
        val quiet = QuietHours(true, 22 * 60, 7 * 60, "Europe/Oslo")
        assertTrue(quiet.contains(java.time.Instant.parse("2026-11-12T22:00:00Z").toEpochMilli()))
        assertFalse(quiet.contains(java.time.Instant.parse("2026-11-12T11:00:00Z").toEpochMilli()))
    }
    @Test fun completionAndFutureReleaseAreSuppressed() {
        val center = NotificationCenter(Memory(), { 1000L })
        center.configure("a", CenterPreferences(enabled = NotificationCategory.values().toSet()))
        assertFalse(center.ingest("a", CenterEvent("done", NotificationCategory.CONTINUE_WATCHING, "Playback", "Title", "", 900, completed = true)))
        assertFalse(center.ingest("a", CenterEvent("future", NotificationCategory.WATCHLIST_MOVIE, "Feed", "Title", "", 2000)))
    }
    @Test fun revokedAccessPurgesMirroredContentAndRejectsFurtherEvents() {
        val center = NotificationCenter(Memory(), { 1000L })
        center.configure("a", CenterPreferences(enabled = setOf(NotificationCategory.SYSTEM)))
        center.setSystemAccess("a", SystemAccess.CONNECTED)
        assertTrue(center.ingest("a", CenterEvent("system:1", NotificationCategory.SYSTEM, "Android", "Title", "", 900)))
        center.setSystemAccess("a", SystemAccess.REVOKED)
        assertTrue(center.snapshot("a").items.isEmpty())
        assertFalse(center.ingest("a", CenterEvent("system:2", NotificationCategory.SYSTEM, "Android", "Title", "", 900)))
    }
}
