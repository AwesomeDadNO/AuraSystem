# AuraOS v2 migration matrix (legacy `83ea63b` → v2)

**Status: for review. No functional code has been moved.** Source inspected: branch `auraos/claude-tv-rc-integration` at `83ea63b` (Draft PR #168, package `no.aura.os.tv`, 94 Kotlin sources, 20 test files). Everything below comes from reading that tree and its own status documents (`docs/RC_REQUIREMENTS.md`, `P0_AURAAMP_ECLIPSE.md`, `ULTRAMAX_PLAYBACK.md`, `TMDB_INTEGRATION.md`, `AURACONNECT_V3_PAKE.md`). Nothing was built or run for this document.

## 0. Ground rules

- **Reuse logic, repositories, protocols and verified integrations. Do not migrate legacy UI.** v2 stays the presentation layer; old logic adapts to v2 contracts (`DemoRepository`/`DemoCatalog`, `HeroEntry`, `Shelf`, `DataMode`).
- Legacy stays untouched. Each migration task = its own branch targeting `AuraOSv2`, Draft PR, `[skip ci]`, tests first.
- Demo Mode stays as the offline visual-regression baseline (tag target: `889408d`). Real Mode is a separate repository implementation; no fictional data in Real Mode without a visible label.

### Classification used (strict)

| Class | Meaning |
|---|---|
| **Verified reusable** | Pure-Kotlin logic with passing JVM tests, no Android dependency. This is **unit-level verification only**: no live-service or device behaviour is claimed. |
| **Reusable with adaptation** | Logic is sound but touches Android APIs, v2 models, storage or dependencies that must be adapted. |
| **Requires significant rework** | Mostly legacy UI or flow, or it conflicts with the v2 design or architecture. |
| **Not implemented** | Absent in legacy (names or constants only). |
| **Deferred** | Exists, but blocked by a security review, an approval or a missing design. |

### What "verified" means in the legacy tree

Per its own `RC_REQUIREMENTS.md`: 235 JVM tests passed (JUnitCore, kotlinc 1.9.24), debug and release Kotlin compiled with 0 errors. At that point **no** AGP build, emulator, Google TV, live TMDB, live Eclipse or live UltraMax call had been run. Later Gemini/user builds of the legacy app are not recorded in the repo, so the matrix treats **all Android and live behaviour as unverified** until the user confirms otherwise. Outstanding user-side items: real Google TV test of the legacy build, UltraMax Catalog Diagnostics output, AuraConnect TLS (on hold).

## 1. Summary

| # | Capability | Class | Phase | Risk | Order |
|---|---|---|---|---|---|
| 1 | Module state / enablement | Reusable with adaptation | 1 | Low | 1b |
| 2 | Installed-app discovery + launch | Reusable with adaptation | 1 | Low | **1a (first task)** |
| 3 | Preferences + settings persistence | Reusable with adaptation | 1 | Low | 1c |
| 4 | Profile model + selection | Reusable with adaptation | 1 | Medium | 1d |
| 5 | Real status reporting | Reusable with adaptation | 1 | Low | 1e |
| 6 | TMDB catalogue core | Verified reusable (unit) | 2 | Medium | 2a |
| 7 | Image pipeline | Reusable with adaptation | 2 | Medium | 2b |
| 8 | Catalogue mapping into v2 pages | Requires significant rework | 2 | Medium | 2c |
| 9 | Search + details | Reusable with adaptation | 2 | Medium | 2d |
| 10 | Stremio protocol + UltraMax adapter | Verified reusable (unit) | 3 | **High** | 3a |
| 11 | Stream classification + source selection | Verified reusable (unit) | 3 | Medium | 3b |
| 12 | Media3 playback (video) | Requires significant rework | 3 | **High** | 3c |
| 13 | Progress, resume, Continue Watching | Reusable with adaptation | 3 | Medium | 3d |
| 14 | Watchlist persistence | Not implemented (UI toggle only) | 3 | Low | 3e |
| 15 | Seasons + episodes | Requires significant rework | 3 | Medium | 3f |
| 16 | Third-party deep links / app launch for titles | Reusable with adaptation | 3 | Medium | 3g |
| 17 | Eclipse connection + ARL storage | Reusable with adaptation | 4 | **High** | 4a |
| 18 | Eclipse catalogue, library, queue logic | Verified reusable (unit) | 4 | Medium | 4b |
| 19 | AuraAmp playback host + media session | Requires significant rework | 4 | **High** | 4c |
| 20 | AuraConnect v3 (PAKE, transport, sync) | Deferred | 5 | **High** | 5 |
| 21 | Plex / Jellyfin / Emby | Not implemented | 6 | Medium | 6 |
| 22 | Simkl | Not implemented | 6 | Medium | 6 |
| 23 | Sports | Not implemented | 7 | Approval | 7 |
| 24 | Live TV / IPTV | Not implemented | 7 | Approval | 7 |
| 25 | Screensaver | Deferred | — | Low | — |
| 26 | Legacy UI (Home, Box, Amp, settings dialogs, Glass/Pill components) | Not migrated by design | — | — | — |

## 2. Details per capability

Legacy paths are relative to `AuraOS/tv/app/src/main/java/no/aura/os/tv/` at `83ea63b`.

### 1. Module state / enablement
- **Source:** `core/model/Model.kt` (`ModuleState`, `ModuleResolver`, `PlaybackRouter`), `core/model/MediaFirst.kt`, `core/model/NavRouter.kt`, `core/adapters/Adapters.kt` (`ModuleSettings`), `core/android/AndroidAdapters.kt` (`PrefsModuleSettings`).
- **Dependencies:** none for the pure part; SharedPreferences for the store.
- **Status:** logic unit-tested (`ModelTest` 14, `RcCoreTest`); Android store never run.
- **Reusable:** `ModuleState`, `ModuleResolver`. Legacy modules are `BOX`/`AMP` (separate apps `com.aura.box…`/`com.aura.amp…`); v2 has in-app AuraBox/AuraAmp and `ModuleFlags`.
- **Security:** none notable.
- **v2 compatibility:** needs mapping from `ModuleFlags` to persisted state. Legacy "separate app installed" semantics do not apply to v2 in-app modules.
- **Tests:** keep `ModelTest`; add `ModuleFlags` persistence and "disabled module disappears from navigation and is not reachable" tests (partly exist in `NavModelTest`).
- **Risk:** low.

### 2. Installed-app discovery + launch (**proposed first task**)
- **Source:** `core/services/Services.kt` (`KnownStreamingApps`, `InstalledApp`), `core/android/AndroidAdapters.kt` (`PackageManagerInstalledApps`, launch via `getLeanbackLaunchIntentForPackage` with fallback), `feature/apps/AllApps.kt` (UI, not reused).
- **Dependencies:** `PackageManager`; manifest `<queries>` (Android 11+ package visibility): legacy declares a LEANBACK_LAUNCHER intent query plus three AURA packages.
- **Status:** known-app table unit-tested (`ServiceCatalogTest` 6, `ServiceBrandingTest`); PackageManager code compiled only.
- **Reusable:** known-app table (Netflix, Prime Video, Disney+, Max, Plex, Jellyfin, Emby, Viaplay, Apple TV, Hulu, Peacock), package mapping, launch helper.
- **Security:** launches only packages resolved by PackageManager; no arbitrary intents from data. Must not enable `QUERY_ALL_PACKAGES`.
- **v2 compatibility:** good. v2 `ServiceEntry`/`ServiceState` (Services page) is the target; v2 All Apps page uses `DemoCatalog.apps`.
- **Tests:** JVM tests with a fake `InstalledApps`; mapping of installed/not installed to `ServiceState`; "tile selection never changes connected state".
- **Risk:** low. No network, no secrets, no new dependencies.

### 3. Preferences + settings persistence
- **Source:** `core/android/PrefsStores.kt` (playback, progress, library, screensaver, profile, menu stores, profile-keyed), `core/menu/MenuPrefs.kt`, `core/playback/PlaybackPrefs.kt`.
- **Dependencies:** SharedPreferences. Plain prefs for non-secret values.
- **Status:** interfaces + in-memory fakes unit-tested; prefs implementations never run.
- **Reusable:** store interfaces and keys. `MenuPrefs` (icon/label appearance, dynamic favourites) is a legacy-menu concern and probably not needed.
- **Security:** secrets must **not** go through these stores (see 10 and 17).
- **v2 compatibility:** v2 settings page is a demo list; needs a settings model that persists the same toggles.
- **Tests:** round-trip per profile, defaults, corrupted value fallback.
- **Risk:** low.

### 4. Profile model + selection
- **Source:** `core/profile/Profiles.kt` (`Profile`, `ProfileState`, `ProfileStore`), `core/android/PrefsStores.kt` (`PrefsProfileStore`), `feature/settings/UsersSettings.kt` (UI, not reused).
- **Status:** model unit-tested; kids/admin restrictions are partly implemented, plan in `FUNCTIONAL_PLAN.md` (Fase A) was not completed.
- **Reusable:** `Profile(id, name, kids)`, active-profile logic, per-profile keying of progress and playback prefs.
- **Security:** profile isolation of tokens and history; kids filter must apply at the data source.
- **v2 compatibility:** v2 Profile page is a demo list; needs a profile picker overlay built from v2 components.
- **Risk:** medium (every later store keys on `profileId`; getting it wrong forces data migration).

### 5. Real status reporting
- **Source:** `core/services/Services.kt` (`ServiceCards`, `SourceState`, `ServiceKind`), `core/android/Diagnostics.kt`, `core/stremio/CatalogDiagnostics.kt`.
- **Status:** `ServiceKind { CONNECTED_SOURCE, INSTALLED_APP, SOURCE_SERVICE }` and `SourceState { NOT_CONFIGURED, CHECKING, CONNECTED, UNREACHABLE }` already separate installed apps from connected sources, which matches the v2 requirement.
- **Rule:** "Connected" only after an authenticated check in this process (as in legacy Eclipse and UltraMax); selecting a tile never sets it.
- **v2 compatibility:** extend v2 `ServiceState` (INSTALLED, NOT_INSTALLED, CONNECTED, DISCONNECTED, UNAVAILABLE) with CHECKING/NOT_CONFIGURED/UNREACHABLE for Real Mode.
- **Risk:** low.

### 6. TMDB catalogue core
- **Source:** `core/tmdb/{TmdbMediaCatalog,TmdbTransport,TmdbModels,CatalogData,Json}.kt` (about 530 lines, pure Kotlin).
- **Dependencies:** `HttpURLConnection` only; own JSON parser; no library.
- **Status:** 42 JVM tests (`TmdbMediaCatalogTest` 14, `CatalogDataTest` 16, `TmdbRequestBudgetTest` 9, `JsonTest` 3). **Never called live.**
- **Reusable:** typed results (`Ok/Empty/Failure` with NO_TOKEN, UNAUTHORIZED, RATE_LIMITED, NOT_FOUND, SERVER, NETWORK, BAD_RESPONSE), request budget (6 list calls at start, max 3 concurrent, 15 min list cache, 30 min detail cache, LRU 64, 30 s negative cache, global stop on 429 `Retry-After`, stop on 401/403), `MediaRef(kind,id)` keys, hero/shelf assembly (`CatalogData`).
- **Security (important):** the token is injected through `BuildConfig.TMDB_READ_TOKEN` in **debug builds only**; release gets an empty string. A token compiled into an APK is extractable. Needs a decision before Real Mode ships (user-entered token in Keystore storage, or a server-side proxy). Never log the token.
- **v2 compatibility:** results map to `HeroEntry` and `Shelf` through a v2 `RealRepository` (see 8). Demo catalogue stays the default.
- **Tests:** keep all legacy tests as-is; add mapping tests; offline/failure/empty states per shelf.
- **Risk:** medium (live behaviour unverified, token strategy).

### 7. Image pipeline
- **Source:** Coil `AsyncImage` in `ui/components/Cards.kt` and `ui/components/Hero.kt`; TMDB sizes per use (card backdrop w780, poster w342, hero w1280, episode w300).
- **Dependencies:** `io.coil-kt:coil-compose:2.7.0` is **not** in v2. Adding it is a dependency decision.
- **v2 compatibility:** v2 `ArtworkImage` resolves bundled drawables by name; it needs a URL branch (`Artwork.imageUrl` already exists in the model). Memory and disk cache come with Coil.
- **Security:** https only; no tokens in image URLs.
- **Risk:** medium (memory on TV hardware with 1920 px hero images; downsampling to the canvas density).

### 8. Catalogue mapping into v2 pages
- **Source:** `ui/model/CatalogMapping.kt`, `ui/model/UiModels.kt`, `feature/home/HomeScreen.kt`, `feature/browse/*`.
- **Class:** requires rework because the old mapping targets old UI models. Only the *assembly rules* are reused (hero = 3 movies + 3 series from trending that have a backdrop; max 12 per shelf; Aura Latest = now playing + on the air; Trending = trending movie + TV).
- **v2 compatibility:** v2 pages are fixed to the approved shelf titles (Home: Continue Watching, Aura Latest, Trending on Aura; Movies: Popular Movies, New Releases, Award Winners; TV: New Episodes, Popular Series, Because You Watched). "Award Winners" and "Because You Watched" have no legacy source; they need a defined TMDB query or must stay empty with an honest empty state.
- **Risk:** medium.

### 9. Search + details
- **Source:** `core/browse/Browse.kt`, `feature/browse/{BrowseScreen,ExploreRows,ServiceCatalog,ServicePages}.kt`, `feature/details/DetailsScreen.kt`; TMDB `/search`, details and `external_ids`.
- **Status:** search debounce and 429 budget are covered by the TMDB tests; UI never run.
- **v2 compatibility:** v2 Search is a minimal demo page; the real search page is not approved design. Needs a design decision before Real search UI (use the existing shelf/card components only).
- **Risk:** medium.

### 10. Stremio protocol + UltraMax adapter
- **Source:** `core/stremio/{StremioClient,StremioModels,CatalogDiagnostics}.kt`, `core/ultramax/UltraMax.kt` (`UltraMaxService`, `UltraMaxEndpoint`), `core/android/KeystoreConnectionStore.kt`.
- **Dependencies:** `HttpURLConnection`, Android Keystore (AES-256-GCM) for the stored code.
- **Status:** `UltraMaxTest` 28, `PlaybackSecurityTest` 11, `CatalogDiagnosticsTest` 4 pass. **No live UltraMax call has been verified.** The UltraMax Catalog Diagnostics output requested from the user is still outstanding.
- **Reusable:** code normalisation and validation before any network call, fixed internal manifest URL, redirect guard (https only, same host), "Connected" only after manifest fetch + parse + stream resource validation, status states, `ConnectionStore` interface, client timeouts/cache (5 min)/max 2 parallel.
- **Security:** the connection code is a credential: never in logs, fixtures, BuildConfig, commit messages or GitHub comments; masked in UI. No cleartext unless explicitly allowed. Review needed that no URL from a provider can redirect to a non-https or different host.
- **v2 compatibility:** behind a `SourceService`-style interface; v2 AuraBox "Open in App"/source cards consume it.
- **Tests:** keep legacy suites; add failure/offline/timeout states for the v2 repository.
- **Risk:** high (credential handling, live protocol unverified).

### 11. Stream classification + source selection
- **Source:** `core/playback/{Playback,SourceSelector,PlaybackSecurity,PlayerExtras}.kt` (`StreamIdMapper`, `StreamClassifier`, `StreamSourceAdapter`, `StreamUrlResolver`, `PlaybackHeaderPolicy`).
- **Status:** unit-tested (`UltraMaxTest`, `PlaybackSecurityTest`, `P5ATest` 36).
- **Reusable:** ID mapping TMDB→provider ID format, type/resolution/size/audio ranking, `StreamSourceAdapter` seam intended for Plex/Jellyfin/Emby, URL resolution with https→http downgrade rejection, header and subtitle URL policy.
- **v2 compatibility:** v2 has no source-selection UI. Needs an overlay from existing v2 dialog/card components (design decision; no mockup).
- **Risk:** medium.

### 12. Media3 playback (video)
- **Source:** `feature/playback/{PlayerScreen,PlaybackFlow,CinematicLoading}.kt` (about 700 lines UI + flow), `core/playback/LoadingModel.kt` (unit-tested, 4).
- **Dependencies:** `androidx.media3` exoplayer/hls/dash/ui/session (not in v2).
- **Class:** logic (`LoadingModel`, headers, URL policy) reusable; player UI and flow need rework to v2 (the v2 player is a compact overlay and has no full-screen design yet).
- **Security:** header and redirect policy from item 11; no credentials in URLs shown or logged.
- **Tests:** device only for real playback; JVM for state machine. **Needs a physical Google TV Streamer** before any "works" claim.
- **Risk:** high.

### 13. Progress, resume, Continue Watching
- **Source:** `core/playback/Progress.kt` (`WatchProgress`, `ProgressStore`, `EpisodePicker`), `core/android/PrefsStores.kt` (`PrefsProgressStore`, profile-keyed), `core/adapters` `ContinueWatching`.
- **Status:** unit-tested with in-memory store; prefs store never run.
- **v2 compatibility:** feeds the Home/Movies/TV "Continue Watching" shelf (`continueStyle`: progress bar blue + remaining time).
- **Risk:** medium (profile keying, resume threshold semantics).

### 14. Watchlist persistence
- **Status:** legacy has no persisted watchlist (`FUNCTIONAL_PLAN` B4 "Min liste per profil" was planned only). v2 AuraBox "Add to Watchlist" is a local demo toggle.
- **Class:** not implemented. New small store keyed by profile.
- **Risk:** low.

### 15. Seasons + episodes
- **Source:** TMDB season/episode calls (core), `feature/details/DetailsScreen.kt`, `EpisodePicker`.
- **Class:** data part reusable; UI requires design, because there is no v2 mockup for seasons/episodes (`references/README.md` lists it as missing).
- **Risk:** medium.

### 16. Third-party deep links / launch for titles
- **Source:** `core/model/Model.kt` (`PlaybackRouter`, `PlaybackDecision`), `core/services/Services.kt` (`WatchProviders`, `ProviderAvailability`).
- **Status:** TMDB watch-provider mapping unit-tested (unknown providers map to null, never guessed). Per-service deep-link URIs were not implemented beyond launching the app.
- **Risk:** medium (service URI schemes are unstable and need real-device checks).

### 17. Eclipse connection + ARL storage
- **Source:** `core/amp/{Eclipse,EclipseSession,EclipseMatch}.kt`, `core/android/KeystoreEclipseStore.kt`, `feature/amp/{AmpConnect,EclipseSettings}.kt` (UI, not reused).
- **Status:** states Not configured / Connecting / Connected / Authentication failed / Offline / Invalid response; Connected only after an authenticated manifest check in the current process. `AmpTest` 58 and `CatalogParityTest` 11 pass. **Never run against a live service.**
- **Security:** ARL is a credential: AES-GCM with an Android Keystore key, masked, never shown again, never logged; redirects limited to same host + https. Host and ARL are typed by the user and never hard-coded. Product-facing name must be **Eclipse**, not Deezer.
- **Risk:** high.

### 18. Eclipse catalogue, library, queue logic
- **Source:** `core/amp/{AmpCatalog,AmpLibrary,AmpModels,AmpSort,AmpImages}.kt`, `core/amp/ScrubModel.kt` (5 tests), `feature/amp/AmpData.kt`.
- **Status:** typed errors, bounded LRU cache, pagination (1000-item cap), library (liked, playlists, recent, followed artists), sort, queue operations are unit-tested.
- **v2 compatibility:** feeds AuraAmp shelves (Recently Played, Recommended For You, New From Followed Artists) and the hero (`HeroEntry`) through `MusicShelf`/`MusicCard`.
- **Risk:** medium.

### 19. AuraAmp playback host + media session
- **Source:** `feature/amp/{AmpPlayerHost,AmpEngine,AmpPlayerUi,AmpScrubber,ScreensaverController}.kt`, manifest `AmpPlaybackService` (foreground, mediaPlayback).
- **Dependencies:** Media3 exoplayer + session; permissions FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS.
- **Class:** the engine (queue, shuffle, repeat, reorder, jump) is reusable; the UI is replaced by the existing v2 compact player and expanded player (`MiniPlayerReducer` is already tested).
- **Security finding:** the legacy manifest declares `AmpPlaybackService` with `android:exported="true"`. Review and restrict to what MediaSession clients need before reuse.
- **Risk:** high (background playback, audio focus, lifecycle). Device only.

### 20. AuraConnect v3 (PAKE, transport, sync)
- **Source:** `core/connect/{AuraConnect,PakeHandshake,PlaylistTransfer}.kt`, `core/amp/LocalSyncV2.kt`, `docs/AURACONNECT_V3*.md`.
- **Dependencies:** `org.bouncycastle:bcprov-jdk18on:1.80.2` (about 8.7 MB jar; hash recorded in the design doc).
- **Status:** `PakeHandshakeTest` 12 + `AuraConnectTest` 20 pass; pairing is **disabled** (`AmpFlags.LAN_SYNC = false`). Local Sync v2 is known-broken (offline search of the 6-digit code) and must never be revived. TLS/transport is on hold; no mobile↔TV integration test has been run; mobile PR #173 is separate.
- **Class:** deferred until independent security review (authentication, encryption, trust boundaries, profile isolation) and the mobile integration test.
- **Risk:** high.

### 21. Plex / Jellyfin / Emby
- **Status:** not implemented. Legacy only knows their app package names (item 2) and a `StreamSourceAdapter` interface comment. No authentication, library browsing or playback exists.
- **Rule:** an installed app is not a connected source; "Connected" requires an authenticated check.

### 22. Simkl
- **Status:** not implemented (only mentioned in `FUNCTIONAL_PLAN.md`: OAuth device-code flow, planned).

### 23. Sports and 24. Live TV / IPTV
- **Status:** not implemented in legacy. v2 has the approved demo pages with fictional data only. Real data needs separate data-source and legal approval; no invented live results; no bundled IPTV streams.

### 25. Screensaver
- **Source:** `core/amp/Screensaver.kt`, `feature/amp/{ScreensaverUi,ScreensaverController}.kt`. Outside the approved v2 scope; deferred.

### 26. Legacy UI
- `ui/components/*` (Glass, SlidingPill, UnderlineTabs, PillGroup…), `ui/nav/TopBar.kt`, `feature/home|box|amp|settings` screens and `shell/AuraApp.kt` belong to the old orange/glass design. **Not migrated.** Used only as behavioural reference.

## 3. Cross-cutting items

| Item | Finding |
|---|---|
| **Dependencies new to v2** | Coil 2.7.0 (images), Media3 1.x (video + audio + session), BouncyCastle 1.80.2 (AuraConnect only). Each needs an explicit decision. v2 currently has no network dependency. |
| **Manifest** | v2 has no permissions. Phase 1 needs `<queries>` only; Phase 2 needs `INTERNET`; Phase 4 needs the foreground-service/notification permissions. Add each in the phase that needs it. |
| **Secrets** | TMDB token (debug BuildConfig today), UltraMax code, Eclipse ARL. Rule: Keystore-backed storage for user-entered credentials; nothing in source, fixtures, logs or CI. |
| **Demo/Real boundary** | v2 already has `DataMode { DEMO, REAL }` and `DemoRepository`. Add Real repositories behind the same interface; Demo Mode stays for screenshots and regression tests. |
| **Visual regression** | Demo baseline `889408d`; every migration task must keep all JVM tests green and be verified on device by Gemini or the user. |
| **Gradle/AGP** | v2 uses its own wrapper (Gradle 8.7, AGP 8.5.2, Compose BOM 2024.10.01, Kotlin 1.9.24), same versions as legacy, so legacy code compiles against v2 without upgrades. |
| **Package** | Legacy `no.aura.os.tv`; v2 `no.aura.os.v2`. Port code into `no.aura.os.v2.core.*`, never import the legacy package. |
| **Test baseline** | Legacy 235 JVM tests; v2 58 JVM tests. Port the legacy tests together with the code they cover. |

## 4. Proposed order

1. **Phase 1a** Installed-app discovery + launch, Services page real status for installed apps only (this document, section 5).
2. 1b–1e: module state, preference store, profile model/selection, real status states.
3. **Phase 2** TMDB core, image pipeline, Movies/TV/Home Real Mode, search/details. Decide the token strategy first.
4. **Phase 3** UltraMax, source selection, Media3 playback, resume, watchlist, seasons. Device-verified at each step.
5. **Phase 4** Eclipse, AuraAmp playback/queue/media session.
6. **Phase 5** AuraConnect only after the independent security review.
7. **Phase 6** Plex/Jellyfin/Emby/Simkl.
8. **Phase 7** Sports/Live TV only with separate approval.

## 5. First safe functional task (V2-F1: implemented in source, awaiting device verification; see `V2_F1_INSTALLED_APPS.md`)

**"V2-F1: installed-app discovery and launch"**

- Add `core/services` interfaces in `no.aura.os.v2`: `InstalledAppsRepository` (list known streaming apps, `isInstalled`, `launch(packageName)`), a PackageManager implementation, and a fake for tests. Port `KnownStreamingApps` and its tests.
- Services page: in Real Mode show real *installed / not installed* for the four apps (Netflix, Prime Video, Disney+, Max) plus other known apps; selecting an installed app launches it; media sources (Plex, Jellyfin, Emby, UltraMax) show "Not configured" because no connection exists yet. Demo Mode unchanged.
- Manifest: `<queries>` for the known packages and the LEANBACK_LAUNCHER intent only. No permissions, no network, no new dependencies, no secrets.
- Tests: JVM (mapping, state, never "Connected" from selection); device test: launch an installed app and return with focus restored on the same tile.
- Why first: smallest surface, real value, exercises the Real/Demo boundary and Services semantics that later phases rely on.
- Gate: user/Gemini builds and tests on the Google TV Streamer before F2.

## 6. Open questions for the owner

1. TMDB token strategy for Real Mode (user-entered in Keystore vs proxy).
2. Approval to add Coil and Media3 as v2 dependencies, and when.
3. Whether the legacy settings (appearance/menu preferences) are wanted in v2 at all.
4. Seasons/episodes, source selection, profile picker, real search: v2 has no approved design for these; mockups or approval to compose them from existing components.
5. Result of the real Google TV test of the legacy build and the UltraMax Catalog Diagnostics output.
