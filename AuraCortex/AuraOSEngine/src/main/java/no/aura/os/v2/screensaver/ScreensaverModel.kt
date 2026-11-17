package no.aura.os.v2.screensaver

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File

enum class PlayerCorner(val label: String) {
    TOP_LEFT("Top Left (Default)"),
    TOP_RIGHT("Top Right"),
    BOTTOM_LEFT("Bottom Left"),
    BOTTOM_RIGHT("Bottom Right"),
    OFF("Hidden");

    companion object {
        fun parse(value: String?): PlayerCorner = values().firstOrNull { it.name == value } ?: TOP_LEFT
    }
}

enum class AutoStartTimeout(val label: String, val minutes: Int) {
    OFF("Manual Only", 0),
    MIN_1("1 Minute", 1),
    MIN_3("3 Minutes", 3),
    MIN_5("5 Minutes", 5);

    companion object {
        fun parse(value: String?): AutoStartTimeout = values().firstOrNull { it.name == value } ?: MIN_3
    }
}

data class ScreensaverVideo(val id: String, val title: String, val fileName: String, val macPath: String) {
    fun resolvedUri(): Uri {
        val candidates = listOf(
            File("/sdcard/Download/Screensavers", fileName),
            File("/storage/emulated/0/Download/Screensavers", fileName),
            File("/sdcard/Movies/Screensavers", fileName),
            File("/storage/emulated/0/Movies/Screensavers", fileName),
            File(macPath)
        )
        val found = candidates.firstOrNull { runCatching { it.exists() && it.length() > 0 }.getOrDefault(false) }
        return if (found != null) {
            Uri.fromFile(found)
        } else {
            Uri.parse("file:///sdcard/Download/Screensavers/$fileName")
        }
    }
}

object ScreensaverCatalog {
    const val ROTATE_ALL_ID = "rotate"

    val VIDEOS = listOf(
        ScreensaverVideo("hydrangeas_rain", "Hydrangeas Rain", "hydrangeas-rain.3840x2160.mp4", "/Users/awesomedad/Downloads/Screensavers/hydrangeas-rain.3840x2160.mp4"),
        ScreensaverVideo("large_sakura_tree", "Large Sakura Tree", "large-sakura-tree.3840x2160.mp4", "/Users/awesomedad/Downloads/Screensavers/large-sakura-tree.3840x2160.mp4"),
        ScreensaverVideo("lightning_ff", "Lightning FF XIII", "lightning-final-fantasy-xiii.3840x2160.mp4", "/Users/awesomedad/Downloads/Screensavers/lightning-final-fantasy-xiii.3840x2160.mp4"),
        ScreensaverVideo("spiderman_miles", "Spider-Man Miles Morales", "marvel-spiderman-miles-morales.1920x1080.mp4", "/Users/awesomedad/Downloads/Screensavers/marvel-spiderman-miles-morales.1920x1080.mp4"),
        ScreensaverVideo("miles_multiverse", "Miles Morales Multiverse", "miles-morales-in-multiverse.3840x2160.mp4", "/Users/awesomedad/Downloads/Screensavers/miles-morales-in-multiverse.3840x2160.mp4"),
        ScreensaverVideo("nahida_gi", "Nahida Genshin Impact", "nahida-gi.3840x2160.mp4", "/Users/awesomedad/Downloads/Screensavers/nahida-gi.3840x2160.mp4"),
        ScreensaverVideo("orange_train", "Orange Train at Sunset", "orange-train-at-sunset.3840x2160.mp4", "/Users/awesomedad/Downloads/Screensavers/orange-train-at-sunset.3840x2160.mp4"),
        ScreensaverVideo("rainy_pine_forest", "Rainy Pine Forest", "rainy-pine-forest.1920x1080.mp4", "/Users/awesomedad/Downloads/Screensavers/rainy-pine-forest.1920x1080.mp4"),
        ScreensaverVideo("urban_calm_cat", "Urban Calm Cat", "urban-calm-cat.3840x2160.mp4", "/Users/awesomedad/Downloads/Screensavers/urban-calm-cat.3840x2160.mp4"),
        ScreensaverVideo("anime_train", "Anime Train at Sunset", "anime-train-at-sunset.3840x2160.mp4", "/Users/awesomedad/Downloads/Screensavers/part 1/anime-train-at-sunset.3840x2160.mp4"),
        ScreensaverVideo("chasing_freedom", "Chasing Freedom at Sunset", "chasing-freedom-at-sunset.3840x2160.mp4", "/Users/awesomedad/Downloads/Screensavers/part 1/chasing-freedom-at-sunset.3840x2160.mp4"),
        ScreensaverVideo("colorful_sunset", "Colorful Sunset on Street", "colorful-sunset-on-street.3840x2160.mp4", "/Users/awesomedad/Downloads/Screensavers/part 1/colorful-sunset-on-street.3840x2160.mp4"),
        ScreensaverVideo("fantasy_flute", "Fantasy Flute Sunset", "fantasy-flute-sunset-scene.3840x2160.mp4", "/Users/awesomedad/Downloads/Screensavers/part 1/fantasy-flute-sunset-scene.3840x2160.mp4"),
        ScreensaverVideo("floating_city", "Floating City Dreams", "floating-city-dreams.3840x2160.mp4", "/Users/awesomedad/Downloads/Screensavers/part 1/floating-city-dreams.3840x2160.mp4"),
        ScreensaverVideo("christmas_tree", "Glowing Christmas Tree", "glowing-christmas-tree.3840x2160.mp4", "/Users/awesomedad/Downloads/Screensavers/part 1/glowing-christmas-tree.3840x2160.mp4"),
        ScreensaverVideo("gwen_stacy", "Gwen Stacy Street", "gwen-stacy-street.3840x2160.mp4", "/Users/awesomedad/Downloads/Screensavers/part 1/gwen-stacy-street.3840x2160.mp4")
    )

    fun find(id: String?): ScreensaverVideo = VIDEOS.firstOrNull { it.id == id } ?: VIDEOS.first()
}

data class ScreensaverSettings(
    val selectedVideoId: String = ScreensaverCatalog.ROTATE_ALL_ID,
    val playerCorner: PlayerCorner = PlayerCorner.TOP_LEFT,
    val autoStartTimeout: AutoStartTimeout = AutoStartTimeout.MIN_3
)

class PrefsScreensaverStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("aura_v2_screensaver", Context.MODE_PRIVATE)

    fun load(): ScreensaverSettings {
        val video = prefs.getString("video_id", ScreensaverCatalog.ROTATE_ALL_ID) ?: ScreensaverCatalog.ROTATE_ALL_ID
        val corner = PlayerCorner.parse(prefs.getString("corner", PlayerCorner.TOP_LEFT.name))
        val timeout = AutoStartTimeout.parse(prefs.getString("timeout", AutoStartTimeout.MIN_3.name))
        return ScreensaverSettings(video, corner, timeout)
    }

    fun save(settings: ScreensaverSettings) {
        prefs.edit()
            .putString("video_id", settings.selectedVideoId)
            .putString("corner", settings.playerCorner.name)
            .putString("timeout", settings.autoStartTimeout.name)
            .apply()
    }
}
