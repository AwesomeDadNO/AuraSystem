# AuraOS v2 — design contract

## Current override — line navigation and sport widgets, 13 November 2026

Menu/season/episode text uses logo-orange underline; rail icons become orange without pills. Amp shortcut removed; Albums/Artists/Playlists followed by line and Search field. Four module mockups are proposals, not implemented new pages. AuraSport adds profile favorites and selected demo widgets on Home/Sports. Startup age/content banner remains visible with honest unknown states; detailed categories require verified source data. See [current contract](docs/V2_F6H_AMP_LINES_SPORT_WIDGETS.md). No install/merge/release; TV acceptance open.

## Mobile visual identity override — 13 November 2026

Owner approves mobile palette throughout AuraOS: background#0C0F14/#0E121A, surface#141923, signature#FF8A00–#FFA133. AuraAmp TV player adopts mobile cover/title/waveform, round gradient Play/Pause and native icon hierarchy within existing TV layout. Download/lyrics remain visibly disabled in the no-audio demo. See [current visual contract](docs/V2_F6H_MOBILE_VISUAL_IDENTITY.md). Supersedes previous darker palette/player styling; no install/merge/release, TV acceptance open.

## Current correction — 13 November 2026

White text/icons on warm active/focus states; background#05080D. Cast/related now live below episodes in the same Cinema page; obsolete TV page and popup link are removed. Profile-local bulk notifications, split movie/series saved shelves plus Continue Watching, system row artwork/TV intents, Media3 track discovery and landscape preparing image are corrected. Audio output and cross-app provider availability still require device/source verification. See [correction contract](docs/V2_F6H_CINEMA_CORRECTIONS.md). No install/merge/release.

## Current override — Cinema mode and warm focus, 13 November 2026

Owner selected concept07 for TV series and reinstated softened logo-orange active/focus. Primary TV details now use selected-episode cinema background, one shared action lane and large stable chapter strip; films keep their composition. Focus/Progress=BrandOrange #FF8A00; NavPill14%, thin diffuse focus. This supersedes blue-only interaction and80/20 episode-button layout for the primary TV page. See [current contract](docs/V2_F6H_CINEMA_SERIES.md). No install/merge/release; TV acceptance remains open.

## Current owner override — blue navigation, 13 November 2026

This supersedes earlier orange/yellow interaction and boxless line rules: branding-only #FF8A00, universal #79D5FF focus, selected 18% blue NavPill, 10dp navigation corners, text-only main destinations, pinned chrome transitioning from 80% hero opacity to opaque #0A111A on actual outer scroll. See [current contract](docs/V2_F6H_BLUE_NAVIGATION.md). TV acceptance remains open; no merge/release/install.

## AuraSport demo update — 13 November 2026

The owner-approved news hero / league match grid is now a native fictional demo, with dedicated per-profile AuraSport settings (independent news/fixture leagues and spoiler mode). Main menu text is 25 sp; line menus are boxless. Episode controls supersede 90/10 with transparent outlined 80/20 and semantic checkmark. See [scope and TV acceptance](docs/V2_F6H_AURASPORT_DEMO.md). Real sports/news providers, broadcast mapping and reminders remain unconnected; do not mark live integration complete. No install/merge/release.

## Owner update — AuraAmp v5 and line navigation (12 November 2026)

This update supersedes older v4/carousel and filled navigation rules. AuraAmp uses the approved v5 hero and right player, four landscape releases, compact artist row, and Recently played / Queue line tabs. Module navigation: Amp waveform icon, Albums, Artists, Playlists. It remains a fictional, profile-local no-audio demo; no bottom mini-player.

Shared top navigation uses logo orange (#FF7A00) text/icons and an underline for selected/focused items, with no pill/background. The left rail has no boxes or underline: only icon colour changes. Focus retains stable target sizes. Track actions expose favourite and queue controls in sequence; player transport and footer have explicit left/right routes. The waveform supports simulated ten-second seeking with Left/Right and exits with Up/Down. Queue removal restores a valid focus target.

Acceptance on the owner's TV remains open: traverse shell → module → hero → releases → artists → track tabs/list → player and back; verify favourite/queue access, empty/removed queue, seek, profile changes, clipping and legibility. No performance or physical remote claim follows from JVM/build checks. F6f remains the installed baseline; no install, merge or release is authorized by this update.


**Status:** ACTIVE for branch `AuraOSv2`. The single source of truth for the v2 visual system is [`docs/V2_DESIGN_SYSTEM.md`](docs/V2_DESIGN_SYSTEM.md); this file is the short contract.
The previous orange-pill / glassmorphism specification is retained only as a **legacy reference**: [`docs/legacy/DESIGN_CONTRACT_V0_1_ORANGE.md`](docs/legacy/DESIGN_CONTRACT_V0_1_ORANGE.md). Do not use it for v2 and do not mix the two systems.

## Direction
Cinematic, lightweight, premium streaming interface. Dark navy/anthracite, blue accents, restrained surfaces, no advertising or clutter, easy with a Google TV remote, tuned for real Android TV hardware.

## Invariants
1. **One shell on every page:** `AURA | Home | Movies | TV Shows | Sports | Live TV | AuraBox | AuraAmp | Services | All Apps | Search | Settings | Profile`, plus a narrow left rail. Active page = soft blue pill. No orange navigation gradient; orange is branding only, never an active colour.
2. **One `CinematicHero`**, full-bleed, part of the vertical scrolling content (never floating or pinned), left-to-right readability gradient, no outline.
3. **One card family** derived from the same tokens: 16:9, 12 dp radius, thin subdued border, subtle blue focus + gentle scale, no strong glow.
4. **Minimal glass:** thin low-contrast borders, optional top-edge highlight, no backdrop blur over large regions, no stacked heavy shadows.
5. **Tokens only:** colours, typography, spacing (8 dp grid) and radii come from the central token objects. Same radius for the same component type everywhere.
6. **Compact AuraAmp player is an overlay** (bottom-right), never a column that resizes content.
7. **DPAD first:** predictable focus between rail, top navigation and content; shelves remember position; Back restores meaningful focus; dialogs trap focus.
8. **Honesty:** demo content is labelled *Demo Mode*; no fake connected status, availability, scores or playback; third-party logos only when permitted (documented fallback otherwise).
9. **Performance is measured, not assumed.** No 60 FPS claim without device evidence.

## Acceptance
See [`docs/V2_DEMO_ACCEPTANCE.md`](docs/V2_DEMO_ACCEPTANCE.md). Mockups are design references, not runtime screenshots and not authorisation to package protected artwork. Reference index: [`references/README.md`](references/README.md).

## Navigation update: Services (official)
"Subscriptions" is renamed **Services**. Approved menu: `Home | Movies | TV Shows | Sports | Live TV | AuraBox | AuraAmp | Services`. Services is the central overview of external streaming apps (Netflix, Prime Video, Disney+, Max) and connected media sources (Plex, Jellyfin, Emby, UltraMax) with installed / connected / disconnected / unavailable states. Installed apps and connected sources are shown separately. It is not an AURA subscription store or payment system. The name "Subscriptions" is not used in new v2 UI work. The demo page uses local illustrative data only.

## Design lock and branding (official)
The v2 visual system is **locked** (navigation, hero, shelf geometry, cards, overlays, page compositions). The demo at `889408d` is the visual regression baseline. Only the branding below was added.

- **Wordmark:** the top navigation shows the approved AURAOS wordmark (white `AURA`, orange `OS.`, transparent). Asset: `tv/app/src/main/res/drawable-nodpi/auraos_wordmark.png` (cropped from `references/brand/auraos-wordmark-source.png`). `AuraWordmark` falls back to equivalent text only when the resource is missing. The TV banner is `drawable-nodpi/banner.png` (approved 16:9 artwork on the navy background).
- **Brand label:** one shared component `brand/AuraBrandLabel` (narrow orange bar, white primary text, orange accent text, bold sans). Texts live in `brand/BrandLabel.kt`: Home/Movies/TV Shows `AURA RECOMMENDED`, Sports `AURA SPORTS CENTER`, Live TV `LIVE ON AURA`, AuraBox `AURA BOX`, AuraAmp `AURA AMP`. The old blue underlined label is retired.
- **Orange token:** `BrandOrange` `#FF7A00`. **Orange is branding, blue is interaction:** nav pills, focus outlines, playback/continue progress and selected EPG items stay blue.

## Settings and label update (F2)
Branded section labels have **no underline or second decorative line**; they use `AuraBrandLabel` only. Settings is structured as General / Modules / Connections / Users / Status / About; unimplemented items are visibly disabled ("Coming later") and never simulate working behaviour. See `docs/V2_F2_SETTINGS.md`.


## AuraAmp owner design lock — 2026-11-12

The owner approved the horizontal-navigation v4 AuraAmp mockup for the next native demo. [AuraAmp design lock](docs/AURAAMP_DESIGN_LOCK.md) is authoritative for the module: shared OS chrome, horizontal music navigation, layered album banner, circular artists, track rows and right Now Playing panel; no module sidebar or bottom mini-player. The native demo uses clearly fictional content and does not establish audio/provider integration. Later changes to this module design require owner direction. F6f baseline history and unrelated module contracts remain preserved.


## Latest owner revision — 2026-11-13 hero/settings/sync

See [hero, Settings and Simkl contract](docs/V2_F6H_HERO_SETTINGS_SYNC.md). Bold white shadowed fixed-slot wrapping hero titles; Settings search/device settings/redesign; saved-page focus/duplicate-key corrections; first-grant automatic and approximately hourly read-only Simkl Plan-to-Watch import with toggle/status. Device crash resolution and provider/background timing acceptance remain open. F6f retained; no merge/release/install.


## Latest owner revision — operational Amp catalog/provider

See [AuraAmp real catalog and Eclipse playback](docs/V2_F6H_AMP_OPERATIONAL.md). Adult Real Mode now uses actual Deezer metadata and native Eclipse/Media3 playback plumbing; Demo remains isolated. Five editorial slides, real local history/queue, Artists/Albums/Playlists/Popular/Trending, date sorts and public playlist import. Base #0E121A, glossy player and exact-route active chrome. Credential format is fixed host + encrypted per-profile ARL + manifest path; no secret ships. Actual authenticated Eclipse/TV acceptance is open. Automatic cross-device/private-account playlist sync and future Deezer SSO are not supplied. No merge/release/install; F6f unchanged.


## Latest correction — Eclipse protocol and approved Amp design

See [protocol/design correction](docs/V2_F6H_AMP_PROTOCOL_DESIGN_FIX.md). Owner manifest-verification success does not establish audio success. Correct documented addon-local identity/direct streamURL/ISRC/HLS handling and safe stage diagnostics; restore four overlaid release cards, five artists, uncluttered hero and native mobile-style player controls. TV and actual authenticated playback acceptance remain open. No merge/release/install; F6f preserved.
