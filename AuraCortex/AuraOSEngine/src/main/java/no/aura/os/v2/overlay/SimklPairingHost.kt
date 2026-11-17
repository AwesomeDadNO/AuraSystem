package no.aura.os.v2.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.simkl.SimklPairingController
import no.aura.os.v2.theme.*

@Composable
fun SimklPairingHost(session: SimklPairingController, onClose: () -> Unit) {
    val view by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    val first = remember(session) { FocusRequester() }
    LaunchedEffect(session) { session.run() }
    DisposableEffect(session) { onDispose { session.close() } }
    CanvasDialog({ session.close(); onClose() }, dismissOnClickOutside = false) {
        Column(Modifier.widthIn(max = 760.dp).background(AuraColors.SurfaceElevated, RoundedCornerShape(20.dp)).padding(36.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Connect Simkl", style = AuraTypography.SectionTitle)
            Text(view.verificationUrl, style = AuraTypography.Body)
            Text(view.userCode, style = AuraTypography.PageTitle)
            Text(view.message, style = AuraTypography.Body, color = AuraColors.TextSecondary)
            Text("The code stays here until approved or you close this window.", style = AuraTypography.Label)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(if (view.checking) "Checking…" else "Check approval", style = AuraTypography.Button,
                    modifier = Modifier.focusRequester(first).auraFocusable(RoundedCornerShape(12.dp)) { scope.launch { session.check() } }.padding(16.dp))
                Text("Close", style = AuraTypography.Button, modifier = Modifier.auraFocusable(RoundedCornerShape(12.dp)) { session.close(); onClose() }.padding(16.dp))
            }
        }
        LaunchedEffect(session) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
}
