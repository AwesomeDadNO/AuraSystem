package no.aura.os.v2.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.focus.leftToRail
import no.aura.os.v2.notifications.*
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraTypography
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Host owns profile data and routing. [profileKey] resets the local view on a profile switch. */
@Composable
fun NotificationCenterPage(state: CenterSnapshot, entry: FocusRequester, onToggle: (NotificationCategory) -> Unit,
    onQuietHours: () -> Unit, onSystemSettings: () -> Unit, onRefresh: () -> Unit, onRead: (String) -> Unit,
    onDismiss: (String) -> Unit, onOpen: (String) -> Unit, modifier: Modifier = Modifier,
    profileKey: String = "", onReadAll: () -> Unit = {}, onDismissAll: () -> Unit = {}) {
    var settings by remember(profileKey) { mutableStateOf(false) }
    var unreadOnly by remember(profileKey) { mutableStateOf(false) }
    val settingsEntry = remember { FocusRequester() }
    fun returnToInbox() { settings = false }
    BackHandler(enabled = settings) { returnToInbox() }
    LaunchedEffect(settings, profileKey) {
        withFrameNanos { }
        runCatching { if (settings) settingsEntry.requestFocus() else entry.requestFocus() }
    }
    Column(modifier.fillMaxSize().background(AuraColors.Background)
        .padding(start = 150.dp, top = 112.dp, end = 60.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(if (settings) "Notification settings" else "Notifications", style = AuraTypography.PageTitle)
            if (!settings) Text("${state.items.count { !it.read }} unread", style = AuraTypography.Metadata)
        }
        if (settings) {
            NotificationAction("Back to inbox", { returnToInbox() }, Modifier.focusRequester(settingsEntry), firstInRow = true)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { Text("Choose what reaches this profile", style = AuraTypography.SectionTitle) }
                items(NotificationCategory.values().toList(), key = { it.name }) { category ->
                    Column(Modifier.fillMaxWidth().background(AuraColors.Surface, RoundedCornerShape(14.dp)).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NotificationAction("${category.label} · ${if (category in state.preferences.enabled) "On" else "Off"}", { onToggle(category) })
                        Text(if (category == NotificationCategory.SYSTEM) systemAccessLabel(state.systemAccess)
                            else state.sourceStates[category] ?: "No source connected yet.", style = AuraTypography.Metadata)
                        if (category == NotificationCategory.SYSTEM) {
                            NotificationAction("Manage Android notification access", onSystemSettings)
                            Text("Optional access to system notifications. Private and earlier notifications may be unavailable. This does not allow AuraOS to post notifications.", style = AuraTypography.Label)
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Quiet hours", style = AuraTypography.SectionTitle)
                        NotificationAction("${if (state.preferences.quietHours.enabled) "On" else "Off"} · ${minuteLabel(state.preferences.quietHours.startMinute)}–${minuteLabel(state.preferences.quietHours.endMinute)}", onQuietHours)
                        Text(state.preferences.quietHours.zone, style = AuraTypography.Metadata)
                    }
                }
                item { NotificationAction("Refresh notifications", onRefresh) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                NotificationAction("All", { unreadOnly = false }, Modifier.focusRequester(entry), firstInRow = true, active = !unreadOnly)
                NotificationAction("Unread", { unreadOnly = true }, active = unreadOnly)
                NotificationAction("Mark all as read", onReadAll)
                NotificationAction("Dismiss all", onDismissAll)
                Spacer(Modifier.weight(1f))
                NotificationAction("Settings", { settings = true })
            }
            val visible = notificationInboxItems(state.items, unreadOnly)
            if (visible.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f).background(AuraColors.Surface, RoundedCornerShape(18.dp)).padding(40.dp),
                    verticalArrangement = Arrangement.Center) {
                    Text(if (unreadOnly) "You're all caught up" else "Nothing here yet", style = AuraTypography.SectionTitle)
                    Spacer(Modifier.height(12.dp))
                    Text(if (unreadOnly) "Your read notifications are in All." else "New notifications will appear here. Choose what you'd like to receive in Settings.", style = AuraTypography.Body)
                }
            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(4.dp)) {
                items(visible, key = { it.event.id }) { row ->
                    val event = row.event
                    val shape = RoundedCornerShape(16.dp)
                    Row(Modifier.fillMaxWidth().background(AuraColors.Surface, shape).border(1.dp, AuraColors.CardBorder, shape).padding(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text(if (row.read) "○" else "●", color = if (row.read) AuraColors.TextMuted else AuraColors.Focus, style = AuraTypography.CardTitle)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${event.source} · ${event.category.label}", style = AuraTypography.Metadata)
                            Text(event.title, style = AuraTypography.SectionTitle)
                            if (event.body.isNotBlank()) Text(event.body, style = AuraTypography.Body)
                            Text(DateTimeFormatter.ofPattern("d MMM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(event.occurredAt)), style = AuraTypography.Label)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                event.destinationKey?.let { destination -> NotificationAction("Open", { onRead(event.id); onOpen(destination) }, firstInRow = true) }
                                if (!row.read) NotificationAction("Mark as read", { onRead(event.id) }, firstInRow = event.destinationKey == null)
                                NotificationAction("Dismiss", { onDismiss(event.id) }, firstInRow = row.read && event.destinationKey == null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, firstInRow: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    Text(label, style = AuraTypography.Button, modifier = modifier.semantics { selected = active }.leftToRail(firstInRow)
        .auraFocusable(shape, scale = 1.02f, onClick = onClick)
        .background(if (active) AuraColors.NavPill else AuraColors.SurfaceElevated, shape)
        .padding(horizontal = 22.dp, vertical = 14.dp))
}

private fun systemAccessLabel(access: SystemAccess): String = when (access) {
    SystemAccess.UNAVAILABLE -> "System notification access is unavailable on this device."
    SystemAccess.NOT_GRANTED -> "Android notification access has not been granted."
    SystemAccess.CONNECTED -> "Android notification access is connected."
    SystemAccess.DISCONNECTED -> "Android notification access is disconnected."
    SystemAccess.REVOKED -> "Android notification access has been revoked."
}
private fun minuteLabel(minute: Int): String = "%02d:%02d".format(java.util.Locale.ROOT, minute / 60, minute % 60)
