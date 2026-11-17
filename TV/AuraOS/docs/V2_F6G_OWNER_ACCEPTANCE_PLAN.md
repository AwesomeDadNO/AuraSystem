# AuraOS V2 — F6g owner-requested functional + cinematic changes

Parent: F6f Draft PR #196, known-good **installed** build according to owner's Gemini report at commit `8a03525098fb8ac3ddccf26fd8c6edffa3a17d27`. This document is an approved **development scope**, not evidence that the listed features work.

## Acceptance order

### P0: Restore TV navigation inside movie and series Details
- Full-screen Details MUST provide the same destinations as the AuraOS top-level navigation (Home, Movies, TV Shows, Sports, Live TV, then dynamic media app shortcuts if supported) and access to the floating left icon rail. Returning to a destination must close Details safely, preserve state and move focus to a valid destination. A back-only full-screen trap is unacceptable.
- Detail-specific modal overlays (source picker, episode synopsis) trap DPAD only while open. A keyboard or app menu must not be mounted behind an active modal.
- Add/Remove Watchlist must be visible on movie and show Details, persist to the active profile, and update Favorites/Watchlist/Library without requiring a restart. Favorites remains an independent flag. Never conflate with Simkl remote state.

### P0: Simkl AUTH V2 real pairing
- The owner confirms PIN never appears despite a private Simkl V2 test client ID in the installed F6f perf APK. Inspect HTTP status/body **without leaking codes, tokens or IDs** and verify V2 device endpoints, request encoding, exact response names, client registration category and Android INTERNET permission. Reproduce with fake 200/pending/slow_down/error fixtures.
- **Root cause found in source review:** Simkl's official AUTH V2 docs explicitly require S256 PKCE even on its device/PIN grant. F6f accidentally removed `code_challenge`/`code_verifier`; F6g restores these while retaining `application/x-www-form-urlencoded` OAuth requests. This is a code correction, not evidence of successful pairing.
- Show meaningful error codes/reasons in Settings, distinguish not configured from rejected; do not claim successful connection until a real device grant completes. Per-profile Keystore token encryption remains mandatory.
- Do not expose the user's Simkl application identifier or any user tokens in source/tests/logs. If the ID is entered into a build config client APK, it is extractable by design.

### P1: TV redesign for large (85-inch) panels
- Increase hero vertical height (responsive target about 60% of 1080 canvas, with safe actions and image visibility). Preserve title metadata and DPAD.
- Portrait posters on Home, Movies, TV Shows **9:16 about 2× current width and height**: start 300–332 canvas units wide and 534–590 high, but measure memory use and screen density first. The user's aim is larger readable artwork, not more simultaneous cards. Use w500 TMDB artwork at 1080p and w780 at 4K to prevent blurry upscaled images. Hide *always-visible* title below real TMDB posters, but retain alt/focus accessibility and a meaningful fallback for empty/failed poster artwork. Never presume all TMDB poster assets contain embedded text.
- Episode card: clean background-free 16:9 TMDB still (entire still visible), overlaid episode number/title/runtime in the still with a modest legibility gradient. Below: equal-width Play and Watched buttons aligned with the image width, orange Play and light Watched. Below: More info button opening synopsis popup. Single focus ring, 10-unit rounded image/cast corners, never a duplicate border. Play and Watched must remain separate DPAD actions.

### P1: Plex / Jellyfin / Emby secure connections
- Add connection models, endpoint discovery/handshake with safe LAN endpoint verification, authenticated per-profile sessions/tokens encrypted at rest, explicit user approval for servers. Support HTTPS and local HTTP only after informed approval, enforce redirects/timeouts/host safety to avoid SSRF or credential leakage. No logging secrets.
- Jellyfin Quick Connect when enabled; Emby Connect or server PIN/password appropriate to its documented authentication path; Plex PIN OAuth and authorized resource discovery. Do not claim «connected» for simple TCP reachability: test authenticated user identity and access. A single generic «handshake» is insufficient across all three.
- Library scans are separate from verified connection; support multiple servers, independent errors/retry and per-user authorization.

### P2: Google identity + private YouTube data
- Do **not** silently use Android TV's existing signed-in Google account for Google/YouTube OAuth scopes. The platform does not provide cross-app YouTube credentials by default.
- Prefer explicit Google Identity Services/Credential Manager where supported on Android TV; if unavailable, offer Google officially documented TV limited-input OAuth, asking user consent. Device registration must be correctly configured for TV; YouTube private data needs a supported YouTube scope such as `youtube.readonly`. Treat Google profile identity and YouTube API authorization as separate permissions, linked to active AuraOS profile. Avoid unnecessary permissions.
- Existing YouTube public Data API v3 feed stays independent of OAuth.

### P2: Multi-country independent EPG and TV-app preference
- Introduce `LiveTvSourceMode` with `IPTV_PLAYLIST` and `INSTALLED_TV_APPS` modes. Toggling modes must not delete M3U/M3U8 setup. Independently configured EPG must work with either mode.
- EPG source: licensed provider API and/or user supplied XMLTV, country + region/timezone + stable channel identifiers. A country alone is **not** sufficient to infer an app's entitled channel lineup.
- In installed-TV-app mode: show installed verified TV provider apps (e.g. Altibox/RiksTV when installed) and an EPG for channels whose metadata can be legally obtained and matched. Launch the app or its documented deep link; **do not pretend** a third-party app exposes its internal channel list or a channel-level intent when it doesn't. Label unlinked listings honestly; cache respecting rights/ToS.
- Android TV TV Input Framework does not grant ordinary apps universal read access to privileged provider EPG database; do not attempt permission bypass.

## Process and verification
- Each subsystem gets separate commits, unit tests and TV smoke tests; branches/PR only, no merge/release without owner approval.
- Preserve the F6f installed APK baseline, app data, signing and local TMDB/UltraMax/YouTube/Simkl settings.
- Run local JDK 21 `:app:testDebugUnitTest` and `:app:assemblePerf` before any APK installation. User alone tests TV.
- Hardware: Google TV Streamer at 1920×1080, 320dpi, 85-inch display. Do not claim tests, login or handshakes passed without evidence.
