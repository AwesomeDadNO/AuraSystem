# Amp line menus, proposals, AuraSport and rating banner — 13 November 2026

Owner reinstates logo-orange underlines for menu/season/episode labels and orange rail icons without active/focus pills. This supersedes filled selection rectangles; media-card focus frames remain for remote clarity. Underline is2dp selected/3dp focused, same shared logo-orange token, stable target geometry. Rail icons use the same hue and no plate/underline. Existing foundation palette/mobile-style player remains.

## AuraAmp

Only Albums / Artists / Playlists appear in the module line. The old Amp icon shortcut is hidden (enum/session compatibility retained). A thin line fills remaining width and the right Search field filters the fictional demo's track title/artist metadata; query resets scroll, no-result state is explicit and Queue clears search. TV IME Search hides keyboard and targets the first result if present. Existing music-provider/global-search integration is not implied.

Four owner-requested proposals are in [Albums](references/amp-module-proposals/albums.png), [Artists](references/amp-module-proposals/artists.png), [Playlists](references/amp-module-proposals/playlists.png), [Search](references/amp-module-proposals/search.png). They are generated design concepts with original fictional art, not runtime screenshots or approved implementations of four new pages. Minor generated icon/logo/focus variation is not a token override. Current Albums/Artists/Playlists remain existing demo anchors pending owner choice.

## AuraSport

Favorite teams and pinned fixture widget IDs persist per active profile. Settings lists teams from the available demo fixtures and individual widget choices. For deg filters by favorites; full league views remain independent. OK on a fixture toggles its widget, Mine widgeter shows chosen matches and removal restores valid menu focus. Selected widgets also appear below Home hero through the existing status slot, with explicit DEMO/fictional labels and child/profile/module boundaries preserved. No overlay over Netflix/other apps, real live score/rights/provider or background-match alert is added.

## Age/content banner

The old one-time banner could silently consume its opportunity when metadata supplied no text. It now always shows once playback actually starts, at a stable upper-left location above controls for8 seconds. Missing metadata shows honest Age rating unavailable / Detailed content advisory unavailable. Late metadata can update the visible text without position jumps. Actual certification retains source attribution. Violence/nudity/substance advice is displayed only from genuinely supplied VerifiedContentAdvisory; existing TMDB title data does NOT provide those structured categories. No inferred or fabricated warnings.

## Verification and owner checks

Working-tree JDK21 offline test/perf build passed:468 tests, zero failures/errors/skips. Tests cover shared underline state policy, favorite/widget filtering/stale IDs, profile-model behavior and unknown/attributed rating policy. Parent source review checked query/queue transitions, widget removal focus, opt-in Home injection and banner draw order. Exact committed build evidence is recorded in Draft199.

TV acceptance remains: text/rail focus distinguishability, episode underline/card focus, Search IME and empty/queue interactions, Home hero→widgets→categories focus, settings/profile persistence and spoiler mode, widget pin/unpin, and visible startup advisory with real/unknown metadata. Four proposal pages require owner design choice before their future implementation. No install/merge/release; F6f installed baseline unchanged.

Final navigation review applies the same underline-only treatment to Settings categories. Search results use add-to-queue (not the queue-removal glyph/action); selecting a track tab clears Search, and an active query retains its underline. Full JVM tests and both debug/perf compilation passed after these final corrections;468 tests remain green. Exact artifact evidence is in Draft199.
