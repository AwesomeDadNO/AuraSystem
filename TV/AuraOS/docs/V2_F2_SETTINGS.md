# V2-F2: settings foundation

Status: **implemented in source, not built with Gradle, not device-verified.**

**Stack (parents):** `AuraOSv2` ← #174 `auraosv2/demo-task-01-shell-home` (design lock `02a9d17`) ← #175 `auraosv2/f1-installed-apps` (`2e66092`) ← this branch `auraosv2/f2-settings-foundation`. Merge order: #174, #175, then F2. F2 does not duplicate F1; it builds on its code.

## Design correction
The decorative blue line under the AURA section label (it was still present in `CinematicHero` after the label component was introduced) is removed. All branded labels now come only from `AuraBrandLabel`: orange vertical marker, white prefix, orange category text, nothing underneath. The vertical gap above the title is unchanged (spacer replaces the old 6 + 2 + 12). Blue is untouched elsewhere (active pills, focus, progress, EPG).

## Settings structure (`no.aura.os.v2.settings`, `pages/SettingsPage.kt`)
Categories: General, Modules, Connections, Users, Status, About. Category list on the left (pane follows focus), rows on the right; RIGHT enters the pane, LEFT returns, LEFT from the category list goes to the rail.

| Works today | Visible but disabled ("Coming later (Fn)") |
|---|---|
| Default launcher status (read-only) | Set AuraOS as default Home (F11), Home button behavior (F11) |
| Accessibility status ("Not used by AuraOS") | Language, theme, reduce motion, image quality, stream quality, cache, storage, autoplay, subtitles, playback, performance |
| Demo / Real mode switch | Create/edit/delete/switch profile, PIN, child restrictions (F3) |
| Module switches: AuraBox, AuraAmp, Sports, Live TV (persisted, hide navigation, AuraAmp off removes its player) | Changelog, updates, licenses, diagnostics |
| Connections list with explicit states and detail dialog | Configure / Connect / Test / Disconnect for every connection (F4, F5, F7, F9, F10) |
| Status: version/build, profile, modules, connected count, device, error summary | Last sync, cache (shown as "Not available yet") |

The previous demo-only Settings toggles (autoplay, subtitles, high contrast) were removed: they persisted nothing and would have been fake settings. This changes the Demo Settings page deliberately.

## Models
- `ConnectionState`: Not configured / Connecting / Connected / Authentication failed / Offline / Unreachable / Error. `ConnectionStatus` enforces that **Connected requires a verification timestamp**. F2 ships only `NotConfiguredConnections`: nothing can show Connected.
- `CredentialStore`: interface for secrets (masked display, never returns the secret). Only an in-memory test fake exists; the Android Keystore implementation arrives with the first integration that stores a secret (F4/F5/F7). No secrets are imported or stored.
- `ModulePrefs` + `PrefsModulePrefsStore` (SharedPreferences, default all on). Disabling a module changes navigation only: no data or configuration is deleted. Home cannot be disabled. A disabled active route returns to Home.
- `LauncherStatusSource` / `DeviceInfoSource` read the default Home app and app/device version through PackageManager/Build. The manifest `<queries>` gained the HOME intent for this read; no permission, no HOME intent-filter (AuraOS cannot become the default launcher until F11).

## Verification
- JVM: 81 tests in the cloud harness (13 new in `SettingsTest`): structure, disabled-not-faked invariant, module persistence/navigation/Home-always, Connected-requires-verification, masked credentials, status values, minimal manifest, underline removed.
- **Needs emulator (Gemini):** Settings DPAD in both directions, module switch hides/restores navigation and the player, mode switch persists across restart, launcher status text, no crash, hero label without underline, logo unchanged.
