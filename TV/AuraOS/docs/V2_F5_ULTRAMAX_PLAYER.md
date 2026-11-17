# V2-F5 — UltraMax source, simple video player, playback settings, orange accent

Status: implemented and JVM-tested in the cloud (223 tests). **Not built with Gradle/AGP, not run on an emulator, and UltraMax has not been contacted from the cloud (no code, no network).** Everything below marked "wired" is unverified until Gemini runs it.

## 1. Orange accent (design rule update)
`AuraColors.Focus` and `AuraColors.Progress` are aliases of the logo orange `BrandOrange` (`0xFFFF7A00`); no new hue. Applied to: the global focus border (`auraFocusable`, so cards, buttons, nav items, sheets, dialogs), a soft orange-tinted elevation shadow on focus (the "glow", one shadow, API 28+ tint), all progress bars (cards, AuraBox resume, AuraAmp, compact player) and the player scrubber/thumb. The active-navigation pill and info accents stay blue. A test forbids `FocusBlue`/`ProgressBlue` outside the theme package.

## 2. UltraMax (Stremio-style addon, as in the legacy AuraBox bridge)
- Code: exactly 8 letters/digits, upper-cased. Entered with an on-screen grid in Settings → Connections → UltraMax. Changing/removing it needs the Administrator PIN (enforced in `PlaybackService`, not only the UI).
- Stored AES-256-GCM with a non-exportable AndroidKeyStore key (`KeystoreUltraCodeStore`); unreadable storage reads as "no code". The code is never displayed after saving, never logged; `toString()` is masked.
- Requests: only `https://ultramax.vip/c/<CODE>/manifest.json`, `stream/movie/ttN.json`, `stream/series/ttN:S:E.json` (types make other paths unrepresentable). No redirects, 4 MB cap, 6 s/15 s timeouts, cancellable, off the main thread.
- Streams: only direct `https://` links are kept (max 60); URLs are redacted in `toString`, never logged or shown. Torrent/magnet entries are ignored.
- Connection status: Connected only after the manifest or a stream request actually succeeded. A saved code is "not verified". Verify/Retry is Settings → Connections → UltraMax → Verify connection.
- AuraBox/Home/Movies/TV details → Play: TMDB supplies the IMDb id (`external_ids`), UltraMax supplies sources. Series: season chips + episode chips (TMDB), then `Play S·E`. Without a code, without an IMDb id, with no sources, or on a network error the screen says exactly that. Child profiles cannot reach any of this for a blocked title (details and seasons are gated by ChildPolicy).

## 3. Playback settings (Settings → General)
Preferred quality (Auto / 4K / 1080p / 720p), Auto-start at preferred quality, Audio language, Secondary audio language, Subtitles on/off, Preferred subtitle language. Ordinary preferences (no PIN), persisted. Behaviour:
- Auto-start on: best source for the preference (exact, else nearest lower, else nearest higher; unknown-quality links last) starts immediately. Off: the person picks from a source list.
- Audio: primary language track, else secondary, else the platform default. Subtitles: only if on AND a track in the preferred language exists; never another language.

## 4. Player (`VideoPlayerHost`, `PlayerEngine`)
Full-screen window: play/pause, ±10 s, scrubber (orange progress, orange thumb on focus, LEFT/RIGHT ±10 s), time labels, audio-language menu, subtitle menu (Off + tracks), close, status overlays (loading, buffering, error, finished). Controls auto-hide 4 s after the last key while playing; OK/LEFT/RIGHT work with hidden controls; media keys work. Back closes a menu first, then the player and releases it.
**Honest limits of this first engine:** it uses the platform `MediaPlayer` (no new dependency, so the cloud could compile it). Adaptive-bitrate control is not available; only audio and timed-text tracks that the platform exposes are listed; **external subtitle files are not supported and embedded text subtitles may not render on every device/container**; no resume position is saved yet. The UI talks only to `PlayerEngine`, so switching to Media3/ExoPlayer later does not touch the screens. If Gemini finds subtitle/track support too weak on the emulator, Media3 is the recommended next step (F5b).

## 5. Performance review (no measurements yet)
- Position is polled every 500 ms **only while the controls are visible**; no polling when hidden, in menus' background, or outside the player. No timers outside the player.
- Player and `MediaPlayer` are released on dispose; one engine per source.
- Settings/connection statuses are recomputed on resume/result/visit to Settings, not per recomposition. UltraMax is contacted only on Verify or Play (never at startup).
- Season data is cached with details (6 h); the F4 caches/image loader are unchanged. The sheet host now scrolls instead of growing past the screen.
- Expect no regression to F4 startup/heap; **unmeasured**.

## 6. Gemini checklist (emulator-5554, 1920×1080)
1. `./gradlew :app:assembleDebug :app:testDebugUnitTest` (TMDB token in `local.properties`); report test count (expect 223).
2. Real Mode: Home, Movies, TV Shows, AuraBox still show TMDB data; nav styles and external shortcuts unchanged. Screenshots 1, 2.
3. Settings → Connections → UltraMax: Enter code (PIN), code grid works with DPAD, Verify → Connected only if the code is valid. Screenshot 3. Settings → General: the six playback rows.
4. Open a movie → Play: with a valid code a source plays; without a code the message says so. Open a series → pick season/episode → Play.
5. Player: play/pause, seek ±10 s with scrubber focus, audio menu, subtitle menu (note which tracks appear), Back closes cleanly and focus returns to Play. Screenshot 4.
6. Orange focus on cards/buttons and orange progress (scrubber, AuraAmp/AuraBox bars). Screenshot 5.
7. Measure cold/warm start, heap, and CPU while the player is open vs. F4.

## 7. Unfinished
Verified end-to-end UltraMax playback (needs a real code on a device); external subtitles; saving resume position / Watch Next; HLS/DASH quality switching; Media3 engine; the older design docs still describe blue focus (this document supersedes them).
