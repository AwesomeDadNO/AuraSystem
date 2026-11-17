package no.aura.os.v2.overlay

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.media.MediaDataSource
import android.os.Bundle
import androidx.compose.ui.platform.LocalContext
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import no.aura.os.v2.demo.ArtworkImage
import no.aura.os.v2.brand.AuraBrandLabel
import no.aura.os.v2.brand.BrandLabels
import no.aura.os.v2.theme.LocalReduceMotion
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.cards.auraGlass
import no.aura.os.v2.playback.*
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraDimensions
import no.aura.os.v2.theme.AuraTypography

private enum class Menu { NONE, AUDIO, SUBTITLES, SOURCES }

/**
 * Simple full-screen video player (separate Dialog window: focus is trapped, Back closes). Controls: play/pause, +/-10 s, scrubber with the
 * Aura orange progress, audio and subtitle menus, close. They auto-hide 4 s after the last key while playing; position is polled (500 ms)
 * ONLY while the controls are visible. The player is released when this leaves composition.
 */
@Composable
fun VideoPlayerHost(title: String, subtitle: String?, source: String, prefs: PlaybackPrefs, onClose: () -> Unit, onProgress: ((Long, Long) -> Unit)? = null, startPositionMs: Long = 0L,
    alternatives: List<PlayerSource> = emptyList(), onSourceSelected: ((PlayerSource, Long) -> Unit)? = null,
    mediaSource: MediaDataSource? = null,
    onPlaybackState: ((Long, Long, Boolean, PlayerStatus) -> Unit)? = null,
    onStopped: ((Long, Long) -> Unit)? = null, notice: String? = null,
    metadata: PlaybackMetadata = PlaybackMetadata(),
) {
    val context = LocalContext.current
    val model = remember(source, mediaSource) { PlayerModel() }
    val engine = remember(source, mediaSource) { PlayerEngine(context, model, prefs).also { if (mediaSource != null) it.open(mediaSource, autoStart = true) else it.open(source, autoStart = true) } }
    val progressSink by rememberUpdatedState(onProgress)
    val stateSink by rememberUpdatedState(onPlaybackState)
    val stopSink by rememberUpdatedState(onStopped)
    val closeSink by rememberUpdatedState(onClose)
    val activity = remember(context) { playerActivity(context) }
    val sources = PlayerSourcePolicy.choices(alternatives)
    DisposableEffect(engine, activity) {
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(a: Activity) { if (a === activity) { engine.pause(); closeSink() } }
            override fun onActivityDestroyed(a: Activity) { if (a === activity) engine.release() }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
        }
        activity?.application?.registerActivityLifecycleCallbacks(callbacks)
        onDispose { activity?.application?.unregisterActivityLifecycleCallbacks(callbacks) }
    }
    val resumeFrom = remember(engine) { startPositionMs }
    DisposableEffect(engine) { onDispose {
        if (model.status == PlayerStatus.ENDED && model.durationMs > 0) progressSink?.invoke(model.durationMs, model.durationMs)
        else if (model.status == PlayerStatus.READY) progressSink?.invoke(engine.positionMs(), model.durationMs)
        stopSink?.invoke(if (model.status == PlayerStatus.ENDED) model.durationMs else engine.positionMs(), model.durationMs)
        engine.release()
    } }

    // One banner per mounted playback session, independent of pauses, buffering and source changes.
    var advisoryShown by remember { mutableStateOf(false) }
    var advisoryVisible by remember { mutableStateOf(false) }
    val advisory = PlaybackAdvisoryPolicy.banner(metadata)
    LaunchedEffect(model.status, model.playing) {
        if (!advisoryShown && model.status == PlayerStatus.READY && model.playing) {
            advisoryShown = true
            // Missing metadata must not consume the only display opportunity silently.
            // The policy supplies honest unknown text, updated in-place if metadata arrives.
            advisoryVisible = true
        }
    }
    LaunchedEffect(advisoryVisible) {
        if (advisoryVisible) { delay(PlaybackAdvisoryPolicy.DISPLAY_MS); advisoryVisible = false }
    }

    var controls by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(Menu.NONE) }
    var lastKey by remember { mutableIntStateOf(0) }
    var position by remember { mutableLongStateOf(0L) }
    val root = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    var resumeApplied by remember(engine) { mutableStateOf(false) }
    // Resume the genuine position after the player has loaded a seekable timeline.
    LaunchedEffect(engine, model.status) {
        if (model.status == PlayerStatus.READY && !resumeApplied) {
            resumeApplied = true
            if (resumeFrom > 0L && model.durationMs > resumeFrom) engine.seekTo(resumeFrom)
        }
    }
    LaunchedEffect(engine, model.status, model.playing) {
        if (model.status == PlayerStatus.READY || model.status == PlayerStatus.ENDED) {
            stateSink?.invoke(engine.positionMs(), model.durationMs, model.playing, model.status)
        }
    }
    // Auto-hide only while playing and no menu is open.
    LaunchedEffect(lastKey, model.playing, menu, controls) { if (controls && model.playing && menu == Menu.NONE) { delay(4_000); controls = false } }
    LaunchedEffect(model.status) {
        if (model.status == PlayerStatus.ENDED && model.durationMs > 0) progressSink?.invoke(model.durationMs, model.durationMs)
    }
    // Persist actual playback position independently of the overlay controls (never invent progress).
    LaunchedEffect(engine) {
        while (true) {
            delay(5_000)
            if (model.status == PlayerStatus.READY) {
                if (model.playing) progressSink?.invoke(engine.positionMs(), model.durationMs)
                stateSink?.invoke(engine.positionMs(), model.durationMs, model.playing, model.status)
            }
        }
    }
    // Position polling only while there is something to show it on.
    LaunchedEffect(controls, model.status) { while (controls && model.status != PlayerStatus.ERROR) { position = engine.positionMs(); delay(500) } }
    LaunchedEffect(controls, menu, model.status) { withFrameNanosCompat(); runCatching { if (menu != Menu.NONE) Unit else if (model.status == PlayerStatus.PREPARING) root.requestFocus() else if (controls) playFocus.requestFocus() else root.requestFocus() } }
    fun wake() { lastKey++; controls = true }

    CanvasDialog({ if (menu != Menu.NONE) menu = Menu.NONE else onClose() }, fullScreen = true) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).focusRequester(root).focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { engine.togglePlay(); wake(); true }
                        Key.MediaFastForward -> { engine.seekBy(PlayerMath.SEEK_STEP_MS); wake(); true }
                        Key.MediaRewind -> { engine.seekBy(-PlayerMath.SEEK_STEP_MS); wake(); true }
                        else -> if (!controls) {            // hidden controls: OK toggles, LEFT/RIGHT seek, UP/DOWN reveal
                            when (e.key) {
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { engine.togglePlay(); wake(); true }
                                Key.DirectionLeft -> { engine.seekBy(-PlayerMath.SEEK_STEP_MS); wake(); true }
                                Key.DirectionRight -> { engine.seekBy(PlayerMath.SEEK_STEP_MS); wake(); true }
                                Key.DirectionUp, Key.DirectionDown -> { wake(); true }
                                else -> false
                            }
                        } else { lastKey++; false }
                    }
                },
        ) {
            key(engine) { AndroidView(factory = { ctx ->
                SurfaceView(ctx).apply { holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) = engine.attach(h)
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) = engine.detach()
                }) }
            }, modifier = Modifier.fillMaxSize()) }

            if (model.subtitleText.isNotBlank() && model.subtitleSelected != null) Text(model.subtitleText, style = AuraTypography.Body, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (controls) 220.dp else 64.dp, start = 160.dp, end = 160.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 6.dp))

            when (model.status) {
                PlayerStatus.PREPARING -> PreparingPlayback(metadata, title)
                PlayerStatus.ERROR -> Status(model.error ?: "Playback failed.", hint = "Press Back to close and try another source.")
                PlayerStatus.ENDED -> Status("Finished", hint = "Press OK to play again.")
                PlayerStatus.READY -> if (model.buffering) Status("Buffering…")
            }

            if (controls && model.status != PlayerStatus.PREPARING) {
                Box(Modifier.fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))))
                if (!advisoryVisible) Column(Modifier.padding(start = 64.dp, top = 40.dp)) { Text(title, style = AuraTypography.SectionTitle, maxLines = 1); subtitle?.let { Text(it, style = AuraTypography.Metadata, maxLines = 1) } }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))).padding(horizontal = 64.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Scrubber(position, model.durationMs, onSeek = { engine.seekBy(it); lastKey++ })
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        CtrlButton(Icons.Rounded.Replay10, "Back 10 seconds") { engine.seekBy(-PlayerMath.SEEK_STEP_MS) }
                        CtrlButton(if (model.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (model.playing) "Pause" else "Play", Modifier.focusRequester(playFocus)) { engine.togglePlay() }
                        CtrlButton(Icons.Rounded.Forward10, "Forward 10 seconds") { engine.seekBy(PlayerMath.SEEK_STEP_MS) }
                        Spacer(Modifier.weight(1f))
                        CtrlButton(Icons.Rounded.Audiotrack, "Audio language", label = "Audio") { menu = Menu.AUDIO }
                        CtrlButton(Icons.Rounded.Subtitles, "Subtitles", label = "Subtitles") { menu = Menu.SUBTITLES }
                        CtrlButton(Icons.Rounded.List, "Playback sources", label = "Sources") { menu = Menu.SOURCES }
                        CtrlButton(Icons.Rounded.Close, "Close player", label = "Close") { onClose() }
                    }
                }
            }

            (model.trackMessage ?: notice)?.let { Text(it, style = AuraTypography.Metadata, color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 112.dp).background(Color.Black.copy(alpha = .8f)).padding(12.dp)) }
            if (menu == Menu.SOURCES) TrackMenu(
                "Sources",
                options = if (sources.size > 1 && onSourceSelected != null) sources.map { choice -> Triple(choice.label, choice.url == source) {
                    if (choice.url != source) onSourceSelected(choice, engine.positionMs())
                    menu = Menu.NONE
                } } else emptyList(),
                emptyText = "Only this source is available. Other streams must come from this provider.",
                onClose = { menu = Menu.NONE },
            )
            if (menu == Menu.AUDIO || menu == Menu.SUBTITLES) TrackMenu(
                if (menu == Menu.AUDIO) "Audio language" else "Subtitles",
                options = if (menu == Menu.AUDIO) model.audioTracks.map { Triple(it.label + if (it.supported) "" else " (unsupported on this TV)", model.audioSelected == it.index) { engine.selectAudio(it.index); menu = Menu.NONE } }
                else listOf(Triple("Off", model.subtitleSelected == null) { engine.selectSubtitle(null); menu = Menu.NONE }) + model.subtitleTracks.map { Triple(it.label + if (it.supported) "" else " (unsupported on this TV)", model.subtitleSelected == it.index) { engine.selectSubtitle(it.index); menu = Menu.NONE } },
                emptyText = if (menu == Menu.AUDIO) "This video exposes no selectable audio tracks." else "This video has no discovered embedded subtitle tracks. External subtitle files are not supported.",
                onClose = { menu = Menu.NONE },
            )
            // Last overlay: controls, menus and a source's preparing screen cannot cover
            // the session advisory or move it when controls auto-hide.
            if (advisoryVisible) {
                Text(advisory, style = AuraTypography.Metadata, color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 64.dp, top = 48.dp)
                        .widthIn(max = 620.dp).clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = .88f)).padding(18.dp))
            }
        }
    }
}

private suspend fun withFrameNanosCompat() = androidx.compose.runtime.withFrameNanos { }

@Composable
private fun Status(text: String, hint: String? = null) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = AuraTypography.SectionTitle, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 24.dp, vertical = 12.dp))
        hint?.let { Text(it, style = AuraTypography.Metadata, color = Color.White, modifier = Modifier.padding(top = 10.dp)) }
    }
}

/** The Aura progress line: orange fill, orange thumb when focused. LEFT/RIGHT seek by 10 s while it has focus. */
@Composable
private fun Scrubber(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val f = PlayerMath.fraction(positionMs, durationMs)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(PlayerMath.format(positionMs), style = AuraTypography.Metadata, color = Color.White, modifier = Modifier.width(80.dp), textAlign = TextAlign.End)
        BoxWithConstraints(
            Modifier.weight(1f).height(28.dp).onFocusChangedCompat { focused = it }.focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false else when (e.key) {
                        Key.DirectionLeft -> { onSeek(-PlayerMath.SEEK_STEP_MS); true }
                        Key.DirectionRight -> { onSeek(PlayerMath.SEEK_STEP_MS); true }
                        else -> false
                    }
                }.semantics { contentDescription = "Seek bar" },
            contentAlignment = Alignment.CenterStart,
        ) {
            val h = if (focused) 8.dp else 5.dp
            Box(Modifier.fillMaxWidth().height(h).clip(CircleShape).background(Color.White.copy(alpha = 0.30f)))
            Box(Modifier.fillMaxWidth(f).height(h).clip(CircleShape).background(AuraColors.Progress))
            if (focused) Box(Modifier.offset(x = (maxWidth * f) - 10.dp).size(20.dp).clip(CircleShape).background(AuraColors.Focus))
        }
        Text(PlayerMath.format(durationMs), style = AuraTypography.Metadata, color = Color.White, modifier = Modifier.width(80.dp))
    }
}

private fun Modifier.onFocusChangedCompat(block: (Boolean) -> Unit): Modifier = this.onFocusChanged { block(it.isFocused) }

@Composable
private fun CtrlButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, label: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(AuraDimensions.RadiusButton)
    Row(
        modifier.auraFocusable(shape, onClick = onClick).clip(shape).background(AuraColors.ButtonInactiveBg).padding(horizontal = 16.dp, vertical = 10.dp).semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { Icon(icon, null, tint = AuraColors.ButtonInactiveIcon, modifier = Modifier.size(28.dp)); label?.let { Text(it, style = AuraTypography.Button, color = AuraColors.ButtonInactiveIcon) } }
}

@Composable
private fun TrackMenu(title: String, options: List<Triple<String, Boolean, () -> Unit>>, emptyText: String, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    CanvasDialog(onClose, fullScreen = true) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.padding(end = 64.dp).width(420.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState()).clip(RoundedCornerShape(AuraDimensions.RadiusContainer)).background(AuraColors.SurfaceElevated).padding(AuraDimensions.Space24), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = AuraTypography.SectionTitle)
            if (options.none { it.first != "Off" }) Text(emptyText, style = AuraTypography.Metadata, color = AuraColors.TextSecondary)
            val shape = RoundedCornerShape(AuraDimensions.RadiusButton)
            options.forEachIndexed { i, (label, selected, pick) ->
                Text((if (selected) "✓  " else "     ") + label, style = AuraTypography.Button, color = AuraColors.TextPrimary, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier).auraFocusable(shape, onClick = pick).clip(shape).background(if (selected) AuraColors.NavPill else AuraColors.Surface).auraGlass(shape).padding(horizontal = 20.dp, vertical = 12.dp))
            }
            Text("Close", style = AuraTypography.Button, modifier = Modifier.fillMaxWidth().then(if (options.isEmpty()) Modifier.focusRequester(first) else Modifier).auraFocusable(shape, onClick = onClose).clip(shape).background(AuraColors.Surface).auraGlass(shape).padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }
    LaunchedEffect(Unit) { withFrameNanosCompat(); runCatching { first.requestFocus() } }
}

}

private fun playerActivity(context: Context): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> if (context.baseContext !== context) playerActivity(context.baseContext) else null
    else -> null
}

/** Artwork is supplied by the title owner; no provider URL, fabricated poster or rating is shown. */
@Composable
private fun PreparingPlayback(metadata: PlaybackMetadata, title: String) {
    val reduce = LocalReduceMotion.current
    val angle = if (reduce) 0f else {
        val transition = rememberInfiniteTransition(label = "playbackLoading")
        val rotation by transition.animateFloat(0f, 360f,
            infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "loadingRing")
        rotation
    }
    Box(Modifier.fillMaxSize().background(AuraColors.Surface), contentAlignment = Alignment.Center) {
        (metadata.backdropArtwork ?: metadata.artwork)?.let { ArtworkImage(it, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f)))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.matchParentSize().padding(8.dp).semantics { contentDescription = "Preparing playback" }) {
                    drawCircle(Color.White.copy(alpha = .14f), style = Stroke(width = 5.dp.toPx()))
                    drawArc(AuraColors.Focus, angle - 90f, 100f, false,
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
                }
                AuraBrandLabel(BrandLabels.AURABOX)
            }
            Text("Preparing playback…", style = AuraTypography.SectionTitle, color = Color.White)
            if (title.isNotBlank()) Text(title, style = AuraTypography.Metadata, color = Color.White, maxLines = 2,
                modifier = Modifier.widthIn(max = 800.dp), textAlign = TextAlign.Center)
        }
    }
}
