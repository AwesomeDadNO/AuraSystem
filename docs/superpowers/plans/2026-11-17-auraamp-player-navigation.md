# AuraAmp Player Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the expanded phone player transform the bottom navigation into transport controls, with a slow player transition and distinct AuraPlay, queue, and artist-context actions.

**Architecture:** Keep playback state and actions in the existing AuraAmp JavaScript UI. Render a shared bottom dock whose contents vary between normal navigation and player controls, and use CSS transitions for expansion/collapse. Reuse the existing track action sheet for current-track and artist actions.

**Tech Stack:** Existing AuraAmp WebView HTML, CSS, and JavaScript; no new dependencies.

**Spec:** `docs/superpowers/specs/2026-11-17-auraamp-player-navigation-design.md`

## Global Constraints

- Keep Coretex and AuraOS unchanged for this phone UI task.
- Preserve existing music, shuffle, repeat, queue, AuraPlay and artist navigation behavior.
- Keep AuraPlay on the left and queue on the right below the timeline.
- Do not build or run tests; inspect the source diff and whitespace only.
- Preserve unrelated working-tree changes and local data.

## Review Focus

- Repeat must visibly distinguish off, repeat-all, and repeat-one while retaining the current cycle order.
- Opening and collapsing the player must restore the prior page and its normal bottom navigation.
- AuraPlay and queue must remain separate, reachable controls on narrow phones.
- Artist follow/profile actions must use the current track's artist and existing artist navigation.
- Track context actions elsewhere in the app must continue to work after adding artist actions.

---

### Task 1: Transform the player dock and reuse track context actions

**Files:**
- Modify: `Phone/AuraAmp/v2/app/src/main/assets/app.js`
- Modify: `Phone/AuraAmp/v2/app/src/main/assets/index.html`

**Interfaces:**
- The dock reads the existing `page`, `shuffleEnabled`, `repeatMode`, and `playing` state.
- Buttons use the existing delegated `data-*` action handler: `data-shuffle`, `data-player`, `data-repeat`, `data-auraplay`, and `data-queue`.
- Track menu artist actions receive the selected `actionTrack` and use `openArtist(name, seed)` and `persist.followedArtists`.

- [x] Render the normal bottom navigation outside the player and a five-button player dock inside it: shuffle, previous, play/pause, next, repeat.
- [x] Move AuraPlay and queue to separate left/right controls below the timeline; remove their duplicate placement from the central transport controls.
- [x] Add slow enter/collapse transitions so player content rises as the dock changes and the normal navigation returns when collapsed.
- [x] Extend the existing track action sheet with follow/unfollow artist and open artist profile actions; connect the player's three-dot button to that same sheet.
- [x] Review the resulting JavaScript/CSS diff and confirm `git diff --check` is clean; do not build or run tests.
