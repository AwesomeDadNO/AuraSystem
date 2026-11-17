# V2-F1: installed apps and real Services status

Status: **implemented in source, not built with Gradle, not device-verified.** Branch `auraosv2/f1-installed-apps`, stacked on `02a9d17` (design lock).

## Behaviour
| | Demo Mode (default) | Real Mode (opt-in: Settings → "Real mode (installed apps only)") |
|---|---|---|
| Services: streaming apps | illustrative toggles | Netflix, Prime Video, Disney+, Max: **Installed / Not installed** from PackageManager; selecting an installed app launches it, otherwise a short message |
| Services: media sources | illustrative Connected/Disconnected | Plex, Jellyfin, Emby, UltraMax: always **Not configured** (installed does not mean connected) |
| All Apps | internal AuraOS sections | launchable Android TV apps (Leanback launcher), one tile per package, own app excluded, sorted, scrollable |
| Other pages | demo | still demo, with the badge "Real mode: installed apps only · other pages are illustrative" |

## Architecture (`no.aura.os.v2.apps`)
- `InstalledAppsSource`, `AppLauncher`, `ModeStore`: platform-free interfaces; `AppsService` holds all mapping and the launch allowlist (pure Kotlin, fake-testable).
- `PackageManagerApps` (Android): `queryIntentActivities(LEANBACK_LAUNCHER)`, `getApplicationInfo`, Leanback launch intent with launch-intent fallback; every failure maps to `LaunchResult`. `PrefsModeStore`: mode persistence (default Demo).
- UI only receives `RealAppsUi` (snapshot + callbacks); Compose never touches PackageManager.
- Launch rules: only packages returned by discovery (All Apps) or constants in `ServiceApps` (Services) are launched; never a package from content or input.
- Focus: `LaunchFocusTracker` remembers the launching tile; `MainActivity.onResume` bumps `resumeTick`, which refreshes detection and re-requests focus on that tile once.

## Security
Manifest adds only `<queries>` (Leanback launcher intent and six known streaming packages). No permissions, no `QUERY_ALL_PACKAGES`, no network, no credentials, no signing changes.

## Verification
- JVM: 68 tests pass in the cloud harness (10 new: detection, visibility failures, dedupe, allowlist, launch failure, no false Connected, focus tracker, Demo/Real isolation, manifest minimality).
- **Still required on a Google TV / emulator:** launch an installed app, return to AuraOS, focus back on the same tile; All Apps scrolling with DPAD; Real Mode toggle persistence; no crash with Netflix not installed.
