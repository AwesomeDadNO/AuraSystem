package no.aura.os.v2.notifications

/** Newest first in either tab; equal timestamps retain source order. Does not alter stored state. */
fun notificationInboxItems(items: List<CenterItem>, unreadOnly: Boolean): List<CenterItem> =
    items.filter { !unreadOnly || !it.read }.sortedByDescending { it.event.occurredAt }
