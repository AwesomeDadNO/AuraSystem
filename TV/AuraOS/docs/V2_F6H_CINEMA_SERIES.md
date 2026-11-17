# Approved Cinema mode TV details — 13 November 2026

## Current correction — 13 November 2026

White text/icons on warm active/focus states; background#05080D. Cast/related now live below episodes in the same Cinema page; obsolete TV page and popup link are removed. Profile-local bulk notifications, split movie/series saved shelves plus Continue Watching, system row artwork/TV intents, Media3 track discovery and landscape preparing image are corrected. Audio output and cross-app provider availability still require device/source verification. See [correction contract](V2_F6H_CINEMA_CORRECTIONS.md). No install/merge/release.

Owner selected generated concept07 (Cinema mode) and explicitly requested implementation plus softened logo-orange active/focus colours. This supersedes the blue-only navigation contract and the primary TV episode80/20 per-card action layout. Film details retain their composition.

## Native TV composition

One full-screen selected-episode still with readable gradients; genuine series backdrop then poster are fallbacks, otherwise a neutral surface. Compact series title/metadata, season selection, large selected-episode title, runtime/date/synopsis, and ONE contextual Play/More info/checkmark watched lane. A bottom strip contains408dp-wide16:9 chapter cards with stable size, real watched state and real locally recorded progress only. No focus expansion or duplicated info/watch buttons beneath every episode.

Episode focus selects the content without moving focus into the hero. Up reaches Play; Down from the contextual actions returns to the selected chapter. Left/right traverses actions; seasons remain reachable above. Season changes clear old episode selection and load the selected season through existing profile-gated service. Initial/default selection uses remembered episode, otherwise first unwatched, otherwise first available. Loading/empty/error states and Retry are explicit.

Play calls the existing source-resolution path, retaining future-airdate guard, per-profile resume, provider transport and player controls. Mark watched retains WatchKeys/profile storage and checkbox state semantics. More info opens an actual modal with episode art, full synopsis, airdate/runtime, series synopsis/cast/language metadata, fixed Close and remote scroll; Back/Close restores info focus. Watchlist/favourite remain there. Cast & related opens the existing secondary details library, with a return-to-Cinema action, preserving existing recommendations/crew features.

## Interaction colour override

Focus and Progress now use BrandOrange #FF8A00. NavPill is orange at14% opacity; navigation focus has an85%-opacity thin outline and subtle orange shadow. Text-only main navigation,10dp rectangles, stable hit targets, existing pinned80%→opaque scroll chrome and Reduce Motion are preserved. No bright solid-orange navigation fills. Actual player/progress controls follow central accent; brand artwork remains untinted.

## Validation and limits

Pure tests cover selected episode priority, empty/missing selections, artwork fallback, bounded real progress, and host wiring to guarded playback/profile state. Source guards cover shared orange tokens and no old blue/yellow overrides. JVM/perf and exact committed evidence are recorded in Draft PR199.

TV acceptance remains open: background framing/legibility, chapter strip scrolling and remembered episode, changing season during loading/error, info scroll/Close/Back, all four info-footer actions, watched/favourite/watchlist persistence and profile isolation, real playback/resume/future-episode guard, related-view return, Reduce Motion and warm focus visibility. No physical-TV/performance/provider acceptance is claimed. F6f remains installed baseline; no install/merge/release.

Parent source review corrected per-season selection restoration, leaf-only episode focus observation and scroll reporting when returning to the secondary library. Working-tree JDK21 offline JVM/perf verification passed:447 tests, zero failures/errors/skips. Exact committed build evidence belongs to Draft PR199. No hardware acceptance is implied.

Final contextual-action review additionally locks the selected episode before Play/More info/Mark watched, so marking the default first-unwatched episode cannot unexpectedly move the backdrop to the next episode. Regression and full working-tree JVM/perf build passed:448 tests, zero failures/errors/skips.

## Series-library and artwork refinement

The TV Cast & related library now opens Latest TV Shows / Aura Recommended / What's next?, with series cast retained above the content. Latest means new series premieres (not new episodes or guaranteed provider availability): existing TMDB transport/cache uses discover/tv, first_air_date.desc, first_air_date.lte=today and non-null dates. Results are also checked locally for valid non-future dates/type, deduplicated/sorted and passed through the existing child certification gate. Failure/empty/loading and Retry remain explicit. Recommendations and similar-title feeds remain distinct; nothing claims personalised suggestions. [TMDB discover reference](https://developer.themoviedb.org/reference/discover-tv).

Cinema seasons now show a fine vertical divider, Seasons: label and inline Season1/Season2 choices. Episode images carry a local two-layer reflection/edge sheen below text and focus, without blur, geometry changes or new images. Player-return focus still reaches the selected chapter after the library's new default tab.

TV checks: three library tabs and actual feed/error/retry behavior, cast and poster DPAD/Back, season label spacing with long season lists, and gloss legibility on bright/dark stills. No device/install/release acceptance is implied.

Polish verification:452 JVM tests, zero failures/errors/skips; offline test/perf build passed. New tests exercise real discover query parameters, future/unknown date exclusion, sorting/deduplication, child fail-closed filtering, provider failure propagation, requested TV tabs, season labeling and artwork-only gloss. Parent review retained chapter return focus under the new default tab and a stable refresh/retry action. Exact committed evidence is in Draft PR199; TV checks remain open.
