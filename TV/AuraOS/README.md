# AuraOS — media-first ecosystem

**Status:** planning / implementation handoff. This directory is the canonical workspace for the new AuraOS TV and desktop ecosystem. No production modules have been migrated yet.

**Vision:** A fast, advertising-free, media-first interface, not an app-first home screen. TV and desktop present one coherent experience containing AuraLauncher (shell), optionally enabled AuraBox (movies/TV) and AuraAmp (music). Android mobile remains standalone Box and Amp with AURA Connect.

## Critical baseline gate
- User-confirmed last working launcher build: `3.5-amp-active-session`.
- This is a build label, **not yet a verified Git commit**. Find the precise source revision and verify functional behavior before migration. Do not equate `feat/launcher-v3-6-fast-home-review` or the v2 modular branch with this baseline.
- Do not overwrite the launcher, AuraBox, AuraAmp, or their release pipelines. Preserve all existing code, user data, integrations and Git history.
- v2 work: task branches derived from `AuraOSv2`, Draft PRs into `AuraOSv2`; never merge to `AuraOS` or `main`.

## Files
- [ARCHITECTURE.md](ARCHITECTURE.md) — modules, integration boundaries, fallback behavior.
- [DESIGN_CONTRACT.md](DESIGN_CONTRACT.md) — v2 design contract (legacy orange spec: `docs/legacy/`).
- [docs/V2_DESIGN_SYSTEM.md](docs/V2_DESIGN_SYSTEM.md), [V2_COMPONENT_INVENTORY](docs/V2_COMPONENT_INVENTORY.md), [V2_DEMO_ACCEPTANCE](docs/V2_DEMO_ACCEPTANCE.md), [V2_MIGRATION_MATRIX](docs/V2_MIGRATION_MATRIX.md).
- [CLAUDE_START_HERE.md](CLAUDE_START_HERE.md) — prioritized implementation assignment, test gates and handoffs.
- [references/README.md](references/README.md) — design-asset manifest and attachment instructions.

## Team ownership
- Claude: primary AuraOS implementer and local build owner.
- ChatGPT: independent design, security and code review; no unreviewed implementation changes.
- Gemini: final **read-only** technical review and prioritized recommendations before physical TV testing.
- Existing mobile development remains in its separate branches; no TV changes in mobile workstreams unless explicit shared-protocol changes are reviewed.

## Restrictions
- No unnecessary GitHub Actions; monthly ceiling $10. Prefer local Gradle, emulator and ADB validation.
- No tokens, ARLs, signing keys, pairing secrets or private server URLs in source, logs, reference images, or commits.
- Display only genuine media/availability/playback data. TMDB metadata is not proof of playback availability.
- Never silently claim real integrations or performance metrics before tests.
