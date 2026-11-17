# AuraOS v2 component inventory

Status: ✅ implemented in Demo Task 01 · ⏳ later phase · ❌ out of scope for the demo. "Implemented" means written and compile-checked in the cloud harness only; nothing here has been built with AGP or run on a device.

| Component | Package | Phase | Status | Notes |
|---|---|---|---|---|
| AuraColors / AuraTypography / AuraDimensions / AuraTheme | theme | A | ✅ | tokens from V2_DESIGN_SYSTEM |
| AuraShell (nav + rail + content + overlays) | shell | A | ✅ | ordered layers, back/focus policy |
| AuraTopNavigation | shell | A | ✅ | soft blue active pill, feature-flagged modules |
| AuraSideRail | shell | A | ✅ | Home / Saved / Library (Saved & Library are demo placeholders) |
| NavModel + ModuleFlags | shell | A | ✅ | pure, unit-tested |
| CinematicHero | hero | A | ✅ | scrolls with page; auto-rotation logic unit-tested |
| MediaCard (standard) | cards | A | ✅ | 16:9, 12 dp |
| MediaCard (continue watching) | cards | A | ✅ | progress bar + time left (demo values) |
| MediaShelf | shelves | A | ✅ | lazy row, stable keys, restores position |
| CompactPlayer + MiniPlayerState | overlay | A | ✅ | simulated demo playback, dismissible |
| Demo repository + procedural art | demo / data | A | ✅ | Demo Mode only |
| Service card (brand tint, official logos) | cards | B | ⏳ | needs permitted logo assets |
| Music card | cards | B | ⏳ | |
| Sports score card | cards | B | ⏳ | demo fixtures only; licensed data later |
| Live TV channel / program card, EPG grid | cards | B | ⏳ | |
| ChannelCard, SportsScoreCard | cards | B | ⏳ | |
| Full AuraAmp player, queue | overlay | C | ⏳ | reuse legacy scrubber behaviour as reference |
| AuraBox video overlay | overlay | C | ⏳ | no UltraMax/DRM logic in demo |
| SettingsPanel | settings | C | ⏳ | mockup missing |
| PlayerOverlayHost (dialogs) | overlay | C | ⏳ | |
| Movies / TV Shows / AuraBox / AuraAmp / Sports / Live TV / Services pages | — | B | ❌ | placeholder page only in Demo Task 01 |
| AuraBrandLabel / BrandLabels | brand/ | Design lock | ✅ code, ❌ device | Shared orange AURA label used by every hero |
| AuraWordmark | brand/ | Design lock | ✅ code, ❌ device | Approved bitmap with text fallback |
