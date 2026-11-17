# AuraConnect phone control and AuraPlay design

## Intent

After pairing AuraAmp mobile with AuraOS, the phone should control the TV's AuraAmp playback, queue and playlists, start AuraPlay on the TV, and send or update the Deezer ARL through an explicit TV-approved transfer. Coretex remains the canonical shared logic and protocol source; each app stays a platform shell.

## Goals

- Define the shared AuraConnect commands, permissions, track data and result behavior in `AuraCortex/coretex.kt`.
- Support TV playback status, play, pause, next, previous, queue clear, append-to-queue and insert-next.
- Make AuraPlay send the selected phone track and its upcoming queue to the paired TV, then start that track in the TV's AuraAmp player.
- Transfer and update phone playlists in the active TV profile.
- Standardize Deezer ARL transfer and preserve the TV's explicit, one-time approval flow.
- Keep UI and Android player/service integration inside AuraAmp and AuraOS adapters; avoid separate protocol implementations in the shells.

## Non-goals

- Replace Android's native Google Cast support or implement a third-party AirPlay receiver. AuraPlay uses AuraConnect directly.
- Send audio bytes or a phone-local file over AuraConnect in this first scope.
- Automatically grant a paired phone permission to send credentials.

## Chosen approach

Use Coretex as the shared protocol and behavior contract, with narrow platform adapters. AuraAmp sends a track descriptor (provider ID, title, artist, duration, ISRC and artwork where available) and queue intent over its paired, encrypted AuraConnect session. AuraOS hands the descriptor to its existing AuraAmp playback engine, which resolves the track from a source configured on the TV and reports playback state through AuraConnect. This lets playback continue if the phone disconnects.

The existing phone-to-TV playlist push remains the transport, with its data shape and result reporting made consistent. For credentials, use one canonical Deezer ARL `kind` across Coretex, phone and TV; the TV must arm receipt for a paired device and accept one transfer only.

## Assumptions and limits

- AuraPlay in this scope supports tracks that the TV's AuraAmp source can resolve from the descriptor. Local-only phone files and sources unavailable on TV need a later secure media-relay design.
- An ordinary approved pairing grants playback control, queue editing and playlist sync. Credential receipt remains separately armed and time-limited on the TV.
- Existing unrelated local changes in the repository must be preserved.

## Implementation outline

1. Define canonical Coretex command/permission and track-transfer contracts for playback, queue insertion mode, AuraPlay handoff, playlist push and credential type.
2. Adapt AuraOS's playback target and AuraAmp player to execute the shared contracts and return fresh snapshots or actionable errors.
3. Adapt AuraAmp's remote, track actions, AuraPlay picker, playlist transfer and Deezer ARL bridge to use those contracts.
4. Remove the specific protocol mismatches and metadata loss in the phone copy/integration, keeping app shells free of competing command semantics.

## Open product question

Should AuraPlay include local-only tracks from the phone in this first delivery? The chosen TV-resolve design cannot play those without a separate secure stream relay. Recommended first scope: AuraPlay supports tracks resolvable by AuraOS, and local-only transfer is a later feature.
