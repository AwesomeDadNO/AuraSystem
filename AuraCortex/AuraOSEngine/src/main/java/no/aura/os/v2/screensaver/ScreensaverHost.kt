package no.aura.os.v2.screensaver

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import no.aura.os.v2.amp.AmpPlayback
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.data.Artwork
import no.aura.os.v2.demo.ArtworkImage
import no.aura.os.v2.overlay.CanvasDialog
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraDimensions
import no.aura.os.v2.theme.AuraTypography

/**
 * Fullscreen Video Screensaver Host with repositionable Mini Player & Settings Popup.
 * Auto-hides Settings Button after 12s; DPAD interaction resets auto-hide timer.
 * Only BACK / ESCAPE keys exit screensaver mode.
 */
@Composable
fun ScreensaverHost(
    playback: AmpPlayback?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { PrefsScreensaverStore(context) }
    var settings by remember { mutableStateOf(store.load()) }
    var settingsDialogOpen by remember { mutableStateOf(false) }

    // Auto-hide controls timer
    var controlsVisible by remember { mutableStateOf(true) }
    var lastActivityMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(lastActivityMs, settingsDialogOpen) {
        if (!settingsDialogOpen) {
            delay(12_000) // 12 seconds auto-hide
            controlsVisible = false
        } else {
            controlsVisible = true
        }
    }

    // Video Playlist / Rotation logic
    var activeVideoIndex by remember { mutableIntStateOf(0) }
    val rotateMode = settings.selectedVideoId == ScreensaverCatalog.ROTATE_ALL_ID

    val activeVideo = remember(settings.selectedVideoId, activeVideoIndex) {
        if (rotateMode) {
            ScreensaverCatalog.VIDEOS.getOrNull(activeVideoIndex % ScreensaverCatalog.VIDEOS.size)
                ?: ScreensaverCatalog.VIDEOS.first()
        } else {
            ScreensaverCatalog.find(settings.selectedVideoId)
        }
    }

    // Auto rotate every 20 seconds if in rotate mode
    LaunchedEffect(rotateMode, activeVideoIndex) {
        if (rotateMode) {
            delay(20_000)
            activeVideoIndex = (activeVideoIndex + 1) % ScreensaverCatalog.VIDEOS.size
        }
    }

    // Media3 ExoPlayer for screensaver video
    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f // Muted video background
        }
    }

    DisposableEffect(activeVideo) {
        val mediaUri = activeVideo.resolvedUri()
        val mediaItem = MediaItem.fromUri(mediaUri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        onDispose {
            exoPlayer.stop()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    val closeFocus = remember { FocusRequester() }

    CanvasDialog(onDismissRequest = onClose, fullScreen = true) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { event ->
                    // Reset auto-hide timer on any DPAD key press
                    lastActivityMs = System.currentTimeMillis()
                    controlsVisible = true
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                        onClose()
                        true
                    } else false
                }
        ) {
            // Fullscreen video background
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        exoPlayer.setVideoTextureView(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Dark vignette overlay to ensure mini player & text readability
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.4f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.5f)
                            )
                        )
                    )
            )

            // Top Center: Settings Button (Auto-hides after 12s, reappears on DPAD activity)
            if (controlsVisible || settingsDialogOpen) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 28.dp)
                ) {
                    val shape = RoundedCornerShape(20.dp)
                    Row(
                        Modifier
                            .focusRequester(closeFocus)
                            .auraFocusable(shape, onClick = {
                                controlsVisible = true
                                lastActivityMs = System.currentTimeMillis()
                                settingsDialogOpen = true
                            })
                            .clip(shape)
                            .background(AuraColors.ButtonInactiveBg)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Settings, null, tint = AuraColors.ButtonInactiveIcon, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Screensaver Settings", style = AuraTypography.Button, color = AuraColors.ButtonInactiveIcon)
                    }
                }
            }

            // Repositionable Mini Player
            if (settings.playerCorner != PlayerCorner.OFF && playback != null) {
                val cornerAlignment = when (settings.playerCorner) {
                    PlayerCorner.TOP_LEFT -> Alignment.TopStart
                    PlayerCorner.TOP_RIGHT -> Alignment.TopEnd
                    PlayerCorner.BOTTOM_LEFT -> Alignment.BottomStart
                    PlayerCorner.BOTTOM_RIGHT -> Alignment.BottomEnd
                    PlayerCorner.OFF -> Alignment.TopStart
                }

                Box(
                    Modifier
                        .align(cornerAlignment)
                        .padding(36.dp)
                ) {
                    ScreensaverMiniPlayer(playback = playback)
                }
            }

            // Video Title & Credits at Bottom Center
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).height(18.dp).background(AuraColors.BrandOrange))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "SCREENSAVER  ·  ${activeVideo.title.uppercase()}",
                        style = AuraTypography.Label,
                        color = Color.White.copy(alpha = 0.85f),
                        letterSpacing = 2.sp
                    )
                }
            }

            // Settings Popup Dialog
            if (settingsDialogOpen) {
                ScreensaverSettingsPopup(
                    currentSettings = settings,
                    onSave = { updated ->
                        settings = updated
                        store.save(updated)
                        settingsDialogOpen = false
                    },
                    onDismiss = { settingsDialogOpen = false }
                )
            }
        }
    }
}

@Composable
private fun ScreensaverMiniPlayer(playback: AmpPlayback) {
    val currentTrack = playback.selected
    val playing = playback.playing
    val shape = RoundedCornerShape(18.dp)

    Column(
        Modifier
            .width(440.dp)
            .clip(shape)
            .background(Color(0xFF141923).copy(alpha = 0.92f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album Art Thumbnail
            Box(
                Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AuraColors.Surface)
            ) {
                val cover = currentTrack?.cover
                if (cover != null) {
                    ArtworkImage(Artwork(imageUrl = cover), Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Rounded.MusicNote, null, tint = AuraColors.TextMuted, modifier = Modifier.align(Alignment.Center))
                }
            }

            Spacer(Modifier.width(14.dp))

            // Title & Artist
            Column(Modifier.weight(1f)) {
                Text(
                    currentTrack?.title ?: "No track playing",
                    style = AuraTypography.CardTitle.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    currentTrack?.artist ?: "AuraAmp",
                    style = AuraTypography.Label.copy(fontSize = 15.sp),
                    color = AuraColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(10.dp))

            // Compact Controls: Previous, Play/Pause, Next
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val btnShape = CircleShape

                // Rewind / Previous
                Box(
                    Modifier
                        .size(40.dp)
                        .auraFocusable(btnShape, onClick = playback::previous)
                        .clip(btnShape)
                        .background(AuraColors.ButtonInactiveBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous", tint = AuraColors.ButtonInactiveIcon, modifier = Modifier.size(24.dp))
                }

                // Play / Pause
                Box(
                    Modifier
                        .size(48.dp)
                        .auraFocusable(btnShape, onClick = playback::toggle)
                        .clip(btnShape)
                        .background(AuraColors.OrangeGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(28.dp))
                }

                // Fast Forward / Skip Next
                Box(
                    Modifier
                        .size(40.dp)
                        .auraFocusable(btnShape, onClick = playback::next)
                        .clip(btnShape)
                        .background(AuraColors.ButtonInactiveBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.SkipNext, "Next", tint = AuraColors.ButtonInactiveIcon, modifier = Modifier.size(24.dp))
                }
            }
        }

        // Waveform Scrobble Bar (matching reference image)
        ScreensaverWaveform(
            positionMs = playback.positionMs,
            durationMs = playback.durationMs,
            onSeek = playback::seek
        )
    }
}

@Composable
private fun ScreensaverWaveform(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit
) {
    val positionSec = (positionMs / 1000).toInt()
    val durationSec = (durationMs / 1000).toInt()
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    fun formatTime(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return "%d:%02d".format(m, s)
    }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            formatTime(positionSec),
            style = AuraTypography.Label.copy(fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
        )

        Canvas(
            Modifier
                .weight(1f)
                .height(38.dp)
                .auraFocusable(RoundedCornerShape(6.dp)) {
                    onSeek((positionMs + 10_000).coerceAtMost(durationMs))
                }
        ) {
            val bars = 48
            val step = size.width / bars.toFloat()
            repeat(bars) { i ->
                val x = (i + .5f) * step
                val wave = kotlin.math.abs(kotlin.math.sin(i * .31) * kotlin.math.cos(i * .085))
                val envelope = .25f + .75f * kotlin.math.sin(Math.PI * (i + 1) / (bars + 1)).toFloat()
                val barHeight = (10f + wave.toFloat() * size.height * .80f) * envelope
                val isPlayed = (i / bars.toFloat()) < progress
                val color = if (isPlayed) Color(0xFFFFB856) else Color.White.copy(alpha = 0.25f)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, size.height / 2 - barHeight / 2),
                    size = Size(step * 0.55f, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
        }

        Text(
            formatTime(durationSec),
            style = AuraTypography.Label.copy(fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
        )
    }
}

@Composable
private fun ScreensaverSettingsPopup(
    currentSettings: ScreensaverSettings,
    onSave: (ScreensaverSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedVideoId by remember { mutableStateOf(currentSettings.selectedVideoId) }
    var selectedCorner by remember { mutableStateOf(currentSettings.playerCorner) }
    var selectedTimeout by remember { mutableStateOf(currentSettings.autoStartTimeout) }
    val firstFocus = remember { FocusRequester() }

    CanvasDialog(onDismissRequest = onDismiss) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { firstFocus.requestFocus() }
        }

        Column(
            Modifier
                .width(580.dp)
                .clip(RoundedCornerShape(AuraDimensions.RadiusContainer))
                .background(AuraColors.SurfaceElevated)
                .padding(28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Screensaver Settings", style = AuraTypography.SectionTitle, color = Color.White)

            // Section 1: Video Selection
            Text("Video Theme", style = AuraTypography.Label, color = AuraColors.BrandOrange, fontWeight = FontWeight.Bold)
            val videoOptions = listOf(ScreensaverCatalog.ROTATE_ALL_ID to "Rotate All Videos (20s)") +
                    ScreensaverCatalog.VIDEOS.map { it.id to it.title }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                videoOptions.take(8).forEachIndexed { index, (id, title) ->
                    val isSelected = selectedVideoId == id
                    val shape = RoundedCornerShape(10.dp)
                    Text(
                        (if (isSelected) "✓  " else "     ") + title,
                        style = AuraTypography.Button,
                        color = if (isSelected) Color.White else AuraColors.TextSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                            .auraFocusable(shape, onClick = { selectedVideoId = id })
                            .clip(shape)
                            .background(if (isSelected) AuraColors.NavPill else AuraColors.Surface)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }

            // Section 2: Mini Player Corner
            Text("Mini Player Position", style = AuraTypography.Label, color = AuraColors.BrandOrange, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerCorner.values().forEach { corner ->
                    val isSelected = selectedCorner == corner
                    val shape = RoundedCornerShape(10.dp)
                    Text(
                        corner.label.take(12),
                        style = AuraTypography.Label,
                        color = if (isSelected) Color.White else AuraColors.TextSecondary,
                        modifier = Modifier
                            .weight(1f)
                            .auraFocusable(shape, onClick = { selectedCorner = corner })
                            .clip(shape)
                            .background(if (isSelected) AuraColors.NavPill else AuraColors.Surface)
                            .padding(vertical = 10.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            // Section 3: Music Playback Auto-Start Timeout
            Text("Auto-Start Timeout during Playback", style = AuraTypography.Label, color = AuraColors.BrandOrange, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AutoStartTimeout.values().forEach { timeout ->
                    val isSelected = selectedTimeout == timeout
                    val shape = RoundedCornerShape(10.dp)
                    Text(
                        timeout.label,
                        style = AuraTypography.Label,
                        color = if (isSelected) Color.White else AuraColors.TextSecondary,
                        modifier = Modifier
                            .weight(1f)
                            .auraFocusable(shape, onClick = { selectedTimeout = timeout })
                            .clip(shape)
                            .background(if (isSelected) AuraColors.NavPill else AuraColors.Surface)
                            .padding(vertical = 10.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Action Buttons: Save & Cancel
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val shape = RoundedCornerShape(AuraDimensions.RadiusButton)
                Text(
                    "Save",
                    style = AuraTypography.Button,
                    color = Color.White,
                    modifier = Modifier
                        .weight(1f)
                        .auraFocusable(shape, onClick = {
                            onSave(ScreensaverSettings(selectedVideoId, selectedCorner, selectedTimeout))
                        })
                        .clip(shape)
                        .background(AuraColors.OrangeGradient)
                        .padding(vertical = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Text(
                    "Cancel",
                    style = AuraTypography.Button,
                    color = AuraColors.TextPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .auraFocusable(shape, onClick = onDismiss)
                        .clip(shape)
                        .background(AuraColors.ButtonInactiveBg)
                        .padding(vertical = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}
