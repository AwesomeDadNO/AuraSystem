# Refactor AuraAmp v2 into App Shell and AuraMusicEngine Module

Extract core backend services and logic from `v2/app` into a dedicated Android library module at `/Users/awesomedad/Documents/GitHub/AuraSystem/AuraCore/AuraMusicEngine`, leaving `v2/app` as a thin UI shell that imports it.

## User Review Required

> [!IMPORTANT]
> The core music playback service (`AuraMediaService`), local sync (`LanSync`), Cast handling (`AuraCast`), credentials (`AuraCredentialVault`), update agent (`AuraUpdateAgent`), Spotify import (`SpotifyImport`), and AuraConnect client (`AuraConnectClient`) will be moved to `AuraMusicEngine`.
> `MainActivity` and all design/frontend assets (`index.html`, `app.js`, themes, layouts) remain in `v2/app`.

## Proposed Changes

### AuraMusicEngine Module (`/Users/awesomedad/Documents/GitHub/AuraSystem/AuraCore/AuraMusicEngine`)
- [NEW] `build.gradle` - Android library module configuration.
- [NEW] `src/main/AndroidManifest.xml` - Module manifest declaring services (`AuraMediaService`).
- [NEW] `src/main/java/com/aura/amp/` (or shared package) containing:
  - `AuraMediaService.java`
  - `LanSync.java`
  - `AuraConnectClient.kt`
  - `SpotifyImport.java`
  - `AuraCast.java`
  - `AuraCastOptionsProvider.java`
  - `AuraCredentialVault.java`
  - `AuraUpdateAgent.java`
  - `AuraWidget.java`

### App Shell (`v2/app`)
- [MODIFY] Remove moved Java/Kotlin service classes from `v2/app/src/main/java/com/aura/amp/`.
- [MODIFY] `v2/app/build.gradle` - Add implementation dependency on `AuraMusicEngine`.
- [MODIFY] `v2/settings.gradle` - Configure `includeBuild` or module inclusion for `AuraMusicEngine`.

## Verification Plan

### Automated Build
- Run gradle build to ensure `AuraMusicEngine` compiles successfully and `v2/app` resolves all service dependencies correctly.
