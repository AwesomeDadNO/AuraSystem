package no.aura.os.v2

import no.aura.os.v2.notifications.*
import org.junit.Assert.*
import org.junit.Test

class NotificationInboxPresentationTest {
    private fun item(id: String, time: Long, read: Boolean = false) = CenterItem(
        CenterEvent(id, NotificationCategory.AURA_UPDATE, "AuraOS", id, "", time), read)

    @Test fun unreadTabExcludesReadEventsWithoutChangingAllTab() {
        val items = listOf(item("old", 1), item("read", 3, true), item("new", 2))
        assertEquals(listOf("new", "old"), notificationInboxItems(items, true).map { it.event.id })
        assertEquals(listOf("read", "new", "old"), notificationInboxItems(items, false).map { it.event.id })
        assertEquals(listOf("old", "read", "new"), items.map { it.event.id })
    }

    @Test fun sameTimeEventsKeepTheirSourceOrderAndAllReadProducesEmptyUnreadTab() {
        val items = listOf(item("first", 2, true), item("second", 2, true))
        assertEquals(items, notificationInboxItems(items, false))
        assertTrue(notificationInboxItems(items, true).isEmpty())
    }
}
