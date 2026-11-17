# AuraOS V2 — F6f interim UI + AUTH V2 integration

Parent: F6e Draft PR #195. **Draft/unbuilt**: source changes are pushed; no Gradle/JVM tests, Android Studio build or physical Google TV testing have been executed by ChatGPT. Do NOT merge or release until QA.

## UX contract from owner mockups

- Top: **Home / Movies / TV Shows / Sports / Live TV | installed media apps, ordered by AuraOS launch recency** (current active profile). No duplicate AuraBox tab. All installed app shortcuts are discovered via Android PackageManager; known streaming/media packages only; unknown or uninstalled packages are never manufactured. For privacy and permission minimization, recency means **launched through AuraOS**, not arbitrary Android device UsageStats.
- Left: floating, narrow icon-only rail with **Home / AuraAmp / AuraTube / Favorites-Watchlist / All Apps**. Module flags control AuraAmp/AuraTube visibility. Settings/search/profile stay top-right.
- **AuraBox remains an optional engine** behind Home/Movies/TV, with no separate top navigation destination.
- Warm orange is the canonical UI identity: active pills, 10 dp focus shapes, progress, a burnt-to-amber gradient `#D9531E → #F39C12`.
- TMDB Home, Movies and TV Shows shelves use **9:16 poster images**; Continue Watching uses **16:9** with an orange timeline. Hero has extra vertical room and always lists Play first. Details stays separate and uses the existing verified UltraMax infrastructure.
- AuraAmp demo mini-player follows the approved mockup and includes a **procedural decorative waveform with DPAD left/right 5-second seek**. It is intentionally only present in Demo Mode; **Real Mode is not wired to an actual AuraAmp MediaSession yet and therefore MUST NOT claim playback or display a fake player**.

## Simkl is AUTH V2 ONLY

The owner confirmed their existing test client ID belongs to Simkl **AUTH V2**, not V1. The attempted V1 client and `/oauth/pin` routes were **removed**, not retained.

- `SimklV2Client` sends `POST https://api.simkl.com/oauth2/device` with public `client_id` and read-only `media:read` scope, form-encoded as required by RFC 8628; V2 returns an 8-character display code and private `device_code`.
- The TV shows code and `verification_uri`; user authorizes on their own phone. Clicking `Check pairing` polls `POST /oauth2/token` only after the server-requested minimum interval and stops on expiry.
- A successful grant returns access and refresh tokens. Both are stored together as an AES-GCM encrypted value under the active AuraOS profile in a dedicated Android Keystore key. No token or device code enters a Git commit, log or UI.
- Expiring grants refresh through `POST /oauth2/token` and update the locally encrypted bundle before list import. Refreshing invalidates the prior access token; do not run competing refreshes on other threads.
- `Settings → Connections → Simkl → Sync Watchlist now` runs an explicit **read-only** import of movies and shows currently marked `plantowatch`. It first calls `/sync/activities`, then `/sync/all-items/{type}/plantowatch`. Imported local TMDB keys are additive: no remote writes and no deletion of existing local entries.
- Disconnect attempts V2 `/oauth2/revoke`, then wipes the local per-profile credential; if revocation fails, the user is instructed to revoke via Simkl Connected Apps. Deleting a profile also purges its Simkl token.
- Only `media:read` is requested. **Automatic two-way syncing, watched-history syncing, ratings, scrobbling and background incremental sync are NOT implemented**; they need further design, `media:write` consent and tests.
- The V2 app's `client_id` is a public application identifier, not a user access token. We still keep it outside this PR: configure `SIMKL_CLIENT_ID` in **git-ignored** `AuraOS/tv/local.properties` for the private perf/debug build. An unconfigured build gives an honest unconfigured status. Do NOT use a V1 client ID here. No secret or user token should ever be stored in source.
- Confirm the developer registration is **TV, devices & command line**. Different client types use different sign-in methods, even on V2.

Official docs: https://api.simkl.org/authentication and https://api.simkl.org/api-reference/auth-v2 and https://api.simkl.org/api-reference/simkl/get-all-items.

## YouTube

- Existing F6e public YouTube API v3 browsing is retained: verified `videos.list` mostPopular NO feed, strict YouTube thumbnail host allowlist, official TV-app playback handoff. This is not Google OAuth, subscriptions, or user playlists.
- Set `YOUTUBE_API_KEY` in the same git-ignored local.properties and enable YouTube Data API v3 for that Google Cloud project.
- The APK itself can expose client-side API keys. Restrict the YouTube key to the intended API and Android app signing/package where compatible with the chosen request method. Rotate it if it has been exposed.

## Build/QA gate

1. Use an isolated local worktree at this branch head; inspect `git status` and verify the commit before any build.
2. Private local.properties must have TMDB, YouTube and Simkl V2 client ID; do not echo values in terminal, logs or comments.
3. With JDK 21, run `:app:testDebugUnitTest` and `:app:assemblePerf`. Fix only new regressions on the F6f branch. Preserve dependencies and build signature.
4. Owner-authorized installation **update-only** (no uninstall, no data clear), and owner-only DPAD/UltraMax/Simkl/YouTube UI tests.
5. Verify Settings → About exact commit/variant, and Simkl under Connections. Confirm the TV shows the 8-character V2 code, authorizes, stores tokens, syncs watchlist and remains profile isolated. Test failure, expiry, retry and disconnect.
6. Verify horizontal season selector above episodes and deterministic DOWN to episode row; one focus outline and 10dp corners. Confirm TMDB posters and 16:9 Continue Watching with orange progress.
7. Verify installed app icons after top-nav divider; order changes only after launch through AuraOS. Test module disabling and profile switch.
8. Verify mini-player remains **absent in Real Mode unless real Amp playback exists**.

No merge, release, emulator, local device controls or performance claims are authorized by this documentation.
