# AuraConnect AuraAmp Phone Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Make a paired AuraAmp phone reliably control AuraOS playback and queue, start AuraPlay on TV, transfer playlists, and send/update Deezer ARL using the shared Coretex contract.

**Architecture:** `AuraCortex/coretex.kt` owns portable command names, permission mapping, wire data and protocol behavior. AuraOS and AuraAmp keep thin Android/player/UI adapters, with synchronized Coretex source copies in each app. AuraPlay hands track and upcoming-queue metadata to the TV, which resolves audio through its own configured music source.

**Tech Stack:** Kotlin/JVM shared Coretex file, Android Kotlin/Java adapters, AuraAmp WebView JavaScript UI, existing AuraOS Media3 player.

**Spec:** `docs/superpowers/specs/2026-11-17-auraconnect-auraamp-control-design.md`

## Global Constraints

- Keep Coretex the canonical shared logic and protocol source; synchronize app copies from that source.
- Keep Android UI, lifecycle, storage and player APIs in the app shells/adapters.
- AuraPlay supports tracks AuraOS can resolve from its configured source; local-only phone files remain out of scope.
- Ordinary approved pairing enables playback control, queue editing and playlist sync; Deezer ARL receipt remains one-time, explicit and TV-approved.
- Preserve all pre-existing user changes, local configuration, secrets, signing keys and Git history.
- Do not build, test, commit, push, merge, clean or delete repository data as part of this plan.

## Review Focus

- A phone command must never report success when the TV player or active profile is unavailable; return the existing actionable error.
- Inserting a track as “next” must place it ahead of the existing upcoming queue without replacing the current track.
- AuraPlay must start the requested phone track on TV, not merely import its metadata or resume a different selected track.
- Playlist import must preserve stable IDs and meaningful metadata so a repeat transfer updates the same TV playlist.
- Deezer ARL must remain blocked until the TV arms receipt for the paired phone, and must use the exact same credential kind at both ends.

---

### Task 1: Define the portable Coretex command contract

**Files:**
- Modify: `AuraCortex/coretex.kt`
- Copy/update: `TV/AuraOS/tv/app/src/main/java/no/aura/os/v2/coretex.kt`
- Copy/update: `Phone/AuraAmp/v2/Modules/AuraConnect/send/src/main/java/no/aura/cortex/coretex.kt`

**Interfaces:**
- Add `AuraConnectContract` with operation IDs `playback.status`, `playback.play`, `playback.pause`, `playback.next`, `playback.previous`, `queue.add`, `queue.clear`, `playback.handoff`, `playlists.push` and `credential.offer`, plus `permissionFor(op: String): Permission?`.
- Add `AuraConnectContract.DEEZER_ARL_KIND = "deezer-arl"` and a platform-free `AuraTrackReference(id, title, artist, durationMs, isrc, cover)`; do not include stream URLs or credentials.
- Preserve existing Coretex security/transport behavior and the approved, committed-independent source of truth.

- [x] Add the shared operation IDs, permission lookup and track descriptor to the canonical Coretex file.
- [x] Synchronize the TV and mobile Coretex copies from the canonical file while preserving no app-specific changes inside the shared source.
- [x] Inspect the three copies to confirm identical content and inspect the diff to confirm only the contract additions and approved source-copy synchronization.

### Task 2: Put playback and queue command behavior behind Coretex

**Files:**
- Modify: `AuraCortex/coretex.kt`
- Adapt: `TV/AuraOS/tv/app/src/main/java/no/aura/os/v2/connect/PlaybackControl.kt`
- Adapt: `TV/AuraOS/tv/app/src/main/java/no/aura/os/v2/connect/CortexCompat.kt`
- Adapt: `TV/AuraOS/tv/app/src/main/java/no/aura/os/v2/shell/AuraShell.kt`
- Synchronize Coretex copies from Task 1.

**Interfaces:**
- Define `AuraPlaybackTarget` for status, play/pause/next/previous, `add(track, position)` where position is `"end"` or `"next"`, `clearQueue()` and `handoff(track, upcoming)`.
- Define `AuraPlaybackCommandHandler` to implement `playback.*`, `queue.add`, `queue.clear` and `playback.handoff` using `AuraConnectContract` permission requirements.
- Keep `AuraShell` responsible only for mapping those operations to its active `AmpPlayback` on the Android main thread.
- Permission mapping: playback actions use `PLAYBACK_CONTROL`; `queue.add` and `queue.clear` use `QUEUE_EDIT`; `playback.handoff` uses `PLAYBACK_CONTROL`.

- [x] Move shared command validation/dispatch into Coretex and keep TV code as a thin `AmpPlayback` adapter.
- [x] Implement append and insert-next against the live TV queue without changing the currently playing song.
- [x] Implement play-track-with-queue so AuraPlay replaces the TV's upcoming queue with the supplied order and starts the selected track.
- [x] Keep fresh playback snapshots and explicit no-player/profile errors in command responses.
- [x] Review the relevant source diffs and operation-to-permission mapping.

### Task 3: Connect AuraAmp remote and track actions to TV operations

**Files:**
- Modify: `Phone/AuraAmp/v2/Modules/AuraConnect/send/src/main/java/com/aura/amp/AuraConnectClient.kt`
- Modify: `Phone/AuraAmp/v2/app/src/main/assets/app.js`
- Reference: Task 1 Coretex operation IDs.

**Interfaces:**
- Use `AuraConnectClient.control(deviceId, op, argsJson)` for command/response transport.
- Continue using `AuraConnectClient.sendPlaylists(deviceId, playlistsJson)` only for library import/update.

- [x] Route remote play/pause/next/previous and clear-queue UI actions to the canonical Coretex commands.
- [x] Add distinct phone actions calling `queue.add` with `position="end"` or `position="next"`.
- [x] Replace the current “send as next” playlist import with the insert-next command and show success/failure from the actual TV response.
- [x] Keep device selection bound to the currently selected/paired receiver instead of silently acting on the first receiver where a user selected one.
- [x] Review each action path from UI data attributes through `MainActivity` bridge and `AuraConnectClient` to the Coretex operation.

### Task 4: Make AuraPlay start the requested TV track

**Files:**
- Modify: `Phone/AuraAmp/v2/app/src/main/assets/app.js`
- Modify: `Phone/AuraAmp/v2/Modules/AuraConnect/send/src/main/java/com/aura/amp/AuraConnectClient.kt`
- Modify: `TV/AuraOS/tv/app/src/main/java/no/aura/os/v2/amp/AmpPlayback.kt`
- Adapt: `TV/AuraOS/tv/app/src/main/java/no/aura/os/v2/shell/AuraShell.kt`
- Reference: Task 2 `play-track-with-queue` contract.

**Interfaces:**
- Send `playback.handoff` args containing the selected `AuraTrackReference` and an ordered list of upcoming `AuraTrackReference` values.
- TV maps those references into `AmpTrack` values and lets its own `AmpProvider` resolve the playable stream.

- [x] Serialize the selected phone track and upcoming queue without stream URLs, ARL values or local file paths.
- [x] Call `AuraConnectContract.PLAYBACK_HANDOFF` and wait for a response before reporting AuraPlay started.
- [x] On TV, replace the upcoming queue and start the requested track through the live player; return the resulting playback snapshot.
- [x] Keep native Google Cast separate and return a clear unsupported-source error when AuraOS cannot resolve a transferred track.
- [x] Review the command path and confirm the mobile UI cannot report success after a rejected or failed handoff.

### Task 5: Preserve and update phone playlists on TV

**Files:**
- Modify: `Phone/AuraAmp/v2/Modules/AuraConnect/send/src/main/java/com/aura/amp/AuraConnectClient.kt`
- Modify: `Phone/AuraAmp/v2/app/src/main/assets/app.js`
- Adapt: `TV/AuraOS/tv/app/src/main/java/no/aura/os/v2/connect/PlaylistSync.kt`
- Adapt: `TV/AuraOS/tv/app/src/main/java/no/aura/os/v2/connect/CortexCompat.kt`
- Adapt: `TV/AuraOS/tv/app/src/main/java/no/aura/os/v2/shell/AuraShell.kt`

**Interfaces:**
- Use the canonical Coretex playlist-push operation with stable playlist and track IDs.
- Keep persistence in the TV shell/library adapter; Coretex owns the shared payload contract and validation behavior.

- [x] Preserve playlist description and supported track metadata when converting the AuraAmp payload.
- [x] Keep repeat sends as updates by stable playlist ID and return imported/skipped counts to AuraAmp.
- [x] Ensure the operation remains bound to the profile that approved the pairing and is disabled for child profiles.
- [x] Review the payload fields and result reporting end to end.

### Task 6: Align Deezer ARL transfer with the TV approval flow

**Files:**
- Modify: `AuraCortex/coretex.kt`
- Synchronize: both app Coretex copies
- Modify: `Phone/AuraAmp/v2/Modules/AuraConnect/send/src/main/java/com/aura/amp/AuraConnectClient.kt`
- Adapt: `TV/AuraOS/tv/app/src/main/java/no/aura/os/v2/connect/ArlReceive.kt`
- Adapt: `TV/AuraOS/tv/app/src/main/java/no/aura/os/v2/shell/AuraShell.kt`

**Interfaces:**
- Define one canonical Deezer ARL credential kind in Coretex and use it on both platforms.
- Keep receipt restricted to the TV's paired-device, one-time, expiring approval window.

- [x] Replace the phone's mismatched credential kind with the canonical Coretex value.
- [x] Ensure the TV's arm/disarm lifecycle grants receipt only to the intended paired device and revokes it after one attempt or expiry.
- [x] Return storage/verification outcome to the phone without returning the credential value.
- [x] Review all ARL call sites and the TV-side gating path.

### Task 7: Synchronize, review and summarize

**Files:**
- Review: `AuraCortex/coretex.kt` and both app copies
- Review: all AuraAmp/AuraOS adapter files listed above

- [x] Confirm the Coretex copies are byte-identical after the final changes.
- [x] Review the complete diff, including pre-existing user changes, and leave unrelated changes untouched.
- [x] Check whitespace and inspect the command/permission, queue-order, source-resolution, playlist-profile and credential-approval paths.
- [x] Report what changed and state that no build or tests were run under the plan constraints.

