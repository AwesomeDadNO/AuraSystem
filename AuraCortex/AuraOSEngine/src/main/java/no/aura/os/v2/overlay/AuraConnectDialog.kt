package no.aura.os.v2.overlay

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.connect.reciever.AuraReceiverService
import com.aura.connect.reciever.SystemPairingManager
import kotlinx.coroutines.delay
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraDimensions
import no.aura.os.v2.theme.AuraTypography
import java.util.Locale

@Composable
fun AuraConnectPairingDialog(context: Context, onClose: () -> Unit, onConnected: () -> Unit) {
    var code by remember { mutableStateOf(SystemPairingManager.startVisibility(context)) }
    var remainingSeconds by remember { mutableStateOf(SystemPairingManager.getRemainingSeconds(context)) }
    val fr = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        val receiver = AuraReceiverService(context)
        receiver.startServer { _, _ -> }
        while (remainingSeconds > 0) {
            delay(1000)
            remainingSeconds = SystemPairingManager.getRemainingSeconds(context)
            if (SystemPairingManager.isDeviceConnected(context)) {
                onConnected()
                onClose()
                break
            }
        }
    }

    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val timeFormatted = String.format(Locale.US, "%d:%02d", minutes, seconds)

    CanvasDialog(onClose) {
        Column(
            Modifier.clip(RoundedCornerShape(AuraDimensions.RadiusContainer))
                .background(AuraColors.SurfaceElevated)
                .padding(AuraDimensions.Space32)
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(AuraDimensions.Space16),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("AuraConnect code", style = AuraTypography.SectionTitle)
            Text("Enter this 6-digit code in the AuraMusic app on your phone to connect and control AuraMusic in AuraOS:", style = AuraTypography.Body, color = AuraColors.TextSecondary)

            Box(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(AuraColors.Surface)
                    .padding(horizontal = 32.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(code, style = AuraTypography.PageTitle.copy(fontSize = 48.sp, letterSpacing = 6.sp), color = AuraColors.PrimaryAction)
            }

            Text("Time remaining: $timeFormatted", style = AuraTypography.Label, color = if (remainingSeconds < 60) AuraColors.AlertRed else AuraColors.TextSecondary)

            val shape = RoundedCornerShape(AuraDimensions.RadiusButton)
            Text(
                "Close", style = AuraTypography.Button, color = AuraColors.OnPrimaryAction,
                modifier = Modifier.padding(top = AuraDimensions.Space8)
                    .focusRequester(fr)
                    .auraFocusable(shape, onClick = onClose)
                    .clip(shape)
                    .background(AuraColors.PrimaryAction)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            )
        }
    }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { fr.requestFocus() } }
}
