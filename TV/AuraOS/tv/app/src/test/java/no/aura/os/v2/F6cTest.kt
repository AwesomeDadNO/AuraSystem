package no.aura.os.v2

import kotlinx.coroutines.runBlocking
import no.aura.os.v2.details.DetailsLayout
import no.aura.os.v2.diag.DiagRecorder
import no.aura.os.v2.images.AuraImageLoader
import no.aura.os.v2.settings.CodeInput
import no.aura.os.v2.settings.SettingsCategory
import no.aura.os.v2.settings.SettingsPresenter
import no.aura.os.v2.settings.SettingsState
import no.aura.os.v2.settings.ModulePrefs
import no.aura.os.v2.settings.LauncherStatus
import no.aura.os.v2.settings.DeviceInfo
import no.aura.os.v2.tmdb.ArtSizes
import no.aura.os.v2.tmdb.TmdbImages
import no.aura.os.v2.ultramax.UltraCode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** F6c: responsive details hero, raw (never rewritten) code-field text, real cache accounting, card artwork sizes, build info, perf build type. */
class F6cTest {
    // ---- details hero follows the real viewport ---------------------------------------------------------------------------------------
    @Test fun hero_height_follows_the_viewport_within_its_bounds() {
        assertEquals(454, DetailsLayout.heroHeightDp(1080f))
        assertEquals(DetailsLayout.HERO_MIN_DP, DetailsLayout.heroHeightDp(720f))        // never below what its own content needs
        assertEquals(DetailsLayout.HERO_MAX_DP, DetailsLayout.heroHeightDp(2160f))       // never dominates a tall window
        assertEquals(DetailsLayout.HERO_MIN_DP, DetailsLayout.heroHeightDp(0f)); assertEquals(DetailsLayout.HERO_MIN_DP, DetailsLayout.heroHeightDp(Float.NaN))
        for (h in listOf(900f, 1000f, 1080f)) assertTrue("first content row must be visible at $h", DetailsLayout.aboveContentDp(h) + 290 <= h)
        assertTrue(DetailsLayout.heroHeightDp(1080f) < 500)                              // smaller than the fixed F6b hero
    }

    // ---- UltraMax code field: the raw text is judged, never rewritten ------------------------------------------------------------------
    @Test fun raw_code_text_is_normalised_for_saving_but_reported_not_rewritten() {
        assertNull(CodeInput.problem("ab12cd34")); assertEquals("AB12CD34", CodeInput.normalize("ab12cd34"))            // lowercase from a keyboard that ignores the caps flag
        assertNull(CodeInput.problem("ab12-cd34")); assertNull(CodeInput.problem(" AB12 CD34\n")); assertEquals("AB12CD34", CodeInput.normalize(" ab12-cd34\n"))   // paste with separators
        assertNotNull(CodeInput.problem("ab12cd3!")); assertNotNull(CodeInput.problem("ab12cd3é"))
        assertNotNull("nine code characters must not silently become the first eight", CodeInput.problem("AB12CD345"))
        assertNull(CodeInput.problem("")); assertNull(CodeInput.problem("AB1"))
        assertTrue(CodeInput.MAX_RAW >= 2 * UltraCode.LENGTH)
        assertNotNull(UltraCode.parse(CodeInput.normalize("ab12-cd34")))
    }
    @Test fun code_field_never_rewrites_the_keyboard_text_and_dialogs_reapply_the_canvas_scale() {
        val sheet = File("src/main/java/no/aura/os/v2/overlay/SheetHost.kt").readText()
        assertTrue(sheet.contains("TextFieldValue")); assertTrue(sheet.contains("visualTransformation = UpperCaseDisplay"))
        assertFalse("onValueChange must not normalise the field text", Regex("onValueChange = \\{[^}]*normalize").containsMatchIn(sheet))
        assertTrue(sheet.contains("KeyboardType.Ascii")); assertTrue(sheet.contains("LocalWindowInfo.current.isWindowFocused"))
        // Every dialog window goes through CanvasDialog: a bare Dialog() loses the 1920-wide canvas scale and draws at device density.
        File("src/main/java/no/aura/os/v2").walkTopDown().filter { it.extension == "kt" && it.name != "CanvasDialog.kt" }.forEach { f ->
            assertFalse("${f.name} must use CanvasDialog", Regex("(?<![A-Za-z])Dialog\\(onDismissRequest").containsMatchIn(f.readText()))
        }
        assertTrue(File("src/main/java/no/aura/os/v2/overlay/CanvasDialog.kt").readText().contains("DesignCanvas(content)"))
    }

    // ---- F6d: physical TV fallback and dialog artwork regression ---------------------------------------------------------------
    @Test fun remote_ultramax_keypad_covers_all_letters_and_digits() {
        assertEquals(36, CodeInput.REMOTE_KEYS.length)
        assertEquals(('A'..'Z').map { it.toString() }, CodeInput.REMOTE_KEYS.take(26).map { it.toString() })
        assertEquals("0123456789", CodeInput.REMOTE_KEYS.takeLast(10))
        assertTrue(CodeInput.isComplete(CodeInput.normalize("AB12CD34")))
        val sheet = File("src/main/java/no/aura/os/v2/overlay/SheetHost.kt").readText()
        assertTrue(sheet.contains("Use remote keypad (A–Z / 0–9)"))
        assertTrue(sheet.contains("CodeInput.REMOTE_KEYS"))
        assertTrue(sheet.contains("Backspace"))
        assertTrue(sheet.contains("Clear"))
    }

    @Test fun details_dialog_receives_image_loader_and_recommendations_have_titles() {
        val shell = File("src/main/java/no/aura/os/v2/shell/AuraShell.kt").readText()
        val dialogSection = shell.substringAfter("// DIALOGS:")
        assertTrue(dialogSection.contains("LocalAuraImageLoader provides images"))
        assertTrue(dialogSection.contains("TitleDetailsHost("))
        val canvas = File("src/main/java/no/aura/os/v2/overlay/CanvasDialog.kt").readText()
        assertTrue(canvas.contains("LocalAuraImageLoader provides imageLoader"))
        val cards = File("src/main/java/no/aura/os/v2/cards/MediaCard.kt").readText()
        assertTrue(cards.substringAfter("fun PosterCard(").contains("Text(item.title"))
    }

    // ---- image cache accounting and card artwork sizes ----------------------------------------------------------------------------------
    @Test fun cache_accounting_uses_real_bytes_per_pixel() {
        val b = AuraImageLoader.ImageBytes
        assertEquals(300 * 169 * 2, b.of(300, 169, 2)); assertEquals(1280 * 720 * 4, b.of(1280, 720, 4))
        assertEquals(b.of(500, 281, 4) / 2, b.of(500, 281, 2))        // an RGB_565 card is half of what F6b charged for it
        assertEquals(0, b.of(-1, 10, 4)); assertEquals(Int.MAX_VALUE, b.of(100_000, 100_000, 4))
        assertFalse(File("src/main/java/no/aura/os/v2/images/AuraImageLoader.kt").readText().contains("value.width * value.height * 4"))
    }
    @Test fun cards_request_the_small_artwork_variants_on_a_1080p_ui_only() {
        // F6g enlarged the portrait posters (332 canvas units wide), so the poster variants grew with them; the rule itself is unchanged:
        // the smaller pair up to a 1920 px UI, the larger pair above it, and a poster variant is never narrower than the card it fills.
        assertEquals(ArtSizes.Small, ArtSizes.forDisplayWidth(1920)); assertEquals(ArtSizes.Small, ArtSizes.forDisplayWidth(1280))
        assertEquals(ArtSizes.Large, ArtSizes.forDisplayWidth(3840)); assertEquals(ArtSizes.Large, ArtSizes.forDisplayWidth(0))
        assertEquals("w300", ArtSizes.Small.cardBackdrop)
        assertTrue(TmdbImages.widthOf(TmdbImages.url(ArtSizes.Small.poster, "/p.jpg")!!)!! >= 332)
        assertTrue(TmdbImages.widthOf(TmdbImages.url(ArtSizes.Large.poster, "/p.jpg")!!)!! >= 2 * 332)
        assertEquals(300, TmdbImages.widthOf(TmdbImages.url(ArtSizes.Small.cardBackdrop, "/b.jpg")!!)); assertEquals("w1280", TmdbImages.HERO)   // hero unchanged
    }

    // ---- diagnostics gauge ---------------------------------------------------------------------------------------------------------------
    @Test fun gauge_keeps_the_latest_value_and_is_off_when_disabled() {
        val d = DiagRecorder(); d.gauge("x", 5); assertEquals(0, d.counter("x"))
        d.enabled = true; d.gauge("x", 5); d.gauge("x", 1080); assertEquals(1080, d.counter("x")); assertTrue(d.summary().contains("x = 1080"))
    }

    // ---- build identity in Settings > About ----------------------------------------------------------------------------------------------
    @Test fun about_shows_the_exact_build_commit_and_variant() {
        val s = SettingsState(ModulePrefs(), no.aura.os.v2.data.DataMode.DEMO, false, LauncherStatus(null, null), DeviceInfo("0.1", 3, "Streamer", "14", 34, buildSha = "cc06efee1ab6956f70a607f72a4542011869a73e", buildVariant = "perf"), emptyList())
        val rows = SettingsPresenter.rows(SettingsCategory.ABOUT, s).associate { it.item.id to it.value }
        assertEquals("cc06efee1ab6956f70a607f72a4542011869a73e", rows["about.build"]); assertEquals("perf", rows["about.variant"])
        assertEquals("unknown", DeviceInfo("x", 0, "x", "x", 0).buildSha)
    }

    // ---- build types: the private perf build may carry developer values, the public release never does -------------------------------------
    @Test fun perf_build_is_optimised_and_private_while_release_stays_empty() {
        val gradle = File("build.gradle.kts").readText()
        val release = gradle.substringAfter("release {").substringBefore("proguardFiles")
        assertFalse(release.contains("devValue")); assertTrue(release.contains("\"TMDB_READ_TOKEN\", \"\\\"\\\"\"")); assertTrue(release.contains("isMinifyEnabled = true"))
        val perf = gradle.substringAfter("create(\"perf\") {").substringBefore("proguardFiles")
        assertTrue(perf.contains("isMinifyEnabled = true")); assertTrue(perf.contains("isDebuggable = false")); assertTrue(perf.contains("devValue(\"TMDB_READ_TOKEN\")"))
        assertTrue(perf.contains("signingConfigs.getByName(\"debug\")"))          // installs over the debug build, data kept
        assertFalse("the test application id must not change per build type", gradle.contains("applicationIdSuffix"))
        assertFalse(Regex("eyJ[A-Za-z0-9_-]{20,}").containsMatchIn(gradle))
        assertTrue(gradle.contains("\"BUILD_SHA\""))
        // diagnostics: on for debug and perf, off in the default (release) config
        assertTrue(gradle.substringAfter("defaultConfig {").substringBefore("buildTypes").contains("\"DIAG\", \"false\""))
    }

    // ---- UltraMax response handling stays correct now that parsing is moved off the caller's thread -------------------------------------------
    @Test fun ultramax_parsing_off_thread_keeps_results_and_errors() = runBlocking {
        val code = UltraCode.parse("AB12CD34")!!
        fun client(resp: no.aura.os.v2.tmdb.HttpResponse) = no.aura.os.v2.ultramax.UltraMaxClient(object : no.aura.os.v2.ultramax.UltraHttp {
            override suspend fun get(code: UltraCode, route: no.aura.os.v2.ultramax.UltraRoute) = resp
        })
        val ok = client(no.aura.os.v2.tmdb.HttpResponse(200, """{"streams":[{"name":"1080p","title":"A","url":"https://cdn.example/a.mkv"},{"name":"x","url":"http://insecure.example/b.mkv"}]}"""))
            .streams(code, no.aura.os.v2.ultramax.UltraRoute.Movie("tt0114369"))
        assertTrue(ok is no.aura.os.v2.ultramax.UltraResult.Ok); assertEquals(1, (ok as no.aura.os.v2.ultramax.UltraResult.Ok).value.size)   // https only, as before
        assertTrue(client(no.aura.os.v2.tmdb.HttpResponse(200, "not json")).streams(code, no.aura.os.v2.ultramax.UltraRoute.Movie("tt0114369")) is no.aura.os.v2.ultramax.UltraResult.Failure)
        assertTrue(client(no.aura.os.v2.tmdb.HttpResponse(0, null)).streams(code, no.aura.os.v2.ultramax.UltraRoute.Movie("tt0114369")) is no.aura.os.v2.ultramax.UltraResult.Failure)
    }
}
