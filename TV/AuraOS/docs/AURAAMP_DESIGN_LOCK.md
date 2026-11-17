# AuraAmp approved design lock — v5, 12 November 2026

## Current override — line navigation and sport widgets, 13 November 2026

Menu/season/episode text uses logo-orange underline; rail icons become orange without pills. Amp shortcut removed; Albums/Artists/Playlists followed by line and Search field. Four module mockups are proposals, not implemented new pages. AuraSport adds profile favorites and selected demo widgets on Home/Sports. Startup age/content banner remains visible with honest unknown states; detailed categories require verified source data. See [current contract](V2_F6H_AMP_LINES_SPORT_WIDGETS.md). No install/merge/release; TV acceptance open.

## Mobile visual identity override — 13 November 2026

Owner approves mobile palette throughout AuraOS: background#0C0F14/#0E121A, surface#141923, signature#FF8A00–#FFA133. AuraAmp TV player adopts mobile cover/title/waveform, round gradient Play/Pause and native icon hierarchy within existing TV layout. Download/lyrics remain visibly disabled in the no-audio demo. See [current visual contract](V2_F6H_MOBILE_VISUAL_IDENTITY.md). Supersedes previous darker palette/player styling; no install/merge/release, TV acceptance open.

## Current override — Cinema mode and warm focus, 13 November 2026

Owner selected concept07 for TV series and reinstated softened logo-orange active/focus. Primary TV details now use selected-episode cinema background, one shared action lane and large stable chapter strip; films keep their composition. Focus/Progress=BrandOrange #FF8A00; NavPill14%, thin diffuse focus. This supersedes blue-only interaction and80/20 episode-button layout for the primary TV page. See [current contract](V2_F6H_CINEMA_SERIES.md). No install/merge/release; TV acceptance remains open.

## Current owner override — blue navigation, 13 November 2026

This supersedes earlier orange/yellow interaction and boxless line rules: branding-only #FF8A00, universal #79D5FF focus, selected 18% blue NavPill, 10dp navigation corners, text-only main destinations, pinned chrome transitioning from 80% hero opacity to opaque #0A111A on actual outer scroll. See [current contract](V2_F6H_BLUE_NAVIGATION.md). TV acceptance remains open; no merge/release/install.

The owner-approved combined TV concept v5 supersedes v4 for the next native interactive demo. This is design approval, **not TV/device acceptance** and not approval of a live music service.

Reference: `output/auraamp/mockups/auraamp-combined-tv-concept-v5.png`, 1672 × 941 pixels. Bundled photo atlas: `tv/app/src/main/assets/auraamp/approved_design_v5.png`.
SHA-256: `e361f64589faa1eb61e6717a9bda320979aafada4c9d76bb242261d306c42cf6`.

The shared AuraOS navigation remains parent-owned. Native music content begins at x=137 dp, below 84 dp parent chrome, with 28 dp right/bottom margins and 28 dp column spacing. No separate music sidebar or bottom player is introduced. The 480 dp right panel is fixed within the available height; the track list scrolls within the remaining left-column height at the 1920 × 1080 design canvas.

## Native composition

Horizontal module navigation contains only a native waveform Amp icon, Albums, Artists and Playlists. Amp and Albums focus the single wide AFTERGLOW hero; Artists focuses the compact artist strip; Playlists opens the Queue track tab. The hero has native featured-album text, Jonas Vale label and Play album action over photo art. Four landscape release cards follow: Afterglow, Velvet Horizon, Golden Tides and Nightfall Waltz. Five compact circular artist portraits form one horizontal strip. Native Recently played and Queue tabs sit directly above the bounded track list; Queue is not duplicated as another track tab.

The right Now Playing panel has square cover artwork, title/artist, native simulated waveform and elapsed/duration labels, shuffle/restart/play-pause/next/repeat actions, and a bottom favourite/Discover/queue pill. Discover returns focus to the hero; queue opens the lower Queue tab. All orange controls use `AuraColors.BrandOrange`. Demo/no-audio text stays visible. External metadata, if supplied, is labeled metadata only and never becomes an audio source.

`AmpDemoArtwork` decodes the atlas off the main thread, caches photo-only crops, and recycles the original decoded bitmap. No full screenshot, screenshot labels or screenshot controls are rendered. Source rectangles `(x,y,width,height)`:

- Hero portrait: `(560,133,580,220)`.
- Bloom cover: `(1240,156,344,294)`.
- Landscape release art: `(121,409,248,78)`, `(393,409,236,78)`, `(656,409,230,78)`, `(912,409,229,78)`; original card text is excluded.
- Northern / Quiet / Golden track thumbnails: `(147,772,60,35)`, `(147,822,60,35)`, `(147,872,60,35)`.
- Blue / Night use the second / fourth landscape photo crops.
- Artist portraits: `(127,590,68,68)`, `(339,590,68,68)`, `(550,590,68,68)`, `(761,590,68,68)`, `(976,590,68,68)`.

## Focus, state and review limits

Explicit focus routes connect the module menu downward to the hero, releases, artists, track tabs and first track. Hero Right reaches the right-panel Play action; the last release/artist and Queue tab also lead to the player. Transport controls traverse shuffle, previous, Play, next and repeat; Left from shuffle returns to the hero. The focusable waveform supports simulated ten-second seeking; Up/Down leaves it. Track favourite/queue controls remain reachable, and explicit queue removal restores valid focus. The player controls remain separate native focus targets. Module actions and the queue pill transfer focus to the destination. No local Back interception is added, so the parent retains OS navigation behavior.

`AuraAmpDemoPage` and existing `AuraAmpDemoSession` fields remain source-compatible. Session adds `module: AmpDemoModule` and `trackTab: AmpDemoTrackTab`. Existing `tab`, `query`, and `album` fields are retained for prior callers, though v5 no longer shows the old seven-tab module menu or carousel. Parent remembrance per profile remains the persistence boundary; no disk persistence is claimed.

Pure state adds `trackSection` (four recent fixtures or valid deduplicated queue entries) and `playQueued` (select a valid queued track and consume it). Existing favourites, following, queue deduplication, repeat, deterministic shuffle and simulated timing remain. Release and artist names are fictional demo fixtures; selecting a release selects a fixture track and does not claim a real album catalog. No dependencies, providers, audio source or playback permissions were added.

JVM test sources cover the two track tabs, exact four module choices, queue filtering/order and selection/consumption, alongside existing state coverage. Parent verification is recorded in PR #199. Physical TV focus traversal and visual acceptance remain open.


## Latest correction — Eclipse protocol and approved Amp design

See [protocol/design correction](V2_F6H_AMP_PROTOCOL_DESIGN_FIX.md). Owner manifest-verification success does not establish audio success. Correct documented addon-local identity/direct streamURL/ISRC/HLS handling and safe stage diagnostics; restore four overlaid release cards, five artists, uncluttered hero and native mobile-style player controls. TV and actual authenticated playback acceptance remain open. No merge/release/install; F6f preserved.
