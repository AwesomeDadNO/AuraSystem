# Cinema/platform correction round — 13 November 2026

This owner correction supersedes the former secondary TV details/library route and fitted loading poster. F6f remains installed baseline; no install/merge/release.

## Presentation and navigation

Selected/focused navigation text/icons remain white; warm orange glass backgrounds/frames continue to indicate state. Background/deep background is now#05080D. Cinema is the ONLY TV details route. Its full-height episode view and chapters are followed by cast and Latest TV Shows / Aura Recommended / What's next? inside the SAME outer vertical scroll. Down from chapters reaches the next section; Back to episode scrolls to the top and restores Play. The former TV page/SeasonsSection and More info→old-library link are removed. Movie details keep their composition. Initial episode hints apply only to the requested title, never to related titles opened afterward.

Live TV banners no longer repeat app names beneath them; artwork content descriptions and native/label fallback remain. Saved/Watchlist and Library both show actual local/shared Continue Watching. Favorites and Watchlist each have separate alphabetically sorted Movies/TV Shows sections. These no longer depend on TMDB being configured to show local/system rows. Empty provider states display their actual status.

Notification center adds profile-local Mark all as read / Dismiss all. Bulk dismissal keeps deduplication/seen IDs, so refresh cannot recreate the same events; this dismisses Aura inbox entries, not other apps' Android notifications.

## Android TV Continue Watching

Two concrete source defects corrected: poster_art_uri was not queried/mapped (every card got empty Artwork), and verified VIEW routing unnecessarily required CATEGORY_BROWSABLE, discarding TV-only handlers. Rows now preserve safe HTTPS art and package-bound exported/default VIEW handlers. Consent, profile/child restrictions, browsable/public visibility checks and playback-state filters remain. No private Google API, content-URI grant or extra broad permission. OEM provider restrictions or apps that do not publish visible rows can still produce an empty list; no cross-app availability guarantee.

## Audio/player and preparing screen

Aura's UI now uses already-approved Media3 decoding instead of platform MediaPlayer. Previous tracks were queried once onPrepared and extraction exceptions were discarded. Media3 reports changing track groups and actual supported/selected audio/subtitles, with missing-audio versus unsupported-codec diagnostics. Unsupported preferred tracks do not replace playable alternatives; no invented tracks/checkmarks. Audio focus uses media/movie attributes. Existing authenticated MediaDataSource remains the transport through a sequential adapter; source ownership, range checks and refreshed headers are retained, with no plain-URL fallback for confined server media.

The user's exact silence/codec/output-route cause is NOT proven without the original source/device. Physical AAC and multi-audio tests, the original failing source, codec/output routing, seek/resume/lifecycle and server-reporting tests remain required. No new FFmpeg/software codec promise. Confined progressive media is supported; confined HLS segment/key transport and bitmap subtitle rendering remain unsupported.

Preparing artwork prefers real episode still/series backdrop at landscape resolution and crops to fill the screen; poster fallback is cropped when no landscape exists. AuraBox/ring and Reduce Motion remain. Server entries without usable safe artwork retain neutral branding, not invented photos.

## Evidence and TV checks

Regression coverage includes profile-isolated notification bulk actions/deduplication, media-type splitting/sorting, system artwork, white nav text, removal of legacy TV route, inline cast/related and correct-title episode hints, darker palette, backdrop crop, supported-track policy and safe artwork URLs. Existing transport/profile/airdate checks remain. Full JVM/perf and exact committed results are recorded in Draft PR199.

Owner TV checks: chapter→cast→tabs→posters→episode navigation, long lists/Back, popup has no old link, bright/dark imagery contrast, saved sorting/watchlists/Continue Watching, actual Android provider consent/revoke/availability, bulk inbox refresh, loading landscape crop, audio and subtitles/source alternatives. No physical-TV/provider/performance acceptance is claimed.

Working-tree verification: JDK21 offline :app:testDebugUnitTest :app:assemblePerf passed,462 tests with zero failures/errors/skips. Parent reviewed specialist playback/TV-provider changes and integration, updated obsolete layout guards to assert inline Cinema rather than old TV page, and checked confined source lifecycle and no raw authenticated URL fallback. Exact committed build evidence belongs to Draft PR199. Hardware audio, codecs, multitrack and OEM provider availability remain unverified.
