package no.aura.os.v2.overlay

import androidx.compose.ui.graphics.Brush

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.data.PlayerTrack
import no.aura.os.v2.demo.ArtworkImage
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraTypography

/**
 * The UI's demo clock only; AuraShell MUST hide this entire simulated component in Real Mode.
 * Real AuraAmp progress must come from a proper playback service/controller when integrated.
 */
@Stable
class DemoClock { var positionSec by mutableFloatStateOf(0f); fun reset() { positionSec = 0f } }

object PlayerSpec {
    val ExpandedWidth = 440.dp; val ExpandedHeight = 154.dp
    val CollapsedHeight = 84.dp
    val MarginEnd = 22.dp; val MarginBottom = 22.dp
}

/** Floating AuraAmp preview matching the approved orange/black TV mockup. Demo Mode only. */
@Composable
fun CompactPlayer(
    state: MiniPlayerState, tracks: List<PlayerTrack>, clock: DemoClock,
    onAction: (PlayerAction) -> Unit, modifier: Modifier = Modifier,
) {
    if (state.visibility == PlayerVisibility.HIDDEN || tracks.isEmpty()) return
    val track = tracks[state.trackIndex.coerceIn(0, tracks.lastIndex)]
    LaunchedEffect(state.playing, track.id) {
        while (state.playing) {
            delay(250)
            clock.positionSec = if (clock.positionSec + 0.25f >= track.durationSec) 0f else clock.positionSec + 0.25f
        }
    }
    LaunchedEffect(track.id) { clock.reset() }
    val expanded = state.visibility == PlayerVisibility.EXPANDED
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier.requiredSize(PlayerSpec.ExpandedWidth, if (expanded) PlayerSpec.ExpandedHeight else PlayerSpec.CollapsedHeight)
            .clip(shape).background(AuraColors.Panel)
            .border(1.dp, AuraColors.Focus.copy(alpha = 0.86f), shape)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(10.dp))) {
                ArtworkImage(track.artwork)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("AuraAmp", style = AuraTypography.Label.copy(color = AuraColors.Focus), maxLines = 1)
                Text(track.title, style = AuraTypography.CardTitle.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = AuraTypography.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SmallButton(Icons.Rounded.SkipPrevious, "Previous") { onAction(PlayerAction.Previous) }
            PlayButton(state.playing) { onAction(PlayerAction.TogglePlay) }
            SmallButton(Icons.Rounded.SkipNext, "Next") { onAction(PlayerAction.Next) }
            SmallButton(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                if (expanded) "Collapse" else "Expand", 19.dp) {
                onAction(if (expanded) PlayerAction.Collapse else PlayerAction.Expand)
            }
        }
        if (expanded) {
            WaveformSeek(clock, track.durationSec.toFloat())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(clock.positionSec), style = AuraTypography.Label.copy(fontSize = 13.sp))
                Text(formatTime(track.durationSec.toFloat()), style = AuraTypography.Label.copy(fontSize = 13.sp))
            }
        }
    }
}

private fun formatTime(seconds: Float): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

/**
 * TV-remote-seekable waveform-style progress visualization. The demo bars are original procedural
 * UI geometry, not purported analysis of the song. LEFT/RIGHT jumps 5 seconds.
 */
@Composable
private fun WaveformSeek(clock: DemoClock, duration: Float) {
    val shape = RoundedCornerShape(6.dp)
    var focused by remember { mutableStateOf(false) }
    val progress = if (duration > 0f) (clock.positionSec / duration).coerceIn(0f, 1f) else 0f
    Canvas(
        Modifier.fillMaxWidth().height(23.dp).onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false else when (e.key) {
                    Key.DirectionLeft -> { clock.positionSec = (clock.positionSec - 5f).coerceAtLeast(0f); true }
                    Key.DirectionRight -> { clock.positionSec = (clock.positionSec + 5f).coerceAtMost(duration); true }
                    else -> false
                }
            }
            .auraFocusable(shape, scale = 1f, elevation = 0.dp) {
                clock.positionSec = (clock.positionSec + 5f).coerceAtMost(duration)
            }
    ) {
        val bars = 64
        val step = size.width / bars
        val w = (step * 0.40f).coerceAtLeast(2f)
        for (i in 0 until bars) {
            val beat = ((i * 17 + 7) % 11) / 11f
            val height = size.height * (0.26f + 0.64f * beat)
            val x = (i + 0.5f) * step
            val played = (i + 0.5f) / bars.toFloat() <= progress
            drawLine(
                color = if (played) AuraColors.Focus else AuraColors.TextMuted.copy(alpha = 0.55f),
                start = Offset(x, (size.height - height) / 2f),
                end = Offset(x, (size.height + height) / 2f),
                strokeWidth = if (focused) w + 1f else w,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun SmallButton(icon: ImageVector, label: String, iconSize: androidx.compose.ui.unit.Dp = 22.dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.auraFocusable(shape, scale = 1.07f, elevation = 2.dp, onClick = onClick).clip(shape).padding(2.dp)) {
        Icon(icon, label, tint = AuraColors.TextPrimary, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).auraFocusable(CircleShape, scale = 1.06f, elevation = 2.dp, onClick = onClick)
        .clip(CircleShape).background(Brush.horizontalGradient(listOf(AuraColors.Focus.copy(alpha = .55f), AuraColors.Focus))), contentAlignment = Alignment.Center) {
        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (playing) "Pause" else "Play", tint = AuraColors.TextPrimary, modifier = Modifier.size(24.dp))
    }
}
