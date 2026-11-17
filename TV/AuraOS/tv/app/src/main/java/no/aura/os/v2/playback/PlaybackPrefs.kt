package no.aura.os.v2.playback

import android.content.Context
import android.content.SharedPreferences
import no.aura.os.v2.ultramax.Quality
import no.aura.os.v2.ultramax.UltraStream

/** Languages offered in Settings (ISO 639-1; compared with the 2- or 3-letter code a media track reports). */
enum class Lang(val code: String, val label: String, val alt: String) {
    EN("en", "English", "eng"), NO("no", "Norwegian", "nor"), SV("sv", "Swedish", "swe"), DA("da", "Danish", "dan"), FI("fi", "Finnish", "fin"),
    DE("de", "German", "deu"), FR("fr", "French", "fra"), ES("es", "Spanish", "spa"), IT("it", "Italian", "ita"), NL("nl", "Dutch", "nld");
    fun matches(track: String?): Boolean {
        val t = track?.lowercase()?.trim().orEmpty()
        return t.isNotEmpty() && (t == code || t == alt || t.startsWith("$code-") || t.startsWith("${code}_") || (this == NO && t in NORWEGIAN_VARIANTS))   // nb/nn/nob/nno are Norwegian too
    }
    companion object { private val NORWEGIAN_VARIANTS = setOf("nb", "nn", "nob", "nno"); fun parse(name: String?): Lang? = values().firstOrNull { it.name == name } }
}

/** Playback preferences (device-wide). Defaults: auto-start on, Auto quality, English audio, subtitles off. */
data class PlaybackPrefs(
    val autoStart: Boolean = true, val quality: Quality = Quality.AUTO, val audio: Lang = Lang.EN, val audioSecondary: Lang? = Lang.NO,
    val subtitlesOn: Boolean = false, val subtitleLang: Lang = Lang.EN,
) { init { require(quality in SELECTABLE) } 
    companion object { val SELECTABLE = listOf(Quality.AUTO, Quality.P2160, Quality.P1080, Quality.P720) } }

interface PlaybackPrefsStore { fun load(): PlaybackPrefs; fun save(p: PlaybackPrefs) }
class MemoryPlaybackPrefsStore(private var p: PlaybackPrefs = PlaybackPrefs()) : PlaybackPrefsStore { override fun load() = p; override fun save(p: PlaybackPrefs) { this.p = p } }

class PrefsPlaybackStore(private val prefs: SharedPreferences) : PlaybackPrefsStore {
    constructor(context: Context) : this(context.getSharedPreferences("aura_v2_playback", Context.MODE_PRIVATE))
    override fun load(): PlaybackPrefs = runCatching {
        val d = PlaybackPrefs()
        PlaybackPrefs(
            autoStart = prefs.getBoolean("auto", d.autoStart), quality = Quality.values().firstOrNull { it.name == prefs.getString("q", null) && it in PlaybackPrefs.SELECTABLE } ?: d.quality,
            audio = Lang.parse(prefs.getString("a1", null)) ?: d.audio, audioSecondary = if (prefs.contains("a2")) Lang.parse(prefs.getString("a2", null)) else d.audioSecondary,
            subtitlesOn = prefs.getBoolean("sub", d.subtitlesOn), subtitleLang = Lang.parse(prefs.getString("sl", null)) ?: d.subtitleLang,
        )
    }.getOrDefault(PlaybackPrefs())
    override fun save(p: PlaybackPrefs) { prefs.edit().putBoolean("auto", p.autoStart).putString("q", p.quality.name).putString("a1", p.audio.name).putString("a2", p.audioSecondary?.name).putBoolean("sub", p.subtitlesOn).putString("sl", p.subtitleLang.name).apply() }
}

/** Source choice from the preferred quality. Pure. */
object SourcePicker {
    /** AUTO = best known quality. Otherwise the exact quality, else the nearest LOWER one, else the nearest higher; unknown-quality links are last. */
    fun pick(streams: List<UltraStream>, want: Quality): UltraStream? {
        if (streams.isEmpty()) return null
        val known = streams.filter { it.quality != Quality.UNKNOWN }
        if (known.isEmpty()) return streams.first()
        if (want == Quality.AUTO) return known.maxByOrNull { it.quality.height }
        return known.filter { it.quality.height <= want.height }.maxByOrNull { it.quality.height } ?: known.minByOrNull { it.quality.height }
    }
    /** Order for a manual chooser: best quality first. */
    fun sorted(streams: List<UltraStream>): List<UltraStream> = streams.sortedByDescending { it.quality.height }
}

/** A track reported by the player. [language] is as reported (may be null/"und"). */
data class TrackInfo(val index: Int, val language: String?, val label: String, val supported: Boolean = true)

object TrackPicker {
    /** Audio: primary language, else secondary, else the platform default (null = leave as is). */
    fun audio(tracks: List<TrackInfo>, p: PlaybackPrefs): TrackInfo? =
        tracks.firstOrNull { it.supported && p.audio.matches(it.language) } ?: p.audioSecondary?.let { s -> tracks.firstOrNull { it.supported && s.matches(it.language) } }
    /** Subtitles: only when switched on and a track in the preferred language exists; never guess another language. */
    fun subtitle(tracks: List<TrackInfo>, p: PlaybackPrefs): TrackInfo? = if (!p.subtitlesOn) null else tracks.firstOrNull { it.supported && p.subtitleLang.matches(it.language) }
}

object PlayerMath {
    const val SEEK_STEP_MS = 10_000L
    fun step(pos: Long, dur: Long, deltaMs: Long): Long = (pos + deltaMs).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
    fun fraction(pos: Long, dur: Long): Float = if (dur <= 0) 0f else (pos.toFloat() / dur).coerceIn(0f, 1f)
    fun format(ms: Long): String { val s = (ms.coerceAtLeast(0) / 1000); val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60; return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec) }
}
