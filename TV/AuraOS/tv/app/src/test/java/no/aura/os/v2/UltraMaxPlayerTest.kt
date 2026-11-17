package no.aura.os.v2

import kotlinx.coroutines.runBlocking
import no.aura.os.v2.playback.*
import no.aura.os.v2.settings.*
import no.aura.os.v2.tmdb.HttpResponse
import no.aura.os.v2.ultramax.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

private class FakeUltra(var response: (UltraRoute) -> HttpResponse) : UltraHttp {
    val routes = mutableListOf<String>(); val codes = mutableListOf<UltraCode>()
    override suspend fun get(code: UltraCode, route: UltraRoute): HttpResponse { routes.add(route.path); codes.add(code); return response(route) }
}
private fun streamsJson(vararg s: String) = HttpResponse(200, """{"streams":[${s.joinToString(",")}]}""")
private fun st(name: String, title: String, url: String) = """{"name":"$name","title":"$title","url":"$url"}"""
private val CODE = UltraCode.parse("ab12cd34")!!
private fun stream(q: Quality) = UltraStream(q.label, "", q, "https://x.example/${q.name}")

class UltraCodeTest {
    @Test fun accepts_exactly_eight_letters_or_digits_and_uppercases() {
        assertNotNull(UltraCode.parse("AB12CD34")); assertEquals(UltraCode.parse("AB12CD34"), UltraCode.parse(" ab12cd34 "))
        for (bad in listOf(null, "", "AB12CD3", "AB12CD34X", "AB12-D34", "AB12 D34", "AB12CD3É", "../../ab", "AB12CD3/")) assertNull(bad, UltraCode.parse(bad))
    }
    @Test fun code_never_prints() { assertFalse(CODE.toString().contains("AB12", true)) }
    @Test fun routes_only_allow_well_formed_ids() {
        assertEquals("manifest.json", UltraRoute.Manifest.path); assertEquals("stream/movie/tt0133093.json", UltraRoute.Movie("tt0133093").path)
        assertEquals("stream/series/tt0944947:1:2.json", UltraRoute.Episode("tt0944947", 1, 2).path)
        for (bad in listOf("tt", "x/../tt1", "tt1.json", "TT1", "tt123456789012")) assertThrows(IllegalArgumentException::class.java) { UltraRoute.Movie(bad) }
        assertThrows(IllegalArgumentException::class.java) { UltraRoute.Episode("tt1", 0, 1) }; assertThrows(IllegalArgumentException::class.java) { UltraRoute.Episode("tt1", 1, 0) }
    }
    @Test fun http_client_is_pinned_to_the_ultramax_host_over_https() {
        val src = File("src/main/java/no/aura/os/v2/ultramax/UltraMax.kt").readText()
        assertTrue(src.contains("\"https://\$HOST/c/\${code.value}/\${route.path}\"")); assertEquals("ultramax.vip", UrlConnectionUltraHttp.HOST); assertTrue(src.contains("instanceFollowRedirects = false"))
    }
}

class UltraClientTest {
    @Test fun verify_succeeds_only_for_a_manifest_like_object() = runBlocking {
        val ok = UltraMaxClient(FakeUltra { HttpResponse(200, """{"id":"vip.ultramax","name":"UltraMax","resources":["stream"]}""") }).verify(CODE)
        assertEquals(UltraResult.Ok("UltraMax"), ok)
        assertEquals(UltraResult.Failure(UltraError.BAD_RESPONSE), UltraMaxClient(FakeUltra { HttpResponse(200, "<html>") }).verify(CODE))
        assertEquals(UltraResult.Failure(UltraError.BAD_RESPONSE), UltraMaxClient(FakeUltra { HttpResponse(200, """{"hello":1}""") }).verify(CODE))
    }
    @Test fun errors_map_to_clear_categories() = runBlocking {
        for ((code, e) in listOf(404 to UltraError.INVALID_CODE, 401 to UltraError.INVALID_CODE, 403 to UltraError.INVALID_CODE, 500 to UltraError.SERVER, 0 to UltraError.NETWORK))
            assertEquals(code.toString(), UltraResult.Failure(e), UltraMaxClient(FakeUltra { HttpResponse(code, null) }).verify(CODE))
    }
    @Test fun streams_keep_only_https_links_and_detect_quality() = runBlocking {
        val http = FakeUltra { streamsJson(st("UltraMax 4K", "Movie 2160p", "https://a.example/1"), st("U", "1080p WEB", "https://a.example/2"), st("U", "x", "http://a.example/3"), st("U", "x", "ftp://a.example/4"),
            st("U", "x", "https://a.example/with space"), """{"name":"torrent","infoHash":"abc"}""", st("U", "720p", "HTTPS://a.example/5")) }
        val r = (UltraMaxClient(http).streams(CODE, UltraRoute.Movie("tt1")) as UltraResult.Ok).value
        assertEquals(listOf(Quality.P2160, Quality.P1080, Quality.P720), r.map { it.quality })
        assertTrue(r.all { it.toString().contains("redacted") && !it.toString().contains("a.example") })
    }
    @Test fun streams_are_bounded() = runBlocking {
        val many = (1..200).map { st("n", "1080p", "https://a.example/$it") }.toTypedArray()
        assertEquals(UltraMaxClient.MAX_STREAMS, (UltraMaxClient(FakeUltra { streamsJson(*many) }).streams(CODE, UltraRoute.Movie("tt1")) as UltraResult.Ok).value.size)
    }
    @Test fun quality_detection() { assertEquals(Quality.P2160, Quality.detect("Movie.4K.HDR")); assertEquals(Quality.P1080, Quality.detect("x 1080P")); assertEquals(Quality.UNKNOWN, Quality.detect("cam")) }
}

class PlaybackServiceTest {
    private fun svc(store: UltraCodeStore, auth: Boolean = true, http: UltraHttp = FakeUltra { streamsJson(st("n", "1080p", "https://a.example/1")) }, states: MutableList<UltraState> = mutableListOf()): PlaybackService {
        return PlaybackService(UltraMaxClient(http), store, FakeAuth(auth), clock = { 77L }).also { it.onState = { s -> states.add(s) } }
    }
    @Test fun without_a_code_nothing_is_requested() = runBlocking {
        val http = FakeUltra { streamsJson() }; val s = svc(MemoryUltraCodeStore(), http = http)
        assertEquals(PlaybackService.Resolution.NeedsCode, s.resolve("tt1", null, null)); assertTrue(http.routes.isEmpty())
    }
    @Test fun missing_or_malformed_imdb_id_is_reported_honestly() = runBlocking {
        val s = svc(MemoryUltraCodeStore(CODE)); assertEquals(PlaybackService.Resolution.NoImdbId, s.resolve(null, null, null)); assertEquals(PlaybackService.Resolution.NoImdbId, s.resolve("../x", null, null))
    }
    @Test fun movie_and_episode_use_the_right_routes() = runBlocking {
        val http = FakeUltra { streamsJson(st("n", "720p", "https://a.example/1")) }; val s = svc(MemoryUltraCodeStore(CODE), http = http)
        s.resolve("tt0133093", null, null); s.resolve("tt0944947", 2, 5); assertEquals(listOf("stream/movie/tt0133093.json", "stream/series/tt0944947:2:5.json"), http.routes)
        assertTrue(http.codes.all { it == CODE })
    }
    @Test fun empty_result_is_no_sources_not_an_error() = runBlocking { assertEquals(PlaybackService.Resolution.NoSources, svc(MemoryUltraCodeStore(CODE), http = FakeUltra { streamsJson() }).resolve("tt1", null, null)) }
    @Test fun connected_state_is_reported_only_after_a_real_answer() = runBlocking {
        val states = mutableListOf<UltraState>(); val ok = svc(MemoryUltraCodeStore(CODE), states = states); ok.resolve("tt1", null, null)
        assertEquals(listOf<UltraState>(UltraState.Verified(77L, "UltraMax")), states)
        val bad = mutableListOf<UltraState>(); svc(MemoryUltraCodeStore(CODE), http = FakeUltra { HttpResponse(0, null) }, states = bad).resolve("tt1", null, null)
        assertEquals(listOf<UltraState>(UltraState.Failed(UltraError.NETWORK)), bad)
    }
    @Test fun verify_uses_the_manifest_and_reports_the_result() = runBlocking {
        val states = mutableListOf<UltraState>(); val http = FakeUltra { HttpResponse(200, """{"id":"a","name":"UltraMax"}""") }
        assertEquals(UltraState.Verified(77L, "UltraMax"), svc(MemoryUltraCodeStore(CODE), http = http, states = states).verify()); assertEquals(listOf("manifest.json"), http.routes)
        assertEquals(UltraState.NoCode, svc(MemoryUltraCodeStore()).verify())
        assertEquals(UltraState.Failed(UltraError.INVALID_CODE), svc(MemoryUltraCodeStore(CODE), http = FakeUltra { HttpResponse(404, null) }).verify())
    }
    @Test fun saving_and_removing_the_code_needs_the_administrator() {
        val store = MemoryUltraCodeStore(); val locked = svc(store, auth = false)
        assertEquals(PlaybackService.SaveResult.NEEDS_ADMIN, locked.saveCode("AB12CD34")); assertFalse(store.isSet); assertFalse(locked.clearCode())
        val open = svc(store); assertEquals(PlaybackService.SaveResult.INVALID, open.saveCode("short")); assertFalse(store.isSet)
        assertEquals(PlaybackService.SaveResult.SAVED, open.saveCode("ab12cd34")); assertEquals(CODE, store.load()); assertTrue(open.clearCode()); assertFalse(store.isSet)
        assertFalse(store.toString().contains("AB12", true))
    }
}

class SourceAndTrackTest {
    @Test fun auto_picks_the_best_known_quality() { assertEquals(Quality.P2160, SourcePicker.pick(listOf(stream(Quality.P720), stream(Quality.P2160), stream(Quality.P1080)), Quality.AUTO)!!.quality) }
    @Test fun wanted_quality_exact_else_nearest_lower_else_nearest_higher() {
        val all = listOf(stream(Quality.P720), stream(Quality.P2160), stream(Quality.P1080))
        assertEquals(Quality.P1080, SourcePicker.pick(all, Quality.P1080)!!.quality); assertEquals(Quality.P720, SourcePicker.pick(all, Quality.P720)!!.quality)
        assertEquals(Quality.P1080, SourcePicker.pick(listOf(stream(Quality.P2160), stream(Quality.P1080)), Quality.P720)!!.quality)   // nothing at or below 720: nearest higher
        assertEquals(Quality.P2160, SourcePicker.pick(listOf(stream(Quality.P2160)), Quality.P1080)!!.quality)   // only a higher one exists
        assertEquals(Quality.P720, SourcePicker.pick(listOf(stream(Quality.P2160), stream(Quality.P720)), Quality.P1080)!!.quality)
    }
    @Test fun unknown_quality_is_used_only_when_nothing_else_exists() {
        assertEquals(Quality.P720, SourcePicker.pick(listOf(stream(Quality.UNKNOWN), stream(Quality.P720)), Quality.P2160)!!.quality)
        assertEquals(Quality.UNKNOWN, SourcePicker.pick(listOf(stream(Quality.UNKNOWN)), Quality.AUTO)!!.quality); assertNull(SourcePicker.pick(emptyList(), Quality.AUTO))
    }
    @Test fun selectable_qualities_are_exactly_the_four_requested() { assertEquals(listOf(Quality.AUTO, Quality.P2160, Quality.P1080, Quality.P720), PlaybackPrefs.SELECTABLE) }

    private val audio = listOf(TrackInfo(1, "eng", "English"), TrackInfo(2, "nob", "Norwegian"), TrackInfo(3, "swe", "Swedish"))
    @Test fun audio_prefers_primary_then_secondary_else_leaves_the_default() {
        assertEquals(1, TrackPicker.audio(audio, PlaybackPrefs(audio = Lang.EN, audioSecondary = Lang.NO))!!.index)
        assertEquals(2, TrackPicker.audio(audio, PlaybackPrefs(audio = Lang.DE, audioSecondary = Lang.NO))!!.index)   // nob is Norwegian
        assertNull(TrackPicker.audio(audio, PlaybackPrefs(audio = Lang.DE, audioSecondary = null)))
    }
    @Test fun subtitles_only_when_on_and_in_the_preferred_language() {
        val subs = listOf(TrackInfo(5, "eng", "English"), TrackInfo(6, "nor", "Norwegian"))
        assertNull(TrackPicker.subtitle(subs, PlaybackPrefs(subtitlesOn = false))); assertEquals(6, TrackPicker.subtitle(subs, PlaybackPrefs(subtitlesOn = true, subtitleLang = Lang.NO))!!.index)
        assertNull(TrackPicker.subtitle(subs, PlaybackPrefs(subtitlesOn = true, subtitleLang = Lang.FR)))   // never another language
    }
    @Test fun language_matching() { assertTrue(Lang.EN.matches("en-US")); assertTrue(Lang.EN.matches("ENG")); assertFalse(Lang.EN.matches("und")); assertFalse(Lang.EN.matches(null)); assertTrue(Lang.NO.matches("nb")) }
    @Test fun player_math() {
        assertEquals(0L, PlayerMath.step(3_000, 100_000, -10_000)); assertEquals(100_000L, PlayerMath.step(95_000, 100_000, 10_000)); assertEquals(25_000L, PlayerMath.step(15_000, 100_000, 10_000))
        assertEquals(0f, PlayerMath.fraction(5, 0)); assertEquals(0.5f, PlayerMath.fraction(50, 100)); assertEquals(1f, PlayerMath.fraction(500, 100))
        assertEquals("1:05", PlayerMath.format(65_000)); assertEquals("1:01:05", PlayerMath.format(3_665_000)); assertEquals("0:00", PlayerMath.format(-5))
    }
}

class F5SettingsTest {
    private fun state(playback: PlaybackPrefs = PlaybackPrefs()) = SettingsState(ModulePrefs(), no.aura.os.v2.data.DataMode.REAL, true, LauncherStatus(null, null), DeviceInfo("x", 0, "x", "x", 0), NotConfiguredConnections.statuses(), playback = playback)
    private fun row(id: String, s: SettingsState) = SettingsPresenter.rows(SettingsCategory.GENERAL, s).first { it.item.id == id }
    @Test fun playback_settings_exist_and_reflect_state() {
        val s = state(PlaybackPrefs(autoStart = false, quality = Quality.P1080, audio = Lang.NO, audioSecondary = null, subtitlesOn = true, subtitleLang = Lang.SV))
        assertEquals("1080p", row(SettingsCatalog.PB_QUALITY, s).value); assertEquals(false, row(SettingsCatalog.PB_AUTOSTART, s).on); assertEquals("Norwegian", row(SettingsCatalog.PB_AUDIO, s).value)
        assertEquals("None", row(SettingsCatalog.PB_AUDIO2, s).value); assertEquals(true, row(SettingsCatalog.PB_SUBS, s).on); assertEquals("Swedish", row(SettingsCatalog.PB_SUBLANG, s).value)
        assertTrue(listOf(SettingsCatalog.PB_QUALITY, SettingsCatalog.PB_AUTOSTART, SettingsCatalog.PB_AUDIO, SettingsCatalog.PB_AUDIO2, SettingsCatalog.PB_SUBS, SettingsCatalog.PB_SUBLANG).all { id -> row(id, s).enabled })
    }
    @Test fun playback_preferences_are_ordinary_settings_but_ultramax_code_is_protected_in_the_service() {
        SettingsCatalog.all.filter { it.id.startsWith("playback.") }.forEach { assertFalse(it.id, AccessPolicy.requiresAdmin(it)) }
        assertEquals(ItemKind.ACTION, SettingsCatalog.all.first { it.id == SettingsCatalog.ULTRAMAX_ITEM }.kind)
    }
    @Test fun ultramax_is_connected_only_when_verified() {
        val yt = no.aura.os.v2.youtube.YouTubeConfig("")
        fun st(u: UltraState) = DevConnections(yt, ultra = { u }).statuses().first { it.id == ConnectionId.ULTRAMAX }
        assertEquals(ConnectionState.NOT_CONFIGURED, st(UltraState.NoCode).state); assertEquals(ConnectionState.NOT_CONFIGURED, st(UltraState.Unverified).state)
        assertEquals(ConnectionState.CONNECTED, st(UltraState.Verified(5, "UltraMax")).state)
        assertEquals(ConnectionState.AUTH_FAILED, st(UltraState.Failed(UltraError.INVALID_CODE)).state); assertEquals(ConnectionState.OFFLINE, st(UltraState.Failed(UltraError.NETWORK)).state)
        assertTrue(st(UltraState.Failed(UltraError.SERVER)).isError)
    }
}

class InteractionAccentTest {
    private val root = File("src/main/java/no/aura/os/v2")
    @Test fun focus_and_progress_use_approved_logo_orange() {
        assertEquals(no.aura.os.v2.theme.AuraColors.BrandOrange, no.aura.os.v2.theme.AuraColors.Focus); assertEquals(no.aura.os.v2.theme.AuraColors.BrandOrange, no.aura.os.v2.theme.AuraColors.Progress)
        assertEquals(0xFFE75200L, no.aura.os.v2.theme.AuraPalette.BRAND_ORANGE)   // the logo orange: no new hue was introduced
        assertTrue(File(root, "cards/FocusStyle.kt").readText().contains("AuraColors.Focus"))
    }
    @Test fun no_new_hex_accent_was_added_to_the_player() {
        val t = File(root, "overlay/VideoPlayerHost.kt").readText()
        assertFalse(Regex("Color\\(0x[Ff]{2}[0-9A-Fa-f]{6}\\)").containsMatchIn(t))
        assertTrue(t.contains("AuraColors.Progress") && t.contains("AuraColors.Focus"))
    }
    @Test fun stream_urls_and_codes_are_never_logged() {
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val t = f.readText(); assertFalse("${f.name} logs", Regex("Log\\.[dviwe]\\(|println\\(|printStackTrace").containsMatchIn(t) && (f.path.contains("ultramax") || f.path.contains("playback")))
        }
        assertFalse(File(root, "playback/PlayerEngine.kt").readText().contains("Log."))
    }
    @Test fun manifest_still_only_requests_internet() {
        val m = File("src/main/AndroidManifest.xml").readText()
        assertEquals(listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "com.android.providers.tv.permission.WRITE_EPG_DATA", "android.permission.READ_TV_LISTINGS", "android.permission.READ_EXTERNAL_STORAGE", "android.permission.READ_MEDIA_VIDEO"), Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(m).map { it.groupValues[1] }.toList())
    }
}
