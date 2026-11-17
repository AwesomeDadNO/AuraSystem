# Current navigation/colour contract — 13 November 2026

## Current override — Cinema mode and warm focus, 13 November 2026

Owner selected concept07 for TV series and reinstated softened logo-orange active/focus. Primary TV details now use selected-episode cinema background, one shared action lane and large stable chapter strip; films keep their composition. Focus/Progress=BrandOrange #FF8A00; NavPill14%, thin diffuse focus. This supersedes blue-only interaction and80/20 episode-button layout for the primary TV page. See [current contract](V2_F6H_CINEMA_SERIES.md). No install/merge/release; TV acceptance remains open.

This owner-directed update supersedes prior orange line navigation, boxless active states and yellow hero focus across AuraOS, including AuraAmp and AuraSport.

- BrandOrange is #FF8A00, branding only (wordmark/section branding). Existing approved raster logos retain their artwork/defined gradients; fallback text and native labels use the new token. Brand artwork from external apps is not recoloured.
- FocusBlue #79D5FF is the universal DPAD stroke. Focus and playback/progress accents are blue, including secondary hero/episode controls, loading indicator, checkmarks, notification actions, Sports, Amp and legacy player paths. No orange button fills or active/focus overrides.
- Selected navigation has NavPill = FocusBlue at 18% opacity. Focus has a sharp 2dp blue border, independent of selection. Navigation targets have 10dp corners, no pill shapes or geometry jumps. Buttons use 10dp token.
- Main destinations are text only for every saved legacy style. Utility actions and external app shortcuts remain recognizable icons. The Settings row reports Text only; old preference values are preserved but cannot reintroduce destination icons.
- The header remains pinned. At the top of a hero page its #0A111A background is 80% opaque with a subtle shadow; any outer vertical scroll transitions it to 100% opaque over 220ms. Returning to top restores 80%. Reduce Motion changes opacity immediately.
- Home/Movies/TV, real catalog shelves and Live TV report real outer list scroll. Horizontal shelves do not alter chrome. Each route gets its own scroll state; details and categories isolate their own dialog scroll. Detail artwork starts behind the pinned header while its text remains safely below it.
- No blur dependency, permissions, network provider, device action or release is added.

## Verification and owner TV acceptance

JVM checks verify exact tokens, independent selected/focus decoration, text-only destinations for all legacy preferences, width budgets with five shortcuts, route/dialog scroll wiring and top/scrolled opacity. A source guard rejects interactive orange/yellow overrides outside explicit branding. Build evidence is recorded in Draft PR199.

On TV verify hero → shelf → top opacity in both directions, restored scroll on route return, details/category close without changing underlying chrome, motion preference, menu readability, stable focus, primary/secondary/player contrast and external shortcut identity. No 60fps or hardware acceptance is claimed. F6f remains the installed baseline; no install/merge/release.

Parent source review verified colour sweep, route/dialog isolation, restored scroll reporting, Reduce Motion, text-only compatibility for saved preferences and bounds-safe menu width. 443 JVM tests passed with zero failures/errors/skips in the working-tree check. Exact committed build evidence is recorded in Draft PR199. No physical-TV acceptance is implied.
