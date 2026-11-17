# AuraConnect — one pairing, then send/receive playlists, servers and ARL (design)

Owner decision (2026-11-13): a single QR pairing between phone and TV/desktop, after which either side can, from its settings, send or receive playlists, media-server logins and the Deezer ARL **on demand** — with a handshake that compares both sides first and moves only the differences. This document pins how that works and what each part may do, for ChatGPT's security review. **AuraConnect stays off by default and is not enabled for daily use until that review and device testing are done.**

## Roles and direction

One pairing, one channel. The TV/desktop is the AuraConnect **server (receiver)**; the phone is the **client (controller)**. Every exchange is a client call to the server, so the phone never has to listen:

- **ARL and media-server logins:** phone → TV. You hold the credential on the phone and push it.
- **Playlists (and the server *list*, names/addresses, not tokens):** the phone pulls the TV's state, compares, and writes back the differences. "Send playlists to the Amp app" = the phone fetches them from the TV and imports.

## What one pairing grants

Pairing (QR or typed code + an explicit Allow on the TV) creates a durable trusted device for the **one profile the person picks on the TV**, with the everyday controller permissions only: `PLAYBACK_STATUS`, `PLAYBACK_CONTROL`, `QUEUE_EDIT`, `PLAYLIST_SYNC` (`Permission.DEFAULT_CONTROLLER`).

It does **not** grant `CREDENTIAL_RECEIVE`. Pushing an ARL or a media-server login is a **separate, per-transfer consent on the receiver** every first time, because handing over a secret must never be implied by a scan. Playlists need no extra consent beyond the pair — they are the person's own library data moving to their own phone.

Credentials are never *diffed or merged*: a secret is present-or-absent, newest-wins, and always behind the explicit Allow. Only collections (playlists, the server list) are reconciled.

## The handshake that "sees differences" (reconciliation)

For a collection, each side first exchanges a **manifest** — per item: stable id, `updatedAt`, and a content hash — with no item bodies. Both compute the difference:

- only on A, only on B, changed (same id, different hash), identical (skip).
- Only the deltas' bodies are then transferred.
- Conflict (both changed the same id since the last sync): the default is newest-`updatedAt`-wins, with "keep both" as the fallback for playlists; the person can be shown the differences before applying. No silent overwrite of a two-sided change.

This needs every playlist (and server-list entry) to carry a stable id and an `updatedAt`/hash on both apps. That is the main new requirement the apps must meet, and it is why this is a real sync step, not a copy.

## Status

| Part | State |
|---|---|
| Transport, J-PAKE pairing, pinned TLS (AC-1 #183) | `gradle check` 49/49; security review outstanding |
| Android identity + trust/receiver stores (AC-2 #200) | JVM-tested; Keystore compile-only |
| TV one-shot ARL receiver (F6i #201) | loopback-tested; folds into the pairing below at the credential step |
| **Persistent QR pairing on the TV + device management (this slice)** | **source + loopback tests; off by default** |
| Reconciliation engine (manifests, diff, conflict) | not built |
| Mobile client (pair, pull/push, import), Deezer paste + send | not built |
| **AuraShell wiring (F6l): persistent receiver on demand from Settings + Paired phones + arm-to-receive + playlist push-receive** | **source + 539 JVM tests, R8 perf build; off by default; QR image still pending the mobile client** |
| TV Settings QR **image** | lands with the mobile client (see below) |

### This slice (TV pairing engine)
`no.aura.os.v2.connect`:
- `PairingReceiver`: a persistent AuraConnect server on the TV's home-network address, writing trust to the `FileTrustStore` from AC-2, so a paired phone reconnects on its pinned key without a new code. Grants `DEFAULT_CONTROLLER` for the chosen profile; a granted-but-unimplemented operation answers "not available", never a silent success.
- `PairingPayload`: the `auraconnect://pair?v=3&host&port&id&code` string for the QR and for manual entry. Carries no credential. The code is single-use, two-minute, and proven by J-PAKE, so it is not a bearer secret.
- `TrustedDevices`: list (active first, revoked last) and revoke, for "Paired devices"; `forProfile` so deleting a profile revokes its devices.

Loopback tests (`PairingReceiverTest`): durable pairing and code-free reconnect; a fresh receiver on the same identity + store still trusts the phone; granted-but-unimplemented ops are refused, credential ops are never granted by a pair; revoke drops trust and closes the device; wrong code / denied / disabled build pair nothing; payload round-trips and rejects junk.

531 JVM tests pass; the R8 `perf` build succeeds. **Nothing was run on Android.**

## Why the Settings UI and QR image are not in this slice
Nothing can pair until the phone client exists, so a "Pair a device" screen would show a code with nothing to scan it. The TV Settings entries ("Pair a device", "Paired devices") and the QR **image** land together with the mobile client, so they can be exercised. The QR image also needs a decision: add a small QR library (e.g. ZXing core) or render a QR matrix in pure Kotlin — no dependency is added without the owner's say-so.

## Still the owner's calls
1. **Enable gate:** AuraConnect on for daily use only after ChatGPT's security review of AC-1 and device testing.
2. **QR rendering:** ZXing dependency vs a pure-Kotlin encoder.
3. **Mobile app changes** in `apps/aura-amp` (approved) on their own branch, coordinated with the open mobile PRs.
4. **Reconciliation details:** conflict default (newest-wins vs always-ask) per collection.

## F6k — TV receive handlers: playlists and ARL over the pair (source + tests, off by default)

Branch `auraosv2/f6k-auraconnect-receive`, stacked on F6j. Self-contained classes in `no.aura.os.v2.connect`; **AuraShell and the AuraAmp page are untouched** (ChatGPT is on that line), so this is the receive *logic*, wired into the running app in a later, coordinated AuraShell change.

- **Playlists (TV → phone pull with diff):** `PlaylistSync` + `PlaylistSyncHandler` serve the `sync.playlists` operation, which an ordinary pair already grants (PLAYLIST_SYNC) — no extra consent, it is the person's own library going to their own phone. The phone sends what it has (`id` + content hash); the TV returns the full manifest and only the new/changed bodies, bounded per call. `PlaylistReconcile` is the pure "see differences" (what to fetch, what to delete); `PlaylistHash` makes any real change flip the hash. A playlist body carries metadata only — never a stream URL or credential; a child/empty source exposes nothing. `PlaylistSource` is the seam the shell backs with `AmpLibrary` for the active adult profile.
- **ARL over the pair (per-transfer consent):** `CredentialGrantWindow` adds CREDENTIAL_RECEIVE to one device's grant only after the person chooses "Receive a sign-in" on the TV; `ArmedCredentialHandler` (reusing the F6i `ArlOfferHandler` rules) accepts exactly one `credential.offer` and disarms at once. Without an armed window, a paired phone's ARL send is refused — a pair alone never lets a phone push a credential.

Tests (`ReceiveSyncTest`, over loopback TLS): hashing and reconcile pick the right ids; the reply sends the manifest and only missing/changed bodies and pages; a paired phone pulls playlists and a second pull re-sends nothing; a child/empty source exposes nothing; the ARL is accepted only while armed and the window auto-disarms after one credential. 536 JVM tests pass; the R8 `perf` build succeeds.

**To make it reachable (one coordinated AuraShell change, not done here):** start the `PairingReceiver` with `handlers = {sync.playlists, credential.offer}`, back `PlaylistSource` with `AmpLibrary`, add "Pair a device"/"Paired devices" and the "Receive a sign-in" consent that calls `CredentialGrantWindow.arm`. Kept out of this slice to avoid editing `AuraShell` while ChatGPT is on it. Nothing run on Android; pairing off unless `AURACONNECT_PAIRING=true`.

## F6l — AuraShell wiring, now done (coordinated, owner-approved "Koble inn nå")

Branch `auraosv2/f6k-auraconnect-receive` (the F6k slice extended). The receive logic above is now reachable from the running TV app, and **only** the Eclipse connection menu in `AuraShell` is touched — the AuraAmp page layout (ChatGPT's line) is not.

- **`AmpPlaylistSource`** (`no.aura.os.v2.connect`) backs `PlaylistSource` with the active adult profile's `AmpLibrary`: read-only metadata (title/artist/isrc/cover), never a stream URL or credential; a child profile or a blank profile exposes nothing.
- **Playlist PUSH (phone → TV), the important direction** — building a playlist on the TV is hard, so the phone is where you make one and send it. New op `playlists.push` (mapped to `PLAYLIST_SYNC`, so an ordinary pair already grants it — the person's own library going to their own TV, no extra consent). `PlaylistPushHandler` + `PlaylistSync.parsePush` accept bounded playlist bodies and `AmpPlaylistSink` imports/updates them into `AmpLibrary` newest-wins by id (metadata only; the 50/2000/5000 limits apply — an over-limit list is skipped, reported in `skipped`, without failing the rest). A child/blank profile refuses the whole call.
- **"Connect a phone (pair, playlists, sign-in)"** in `showAmpConnection()` (gated on `AURACONNECT_PAIRING`, adult-only, behind the Administrator prompt) starts a persistent `PairingReceiver` on the home-network address with the two handlers. Trust is the durable file-backed `FileTrustStore` from AC-2, so a paired phone reconnects without a new code. Pairing shows the host/port and the single-use code, and the Allow prompt shows the phone's name and the J-PAKE fingerprint to compare.
- **"Paired phones"** lists trusted devices and removes one (revoking it closes its connection at once).
- **"Receive a Deezer sign-in from a phone"** calls `CredentialGrantWindow.arm` for the paired device: a two-minute, one-credential window. Without it, a paired phone's ARL send is refused — the pair alone never carries `CREDENTIAL_RECEIVE`. A stored ARL re-runs the Eclipse check and updates the connection state, exactly as the F6i local flow does.
- The receiver runs only while its sheet is open and is closed on sheet-dismiss, profile change and app-stop; nothing listens at rest. *(Superseded by F6m: the receiver is now persistent — see below.)*

## F6m — remote playback/queue control, and media-server sign-in transfer

Same branch. Two extensions, both still behind `AURACONNECT_PAIRING` and never run on Android.

### Remote control (granted by an ordinary pair)
`playback.*` and `queue.*` are in `Permission.DEFAULT_CONTROLLER` (PLAYBACK_CONTROL / QUEUE_EDIT), so a paired phone controls the TV's AuraAmp player with **no extra consent** — your own music on your own TV. `PlaybackControl.kt` adds the `PlaybackTarget` seam (the shell backs it with the live `AmpPlayback`, every call marshalled to the main thread) and `PlaybackCommandHandler` covering `playback.status/play/pause/next/previous/seek/shuffle/repeat` and `queue.add/remove/move/clear` (new op `queue.clear` → QUEUE_EDIT). Every command answers with a fresh snapshot (`playing`, `shuffle`, `repeat`, position/duration, selected track, queue length) so the phone's transport controls stay in sync without a second poll. `queue.add` carries the track's metadata (id required) so a track the TV has never seen still enqueues and plays. `AmpPlayback` gained `resume()` (a remote "play" must not pause when already playing), `applyShuffle`/`applyRepeat` (set the exact state, so a phone switch and the TV never drift), `clearQueue`, `moveQueued`.

### Media-server sign-in (Plex / Jellyfin / Emby) — a secret, so armed like the ARL
A server token is a credential, so it rides the **same per-transfer armed window** as the Deezer ARL — never the plain pair. `MediaServerReceive.kt`: `MediaServerOfferHandler` parses and validates the offered `ServerAccount` (the address is re-checked through `ServerAddress.parse`, so a tampered store/wire can never point a token at a loopback or forbidden host), and the shell stores it with `MediaServerService.add` and re-verifies it against the server (identity then credential). `CredentialReceiveHandler` is now the single `credential.offer` handler: it checks the armed window once, routes by `kind` (`deezer-arl` → the ARL handler, `plex`/`jellyfin`/`emby` → the media handler), and disarms after one accepted credential. Wire: `{ "kind": "...", "account": { kind, serverId, serverName, base, lanHttp, userId, userName, token, deviceId } }` → `{ stored, verified, error? }`. The token is never echoed, logged or put in an error message.

### Receiver lifecycle changed to persistent
To make control usable, the receiver now **listens persistently** while an adult profile is active and the app is foregrounded (started on `ON_START`, stopped on `ON_STOP` / profile change / dispose) — not only while the pairing sheet is open. The pairing **code** is still opened only from "Connect a phone", so a new device can pair only during that window; an already-trusted device reconnects any time on its pinned key. Closing any sheet disarms a pending credential window. New Settings action: **"Receive a media-server sign-in from a phone"** (arms the same window; the router handles the kind).

Tests (`RemoteControlTest`, loopback TLS): a paired phone drives every control op and gets the new state back; a missing player answers "not available" (never a silent success); a media-server sign-in is accepted only while armed, the window is one-shot, a bad `kind` fails without consuming the window, and a tampered (loopback) address is refused before anything is stored. 543 app JVM tests pass; the R8 `perf` build succeeds. The core `SecurityTests` (`run-tests.sh`) were not re-run in Claude's environment (no standalone kotlinc); the new ops' authorization is covered by the loopback tests, and Gemini runs `run-tests.sh` on the local build.

539 JVM tests pass (incl. the playlist push over loopback TLS; the F6i shell-contract test updated to the durable-trust wiring); the R8 `perf` build succeeds. The standalone core `SecurityTests` (`run-tests.sh`) were not re-run in Claude's environment (no standalone kotlinc); the new `playlists.push` op's authorization is covered by the loopback push-test, and Gemini runs `run-tests.sh` on the local build. **Nothing run on Android; pairing off unless `AURACONNECT_PAIRING=true`. The QR image and the mobile client are still the owner/Gemini's to build; until then there is a code to type but nothing to scan.**
