# AuraOS V2 – F6c: performance and usability correction

Stacked on F6b (`cc06efe`, Draft PR #192). Branch `auraosv2/f6c-performance-usability`.

**Verification status.** Built with Gradle/AGP on the development Mac: `:app:assembleDebug`, `:app:assemblePerf` (R8) and `:app:testDebugUnitTest` (271 JVM tests, 0 failures). **Nothing was installed, launched or run on an emulator or a TV.** Every behaviour below is unverified on the Google TV Streamer until the owner tests it. No speed-up is claimed for any change: there are no device measurements.

## 1. Root causes found in this round

| # | Problem | Status | Evidence |
|---|---|---|---|
| A | **Every dialog was drawn at device density instead of the 1920-wide canvas scale.** A Compose `Dialog` is a separate window whose root provides the platform `LocalDensity` again, so `DesignCanvas` (applied once in `MainActivity`) did not reach title details, the video player, sheets, the PIN pad or the code sheet. On a 1080p xhdpi device that is 2 px per "canvas unit": the 500-unit details hero was about 1000 px of 1080. | Confirmed in code; size on the Streamer unmeasured | `DesignCanvas` was only in `MainActivity.setContent`; dialogs used bare `Dialog()`. The gauges in section 5 report the real numbers. |
| B | Details hero was a fixed 500 units regardless of the window. | Confirmed in code (fixed) | `DetailsLayout.HERO_HEIGHT_DP` |
| C | The UltraMax code field rewrote the keyboard's text on every key (upper-casing and filtering inside `onValueChange`). With a composing IME this desynchronises the keyboard and restarts the input session. | Confirmed in code; that it is THE cause of the owner's symptom is **unconfirmed** | `SheetHost.CodePad` (F6b) |
| D | The sheet asked for focus from the host composition, possibly before the dialog window had laid out; a failure was swallowed, leaving the field without focus (and `keyboard.show()` without an input session). | Confirmed in code; effect on device unconfirmed | `SheetHost` `LaunchedEffect(sheet)` outside the dialog |
| E | The Demo Mode mini-player (simulated playback, 250 ms clock) ran in Real Mode too, from app start. | Confirmed in code (fixed) | `MiniPlayerState()` default `EXPANDED, playing = true`; composed whenever AuraAmp was on |
| F | The whole shell returned an empty screen until `DemoRepositoryImpl.load()` finished, in Real Mode as well. | Confirmed in code (fixed); duration unmeasured | `AuraShell` early `return` |
| G | Image memory cache charged 4 bytes/pixel for RGB_565 (2 bytes/pixel) card images. | Confirmed in code (fixed) | `LruCache.sizeOf` |
| H | Shelf cards requested `w500` / `w342` artwork for cards 238–337 / 176 units wide. | Confirmed in code (fixed for a UI up to 1920 px) | `RealCatalogService.mediaItem` |
| I | Main-thread work: UltraMax JSON parsing and Keystore decryption on Play, Keystore encryption + `commit()` on Save code, `commit()` on Watched/Watchlist. | Confirmed in code (fixed); durations unmeasured | `UltraMaxClient.fetch`, `PlaybackService`, `PrefsWatchStore` |
| J | Only the debug build can show Real Mode content, so every device measurement so far was of unoptimised, debuggable code. | Confirmed; size of the effect unmeasured | release build types blank the developer values |

## 2. What changed

**Private optimised test build (`perf`).** New build type next to `debug` and `release`: R8 minification, not debuggable, `profileable`, developer values from git-ignored `local.properties` (so Real Mode works), same application id (`no.aura.os.tv.v2demo`), signed with the debug key so it installs over the debug build and keeps profiles, PINs and settings. Resource shrinking is off (demo artwork is resolved by name). The `release` type is unchanged: still empty credentials. **A value compiled into an APK is extractable: a `perf` APK is private and must never be shared.** Diagnostics (`AuraDiag`) are on in `debug` and `perf`, off in `release` (`BuildConfig.DIAG`).

**Build identity.** Settings → About shows "Build (git commit)" (full 40-character SHA; `-dirty` when the tree had uncommitted tracked changes; `unknown` without git) and "Build variant" (`debug` / `perf` / `release`).

**Demo work removed from Real Mode.** The simulated compact player and its clock are composed in Demo Mode only (the overlay slot and `CompactPlayer` remain for a real AuraAmp player). In Real Mode, Play on the illustrative AuraAmp page says playback is not available instead of starting the simulation. The shell (navigation, rail) no longer waits for demo fixtures; they are built off the main thread and only when a page that shows them is opened. Demo Mode behaves as before.

**Dialogs use the canvas scale (`CanvasDialog`).** Title details, video player, sheets, PIN pad, code sheet, source chooser and demo dialogs re-apply `DesignCanvas`. **Visible consequence: on a device whose density is not 1 px per canvas unit, all of these become smaller than in F6b (half size on a 1080p xhdpi device).** This is the designed size, but the owner has only ever seen the enlarged version, so sheets and the PIN pad need a visual check.

**Details hero.** Height = 42 % of the real dialog viewport, between 430 and 470 canvas units (454 at 1080), top padding 32. The page is still one scrolling list.

**UltraMax code entry.** The field keeps exactly what the keyboard produced (`TextFieldValue`); upper-casing is display-only; normalisation (`CodeInput.normalize`: upper-case, separators dropped) is applied to a derived value used for validation and Save, so typing lower-case and pasting `ab12-cd34` both work. Problems are reported, not silently fixed ("Only letters…", "Too many characters…"). Focus goes to the field once the dialog window has input focus, then the keyboard is requested; OK on the field opens it again (only a press that began on the field); DPAD down goes to Save; Done hides the keyboard and goes to Save. Saving (Keystore + disk) runs off the main thread. The Administrator PIN pad is unchanged and numeric.

**Images.** Cache accounting uses the bitmap's real allocation. Shelf cards use `w300` / `w185` when the UI is at most 1920 px wide, the previous sizes otherwise. Hero (`w1280`), stills and portraits are unchanged.

**Main thread.** UltraMax responses are parsed on a background dispatcher; the stored code is decrypted off the main thread; Watched/Watchlist use `apply()`. Security behaviour is unchanged (Administrator check inside `saveCode`, fail-closed storage, HTTPS-only streams, nothing logged).

## 3. Unverified performance hypotheses (not fixed here on purpose)

1. Recomposition of the whole page when shell state changes (opening/closing details, focus between navigation and content, shelf pagination). Counters are in place; no refactor until numbers show the cost.
2. Composing a full new row of cards in one frame on DPAD down.
3. Hero crossfade and first upload of each 1280-wide backdrop.
4. Eight SharedPreferences files read synchronously in `onCreate` (timed individually now).
5. Child profiles: up to 24 full detail requests per shelf to read certifications.
6. Whether the Streamer renders the UI at 1920 or 3840 px (reported by the `display.*` gauges).

## 4. Builds

Requirements: JDK 17 or 21, Android SDK 34, and `AuraOS/tv/local.properties` (git-ignored) with `sdk.dir` and `TMDB_READ_TOKEN`. In `AuraOS/tv`:

| Build | Command | Output |
|---|---|---|
| Debug (unoptimised, debuggable) | `./gradlew :app:assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` |
| Optimised private test build | `./gradlew :app:assemblePerf` | `app/build/outputs/apk/perf/app-perf.apk` |
| JVM tests | `./gradlew :app:testDebugUnitTest` | 271 tests |

Both APKs have the same application id and signature when built on the same machine (same debug keystore), so `adb install -r` replaces one with the other and keeps app data. An APK built on a different machine has a different debug key and will not install over the existing one without uninstalling (which deletes app data).

Baseline profile: the `perf` APK contains the profile rules shipped by the Compose libraries (`assets/dexopt/baseline.prof`) and `androidx.profileinstaller` is already on the classpath through Compose. There is **no app-specific Baseline Profile**: generating one needs a Macrobenchmark run on a device, which only the owner can do. A sideloaded build is compiled from the profile in the background, not at install; to make runs comparable the owner can force it before measuring: `adb shell cmd package compile -f -m speed-profile no.aura.os.tv.v2demo`.

## 5. Diagnostics (`adb logcat -s AuraDiag`, debug and perf builds, owner-run)

A summary is logged when the app goes to the background (`onStop`). New in F6c:

- Gauges: `display.widthPx`, `display.heightPx`, `display.densityDpi`, `details.viewport.widthPx`, `details.viewport.heightPx`, `details.viewport.heightCanvas`, `details.hero.heightCanvas`, `img.mem.bytes`.
- Startup sections: `startup.packageManager`, `startup.prefs.nav`, `startup.prefs.playback`, `startup.prefs.watch`, `startup.setContent` (plus the F6b ones); `demo.catalogue.load`; milestones `firstComposeFrame`, `firstCatalogueShown`, `firstImageDecoded` (time since process start).
- Counters: `shell.recompose`, `shelfpage.recompose`, `shelf.recompose`, `card.compose` (cards composed for the first time), `card.recompose` (every card body execution, including the first), `hero.recompose`, `hero.transition`.

Reading them: `card.recompose − card.compose` is how often existing cards were re-executed. Compare it with `shell.recompose` after one action (open and close a title; move from the navigation into a shelf) to see whether a shell update really re-runs the visible cards.

## 6. Owner test checklist

Install the `perf` build for measurements; use `debug` only to compare. Check Settings → About first: the commit must match the PR head and the variant must be the one you think you installed.

Functional
1. **UltraMax keyboard.** Settings → Connections → UltraMax → Enter code → Administrator PIN (numeric pad). The code sheet opens with the field focused; does the TV keyboard appear by itself? If not, press OK on the field. Type 8 characters in lower case; they display in capitals; "Code complete" appears; Done moves to Save; Save stores it; Verify works. Repeat with a 9th character (must say too many) and with a pasted `ab12-cd34` if you can paste. Note exactly what happens at the first step that fails.
2. **Details size.** Open a movie and a series. The hero should take under half the screen; tabs and the first row (cast, or seasons + episodes) are visible without scrolling; DPAD down walks hero → tabs → rows and the focused item stays visible; Back restores the card.
3. **Other dialogs** (now at canvas scale, i.e. smaller): PIN pad, choice sheets, message sheets, source chooser, player controls. Readable from the sofa? Anything clipped?
4. **Real Mode has no simulated player:** no AuraAmp mini-player on Home/Movies/TV/AuraBox; AuraAmp page → Play says playback is not available. **Demo Mode** still shows and runs the demo player.
5. Watched / Watchlist toggles still persist after an app restart.
6. Play a title with a valid code (source list or auto-start as before).

Measurements (same scenarios on `debug` and `perf`, three runs each, report every value)
- Cold start: `adb shell am force-stop no.aura.os.tv.v2demo`, then `adb shell am start -W -n no.aura.os.tv.v2demo/no.aura.os.v2.MainActivity`.
- Per scenario: `adb shell dumpsys gfxinfo no.aura.os.tv.v2demo reset`, do only that scenario for about 20 s, then `adb shell dumpsys gfxinfo no.aura.os.tv.v2demo`. Scenarios: idle on Home for 20 s (should now produce almost no frames in Real Mode), DPAD left/right through three shelves, DPAD down/up through all shelves, open and close a title five times, series episode row.
- After each scenario press Home and save `adb logcat -d -s AuraDiag` (the summary).

## 7. Next step, not implemented: progressive TMDB loading (proposed F7)

Today `RealCatalogService.load()` awaits genres, the hero feed and every shelf feed, then publishes the whole page; one slow request delays everything and a pagination result replaces the whole page content.

Proposed design, for approval before any code:
1. **Page model of independent sections.** `RealPageState(hero: SectionState, shelves: List<ShelfSlot>)` with per-section `Loading / Ready / Empty / Failed`; the shelf list (ids, titles, order) is known up front from `specs(page)`, so the layout, row keys and focus chain exist before any data arrives.
2. **Loading order.** Hero feed and the first two shelves start immediately; the remaining shelves start when their row comes within one row of the viewport (or after the first ones finish). Genres no longer block: the hero shows without genre names and fills them in.
3. **Stable updates.** Each section is its own state holder read only by its row, so a section arriving or paginating does not replace the page object; scroll position and focus are kept because row keys and `LazyListState`s do not change.
4. **Persistent metadata cache.** Bounded on-disk cache for feed pages and genres only (non-sensitive, identical for every profile): one file per key, schema version, TTL (lists 15 min fresh / 24 h stale-usable, genres 7 days), size cap with oldest-first trimming, atomic writes, corrupt or oversized entries deleted and treated as a miss. Stale-while-revalidate: show the stale page at once, refresh in the background, swap per section.
5. **Child safety unchanged.** The cache stores raw TMDB lists, never an authorisation decision; `ChildPolicy` is applied after the cache for the active profile exactly as today, and a failed certification lookup still blocks. No credentials, profile ids, watch state or UltraMax data go into this cache.
6. **Tests.** Section independence (one failing feed does not block others), ordering/priority, stale-then-fresh swap, corruption and size-limit handling, child gate after a cache hit, focus/scroll state preserved across section updates (state-holder level).
