# AuraAmp catalog and Eclipse playback — 2026-11-13

This owner-directed increment supersedes the no-audio-only Amp boundary for **adult Real Mode**. Demo Mode remains fictional and cannot make provider requests. Earlier TV Cinema and F6f baselines remain unchanged.

## Native content and layout

- Public Deezer metadata populates the existing wide hero / landscape releases / popular artists / Recently played–Queue layout and mobile-style right player. No embedded WebView or bottom mini-player.
- Hero requests `/editorial/0/selection`, takes five unique actual albums, provides manual previous/next, and rotates every eight seconds when idle. Rotation pauses while content is focused, a modal is open, or Reduce Motion is on. Fewer returned albums are displayed honestly; no fake slides.
- `/chart/0` supplies currently ranked artists, tracks, albums and playlists. Artists opens the artist's albums and top songs; Albums and artist album lists sort by actual release date newest/oldest, unknown dates last. Playlist creation dates are fetched on its page and support the same sorting.
- New releases uses `/editorial/0/releases`. If empty, dated editorial/chart album details are sorted and explicitly labelled `Recent editorial & chart albums`. This is a bounded selection, not all worldwide new music, and uses `release_date`, not `track_date`. Future dates are excluded. Unknown-date feed items are qualified.
- Initial hero/chart content publishes before bounded four-at-a-time date enrichment finishes. Detail/search errors do not invent tracks. Search uses public Deezer track search. Popular and Trending are separate views; Trending explicitly discloses that it uses charts, not listening-growth measurements.
- Deezer cover/artist/playlist images enter the existing bounded loader only through narrow HTTPS CDN path validation. Requests do not follow redirects or use cookies.

## Connection and audio

Settings → Connections → Eclipse, or Amp → Connect, accepts a masked bare ARL or exact Eclipse manifest URL. The app constructs the URL from fixed `https://eclipse.ultramax.vip/`, encrypted per-profile ARL, and `/manifest.json`. No user credential or authenticated example URL is embedded, committed or printed. Only the intended Eclipse host receives that credential. ARL entry/replacement and removal retain explicit administrator gating; children cannot use the real provider.

Manifest verification and search/stream routes follow the existing AuraAmp mobile protocol. A saved ARL is not reported as verified until a manifest request succeeds. Numeric Deezer identity or exact artist/title matching prevents arbitrary first-result playback. Returned audio must be public HTTPS and omit the ARL; each request/redirect is checked, same-host redirects only. Range starts/truncation are checked. No DRM extraction or decoder workaround.

Approved Media3 supplies actual position, seek, play/pause, next/previous and audio focus/noisy-device handling. Decorative waveform bars are not claimed to be sampled audio. The controller survives navigation inside AuraOS, pauses when AuraOS stops, and releases on profile/mode/module change. There is no background service/lockscreen/remote-cast claim. Disconnect/replacement cancels resolution and clears prepared media, so the previous stream cannot resume.

## Local data and honest limits

Recently played records only a genuine READY + playing event; it starts empty. Queue contains user actions and current-list continuation, supports repeated songs, and persists per profile. Selecting a recent song preserves the existing queue. Queue selection/removal acts on the actual occurrence. Favorites and imported playlist metadata are per profile. No stream URL or ARL is stored with these records.

Public playlist links/IDs import through paginated Deezer requests (100 per page), preserving track order/repeats. At most 2,000 songs per playlist, 50 imported playlists and 5,000 imported songs total are allowed; oversized imports fail instead of silently truncating. Local upcoming queue is capped at 200; catalog/artist/search lists are bounded at 100. Refreshing a public import is manual by importing the same link again. Private Deezer library/account/SSO and mobile-to-TV automatic sync are **not implemented by this increment**. Future SSO is separate from Eclipse authentication.

## Visual override

Main background, Android startup/system bars and player base are now `#0E121A`. The player adds restrained white translucent highlights and border for gloss/glass separation. Text menus use orange underlines; default Amp Home does not select Albums. Only exact current-route navigation is active; genuine DPAD focus remains visible. LEFT from content targets the current rail destination, when present.

## Required owner TV/provider checks

- Enter/replace/verify/remove the owner's ARL without revealing it. Verify masked input and profile/admin/child rules, network failures and delayed replies.
- Actual Eclipse playback, correct artist/track resolution, seeking/range/EOF, audio output/focus, pause/next, stop/resume and no old stream after disconnect. Authenticated provider and TV playback have **not been verified in this session**.
- Five slides, manual controls, idle rotation/focus pause/Reduce Motion, real cover display, dark/gloss contrast and 85-inch readability.
- DPAD/IME/Back across views, artist albums/newest–oldest, playlist import/refresh and oversized failure, duplicate queue entries, removal/empty focus, profile/restart/history persistence.
- Partial/offline catalog and expired ARL must leave existing local metadata intact. No release/install/merge or F6f replacement is authorized.
