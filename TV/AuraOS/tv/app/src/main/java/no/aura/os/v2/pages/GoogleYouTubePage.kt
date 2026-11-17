package no.aura.os.v2.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.focus.leftToRail
import no.aura.os.v2.google.GoogleFlowState
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraTypography

/** Public user code only. device_code and tokens never enter displayed text. Cancel host job on page/profile exit. */
@Composable
fun GoogleYouTubePage(state: GoogleFlowState, entry: FocusRequester, onConnect: () -> Unit, onCancel: () -> Unit,
    onRefresh: () -> Unit, onDisconnect: () -> Unit, onPlaylist: (String) -> Unit, onVideo: (String) -> Unit,
    modifier: Modifier = Modifier, onChannel: (String) -> Unit = {}) {
    LazyColumn(modifier.fillMaxSize().padding(start = 150.dp, top = 120.dp, end = 60.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Google account & YouTube", style = AuraTypography.PageTitle) }
        item { Text(state.message, style = AuraTypography.CardTitle) }
        state.code?.let { code ->
            item { Text(code.verificationUrl, style = AuraTypography.CardTitle) }
            item { Text("Enter code: ${code.userCode}", style = AuraTypography.PageTitle) }
            item { Text("Only enter this code on Google's authorization page. Never share passwords here.", style = AuraTypography.Label) }
        }
        item { GoogleAction(if (state.busy) "Cancel sign-in" else "Connect Google account", Modifier.focusRequester(entry), if (state.busy) onCancel else onConnect) }
        if (state.connected) {
            item { GoogleAction("Refresh my playlists", onClick = onRefresh) }
            item { GoogleAction("Disconnect and revoke", onClick = onDisconnect) }
        }
        item { Text("Your Google consent grants read-only YouTube access. Watch Later and private Google TV Watchlist are not imported. Public browsing remains separate.", style = AuraTypography.Label) }
        if (state.subscriptions.isNotEmpty()) item { Text("Subscriptions", style = AuraTypography.SectionTitle) }
        items(state.subscriptions, key = { "channel:${it.first}" }) { row -> GoogleAction(row.second, onClick = { onChannel(row.first) }) }
        if (state.playlists.isNotEmpty()) item { Text("Playlists", style = AuraTypography.SectionTitle) }
        items(state.playlists, key = { "playlist:${it.first}" }) { row -> GoogleAction(row.second, onClick = { onPlaylist(row.first) }) }
        items(state.videos, key = { "video:${it.first}" }) { row -> GoogleAction(row.second, onClick = { onVideo(row.first) }) }
    }
}
@Composable
private fun GoogleAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(label, style = AuraTypography.CardTitle, modifier = modifier.fillMaxWidth().leftToRail(true).auraFocusable(shape, onClick = onClick).background(AuraColors.Surface, shape).padding(20.dp))
}
