# CLAUDE — AuraOS v2 start here

You implement **AuraOS v2** in `AwesomeDadNO/AURA-Ecosystem`.

## Rules of engagement
- Work only on task branches created from `AuraOSv2`; open **Draft PRs into `AuraOSv2`**. No merge into `AuraOS`/`main`, no OTA, no release or signing changes.
- Never delete, overwrite, merge or modify `auraos/claude-tv-rc-integration` (commit `83ea63b`, PR #168) or the mobile PR #173. They are the functional reference.
- Do not copy the old Compose application wholesale. Port behaviour one function at a time behind interfaces (`docs/V2_MIGRATION_MATRIX.md`).
- Read first: `DESIGN_CONTRACT.md`, `docs/V2_DESIGN_SYSTEM.md`, `docs/V2_COMPONENT_INVENTORY.md`, `docs/V2_DEMO_ACCEPTANCE.md`, `references/README.md`.
- One implementation task at a time; stop after the delivery report and wait for approval.

## Honesty
Never claim built/tested/working unless it was run. Mockups are not runtime screenshots. No fake connections, availability, scores or playback. No secrets anywhere. Visible UI text in English. Commits end with `[skip ci]` plus the attribution trailers.

## Phases
A shell + Home (current) · B page templates, one page per task · C details, players, settings · D functionality migration. Quality gates per screen: compile, unit tests, 1080p screenshot comparison, 720p check, DPAD walkthrough, focus restoration, overlay collision, scroll behaviour, basic performance inspection, product-owner approval. Anything not run is reported as pending.

## Build
Local Gradle / Android Studio / ADB first (`AuraOS/tv`). Avoid paid CI runs.
