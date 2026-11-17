# Architectural Cleanup & Modularization Plan for AuraOS Ecosystem

This plan outlines the restructuring of the repository into a clean, decoupled multi-module architecture matching the user's vision:
- **Core Layer**: `AuraCore`, `AuraConnect`, `AuraGuard`, and domain engines (`AuraMusic`, `AuraVideo`, `LiveTV`, `SportsEngine`, `TubeEngine`).
- **Feature Modules Layer**: `AuraAMP`, `AuraBox`, `SportCenter`, `AuraTube`, `LiveTV`.
- **Platform Shells**: `AuraOS` (TV), Mobile apps (`AuraAmp`, `AuraBox`), and Desktop (`AuraDesktop`).

## User Review Required

> [!IMPORTANT]
> - Deprecating and archiving legacy standalone TV fragments and duplicate UI code in favor of modular Compose components under AuraOS and feature modules.
> - Organizing Gradle settings (`settings.gradle.kts`) to reflect a clean multi-module project structure.

## Open Questions

- Should we retain legacy backup directories for reference during the transition, or prune them immediately? (Recommendation: Archive in `docs/legacy/` temporarily).

## Proposed Changes

### Project Structure & Gradle Modules

#### [MODIFY] [settings.gradle.kts](file:///Users/awesomedad/Documents/GitHub/AURA-Ecosystem/AuraOS/tv/settings.gradle.kts)
- Define clean module inclusions for core engines, feature modules, and platform shells.

#### [NEW] [Architecture Overview Documentation](file:///Users/awesomedad/Documents/GitHub/AURA-Ecosystem/AuraOS/docs/V2_MODULAR_ARCHITECTURE.md)
- Document the new architectural layers: Core Engines, Feature Modules, and Platform Shells.

### Pruning & Cleanup
- Remove obsolete standalone TV legacy code and redundant adapters.
- Consolidate shared engines into unified directories.

## Verification Plan

### Automated Tests
- Run `./tv/gradlew test` across all modules to ensure compilation and unit test success.

### Manual Verification
- Build and install debug APKs on Google TV Streamer (`192.168.10.239:5555`) to verify runtime stability.
