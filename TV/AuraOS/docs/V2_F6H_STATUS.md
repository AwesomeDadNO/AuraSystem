# AuraOS V2 — F6h development status

## Review fixes by Claude — 13 November 2026 (on top of `161c49d4`)

Code review of `7bfa87b..161c49d4` (21 commits) produced ten findings; the five most severe are fixed here. JVM tests: 511, 0 failures; `:app:assemblePerf` succeeds. **Nothing was run on a TV or against a real server or Eclipse.**

| Fix | What changed | Still to verify on TV |
|---|---|---|
| Media-server playback verified the server for every 1 MB range | `MediaServerService.verifyFresh`: the network identity check is reused for 2 minutes; profile, account and address checks still run on every call; a failed check is never reused | Start time, seeking and buffering from Plex / Jellyfin / Emby |
| Home shortcut could loop Home → AuraOS → crash | `HomeShortcutCrashGuard`: after 3 automatic starts without AuraOS staying open for 10 s the shortcut pauses itself; it resumes when AuraOS has been open for 10 s | Enable the shortcut, confirm normal behaviour; state text in Settings |
| Eclipse audio rejected redirects to another host | Redirects are followed to a public https address unless the URL would carry the ARL off Eclipse's host; every hop is still DNS-validated, no credential header is sent | Whether this is why audio failed after a successful manifest check (unknown until tested) |
| Keystore decryption during shell composition | `AmpArlVault.isSet` (presence only) replaces `has` in the settings state | None functional |
| Stored system notifications were erased on every disconnect | Removed only when the permission is revoked / not granted / unavailable | History survives an app restart |

Open findings, not fixed: the recommended Plex device-key sign-in shows a ~150-character approval URL that cannot be typed (needs a QR code or the PIN flow as default); two PackageManager lookups per window event in the Home-shortcut service; the notification listener only accepts PUBLIC-visibility notifications (most apps use the private default); Watch Next rows are deleted and re-inserted on every resume.

## Current override — line navigation and sport widgets, 13 November 2026

Menu/season/episode text uses logo-orange underline; rail icons become orange without pills. Amp shortcut removed; Albums/Artists/Playlists followed by line and Search field. Four module mockups are proposals, not implemented new pages. AuraSport adds profile favorites and selected demo widgets on Home/Sports. Startup age/content banner remains visible with honest unknown states; detailed categories require verified source data. See [current contract](V2_F6H_AMP_LINES_SPORT_WIDGETS.md). No install/merge/release; TV acceptance open.

## Mobile visual identity override — 13 November 2026

Owner approves mobile palette throughout AuraOS: background#0C0F14/#0E121A, surface#141923, signature#FF8A00–#FFA133. AuraAmp TV player adopts mobile cover/title/waveform, round gradient Play/Pause and native icon hierarchy within existing TV layout. Download/lyrics remain visibly disabled in the no-audio demo. See [current visual contract](V2_F6H_MOBILE_VISUAL_IDENTITY.md). Supersedes previous darker palette/player styling; no install/merge/release, TV acceptance open.

## Current correction — 13 November 2026

White text/icons on warm active/focus states; background#05080D. Cast/related now live below episodes in the same Cinema page; obsolete TV page and popup link are removed. Profile-local bulk notifications, split movie/series saved shelves plus Continue Watching, system row artwork/TV intents, Media3 track discovery and landscape preparing image are corrected. Audio output and cross-app provider availability still require device/source verification. See [correction contract](V2_F6H_CINEMA_CORRECTIONS.md). No install/merge/release.

## Cinema polish — 13 November 2026

TV Cast & related now uses Latest TV Shows / Aura Recommended / What's next?, preserving cast and profile filtering. Latest uses actual date-sorted TMDB premieres, not a renamed popular feed. Cinema seasons have a vertical divider and Seasons: label; chapter images add local gloss beneath text/focus. See [Cinema contract](V2_F6H_CINEMA_SERIES.md). TV/provider acceptance remains open; no install/merge/release.

## Current override — Cinema mode and warm focus, 13 November 2026

Owner selected concept07 for TV series and reinstated softened logo-orange active/focus. Primary TV details now use selected-episode cinema background, one shared action lane and large stable chapter strip; films keep their composition. Focus/Progress=BrandOrange #FF8A00; NavPill14%, thin diffuse focus. This supersedes blue-only interaction and80/20 episode-button layout for the primary TV page. See [current contract](V2_F6H_CINEMA_SERIES.md). No install/merge/release; TV acceptance remains open.

## Current owner override — blue navigation, 13 November 2026

This supersedes earlier orange/yellow interaction and boxless line rules: branding-only #FF8A00, universal #79D5FF focus, selected 18% blue NavPill, 10dp navigation corners, text-only main destinations, pinned chrome transitioning from 80% hero opacity to opaque #0A111A on actual outer scroll. See [current contract](V2_F6H_BLUE_NAVIGATION.md). TV acceptance remains open; no merge/release/install.

## AuraSport demo update — 13 November 2026

The owner-approved news hero / league match grid is now a native fictional demo, with dedicated per-profile AuraSport settings (independent news/fixture leagues and spoiler mode). Main menu text is 25 sp; line menus are boxless. Episode controls supersede 90/10 with transparent outlined 80/20 and semantic checkmark. See [scope and TV acceptance](V2_F6H_AURASPORT_DEMO.md). Real sports/news providers, broadcast mapping and reminders remain unconnected; do not mark live integration complete. No install/merge/release.

## Owner update — AuraAmp v5 and line navigation (12 November 2026)

Working-tree verification: 438 JVM tests, zero failures/errors/skips; offline debug tests and perf assembly passed. Parent source review corrected skipped favourite/queue controls, transport traversal and queue-focus restoration. Exact committed build evidence belongs to PR #199; TV acceptance remains open.

This update supersedes older v4/carousel and filled navigation rules. AuraAmp uses the approved v5 hero and right player, four landscape releases, compact artist row, and Recently played / Queue line tabs. Module navigation: Amp waveform icon, Albums, Artists, Playlists. It remains a fictional, profile-local no-audio demo; no bottom mini-player.

Shared top navigation uses logo orange (#FF7A00) text/icons and an underline for selected/focused items, with no pill/background. The left rail has no boxes or underline: only icon colour changes. Focus retains stable target sizes. Track actions expose favourite and queue controls in sequence; player transport and footer have explicit left/right routes. The waveform supports simulated ten-second seeking with Left/Right and exits with Up/Down. Queue removal restores a valid focus target.

Acceptance on the owner's TV remains open: traverse shell → module → hero → releases → artists → track tabs/list → player and back; verify favourite/queue access, empty/removed queue, seek, profile changes, clipping and legibility. No performance or physical remote claim follows from JVM/build checks. F6f remains the installed baseline; no install, merge or release is authorized by this update.


## Latest owner amendment — AuraAmp lock and episode/playback presentation

The owner approved AuraAmp's horizontal-navigation v4 design and requested a native interactive demo, with no bottom mini-player. Current locked design: [AuraAmp](AURAAMP_DESIGN_LOCK.md). Latest UI corrections supersede earlier hero offsets and50/50 episode actions: hero at0, transparent rail, scoped yellow secondary-hero focus,90/10 More info/checkbox, richer episode info, title language metadata, poster/AuraBox loading ring and an honest verified-rating banner. Details, data-source limits and acceptance: [current polish record](V2_F6H_AMP_EPISODE_PLAYBACK_POLISH.md). AuraAmp is still a no-audio demo; detailed nudity/drugs/violence advisory data is not connected. Earlier contradictory sections below are historical.


## Latest owner refinement — Live Home, Home shortcut and own player

The owner's latest TV feedback supersedes the old Live TV guide rollback and prior no-service Home limit. Implement Home-style Live TV app discovery with explicitly demo EPG, keep IPTV deferred, use Aura own player controls with actual audio/subtitle/source options, real optional consent-bound Home shortcut service, improved transparent logo fitting, stronger glass, inline season tabs and raised hero framing. Static2:3 posters and Sports demo baseline remain. Current details, capability limits and acceptance: [refinement record](V2_F6H_LIVE_HOME_PLAYER_REFINEMENT.md). Earlier sections below are historical where they conflict.


## Latest owner test-edition amendment — visual assets and category pages

Integrate approved transparent app logos and the 12 category backgrounds. Clicking a category opens a full page with shared hero and real genre-filtered Trending / Popular / New / Top Rated shelves, with profile gates and bounded pagination. Episode card image starts existing playback flow with an orange Aura play badge; Add to watched and More info are50/50 below. Keep static2:3 posters. Lower hero artwork framing and test subtle glass on episode/category cards. Details, data semantics, fallback coverage and TV acceptance: [visual implementation record](V2_F6H_VISUAL_ASSETS.md). This supersedes the earlier plain category-list and placeholder wordmark UI descriptions; it is source/test scope, not owner device acceptance.


Updated 2026-11-12. This is source/build status, not TV acceptance. F6f installed/tested owner baseline remains `8a03525098fb8ac3ddccf26fd8c6edffa3a17d27`.

## Ownership and isolation

Owner asked Codex to take over Claude's interrupted work, then explicitly authorized continuing all remaining F6h requirements. Claude auto-continue was disabled by the owner. Branch `auraosv2/f6h-media-integrations-sports`, Draft PR #199, issue #198, parent F6g #197 unchanged. No merge, release, install, emulator, ADB, TV control, uninstall or data wipe performed.

Claude's original uncommitted connection slice was copied without changing the original worktrees. Takeover connection slice `77ccfba5` passed 316 JVM tests and perf build. Development continues in a separate checkout. Owner's existing ignored development settings were copied privately from the original main checkout; the original was unchanged. Keys were not displayed or committed. Missing Google/update settings were added as blank local placeholders.

## Current implementation — owner correction round

The previous TV result was rejected by the owner. Earlier 378-test evidence belongs to `971f663d`; it is not evidence for these corrections. Current exact-commit validation is recorded in PR #199.

| Area | Current source / limit | Still requires owner TV validation |
|---|---|---|
| Posters / background | Static 184×276 2:3, gap12, no expansion/scale, subtle border, #0A111A | Density, appearance, rapid DPAD and Back |
| Apps / shortcuts | Five per row; rounded landscape known-package UI wordmarks, contained native fallback for others | Match supplied reference; local labels are not official logo assets |
| Notifications | All/Unread inbox; source/time/Open/read/dismiss; separate Settings view | Usability, access/revocation, categories, quiet hours, real events |
| Simkl | Dedicated persistent pairing controller/dialog, polling/backoff/expiry, ViewModel retention across Activity recreation; verified save before Connected | Real approval, pending/network failure/Close/Back/profile switching. Full process death requires new pairing; private grants are not stored in plaintext saved state |
| Home / Accessibility | Activity-result role request with settings fallback; actual handler recheck; functional Android Accessibility settings action | OEM prompt/cancel/success/Home/reboot/recovery. An optional accessibility service (`AuraHomeShortcutService`, the Home shortcut) IS declared; it stays inactive until the owner consents in AuraOS and enables it in Android |
| Android Continue Watching | Separate opt-in public TV-provider reader, runtime permission if supported, child block, no persistent import, same-package safe links; Home and Library | Permission grant/deny/revoke, OEM exposed rows, exact app return. Private Google TV Watchlist is not available |
| Live TV / Sports | Old guide and Sports demo routes restored with preview labels; no real route fetching/playback | Confirm old layout; API/player redesign deferred. Existing encrypted preferences/backend retained |
| Existing integrations | Plex/Jellyfin/Emby accounts/libraries/direct playback/progress; Google device OAuth/private YouTube; search/genres/trailers; notification adapters and own-progress publication retained | Real providers/devices. Direct play only; music is metadata, not audio. Missing OAuth/update configuration remains unavailable |

## Decisions and external limits

- Owner approved LAN HTTP for media servers **with a warning** in Claude's session. It is limited to canonical private IP literals and explicit per-server consent; named servers require HTTPS. Other new feed/HLS transports remain public HTTPS only.
- Owner explicitly approved Media3 in this chat. Pinned `media3-exoplayer` and `media3-exoplayer-hls` **1.4.1**, compatible with the existing compile SDK 34/toolchain. No toolchain upgrade or other new runtime library. Review version/toolchain before public distribution.
- System notification access is optional and separate from posting permission. No POST_NOTIFICATIONS, QUERY_ALL_PACKAGES, or privileged ACCESS_ALL_EPG_DATA. (Correction 2026-11-13: the manifest does declare one accessibility service, the optional Home shortcut, bound with BIND_ACCESSIBILITY_SERVICE; using the accessibility API for this is a release-gate question.) Public READ_TV_LISTINGS is newly declared and requested only after explicit per-profile opt-in; it may reveal device-wide history. Own-row Watch Next write and normal network-state permissions remain.
- Current private configuration contains TMDB, Simkl and public YouTube settings. Google TV OAuth ID and signed update URL/public key remain blank and require owner configuration. No accounts were actually connected by this development run.
- MusicBrainz public metadata supports music search/followed-artist release notices for private development; audio playback is not connected. Commercial service terms/contact attribution are a release gate. SportScore commercial usage remains unapproved.
- Update notices require an owner-controlled signed envelope (`SHA256withRSA`, X509 public key), matching app ID, channel, newer version and nonfuture date. No manifest, release, signing key or update distribution was created. Empty configuration is honestly unavailable.

## Validation and review

Commands: JDK 21 and the local Android SDK, `./gradlew :app:testDebugUnitTest :app:assemblePerf`. Current run results and exact final HEAD are recorded in PR #199 and the delivery report. Prior delivered revision passed 378 tests. Correction-round full local run passed **389 tests, zero failures/errors/skips**. Exact committed perf-build evidence is recorded in PR #199; do not reuse the older 378 count.

Behavioral coverage includes OAuth pending/backoff/expiry/revocation/profile isolation; Plex RSA proof/nonce and refresh bindings; rotated resource credentials during playback; account/endpoint confinement; range validation; playlist/guide parsing and Android-like SAX compatibility; cancellation/disconnect/no subsequent fetch; sports cache/errors/favorites/timezones; static poster geometry, persistent Simkl pairing, Home launch fallbacks, safe TV-provider link extraction and consent/child policy; grouped search and partial errors; center preferences/dedupe/revocation/release dates; signed update notices; actual Home-handler policy and official-only trailers.

Independent focused reviews found and corrected: native HLS transport bypass (replaced with guarded Media3), continued cancelled source fetches, Android SAX feature incompatibility, rotated playback-token mismatch, stale profile library snapshots, cold-start Watch Next target reset, child opt-in rating bypass, unofficial trailer fallback category Back-state loss stale writes after profile deletion, service-level legacy credential authorization and Android 14 network-job permissions. Deleted-profile cleanup also removes its Plex signing key when the Administrator-authorized profile deletion succeeds. JVM/source checks do not establish Compose focus, visual smoothness, actual provider interoperability or device performance. Some final wiring/background/subscription changes were self-reviewed; owner device acceptance remains mandatory.

See `V2_F6H_GEMINI_TEST_HANDOFF.md` for the next test session. F6h remains in progress until external configuration and owner acceptance are complete. Keep PR #199 Draft.

## Official implementation references

- Plex JWT/device-key authentication: https://developer.plex.tv/pms/
- Jellyfin Quick Connect: https://jellyfin.org/docs/general/server/quick-connect/
- Emby playback check-ins: https://dev.emby.media/doc/restapi/Playback-Check-ins.html
- Google TV OAuth/scopes: https://developers.google.com/identity/protocols/oauth2/limited-input-device
- YouTube own playlists/subscriptions: https://developers.google.com/youtube/v3/docs/playlists/list and https://developers.google.com/youtube/v3/docs/subscriptions/list
- Watch Next: https://developer.android.com/training/tv/discovery/watch-next-add-programs
- MusicBrainz API/rate limits: https://musicbrainz.org/doc/MusicBrainz_API
- Sports provider: https://sportscore.com/developers/
- Media3 1.4.1 toolchain constants: https://github.com/androidx/media/blob/1.4.1/constants.gradle

Android 14 JobScheduler permission reference: https://developer.android.com/about/versions/14/behavior-changes-14#jobscheduler-network-permission

Correction-round platform references: [public TV permission](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/main/AndroidManifest.xml), [Home role](https://developer.android.com/reference/android/app/role/RoleManager#ROLE_HOME), [Watch Next searchability](https://developer.android.com/reference/android/media/tv/TvContract.WatchNextPrograms#COLUMN_SEARCHABLE). These APIs do not guarantee OEM row availability or a supported Home chooser.

Visual/category round validation: focused review corrected full-screen category Back/focus trapping, status-action DPAD reachability and stable per-feed row state. Latest full JVM run passed402 tests with no failures/errors/skips; exact final-commit test/perf evidence is recorded in PR#199. No owner device or provider validation is implied.

Live Home/player refinement validation:415 JVM tests pass with zero failures/errors/skips; local test/perf working build passed. Exact committed build evidence is recorded in PR199. Focused security/lifecycle review found no concrete important blocker; parent additionally isolated track/source menu focus. No physical TV/provider/Accessibility behavior acceptance is claimed.


## Latest owner revision — 2026-11-13 hero/settings/sync

See [hero, Settings and Simkl contract](V2_F6H_HERO_SETTINGS_SYNC.md). Bold white shadowed fixed-slot wrapping hero titles; Settings search/device settings/redesign; saved-page focus/duplicate-key corrections; first-grant automatic and approximately hourly read-only Simkl Plan-to-Watch import with toggle/status. Device crash resolution and provider/background timing acceptance remain open. F6f retained; no merge/release/install.


## Latest owner revision — operational Amp catalog/provider

See [AuraAmp real catalog and Eclipse playback](V2_F6H_AMP_OPERATIONAL.md). Adult Real Mode now uses actual Deezer metadata and native Eclipse/Media3 playback plumbing; Demo remains isolated. Five editorial slides, real local history/queue, Artists/Albums/Playlists/Popular/Trending, date sorts and public playlist import. Base #0E121A, glossy player and exact-route active chrome. Credential format is fixed host + encrypted per-profile ARL + manifest path; no secret ships. Actual authenticated Eclipse/TV acceptance is open. Automatic cross-device/private-account playlist sync and future Deezer SSO are not supplied. No merge/release/install; F6f unchanged.


## Latest correction — Eclipse protocol and approved Amp design

See [protocol/design correction](V2_F6H_AMP_PROTOCOL_DESIGN_FIX.md). Owner manifest-verification success does not establish audio success. Correct documented addon-local identity/direct streamURL/ISRC/HLS handling and safe stage diagnostics; restore four overlaid release cards, five artists, uncluttered hero and native mobile-style player controls. TV and actual authenticated playback acceptance remain open. No merge/release/install; F6f preserved.
