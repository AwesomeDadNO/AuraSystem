# AuraOS v2 — F6e TV navigation, local media library and public YouTube

Parent: F6d Draft PR #194. This is an **unverified source implementation**, not a tested APK. Do not merge or publish until local Android builds, JVM tests and owner TV verification pass.

## What changed

- TV series Details: seasons are one **horizontal** row, above a separate episode row. DPAD DOWN is expected to travel from a selected season into the episodes. Cast stays on its own horizontal row.
- Episode cards: 10 canvas-unit radius (10 px at the measured 1920×1080 layout), just one orange outline around the entire episode block. Play/Watched text uses an underline when focused, rather than a second inner outline. Cast cards use the same radius.
- Local favorites and watchlists are **different per-profile sets**, displayed separately in Real Mode:
  - **Favorites** (formerly Saved in the rail): titles favorited on real TMDB Details.
  - **Library**: Continue Watching, Favorites and Watchlist, each scoped to the active profile and subject to the existing child certification policy.
  - No fictitious media entries are inserted.
- Local Continue Watching: the actual UltraMax player emits playback position and duration every 5 seconds while playing, plus at closure. At least 5 seconds must have played; >=95% or ENDED removes the entry. A stored episode is re-selected and the playback position is resumed. Stores only TMDB title ids, text/artwork, position and duration — **not** UltraMax streaming URLs or credentials.
- Profile deletion removes that profile's Favorites, Watchlist and local Continue Watching data.
- AuraTube Real Mode: real public YouTube Data API v3 `videos.list(chart=mostPopular,regionCode=NO)`, 20 items, metadata and thumbnails; video playback opens in the official YouTube TV app only. Successful public API response marks **public YouTube** as verified; this does **not** imply the user's Google account is connected.
- Public YouTube metadata has a 20-minute in-process cache, a brief failure backoff, strict video-id validation and a strictly allowlisted official thumbnail URL pattern.

## Owner's local configuration

The app already reads `YOUTUBE_API_KEY` from the **git-ignored** `AuraOS/tv/local.properties` for `debug` and private `perf` builds. It remains blank in public `release`. Configure it privately. Never put the value in a commit, PR comment, screenshot, logs or public APK. The key is extractable even from a minified private APK: restrict it in Google Cloud Console, and rotate if it has been exposed. Ensure YouTube Data API v3 is enabled.

No new build-time `SIMKL_CLIENT_ID` was wired yet. Simkl authenticated access has **NOT** been implemented in this PR; do not label it connected. Simkl now recommends AUTH V2, which needs a separate V2 client registration and a TV device/PIN flow using PKCE. A V1 client ID does not work with the V2 endpoints, and a client ID by itself does not authorize any user's history or watchlist. Implement that in a separate follow-up with encrypted per-profile token storage, explicit consent, refresh/revocation and activity-gated incremental sync. Documentation: https://api.simkl.org/authentication and https://api.simkl.org/guides/sync.

## Test on Google TV Streamer (owner only)

1. Verify Settings → About shows this PR head SHA and `perf`. Do not uninstall or clear data.
2. TV series Details: choose Season 1/2/3 horizontally, DPAD DOWN to episodes; ensure that selection, scrolling and returned focus work.
3. Focus episode image/title, Play and Watched controls: **only one outer orange focus border**, inner control uses underline; verify 10 px corners on episode/cast.
4. Add a movie/series to Favorites and separately to Watchlist. Open Favorites and Library. Confirm independent groups, correct artwork/title, persistence after relaunch and isolation across profiles.
5. Play a movie or episode for >10 seconds and exit. Home and Library Continue Watching should show a real progress indicator; reopen and verify it resumes near the stored position.
6. Fully finish a video: it should disappear from Continue Watching. Test a child profile to confirm restricted titles never leak from stored progress.
7. Open AuraTube in Real Mode. With a privately configured YouTube key, verify real popular videos and thumbnails load, and clicking a card opens the official YouTube TV app. Without a key, show honest unconfigured state; do not claim Google OAuth sign-in.
8. Delete a disposable test profile and recreate a new one; ensure old favorites and progress do not appear in the new profile.

## Known limitations and review items

- Device tests and Gradle compilation have not been run by ChatGPT. Use the local JDK 21 Android toolchain to run `:app:testDebugUnitTest` and `:app:assemblePerf` before any installation. No emulator, TV, remote control or release deployment is authorized.
- Simkl sync is **not** done. A proper V2 auth flow and profile-scoped secure token storage is required.
- YouTube browsing includes a public Norway popular list, not full search, subscriptions or private playlists. OAuth and user consent are necessary for private Google account data.
- User-specific Continue Watching is local to this AuraOS instance; cross-device progress will require explicit AuraConnect and/or Simkl sync later.
