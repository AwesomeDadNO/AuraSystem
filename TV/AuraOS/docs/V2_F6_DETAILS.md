# AuraOS V2 – F6 Cinematic Details (AuraBox)

Status: **code complete on the JVM side, NOT built with Gradle and NOT run on an emulator.** Everything below marked "wired" is unverified on Android until Gemini's build + emulator pass.

## What is implemented
- **Metadata (extends the existing TMDB repository, no second repository):** details with credits, recommendations, similar, videos, release dates/content ratings and external ids in one request; season endpoint (with episode stills); per-episode credits (cast + guest stars). Caches: details 6 h, season data, episode credits (200 entries); errors never cached; in-flight requests de-duplicated.
- **Shared hero (movie + series):** "AURA BOX" label with orange marker, serif title (text, no official logo), Movie/Series chip, year, official certification, duration (movie) or season count (series), TMDB user rating (only when votes exist), genres, overview, backdrop top-right with dark gradients.
- **Actions:** Movie – Play, Add/Remove Watchlist, Trailer (only when TMDB lists an official YouTube trailer). Series – Play / Continue Watching, Add/Remove Watchlist, Mark Watched/Unwatched (for the episode currently shown in the hero).
- **Tabs:** Movie – Details | Aura Recommend | What's Next?; Series – Seasons | Aura Recommend | What's Next?. Orange underline marks the active tab; focus selects the tab.
- **Movie Details tab:** Cast row and a separate Creators/Producers row (crew shown under its real job; cast is never shown as crew).
- **Series Seasons tab:** season list left, horizontal episode row right. Episode card = still (w300), number, title, runtime, separate Play and Watched buttons; card gets an orange border while anything in it is focused. Focusing a card updates the hero (code, title, overview, runtime, watched state, play target) with a short crossfade (zero-length when `LocalReduceMotion` is true); focusing a season item restores the series overview. No network happens on focus change (season data is cached). Hero overview block has a fixed height, so nothing below it moves.
- **Cast in This Episode:** real episode credits, debounced 350 ms after focus settles, cached per episode. When TMDB has none (or nothing is focused) the row is labelled "Series Cast" – series cast is never labelled as episode cast.
- **Recommend / What's Next:** TMDB recommendations / similar titles (child-profile gating applied). What's Next is labelled "Related titles from TMDB — not personalised to you". Opening one pushes it on a back stack; Back pops.
- **Watchlist vs Watched:** separate per-profile persistent sets in `PrefsWatchStore` (SharedPreferences, keys `tmdb:tv:ID:sS:eE`), work offline, no AuraConnect/Simkl.
- **Playback:** movie TMDB→IMDb id→UltraMax movie sources; episode IMDb id+season+episode→UltraMax episode sources, using the F5 preferences (auto-start/quality/source chooser dialog). Unaired episodes show a message without a request. No auto-play on focus. After the player closes, focus returns to the played episode's Play button.
- **Trailer:** opens only in the installed YouTube TV app (explicit package, validated 11-char key); otherwise a truthful message.

## Deliberate honesty limits / deviations
- Content-advisory chips are **not shown**: no verified advisory source exists (TMDB genre/age rating is not proof). Movie details shows a muted "Content advisory unavailable".
- Quality badges (4K/HDR/Dolby) are **not shown**: they need confirmed source/track data.
- Title logos not implemented (text title only).
- The details page is a full-screen Dialog, so the top navigation bar from the mockups is not visible on it. Backdrop is used for the hero only; the episode still (w300) appears on cards, not as hero backdrop (too low resolution).
- Reduce Motion: `LocalReduceMotion` exists (default false) but no Setting drives it yet.
- Watch records of deleted profiles are not purged automatically (profile ids are UUIDs, so they are unreachable); `WatchStore.purge` exists for later wiring.
- Simkl sync and AURA Signature Scrubber sharing with AuraAmp are not part of this change.

## Performance review (no measurements possible in the cloud)
- Design choices, **not measured**: single details request instead of 4; season/episode data cached; episode credits debounced + cached; focus changes touch only local state; hero block fixed-height; LazyRow/LazyColumn for all lists; images use sized TMDB variants (w185/w300/w1280) through the existing loader; no polling added.
- Gemini should record frame behaviour while scrolling an episode row and focus-driven hero updates; no improvement is claimed.

## Part 1 – Media3 ExoPlayer review (document only, no rewrite)
Current engine: platform `MediaPlayer` (`PlayerEngine`), embedded timed-text only, no external subtitles, no ABR control, no resume. Media3 would add adaptive HLS/DASH, richer track selection, external subtitles, language preferences, resume, MediaSession. Suggested migration: (1) add `androidx.media3:media3-exoplayer`/`-ui`-less core behind the existing `PlayerEngine` interface so `VideoPlayerHost` and `PlayerMath` stay; (2) map `TrackPicker`/`Lang` to `TrackSelectionParameters` (preferred audio/text languages); (3) external subtitles via `MediaItem.SubtitleConfiguration` once UltraMax supplies them; (4) persist position per `WatchKeys` id for resume; (5) release in `onDispose`/`onStop`; (6) verify on emulator and a real TV before removing `MediaPlayer`. Requires network for dependency resolution (Gemini's Gradle environment).

## Gemini emulator checklist
1 movie details real data · 2 series details · 3 season selector · 4 episode focus updates hero (S2:E4 etc.) · 5 episode Play · 6 episode Watched/Unwatched persists after restart · 7 movie cast + creators/producers · 8 episode-specific cast vs "Series Cast" · 9 watchlist add/remove per profile · 10 Aura Recommend · 11 What's Next? · 12 UltraMax source selection + player audio/subtitle menus · 13 orange focus, DPAD, Back focus restoration · 14 trailer behaviour with/without YouTube TV. Screenshots must show genuine TMDB content; do not mark playback or episode credits verified from compile/mock tests only.

## Tests
242 JVM tests (credits parser, watch store, details logic). They do not exercise Compose, focus, or the network.
