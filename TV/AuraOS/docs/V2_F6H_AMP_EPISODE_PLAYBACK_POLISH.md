# F6h AuraAmp lock, episode UI and playback presentation

Owner approved the v4 horizontal-navigation AuraAmp mockup and explicitly requested a native demo plus the listed corrections for the next private update. Source/build results are not device acceptance. Keep PR199 Draft/issue198 open and F6f baseline intact. No merge/release/install/ADB/device action.

## Locked AuraAmp demo

See [design lock](AURAAMP_DESIGN_LOCK.md), also referenced from the root design contract. Approved image is bundled at assets/auraamp/approved_design_v4.png with its hash/crop provenance recorded. The page draws native Compose layout/controls and reuses only photographic regions; it is not a screenshot masquerading as a UI.

The module is available in both data modes when enabled. Shared OS chrome stays outside the module. Horizontal icon/label menu uses Search, Discover, New releases, Following, Library, Favourites, Playlists; selected underline is orange. Layered300-high album banner,132-diameter artist circles, track rows,400-wide right player with356-square cover follow the approved proportions. Global bottom mini-player is excluded on AuraAmp.

Fictional fixture search/filter, favourite/follow/queue state, selection and simulated playback/seek/repeat/shuffle are interactive. Demo/no-audio labeling is always visible. State is hoisted per active profile and retained across route changes, not persisted as a real music account. No actual audio, music subscription/provider, output-device or lyrics integration is claimed. Real MusicBrainz metadata search is separately labeled and never converted to fake demo playback.

## Episode/chrome changes

- Hero artwork top offset is0, bounded/top-aligned behind shared OS chrome. Global rail plate is removed; icons/focus remain. Secondary non-Play hero controls are transparent with scoped yellow focused/pressed border. Global orange focus and static2:3 posters remain.
- Episode control row is exact90% white/black More info plus10% semantic watched checkbox, white/off and orange/checked. The image remains the one play target with a simple native triangle/Aura glow treatment.
- More info has actual episode still, title/number, air date/runtime, synopsis and watched status. Close is fixed; a separate content viewport supports explicit DPAD scrolling. Initial display stays at top. Episode actions Up return to image; image Up returns to seasons. Closing info restores originating More info focus.

## Languages, preparing screen and advisory banner

TMDB movie/TV original_language and spoken_languages are parsed with backward-compatible defaults, deduplicated and displayed as readable title metadata. UI explicitly distinguishes these from the actual selected stream's audio/subtitle options. Official schema checked: https://developer.themoviedb.org/openapi/tmdb-api.json .

Preparing playback uses the genuine poster when available (posterPath preferred), fitted without cropping, with native AuraBox branding centered inside an orange loading ring. Reduce Motion makes the ring static. No poster/rating is fabricated for server entries that do not expose usable safe metadata; branding fallback is retained without bypassing authenticated artwork transport.

After actual READY/playing, a temporary8-second upper-left banner uses attributed certification and explicitly supplied attributed content advisories only. Unknown/unattributed ratings are suppressed. **Current TMDB integration supplies age certification, not verified nudity/drugs/violence categories.** Rating-only banner says detailed advisory is unavailable; it does not infer such categories from rating/genre/overview. A licensed/documented advisory source is still required for those categories.

Player focus is reassigned from preparing root to the live play control once READY; prior progress/source/track/lifecycle/transport checks remain. No new dependency or permission.

## Verification and owner checks

Pure tests cover demo state/filter/queue/completion, language parsing/default/dedup, and honest advisory policy. Integration/geometry guards protect no-bottom-player, per-profile demo state,90/10 controls, transparent rail, scoped hero focus and preparation focus transition. Review corrected poster preference, episode action Up links and long-description scrolling; re-review found no remaining important blocker. Full JVM and exact committed perf build evidence is recorded in PR199.

Owner must compare AuraAmp to approved image on TV, test every menu/filter/control and profile/navigation return; check episode90/10/checkbox/scroll/Back, languages versus source tracks, poster/loading/Reduce Motion and rating banner timing; verify hero/menu/faces and no rail background. This work does not certify TV appearance, music playback or detailed content-warning coverage.
