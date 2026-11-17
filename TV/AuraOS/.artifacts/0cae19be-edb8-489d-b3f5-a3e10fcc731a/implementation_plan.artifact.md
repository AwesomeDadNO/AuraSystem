# AuraConnect Dedicated Settings Tile and QR/PIN Pairing Sheet Implementation Plan

Implement a dedicated "AuraConnect" settings tile and immersive pairing sheet (with ZXing QR code generation, formatted PIN, and management actions), removing the legacy "Connect a phone" entry from the Eclipse/Deezer sheet and wiring AuraAmp's connect action to the new flow.

## Proposed Changes

### Build Configuration
#### [MODIFY] [build.gradle.kts](file:///Users/awesomedad/Documents/GitHub/AuraSystem/TV/AuraOS/tv/app/build.gradle.kts)
- Add `implementation("com.google.zxing:core:3.5.3")` for generating QR code bitmaps.

### Settings & Navigation
#### [MODIFY] [SettingsCatalog.kt](file:///Users/awesomedad/Documents/GitHub/AuraSystem/TV/AuraOS/tv/app/src/main/java/no/aura/os/v2/settings/SettingsCatalog.kt)
- Add an AuraConnect settings item/tile when `BuildConfig.AURACONNECT_PAIRING` is true.

#### [MODIFY] [AuraShell.kt](file:///Users/awesomedad/Documents/GitHub/AuraSystem/TV/AuraOS/tv/app/src/main/java/no/aura/os/v2/shell/AuraShell.kt)
- Add state and UI handling for the dedicated AuraConnect pairing sheet showing:
  - QR code generated via ZXing `QRCodeWriter` rendered as an `Image`
  - Formatted PIN (`"123 456"` format), TV name, and `host:port` (port 47131)
  - Action buttons: "New code", "Paired devices", "Receive Deezer sign-in", "Receive media server sign-in"
- Remove "Connect a phone" option from `showAmpConnection()` (Eclipse/Deezer sheet).
- Wire AuraAmp's `onAuraConnect` callback to open the new AuraConnect pairing sheet.
- Maintain existing `onApprovePairing`, `onShowSas`, and `TvCommands`/`buildPairHandlers` routing.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:testDebugUnitTest` to verify all unit tests pass.

### Manual Verification
- Deploy and run debug build with `AURACONNECT_PAIRING=true` in `local.properties`.
- Verify the AuraConnect tile appears in Settings under adult profile.
- Verify clicking it (or AuraConnect button on AuraAmp) opens the sheet displaying the QR code, formatted PIN, IP:47131, and management buttons.
- Verify "New code", "Paired devices", and credential reception flows.
