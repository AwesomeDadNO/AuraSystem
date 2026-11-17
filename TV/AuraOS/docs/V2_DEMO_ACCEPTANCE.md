# AuraOS v2 — Demo Task 01 acceptance (shell + Home)

Primary reference: `references/v2/v2-08-home-silver-realm.png`; tokens: `v2-01-design-system.png`.

## Scope
Bootable Android TV Compose app · v2 theme · navigation shell + left rail · scrolling `CinematicHero` · Continue Watching / Aura Latest / Trending on Aura · consistent 16:9 cards · compact collapsible mini-player demo · DPAD · clear Demo Mode label · no live integrations. Not in scope: Movies, TV Shows, AuraAmp, AuraBox, Sports, Live TV, IPTV, AuraConnect (navigation shows a labelled placeholder page).

## Gates (status is updated in the PR; "pending" = not run)
| # | Gate | Status at delivery |
|---|---|---|
| 1 | Compilation (Gradle/AGP) | **pending** — cloud has no Android SDK; Kotlin sources compile-checked in a cloud harness against pinned Compose jars |
| 2 | Unit tests | JVM tests run in the cloud harness (pure logic only) |
| 3 | 1920×1080 screenshot comparison | **pending** — no emulator/device in the cloud |
| 4 | 720p scaling check | **pending** |
| 5 | DPAD walkthrough | **pending** (implemented; not exercised) |
| 6 | Focus restoration | **pending** |
| 7 | Overlay collision check | **pending** |
| 8 | Scroll behaviour (hero leaves with content) | **pending** (implemented; not exercised) |
| 9 | Basic performance inspection | **pending** — no measurements, no FPS claims |
| 10 | Product-owner visual approval | **pending** |

## Manual DPAD checklist (run on emulator/TV)
1. Launch: focus lands on the hero **Play** button. 2. Up → top navigation; Left/Right moves between items; OK on Movies shows the labelled placeholder; Back returns Home with focus on the Home item. 3. Down from hero → Continue Watching; Left/Right scroll; leaving and returning to a shelf restores the last card. 4. Hero leaves the screen as you move down and returns when you go back up. 5. Mini-player: reachable by DPAD, expand/collapse, dismiss; Back collapses first. 6. Left from any content row reaches the rail; rail never overlaps content. 7. Back on Home with nothing to dismiss exits the app.

## Visual deviation checklist (initial, to be completed with screenshots)
Artwork is procedural placeholder (no film art) · hero serif is the system serif · card titles inside demo art are drawn approximations · icon set is Material Icons (extended) not the mockup icons.

## Visual Implementation Directive — Stage 1 (shell + Home) status
- Implemented from measured mockup geometry: DesignCanvas, nav, rail, hero, Continue/compact cards, bounded 261x186 compact player.
- **Not yet verified at runtime** (no emulator available to the author): every item in the deviation checklist remains open until Gemini/user supplies 1920x1080 screenshots for side-by-side comparison with `references/v2/v2-08-home-silver-realm.png`.
- Known deviations (by construction): colour-field placeholder art instead of the real illustrations; no baked-in titles on cards (titles are real text, letter-spacing approximated); system serif instead of the mockup serif; hero "THE" prefix indent and title offset are estimates; 5 hero entries reuse hero colour fields from other mockups.
- Stages 2-6 (Movies, TV Shows, AuraBox, AuraAmp, Sports, Live TV, consistency pass) are **not started**: each stage needs the previous stage's runtime screenshot review first.

## Complete visual demo (Stage 1 corrections + Stages 2–6) — status
- **Checkpoint A:** AURA wordmark fix (cause: side rail was composed after, i.e. above, the top navigation and its 92 % dark panel covered the wordmark; rail is now composed first and the navigation above it). Deterministic focus: explicit up/down chain per page (hero actions → row 0 → … → last row, `Cancel` below the last row), DPAD_UP reverses, the left rail is entered only by DPAD_LEFT from the first item of a row/hero (`leftToRail`), top navigation DPAD_DOWN → the page's primary control, shelves restore their last focused card (`focusRestorer`).
- **Checkpoint B–E:** Movies, TV Shows, AuraBox, AuraAmp (tabs + simulated player), Sports (Match Hub dialogs), Live TV (channels, EPG on a fixed demo timeline, filter, program dialogs), plus secondary demo views for Services, All Apps, Search, Settings, Profile, Saved, Library.
- **Verified here:** Kotlin compile check (cloud harness) and 51 JVM tests (fixtures match mockup text/order, assets exist, EPG timeline valid, focus chain, layout fit). **Not verified:** Gradle build of this commit, emulator/device run, DPAD behaviour on a device, screenshots.
- **Known deviations:** colour-field placeholder artwork everywhere; team crests are neutral monogram rings; service logos are plain text (no brand marks); card gaps/heights approximated per page; AuraAmp non-"For You" tabs and all secondary views are demonstration views; EPG/sports/live data is illustrative.
