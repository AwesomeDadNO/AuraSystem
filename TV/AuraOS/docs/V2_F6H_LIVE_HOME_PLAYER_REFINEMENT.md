# F6h owner TV refinement — 2026-11-12

Owner feedback after actual TV screenshots supersedes the prior visual tuning and old Live TV guide rollback. Source/JVM/build evidence is not TV acceptance. Keep Draft PR199/issue198 open, F6f installed baseline unchanged, no merge/release/install/device operation.

## Presentation

- Hero art safe top moves68→60 canvas units, bounded and top-aligned beneath navigation. Owner must confirm faces and header boundary against screenshot reference.
- Approved logo atlas already existed;64-unit slots and padded140-high cells shrank visible wordmarks. New76-unit shortcut slots have independent6-unit padding in menu width accounting. Atlas decodes/scans once off-main, ignores alpha≤8 extraction noise, computes each logo's alpha>32 bounds and fits with8% padding. No background plate is introduced; high-alpha extraction artifacts still need owner visual review. Unknown/empty cells use native fallback.
- Stronger glass sheen/edge on video/category/poster cards and active navigation/hero/player buttons. No blur dependency or poster expansion;184×276 remains.
- Inline season tabs replace pills; orange underline marks selected season independently of focus. Episode watched/info controls remain equal width, now outlined with icons, as owner's reference. Custom native Aura play glyph uses orange glow, one image focus target.

## Optional Home shortcut

`general.home_button` and Accessibility open a real optional setup flow, not only a generic settings link. Enabling requires an adult profile and existing Administrator authorization, explicit in-app device-wide consent, then a separate Android Accessibility enablement. Normal default HOME setup remains preferred and available.

The bound `AuraHomeShortcutService` observes only TYPE_WINDOW_STATE_CHANGED package/class metadata; it cannot retrieve window content or request keys, gestures or interactive-window access. It validates the resolved system HOME component exactly, excludes Aura/resolver/settings/system UI, throttles/rejects repeats and checks consent on every event. It opens Aura only when that validated stock Home appears. Back returning to stock Home can also trigger it; it is not a privileged Home-key hook. Some OEMs may block or emit incompatible metadata. Disable in-app immediately; revoke OS access separately. No automatic grant, stock-launcher disabling or universal success claim.

Official sources: https://developer.android.com/guide/topics/ui/accessibility/views/service and https://developer.android.com/guide/components/activities/secure-bal .

## Live TV discovery, IPTV deferred

Live TV now uses a Home-like shared hero plus16:9 native installed-app banners (PackageManager supplied TV artwork, not the new custom-logo atlas on this page). Only discovered launchable known live-capable apps appear; this is not entitlement/channel discovery. Children cannot launch external live apps without verified content restrictions. Missing app/open failure is honest. Launch is the app itself, not an invented programme/channel deep link.

Skal vi danse / TV2 Play20:00 and other programme/time/artwork entries are fixed **demo EPG and illustrative art**, labeled on the hero/page. No real airing, schedule API or provider integration is claimed. No IPTV loading/player route is activated; prior encrypted source preferences/backend are retained untouched, not deleted.

Future EPG work must choose licensed/public schedule data or documented provider integration, stable channel/programme identifiers, country/timezone and documented app links. Existing public TV-listings access does not imply private programme catalogues, entitlements or channel handoff. XMLTV/backend research remains separate from this app-discovery UI.

## Aura own player

TMDB/UltraMax and personal-server playback use Aura's VideoPlayerHost controls, not an IPTV UI/external player. Authenticated server playback still uses ConfinedMediaDataSource with fresh headers/range/endpoint checks; no plain-URL bypass. Resume, lifecycle close and actual START/PROGRESS/STOP reporting retained. Server track defaults now receive current playback preferences.

Audio/Subtitles/Sources are independent modal menus with DPAD focus isolation. Audio selection confirms actual platform state and reports failure. Subtitle selection includes Off and embedded timed text that the platform can render. **Other embedded subtitle formats and external subtitle files are not supported by this platform engine**; options are not invented. Source alternatives come from actual resolved UltraMax results and preserve playback position on switching. Server playback has only its verified direct source unless its API actually supplies alternatives; no fabricated renditions/transcoding.

Official track API limits: https://developer.android.com/reference/android/media/MediaPlayer . Previously approved Media3 libraries remain present but IPTV route/player stays deferred; no new dependencies or platform upgrade.

## Validation

New tests cover consent/system-HOME matching/loop and debounce boundaries, manifest capabilities, alpha bounding/cell confinement, actual source choices/redaction/track selection policy, and installed live-app candidates/child rejection/demo-only schedule. Full JVM and exact-commit perf evidence is in PR199. Owner TV test remains mandatory for Accessibility behavior, visual appearance, playback formats/tracks/source switching and live app return.
