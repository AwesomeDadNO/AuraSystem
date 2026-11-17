# AuraAmp protocol and design correction — 13 November 2026

Owner TV evidence: manifest verification succeeds, playback fails; real-data page drifts from the approved demo/reference. This is a correction to the prior native integration, not proof of device acceptance.

## Established faults

The connector had borrowed unverified generic/mobile fallback routes rather than checking the documented Eclipse contract. It ignored direct `streamURL` (camelcase), assumed numeric addon-local IDs were globally Deezer IDs, and collapsed every failure into one generic message. Proxy HLS without a playlist extension did not receive explicit MIME routing. A nonempty manifest resource array was treated as verification regardless of usable capabilities.

The corrected connector follows the [Eclipse Addon Developer Guide](https://eclipsemusic.app/docs): advertised `isrc` / `resolve` lookups when available, otherwise exact-recording `/search?q=…`, then `/stream/{addonLocalId}` or the direct `streamURL`. IDs returned by the addon are scoped to it, not compared numerically to Deezer IDs. Actual ISRC flows from Deezer track metadata into local records. Explicit mismatched ISRC is rejected. Advertised resolve results are trusted as the provider's identity answer; no random first result is played.

Manifest verification checks stream plus a supported lookup capability; it does not prove audio works. Safe errors distinguish connection/manifest/ISRC/resolve/search/stream/audio and HTTP status without raw payloads/URLs/ARL. HLS uses its declared MIME even for extensionless proxy URLs. Protected/unsupported formats and expired addresses fail honestly. ARL-bearing audio proxy URLs are accepted only on the exact intended Eclipse host; every audio/segment request retains address and secret-host checks. No DRM bypass or third-party cookie forwarding.

No authenticated playback was possible in this work environment: root and manifest probes return HTTP403 here, including the unauthenticated root. This must not be interpreted as invalid owner credentials or evidence that TV will fail. The owner reports manifest success on TV; actual track resolution/audio remains an owner device acceptance item.

## Restored visual direction

- One hero recommendation label, no duplicate page heading or standalone Connect/Refresh clutter on Home.
- Wide ambient artwork and genuine artist portrait when supplied by Deezer, soft gradient into the text, fixed title slot and an actual gradient Play album action. Five real recommendations and previous/next remain.
- Four equal landscape release cards with overlaid title/artist and play badge, no clipped horizontal shelf or external captions. Editorial fallback remains qualified in a compact source label.
- Five compact artist portraits and names fit the row. Recently played/Queue retain real data below them.
- Right player returns to the mobile/demo hierarchy: Back/Now Playing/options, rounded cover, centered title/artist, waveform, native previous/next icons and glowing gradient Play/Pause. Footer uses native favorite/download/shuffle/repeat/queue icons; download/lyrics visibly unavailable. Shuffle/repeat affect the actual local queue. Seek uses the focusable waveform; textual ±10s and +Queue controls are removed.
- Connection/refresh/Discover live under player options and existing Settings. Options use the shared scaled CanvasDialog. Queue icon opens the actual Queue tab. Search is accented only on selection/focus. Global palette/rail/menu design remains unchanged.

All visual matching and DPAD interaction remain subject to the owner's TV review. Current artwork is real service art, not the fictional mockup pictures.

## Regression and device checklist

JVM fixture maps exercise documented resource sets, addon-local numeric IDs, direct streamURL, ISRC identity, exact route encoding, HLS MIME and credential confinement without a new test dependency. Queue policies cover repeat and selected shuffle occurrence. Existing dialog-canvas regression stays enforced.

Owner next checks: existing ARL Verify; open a known album and Play; note the safe stage/HTTP message if it fails; direct and HLS audio, seek/output/focus, next/shuffle/repeat and disconnect. Confirm four release cards, five artists, native player icons/glow, hero art and no extra heading. No merge/release/install/device operation was performed; F6f stays intact.
