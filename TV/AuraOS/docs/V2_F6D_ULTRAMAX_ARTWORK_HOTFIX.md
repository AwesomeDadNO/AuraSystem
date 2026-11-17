# AuraOS v2 — F6d UltraMax and details artwork hotfix

Based on F6c PR #193 (818a3c770b0fdcfbfa47e49b880330d54ae0faa9). This is a **source-only** fix: no Android build or on-device verification was performed by ChatGPT.

## Confirmed root cause: empty details artwork
In `AuraShell.kt`, the app content was inside `CompositionLocalProvider(LocalAuraImageLoader provides images)`, but the dialog hosts (`TitleDetailsHost`, `SheetHost`, `DemoDialogHost`) were composed **after and outside** that provider. Consequently `RemoteArtwork` in the details dialog read the default null loader and rendered neutral backgrounds. The fix explicitly provides the image loader around dialog hosts in AuraShell and re-provides the captured loader inside CanvasDialog.

## UltraMax code input
The F6c Android TV IME path is retained, but because it failed on the owner's TV, the code sheet now includes a selectable `Use remote keypad (A–Z / 0–9)` mode, with **all 26 letters and 10 digits**, Backspace and Clear. A focus requester targets the first key when switching. Save remains disabled until exactly eight valid A–Z/0–9 characters are present. The code remains encrypted in the existing Keystore-backed store and is not logged. The administrator PIN pad remains separate and numeric.

## Recommendations are identifiable
`PosterCard` now shows the real TMDB title and optional year **outside** the artwork frame, even if the network image is unavailable. No fake posters or titles are generated.

## Owner test checklist
1. Build from this branch, ensure the correct TMDB local developer config, and verify the commit in Settings → About.
2. Settings → Connections → UltraMax → Enter/Change code. Complete the separate numeric Administrator PIN if shown.
3. If Android TV IME is unusable, choose `Use remote keypad (A–Z / 0–9)` and enter an eight-character test code using DPAD. Save, then Verify. Do not paste or publish any real code into logs or screenshots.
4. Open movie and series details from Real Mode: backdrop, cast portraits and recommended poster images should load. Check titles beneath recommendation cards, including any intentionally missing artwork.
5. Check scrolling, focus and Play/Watched actions. Do not clear existing app data.
6. Compare to the previous F6c screen. If any image is still blank, log image loader presence (never URLs or credentials) before making unrelated optimizations.

## Limitations
No full Android Gradle build, JVM test execution or physical TV test was possible here. Source regression tests were added. Gemini may perform a local Gradle build and user-authorized install; the owner alone operates the app and tests the TV.
