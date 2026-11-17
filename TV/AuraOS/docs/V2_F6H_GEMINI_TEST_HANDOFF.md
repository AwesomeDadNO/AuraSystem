# F6h — next owner/Gemini test session

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

This update supersedes older v4/carousel and filled navigation rules. AuraAmp uses the approved v5 hero and right player, four landscape releases, compact artist row, and Recently played / Queue line tabs. Module navigation: Amp waveform icon, Albums, Artists, Playlists. It remains a fictional, profile-local no-audio demo; no bottom mini-player.

Shared top navigation uses logo orange (#FF7A00) text/icons and an underline for selected/focused items, with no pill/background. The left rail has no boxes or underline: only icon colour changes. Focus retains stable target sizes. Track actions expose favourite and queue controls in sequence; player transport and footer have explicit left/right routes. The waveform supports simulated ten-second seeking with Left/Right and exits with Up/Down. Queue removal restores a valid focus target.

Acceptance on the owner's TV remains open: traverse shell → module → hero → releases → artists → track tabs/list → player and back; verify favourite/queue access, empty/removed queue, seek, profile changes, clipping and legibility. No performance or physical remote claim follows from JVM/build checks. F6f remains the installed baseline; no install, merge or release is authorized by this update.


## Safety and exact version

PR #199 is Draft. Do not merge, release, uninstall, clear data or operate the TV without the owner's explicit authorization. Preserve installed F6f baseline `8a03525098fb8ac3ddccf26fd8c6edffa3a17d27`. Record the current installed revision before any owner-approved update. Use the existing app ID/signing compatibility and update-in-place procedure; never uninstall to fix a signature mismatch.

Use the separate `f6h-takeover` checkout, not Claude's original dirty worktree. Read final HEAD from PR #199 and verify the checkout matches it. The private artifact is `AuraOS/tv/app/build/outputs/apk/perf/app-perf.apk`; it contains developer configuration and must remain private. Settings → About must show the same exact SHA and `perf`, without `-dirty`.

Local validation (JDK 21, Android SDK; no installation):

```sh
cd AuraOS/tv
JAVA_HOME="$(/usr/libexec/java_home -v 21)" ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest :app:assemblePerf
```

Never print `local.properties`, tokens, server URLs with credentials, keystores or private playback URLs. Google TV OAuth and signed update-channel placeholders are blank unless the owner fills them privately. Public release keys remain empty. Do not ask Gemini to run TV commands by implication; the owner controls the device unless explicitly delegating an operation.

## Test order and expected behavior

1. **Baseline regressions:** existing profiles/PINs, settings, UltraMax playback/resume, Simkl V2, TMDB Home/Movies/TV and public YouTube. Confirm nothing was deleted or silently reset.
2. **Visual correction at 4K/85 inches:** static 184×276 2:3 posters (~35% smaller than rejected build), close 12-unit gaps, no expansion/popup/reserved landscape slots/focus scaling. Rounded subtle borders and #0A111A background. Test first/last/rapid DPAD and Back. Five All Apps tiles per row. Check rounded landscape shortcut badges against owner reference; known brand UI labels replace white square Netflix/YouTube tiles. Record appearance as owner-approved/rejected, not assumed correct.
3. **Chrome/genres/trailer:** landscape shortcut badges/native fallback and slightly larger text/icons; identical transparent details navigation; Movies/TV genre cards open filtered pages; More/Back preserves category/page/position; current hero Trailer opens the correct official YouTube video, unavailable trailer/app is honest, and return restores focus. No autoplay.
4. **Search:** enter accented text using TV IME, switch type filters, paginate, open movie/series/app/music, return without losing query/filter/scroll. Cancel/change a query while fetching. One failed source must not hide the others. Switch profiles or tighten child restrictions: old/private results disappear immediately. Music results are metadata in AuraAmp, not fake audio playback; try Follow/unfollow.
5. **Personal servers:** connect an actual owner-authorized Plex/Jellyfin/Emby account. Test modern Plex key PIN approval/restart/refresh/revocation, explicit legacy compatibility if needed, Jellyfin Quick Connect/manual fallback and Emby manual. Invalid address/expired code/denied credentials must never show Connected. LAN HTTP must require its own warning; public HTTP/numeric aliases rejected. Test libraries, episodes, pages, direct-play supported formats, resume/start-over, progress, background/reopen, remove and profile isolation. No transcoding claim; child server browsing remains blocked without verified ratings.
6. **Google account:** configure a TVs and Limited Input OAuth client privately; consent on a phone; test pending/backoff/expired/denied, Administrator expiry, real account link, restart/refresh/revoke/offline disconnect and profile isolation. Browse own playlists/items/subscriptions, open official YouTube. Watch Later/private Google TV Watchlist must not appear as imported.
7. **Live TV rollback:** old demo guide must appear in both modes, clearly labeled preview. Do not test new IPTV entry/player as accepted UI; it is deferred. Stored playlist/guide preferences must not be deleted.
8. **Sports rollback:** old demo hero/matches must appear with Demo content label, with no live-feed promise or sports polling. Study API in a later round.
9. **Notification center:** opens simple All/Unread inbox, clear empty state and separate Settings view; DPAD into/out of Settings, Open/read/dismiss focus recovery; defaults/categories, unread badge, mark/read/dismiss/restart/dedupe/retention, quiet-hour times/timezone, followed-artist metadata and actual watchlist/episode/Continue Watching events. Completed/future items excluded; child data filtered. Test optional Android access unavailable/denied/granted/revoked; no accessibility request. Background refresh is best effort and only updates the center, never interrupts playback or installs updates. Signed updates stay unavailable until a verified channel is configured.
10. **Home / Watch Next:** Settings action directly opens supported Android role chooser/settings; actual default status changes only after OS confirmation. Accessibility row opens real Android settings without enabling any service. Test supported chooser cancellation/denial/actual success, Home from another app/reboot and switching back to the original launcher. No automatic accessibility. Optional Watch Next disclosure/opt-in/opt-out, actual movie/episode return from a cold start, wrong-profile handling, changed child ratings, provider-denied publication and cleanup. Publishing does not prove Google displays a system row.

## Report format

For each check record exact SHA, build variant, TV model/OS, pass/fail/unrun, reproduction steps and screenshot/measurement when useful. Separate JVM/compile evidence, real provider evidence and owner device results. Report missing client configuration/provider access as blocked, not passed. Do not claim a performance improvement without measurements. Keep secrets out of logs and shared screenshots.

11. **Simkl PIN:** leave code visible for at least a minute, pending checks and network loss; code must remain until approval or explicit Close/Back. Expiry should remain visible with explanation. Test real approval, secure-save success, Activity recreation retaining the same code, profile switch and no late connection after Close. Full process death requires new pairing; private grants are not saved in plaintext state.
12. **Read shared Android TV Continue Watching:** separate Settings opt-in, device-wide history disclosure and public TV listings permission. Test denial/revocation, empty/restricted provider, child block, profile switch/off clearing, unknown duration without fake progress, actual exposed title in Home/Library and safe same-app return. This does not import Google-account Watchlist. Keep own-progress publishing tests separate.

## Latest visual test edition

- Verify approved transparent logo artwork in shortcut strip and All Apps: no colored/white app plates for mapped apps; unknown/unverified packages retain native fallback. Validate Norway apps actually installed and launchable.
- Movies/TV genre cards use new art, dynamic readable label and subtle glass; selecting one opens a full shared-hero page with Trending/Popular/New/Top Rated. Test genre/type accuracy, partial failures/retry/Find more, row paging caps and child gates. Back from details restores the category and its scroll/focus; Back again restores the original genre card.
- Episode image with orange play badge starts playback resolution; below it exactly two equal-width watched/info actions. Test future-airdate denial, source picker preference, watched profile persistence, synopsis and playback return focus. Static2:3 posters must not expand.
- Judge lowered/top-aligned hero framing and minimal glass on actual TV; record any faces still cropped, glare, readability or focus defects. This is a visual experiment, not assumed owner-approved appearance.

## Latest owner refinement checklist

- Verify hero starts just under menu, faces preserved, custom logos visibly larger without plates, alpha edges clean, stronger glass and new orange-glow Aura play glyph. Static2:3 must not expand.
- Season selection is a menu row with orange selected underline. Watched/info are outlined equal-width actions with icons; remote focus and synopsis return remain correct.
- Home shortcut: adult/Admin consent, OS access separate, deny/cancel/off/revoke, exact stock Home appearance/Home and Back trigger, Settings/systemUI/other apps must never trigger, no loop. Normal HOME role/recovery still works. Record OEM/model/OS; do not assume service behavior from JVM tests.
- Live TV: Home-like programme hero explicitly demo EPG/illustrative art; original16:9 native app banners; only actual installed launchable candidates; missing/disabled app and child block; correct app open/return. No IPTV UI/playback or invented entitled channels.
- Aura own player: audio menu actual switch/failure, timed-text/off menu, unsupported/external subtitle explanation, actual UltraMax Sources list and position-preserving switch, server confined transport/resume/report/background/STOP and user playback preferences. No other server sources may be invented. Menus trap DPAD focus and Back closes menu before player.

## Latest AuraAmp design-lock and playback polish checks

- Compare native AuraAmp to approved v4 horizontal-navigation mockup: OS chrome, no music sidebar, layered banner, artists, track list, right player only. Demo/no-audio marking must remain obvious. Test Search/local fixture filters versus separate real metadata search, tabs, favourites/follows/queue, simulated play/seek/repeat/shuffle, route return and profile reset; no bottom mini-player in either mode.
- Hero starts at top under OS chrome, no rail plate, non-Play hero controls transparent/yellow on focus/press; static2:3 posters still do not expand.
- Episode image→More info/checkbox90:10 DPAD, white More info/black text and watched state; rich info opens at top, long synopsis scrolls by remote, fixed Close/Back restore origin.
- Title spoken/original languages must be actual metadata and distinct from available stream audio/subtitles.
- Playback loading: real fitted poster, AuraBox inside loading ring, Reduce Motion static; no artwork/rating fabricated for sources lacking metadata. READY focus works, actual resume/source/audio/subtitles/transport unaffected.
- Rating banner starts only on actual playback, disappears after8sec and does not repeat for same-session source switch. Unknown ratings suppressed; detailed nudity/drugs/violence labels must never be inferred. Current TMDB data is certification only.


## Latest owner revision — 2026-11-13 hero/settings/sync

See [hero, Settings and Simkl contract](V2_F6H_HERO_SETTINGS_SYNC.md). Bold white shadowed fixed-slot wrapping hero titles; Settings search/device settings/redesign; saved-page focus/duplicate-key corrections; first-grant automatic and approximately hourly read-only Simkl Plan-to-Watch import with toggle/status. Device crash resolution and provider/background timing acceptance remain open. F6f retained; no merge/release/install.


## Latest owner revision — operational Amp catalog/provider

See [AuraAmp real catalog and Eclipse playback](V2_F6H_AMP_OPERATIONAL.md). Adult Real Mode now uses actual Deezer metadata and native Eclipse/Media3 playback plumbing; Demo remains isolated. Five editorial slides, real local history/queue, Artists/Albums/Playlists/Popular/Trending, date sorts and public playlist import. Base #0E121A, glossy player and exact-route active chrome. Credential format is fixed host + encrypted per-profile ARL + manifest path; no secret ships. Actual authenticated Eclipse/TV acceptance is open. Automatic cross-device/private-account playlist sync and future Deezer SSO are not supplied. No merge/release/install; F6f unchanged.


## Latest correction — Eclipse protocol and approved Amp design

See [protocol/design correction](V2_F6H_AMP_PROTOCOL_DESIGN_FIX.md). Owner manifest-verification success does not establish audio success. Correct documented addon-local identity/direct streamURL/ISRC/HLS handling and safe stage diagnostics; restore four overlaid release cards, five artists, uncluttered hero and native mobile-style player controls. TV and actual authenticated playback acceptance remain open. No merge/release/install; F6f preserved.
