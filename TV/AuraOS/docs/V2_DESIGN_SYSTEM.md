# AuraOS v2 design system (source of truth)

## Fixed ecosystem colour lock — 2026-11-14

This section supersedes older colour choices below until the owner changes them.
Background: **#1A1D24**. Orange button gradient: **#FFB856 → #E75200**.
Inactive/neutral functional button: **#2B2E37**. Its icon: **#A9A7B2**.
Applies to language/audio/subtitles, YouTube video, Lyrics, hero Play, More info, Watchlist and equivalent functional actions. Logo/focus orange remains **#FF8A00**; navigation underlines are not button gradients.
Portable source: `packages/aura-design-tokens/colors.json` (repository root).
TV token mirrors and startup/window/system bars use the locked background. Concurrent mobile/web consumers must adopt the same contract; their migration is not claimed by the TV change.


## Current override — line navigation and sport widgets, 13 November 2026

Menu/season/episode text uses logo-orange underline; rail icons become orange without pills. Amp shortcut removed; Albums/Artists/Playlists followed by line and Search field. Four module mockups are proposals, not implemented new pages. AuraSport adds profile favorites and selected demo widgets on Home/Sports. Startup age/content banner remains visible with honest unknown states; detailed categories require verified source data. See [current contract](V2_F6H_AMP_LINES_SPORT_WIDGETS.md). No install/merge/release; TV acceptance open.

## Mobile visual identity override — 13 November 2026

Owner approves mobile palette throughout AuraOS: background#0C0F14/#0E121A, surface#141923, signature#FF8A00–#FFA133. AuraAmp TV player adopts mobile cover/title/waveform, round gradient Play/Pause and native icon hierarchy within existing TV layout. Download/lyrics remain visibly disabled in the no-audio demo. See [current visual contract](V2_F6H_MOBILE_VISUAL_IDENTITY.md). Supersedes previous darker palette/player styling; no install/merge/release, TV acceptance open.

## Current correction — 13 November 2026

White text/icons on warm active/focus states; background#05080D. Cast/related now live below episodes in the same Cinema page; obsolete TV page and popup link are removed. Profile-local bulk notifications, split movie/series saved shelves plus Continue Watching, system row artwork/TV intents, Media3 track discovery and landscape preparing image are corrected. Audio output and cross-app provider availability still require device/source verification. See [correction contract](V2_F6H_CINEMA_CORRECTIONS.md). No install/merge/release.

## Current override — Cinema mode and warm focus, 13 November 2026

Owner selected concept07 for TV series and reinstated softened logo-orange active/focus. Primary TV details now use selected-episode cinema background, one shared action lane and large stable chapter strip; films keep their composition. Focus/Progress=BrandOrange #FF8A00; NavPill14%, thin diffuse focus. This supersedes blue-only interaction and80/20 episode-button layout for the primary TV page. See [current contract](V2_F6H_CINEMA_SERIES.md). No install/merge/release; TV acceptance remains open.

## Current owner override — blue navigation, 13 November 2026

This supersedes earlier orange/yellow interaction and boxless line rules: branding-only #FF8A00, universal #79D5FF focus, selected 18% blue NavPill, 10dp navigation corners, text-only main destinations, pinned chrome transitioning from 80% hero opacity to opaque #0A111A on actual outer scroll. See [current contract](V2_F6H_BLUE_NAVIGATION.md). TV acceptance remains open; no merge/release/install.

Reference: `references/v2/v2-01-design-system.png` (+ page mockups). Values below are implementation starting tokens; verify against the references. Code: `no.aura.os.v2.theme`.

## Colour tokens (`AuraColors`)
| Token | Value | Use |
|---|---|---|
| Background | `#0A111A` | main canvas |
| BackgroundDeep | `#060C13` | hero edges, scrims |
| Surface | `#121C27` | cards, panels |
| SurfaceElevated | `#1B2B3A` | focused / elevated surfaces |
| Border | `#2B3A4A` | outlines |
| TextPrimary | `#F5F7FA` | titles, main content |
| TextSecondary | `#A7B8C8` | metadata, subtitles |
| TextMuted | `#71869A` | disabled, low priority |
| AccentBlue | `#4FC3F7` | active highlights, section labels |
| ProgressBlue | `#38A8FF` | watch / playback progress |
| FocusBlue | `#79D5FF` | DPAD focus |
| AlertRed | `#FF6B6B` | live / error |
| Success | `#45C59A` | verified connected |
Derived (documented, not new hues): `NavPill` = FocusBlue @ 18 % over Background (soft blue selected rectangle, 10dp corners), `OnPrimaryAction` = `#0A111A`, primary action fill = TextPrimary @ 94 %.
No orange navigation gradient; orange is branding only (wordmark, AURA section labels), never an active colour in v2.

## Surfaces
Restrained: 1 dp `Border` strokes (≈60 % alpha on cards), optional 1 dp top-edge highlight, no backdrop blur, one shadow only on the focused element. Large gradients are limited to the hero readability scrim and the bottom fade.

## Typography (`AuraTypography`)
Hero title: system serif, Medium, 56 sp, tracking +2 % (cinematic; permitted equivalent of the mockup serif). Page title 32 sp · section title 22 sp SemiBold · card title 18 sp · body 17 sp · metadata 14 sp · small label 13 sp. Scales with density; layout uses fractions of the available size so 720p and 1080p keep proportions. Avoid uppercase UI labels (artwork titles drawn *inside* demo art are art, not UI).

## Spacing & radius (`AuraDimensions`)
Grid 8 dp: 4 · 8 · 12 · 16 · 24 · 32 · 40. Radii: 4 badges · 8 buttons · 12 cards · 16 large cards · 24 major containers only.

## Components
Top navigation, left rail, `CinematicHero`, `MediaCard` (16:9) and continue-watching variant, `MediaShelf`, `CompactPlayer`. Inventory and status: `V2_COMPONENT_INVENTORY.md`.

## Focus
Thin `FocusBlue` 2 dp border, scale ≈ 1.04 (150 ms), a single soft shadow; immediate (no blocking animation). Reduced motion respects the system animator scale.

## Layering
Base content → focus interactions → compact player → dialogs → system-style overlays (`AuraLayers`). Compatible with Google TV system notifications and Home panel.

## Imagery
Backdrops are cropped, never stretched; left-to-right readability gradient; graceful fallback when art is missing. **Demo Mode** uses procedurally drawn placeholder art (no packaged protected imagery, no network).

## Canvas and measured geometry (Visual Implementation Directive, Stage 1)
- **Design canvas = 1920x1080.** `DesignCanvas` sets `LocalDensity` so 1dp/1sp = one canvas pixel on any TV resolution (`DesignCanvasMath.density = widthPx/1920`). All values below are canvas units, measured on the 1672x941 mockups and scaled by 1.148.
- Top navigation: 80 high, 52 side margin, labels 20sp, pill padding 23x5 (38 high), radius 10, wordmark 34sp light; groups `Home..Live TV | AuraBox AuraAmp Services`, right actions All Apps / Search / Settings / Profile.
- Left rail: 94 wide, full height, icon-only (28 icons, 48 hit target, 70 pitch, first centre y=265).
- Hero: item height 525; label top 117 (24sp, 52x2 accent underline); "THE" prefix slot 34 (30sp, indent 50); title 86sp serif, remaining letters 0.78 small caps, tint #EAD7AC (sampled from mockups); description 21/29sp, 520 wide; metadata 16sp; buttons 49 high (More details 209, Play 170, gap 22); indicators 23x10 active / 9 dots, pitch 19; text start x=149.
- Shelves: start x=137; header 30 (24sp semibold); cards Continue 309x138, compact 238x106 (both ~2.24:1, **not** 16:9 - measured from the mockup), gap 12, radius 12, border 1dp Border@75%; rows end before the player column; header centres y=540 / 742 / 912.
- Compact player: 261x186 at bottom-right (margin 18 / 22), panel #0E1822, radius 16, 60 art, 44 white play circle, 4 progress.
- Title inside cards: letter-spaced serif caps 20sp, small "THE" prefix 11sp, optional two-line wrap.

## Branding (design lock)
- Token `BrandOrange` = `#FF8A00`, used only by `AuraWordmark` and `AuraBrandLabel`. Interaction colours remain `AccentBlue`/`FocusBlue`/`ProgressBlue`.
- `AuraBrandLabel`: bar 4x28, gap 12, height 34, 22sp ExtraBold, letter-spacing 0.06em; white primary + orange accent.
- `AuraWordmark`: bitmap 720x126, drawn 28 high (about 160 wide) in the top navigation, composed above the left rail. Navigation width at this size is unverified on a device.
