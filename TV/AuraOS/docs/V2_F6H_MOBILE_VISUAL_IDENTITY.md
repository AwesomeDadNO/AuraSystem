# Mobile/TV visual identity — 13 November 2026

Owner specifies the attached mobile app/player as the reference for the whole AuraOS foundation palette and AuraAmp TV player. This supersedes the previous#05080D background and older v5 player transport styling, while retaining the integrated TV page/layout.

## Foundation tokens

Background#0C0F14, alternate background#0E121A, surfaces/cards#141923. Derived elevated surface#1B2230, subdued border#303744, white primary and neutral secondary/muted typography. Signature gradient#FFA133→#FF8A00. Existing warm selected/focus glass and white navigation labels/icons remain. The shared Panel now derives from Surface, removing its previous teal. Material theme primary/secondary and the legacy AccentBlue caller alias also use the warm mobile accent; Android startup/system bar backgrounds use the same main colour. Video canvas remains black while playing; artwork colours are preserved.

## AuraAmp TV player

Existing right-side player now follows mobile hierarchy: Back / centered Now Playing / More, square rounded cover, centered two-line title/artist, waveform with elapsed/duration, large96dp circular gradient orange Play/Pause between white previous/next icons, and bottom favourite/download/shuffle/repeat/queue icons. Native material icons replace glyph controls.56dp targets remain stable; explicit routes connect transport, seek, footer, options and main content. The options modal retains Discover navigation and restores options focus on close. No new music sidebar or bottom mini-player.

Download and lyrics have disabled semantics and no focus/click behavior because the demo has no actual audio/source. They do not claim downloaded music, lyrics or source integration. Favourite/queue/shuffle/repeat, profile-local session and simulated seeking/timing remain functional demo state. This is the MUSIC/AuraAmp player, not a redesign of movie/video controls.

## Verification/acceptance

Tests check exact approved palette/contrast alongside existing state/profile regressions. Parent source review checks native control hierarchy, disabled demo boundary and explicit DPAD routes; physical interaction/layout still requires TV acceptance. Full JVM/perf and exact committed results are recorded in Draft199. Owner TV checks remain: right-panel cover/text sizing, long titles, elapsed/waveform legibility, every remote control/option route, queue changes and disabled download/lyrics, profile switch and palette match across pages.

No actual music provider/audio, cast/download permission, device operation, merge or release added. Installed F6f baseline remains unchanged.

Working-tree verification passed:JDK21 offline JVM/perf build,463 tests with zero failures/errors/skips. Parent source review retained stable header/seek/transport/footer routes, options-modal restoration, disabled unavailable features and a single diffuse play-button glow. Exact committed build evidence is recorded in Draft PR199. TV visual/remote acceptance remains open.
