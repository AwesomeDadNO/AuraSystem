package no.aura.os.v2.overlay

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import no.aura.os.v2.livetv.Media3PublicDataSource
import no.aura.os.v2.livetv.PublicHttpsPolicy
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.theme.AuraTypography

/** Public HTTPS syntax is checked here; the transport additionally validates DNS and every connected TLS peer. */
object LiveStreamUrlPolicy {
    fun allowed(url: String): Boolean = PublicHttpsPolicy.syntax(url)
}

/** HLS uses one guarded DataSource factory for manifests, variants, segments and AES keys. DRM/custom auth are unsupported. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun LiveStreamPlayerHost(title: String, url: String, onClose: () -> Unit) {
    if (!LiveStreamUrlPolicy.allowed(url)) {
        SheetHost(no.aura.os.v2.settings.Sheet.Message("Stream unavailable", listOf("This channel needs a valid HTTPS stream address without an embedded user name or password.")), onClose)
        return
    }
    val context = LocalContext.current
    val activity = remember(context) { liveActivity(context) }
    val player = remember(url, context) {
        ExoPlayer.Builder(context).setMediaSourceFactory(HlsMediaSource.Factory(Media3PublicDataSource.Factory())).build()
    }
    var opened by remember(player) { mutableStateOf(false) }
    var ready by remember(player) { mutableStateOf(false) }
    var playing by remember(player) { mutableStateOf(false) }
    var released by remember(player) { mutableStateOf(false) }
    var failure by remember(player) { mutableStateOf<String?>(null) }
    val closeCallback by rememberUpdatedState(onClose)
    val focus = remember(player) { FocusRequester() }
    fun release() {
        if (!released) { released = true; playing = false; runCatching { player.stop(); player.clearVideoSurface(); player.release() } }
    }
    fun close() { release(); closeCallback() }
    DisposableEffect(player, activity) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (!released) ready = state == Player.STATE_READY }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!released) playing = isPlaying }
            override fun onPlayerError(error: PlaybackException) {
                failure = "This HLS channel could not be played. Public HTTPS, compatible codecs and standard access are required. DRM and custom authentication are unsupported."
                release()
            }
        }
        player.addListener(listener)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(a: Activity) { if (a === activity) close() }
            override fun onActivityDestroyed(a: Activity) { if (a === activity) release() }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
        }
        activity?.application?.registerActivityLifecycleCallbacks(callbacks)
        onDispose { activity?.application?.unregisterActivityLifecycleCallbacks(callbacks); player.removeListener(listener); release() }
    }
    CanvasDialog({ close() }, fullScreen = true) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { c -> SurfaceView(c).also { v -> v.keepScreenOn = true; v.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (released) return
                    runCatching {
                        player.setVideoSurfaceHolder(holder)
                        if (!opened) {
                            opened = true
                            // An explicit HLS source prevents native/default HTTP transports from fetching nested resources.
                            player.setMediaSource(HlsMediaSource.Factory(Media3PublicDataSource.Factory())
                                .createMediaSource(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()))
                            player.prepare(); player.playWhenReady = true
                        }
                    }.onFailure { failure = "This channel could not be opened."; release() }
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) { release() }
            }) } }, modifier = Modifier.fillMaxSize())
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .8f)).padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = AuraTypography.SectionTitle, color = Color.White)
                Text(failure ?: if (ready) "Live TV" else "Opening channel…", style = AuraTypography.Body, color = Color.White)
                Text(if (playing) "Pause" else "Play", style = AuraTypography.Button, color = Color.White,
                    modifier = Modifier.focusRequester(focus).auraFocusable(RoundedCornerShape(12.dp), onClick = {
                        if (ready && !released) runCatching { if (playing) player.pause() else player.play() }
                    }).padding(16.dp))
                Text("Close", style = AuraTypography.Button, color = Color.White, modifier = Modifier.auraFocusable(RoundedCornerShape(12.dp), onClick = { close() }).padding(16.dp))
            }
            LaunchedEffect(player) { withFrameNanos { }; runCatching { focus.requestFocus() } }
        }
    }
}

private fun liveActivity(context: Context): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> if (context.baseContext !== context) liveActivity(context.baseContext) else null
    else -> null
}
