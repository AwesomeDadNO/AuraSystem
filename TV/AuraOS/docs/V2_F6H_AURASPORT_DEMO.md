# AuraSport native demo — 13 November 2026

Owner approved the Sports Center mockup and requested implementation, larger main menu text, outlined episode controls and boxless line menus. This supersedes the old Sports demo composition and episode 90/10 style.

## Implemented scope

Sports shares the existing OS header/rail. News hero uses existing stadium art and illustrative headlines for independently selected PL/Eliteserien/UCL sources, rotating every 12 seconds when multiple are enabled. News off shows a neutral guide heading. Text explicitly states no connected article source. No full articles are offered.

For deg / Live / Kommende / Resultater and league lines filter stable fixture IDs in a bounded three-column grid. All selected-league matches are included, irrespective of favourite teams. Six fictional fixtures per league demonstrate live/upcoming/final states. No network polling, verified rights, deep links, real results or clock freshness is claimed. Cards open an honest demo explanation rather than launching an arbitrary provider.

Settings has a dedicated AuraSport category, reached by Sports → Tilpass. Fixture leagues, separate news leagues and spoiler mode persist locally per active profile. Empty selections are valid; preference decoding restores a deterministic league order. Country is Norway for this demo; live sources, country selection, team following and reminders remain future scope. No new permissions or dependencies.

Main navigation font increases 22 → 25 sp; outer item margins reduce 5 → 2 dp to preserve the measured 1920-wide budget, including five shortcuts and icon styles. Font/remote legibility still requires TV acceptance.

Episode More info / watched split is now 80/20. Both use transparent outlined surfaces and yellow focus matching secondary hero controls. The watched control retains checkbox semantics and always shows a checkmark, muted when off and orange when watched. Details tabs, seasons and Amp module/track lines now use underline/colour only, no focus boxes.

## Acceptance still open

- TV: news framing, main-menu width in each style, three-column legibility, DPAD/Back and scroll behavior.
- Settings: independently toggle news/leagues, empty selections, spoiler mode, reopen/restart and profile isolation.
- Episodes: exact 80/20, yellow focus, checkmark state/persistence, play/info/back navigation.
- Provider API, source licensing, live delays and accurate broadcast mapping must be validated before replacing fixtures. No live-feed promise.

F6f remains the installed baseline. This work performs no installation, device operation, merge or release.

## Source/build verification

Parent source review checked empty league/news selections, profile wiring, no arbitrary provider launch, focus entry targets and deterministic preferences. JVM coverage includes fixture/view filters, news independence, spoiler suppression, settings wiring, exact 80/20 geometry and all navigation width combinations. Working-tree JDK21 offline test/perf build passed: 442 tests, zero failures/errors/skips. Exact committed build evidence is recorded in Draft PR #199. Physical TV acceptance remains open.
