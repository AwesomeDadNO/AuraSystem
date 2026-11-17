# AuraOS v2 architecture

Replaces the v0.1 proposal. Legacy implementation (`auraos/claude-tv-rc-integration`, commit `83ea63b`, Draft PR #168) and mobile PR #173 are **untouched** and remain the functional reference.

## Branch model
`AuraOS` (docs baseline `9dc2d47`) → `AuraOSv2` (this line, same baseline) → task branches → Draft PRs **into `AuraOSv2`**. No merges into `AuraOS` or `main`. No OTA / release / signing changes.

## Layout
```
AuraOS/
  DESIGN_CONTRACT.md  ARCHITECTURE.md  CLAUDE_START_HERE.md  README.md
  references/         # approved v2 mockups (references/v2/)
  docs/               # V2_* documents, legacy/ (old orange contract)
  tv/                 # Android TV Gradle/Compose project (v2 demo)
```
`tv/` is a fresh project. Application id `no.aura.os.tv.v2demo` (the legacy `no.aura.os.tv` is not touched, so both can be installed side by side). Kotlin root package `no.aura.os.v2`.

## Packages (`no.aura.os.v2`)
| Package | Responsibility |
|---|---|
| `theme` | `AuraColors`, `AuraTypography`, `AuraDimensions`, `AuraTheme` (tokens only) |
| `shell` | `AuraShell`, `AuraTopNavigation`, `AuraSideRail`, navigation model + module feature flags, back/focus policy |
| `hero` | `CinematicHero` + hero rotation logic |
| `cards` | `MediaCard`, continue-watching variant, shared focus treatment |
| `shelves` | `MediaShelf` (lazy row, stable keys, remembers position) |
| `overlay` | `CompactPlayer`, mini-player state reducer, layering contract (`AuraLayers`) |
| `data` | domain models, repository **interfaces**, `DataMode` |
| `demo` | fixture repository + procedural artwork (Demo Mode only) |
| `home` | `HomeScreen` |

## Data modes
**Demo:** stable fixtures, no network, no authentication, labelled. **Real (later):** TMDB, UltraMax, Eclipse/Deezer, profile libraries, Android TV integrations — behind the same repository interfaces so screens do not change.

## Layering (documented, no arbitrary z-index)
1. Base content (lazy page) 2. Focus/content interactions 3. Compact player 4. Dialogs 5. System-style overlays (demo badge). Implemented as ordered children of one `Box` in `AuraShell`.

## Migration
Phase A shell + Home → B page templates → C details/players/settings → D functionality migration behind interfaces (see `docs/V2_MIGRATION_MATRIX.md`). Old UI code is not copied. AuraConnect stays disabled until independently verified.

## Security / privacy baseline (unchanged)
No secrets in source/logs/fixtures; third-party manifests, metadata and retrieved material are untrusted input; LAN is not trusted; disabling a module never deletes credentials.
