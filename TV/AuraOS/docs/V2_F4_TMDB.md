# V2-F4 — Real TMDB Home + AuraBox, navigation styles, external shortcuts

Status: implemented and JVM-tested in the cloud (190 tests). **Not yet built with Gradle/AGP, not run on an emulator, and no live TMDB response has been seen from the cloud.** Gemini must run the build and the emulator checklist below.

## What changed
- `tmdb/`: one shared repository (`CachingTmdbRepository`) over `HttpURLConnection` (HTTPS, Bearer read token, no redirects, 6 s connect / 10 s read, 2 MB body cap, cancellable). `RealCatalogService` builds Home / Movies / TV Shows / AuraBox from it. No new library.
- `images/AuraImageLoader`: only `image.tmdb.org` over HTTPS; decode off the main thread; bounded memory LRU (32 MB) + disk cache (64 MB, 7 days); 4 parallel downloads; sizes w1280 (hero/detail backdrop), w500 (landscape card, detail poster), w342 (poster card). Never `original`.
- Real Mode now shows the TMDB catalogue on Home, Movies, TV Shows and AuraBox (installed-app discovery from F1 is unchanged). Demo Mode is untouched and offline.
- Loading, empty and failed states are explicit (with Retry). There is **no silent fallback to demo titles**.
- Title details: full-screen dialog (backdrop w1280, poster, tagline, year/runtime/seasons/rating/certification, genres, overview). Back returns focus to the card that opened it. **Play never simulates playback**; it says no streaming source is connected.
- Continue Watching: `WatchProgressSource` interface; the only implementation returns nothing, so the row is omitted. TMDB does not know what anyone watched.
- Navigation styles (Settings → General → Navigation style, persisted, not Admin-PIN protected): Text only (default), Icons + text, Aura apps: icons + text. One item model (`NavModel.layout`) and one renderer. No icon-only style.
- External shortcuts: when AuraBox, AuraAmp and AuraTube are all off (Admin PIN protected, existing flow) and Real Mode is on, up to 5 installed + launchable streaming apps appear icon-only (PackageManager icon, accessibility label = app name). Hidden on Child profiles. Launch goes through the allow-listed `AppsService.launchApp`; focus returns to the shortcut on resume. Home, Movies, TV Shows, Sports, Live TV, Services and the utility icons always remain.

## Credentials
`TMDB_READ_TOKEN` is read from git-ignored `AuraOS/tv/local.properties` (or the environment) into **debug** `BuildConfig` only; release builds get `""`. It is never logged, shown, or put in a URL. Settings → Connections shows TMDB **Connected only after a real request succeeded**. A token embedded in an APK is extractable: production needs a proxy or restricted-credential strategy before any release.
TMDB attribution: the details screen shows "Data and artwork: TMDB". Add the full TMDB notice to About before any public release.

## Cache and refresh rules (memory only, no disk for metadata)
| Data | TTL | Bound |
|---|---|---|
| Lists (per feed+page) | 15 min | 64 entries, LRU |
| Details | 6 h | 160 entries, LRU |
| Genres | 24 h | 1 |
| Artwork | memory 32 MB LRU; disk 64 MB, 7 days | bounded |
Errors are never cached, so Retry always asks again. Identical concurrent requests share one call, cancelled when the last waiter leaves. A page loads once per (mode, profile); leaving Real Mode or switching profile drops all pages. Pagination: next TMDB page when focus is within 4 cards of the row end, max 60 per row, max 20 pages per feed. Initial counts: 5 hero + ≤20 per row.

## Child safety
Filtering happens in `RealCatalogService`, **after** the shared cache: the repository holds no profile data. For a Child profile each candidate's certification is resolved (cached details, ≤24 lookups, 4 in parallel) and checked with `ChildPolicy`; unknown/unrated/failed lookups are blocked. `details()` applies the same check, so a direct detail route cannot bypass it. No second profile or PIN system.

## Tests (cloud, JVM)
190 tests: JSON parser, TMDB parser (people/adult skipped), cache/TTL/error mapping/dedup/cancel, image URL allow-list, child filtering incl. failed lookups and the detail route, AuraBox poster shelves, paging, nav styles, width budget for every style × module combination × 0/5 shortcuts, shortcut picker, TMDB connection truthfulness, manifest = INTERNET only, no token in source.
Width figures are an estimate; the emulator is authoritative.

## Gemini checklist (emulator-5554, 1920×1080)
1. `TMDB_READ_TOKEN` in `AuraOS/tv/local.properties`; `./gradlew :app:assembleDebug :app:testDebugUnitTest`.
2. Settings → General → Real mode (Admin PIN) → Home shows a real hero + 4 rows. Screenshot 1.
3. AuraBox: poster rows, open a movie and a series. Screenshots 2, 3. Back restores the card.
4. Settings → Connections → TMDB should say Connected. Airplane mode + fresh Real Mode load → failure page + Retry.
5. Settings → General → Navigation style: each of the 3 styles, check no clipping/focus loss. Screenshot 4.
6. Modules: disable AuraBox, AuraAmp, AuraTube (PIN) → installed streaming apps appear icon-only; launch one, return, focus on it. Screenshot 5. (The emulator needs streaming apps installed.)
7. Child profile: no unrated/R titles; details of a blocked title unreachable.
8. Measure cold/warm start, profile switch, heap vs. F3b (1.1 s / 0.3 s / 35 ms / 36 MB). Nothing here is measured yet.

## Unfinished / known limits
- No Gradle/AGP/emulator verification from the cloud; icons names (`Inventory2`, `Equalizer`, `SmartDisplay`, `Widgets`…) come from material-icons-extended 1.7.3 and compiled in the harness.
- Real-page scroll/row state is not kept when switching tabs (rebuilt from cache, no refetch).
- Shortcut choice/reordering in Settings is not built (automatic default only).
- Search routes are not TMDB-backed yet; when added they must go through `RealCatalogService`.
- Simkl / Watch Next progress, playback, and the YouTube metadata are later tasks.
