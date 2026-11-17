> **LEGACY REFERENCE (superseded).** This is the v0.1 orange-pill specification. It is NOT the v2 design. See `AuraOS/DESIGN_CONTRACT.md` and `AuraOS/docs/V2_DESIGN_SYSTEM.md`.

# AuraOS UI/UX — design contract v0.1

**Approved direction:** refined cinematic TV media hub, between Flat UIX and restrained glossy polish. Dark charcoal/navy (not neon/cyberpunk), white typography, warm AURA orange accent, no advertising or bloat.

## TV Home
1. AURA brand at top-left. **One continuous rounded dark pill** containing Home, AuraBox and AuraAmp, only showing optional module items when installed/enabled. A **small orange active pill slides** within the shared track. DPAD focus is distinct and instant, never a thick border or glow.
2. To the right: compact icons + text for installed favorite streaming services. End the media-app group with a clear **All Apps** grid icon. Search, settings and profile are accessible without crowding the image.
3. **AuraRecommended** cinematic wide hero. Six alternating movie/series recommendations when configured, with metadata, two primary controls, soft dark readability gradient and no deformed artwork. Prefer Box only if installed, enabled, configured and resolvable; otherwise use legitimate provider alternatives.
4. Precisely three content shelves on Home, in this order:
   - **Continue Watching** (actual Watch Next/AURA progress, orange progress indicators; no demos)
   - **Aura Latest** (recently added from genuinely connected sources)
   - **Trending on Aura** (properly labeled source/algorithm; do not imply AURA-wide aggregate if unavailable).
5. 16:9 image cards, no hard focus outlines, restrained corners and shadows. Home remains readable at ten-foot distance and 720p/1080p scaling.

## AuraBox screen
- Same overall shell, typography, spacing and focus grammar; stronger emphasis on movie/series backdrop, artwork, seasons, metadata, collection, Continue Watching, episodes.
- No fake streaming availability. Avoid autoplay, UI overfetch and infinite unrecycled shelves.
- Focus must remain in place on select/back; preserve DPAD pathways and overlay focus traps.

## AuraAmp screen
- Same shell + visual identity, but more music-oriented: current track, album art, playable library, playlists, queue, lyrics if supported.
- MediaSession/controller controls and progress update must not cause the entire launcher to recompose.
- Show one compact mini-player overlay in Launcher when Amp is playing; the full music UI remains in Amp.

## Motion
- Core DPAD response immediate; active pill may settle after focus without blocking focus navigation.
- Animation should be inexpensive on real TV devices, reduced when necessary.
- Image pipeline: right-sized bitmaps, bounded cache, background decoding and no main-thread network/disk work.
- Do not reproduce protected imagery as packaged app assets unless appropriately licensed.

## Acceptance
Nine reference mockups are expected from the product owner. They are **design references**, not code and not authorization to package copyrighted movie artwork. See `references/README.md`.
Claude should establish a screenshot comparison set against all references before implementation signoff. Neither image generation nor pixel matching is evidence of functionality.
