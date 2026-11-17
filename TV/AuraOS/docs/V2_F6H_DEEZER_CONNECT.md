# AuraAmp – simpler Deezer / Eclipse connection (mobile sign-in, transfer to TV)

Status 2026-11-13. This is a cookie-based connection through Eclipse, **not** official Deezer SSO. Part 1 (TV) is implemented in source and JVM-tested. Parts 2 and 3 are designed here and **not implemented**: they need a device test and two owner decisions first.

## Part 1 – TV: honest connection levels and renewal (implemented)

Owner-confirmed behaviour this encodes: an expired ARL still gets a 200 manifest, while search and stream answer 401 ("Invalid or expired Deezer credential"); a fresh ARL gets 200 for manifest, search (tracks) and stream (address). Audio itself was not verified.

| Level | Meaning | How it is established | Shown as |
|---|---|---|---|
| Manifest available | The address answers and advertises streaming. Says nothing about the account. | `manifest.json` 200 + required capabilities | Not connected; "music access not verified" |
| Music access verified | The ARL can search and obtain a stream address | `search?q=music` returns tracks, then `stream/{addon-local id}` (or the hit's own `streamURL`) returns a safe address. No audio is downloaded. | Connected, with the time, and the sentence that playback is not yet confirmed |
| Playback confirmed | Audio played on this TV in this process | The player reached "playing" | Connected, "Playback confirmed" |

- `AmpProvider.verifyAccess()` replaces the manifest-only `verify()` in Save and Verify.
- HTTP meaning (`AmpEclipseProtocol.httpProblem`): **401 = credential rejected** at any stage; 429 = rate limited; 5xx = server; 403 is deliberately *not* treated as a verdict on the credential (a blocked network or region also returns it); transport failures stay "network".
- A rejected ARL shows **"The Deezer connection must be renewed."** in Settings (state Authentication failed), in the Save/Verify sheet and in the player. A rejection during playback overrides an earlier success. Rate limit, server and network errors never ask for renewal.
- Renewal keeps playlists, favourites and history: the ARL vault and the music library are separate stores.
- Replacing or removing the ARL stops a resolution in flight, drops the prepared stream and forgets what the old credential had proved.
- No lifetime is promised and nothing renews automatically.

Tests: `AmpConnectionLevelsTest` (6). Not run against Eclipse from here, and no audio was played.

## Part 2 – Mobile sign-in (designed, NOT implemented)

What the code shows today (`apps/aura-amp/android`): the phone app is one WebView showing the bundled `index.html`, with a JavaScript bridge (`AuraNative`) and an encrypted `AuraCredentialVault` that already stores an ARL per profile.

Design:
1. A **separate activity with its own WebView** for sign-in. It must not be the app's main WebView: that one exposes `AuraNative`, and a remote page must never see a bridge. The sign-in WebView has no `addJavascriptInterface`, no injected script, no form autofill by the app, file and content access off, and navigation limited to `https://*.deezer.com` plus the hosts Deezer's own sign-in needs.
2. After navigation settles on a signed-in Deezer page, native code reads `CookieManager.getCookie("https://www.deezer.com")` and takes only the `arl` value. `CookieManager` returns HttpOnly cookies of the app's own WebView; it cannot read Chrome's or the Deezer app's session.
3. A consent screen first: what is read (one cookie), that it is sent to Eclipse (`eclipse.ultramax.vip`) to obtain music, that it is stored encrypted per Aura profile, and how to disconnect. Nothing is read without that consent.
4. The ARL goes into the existing vault for the chosen profile and is checked with the same three levels as on TV. The WebView's cookies are then cleared.
5. Manual, masked ARL entry stays as the fallback.

**Unknown until tested on a phone (owner test, not assumed):** whether Deezer's sign-in works inside a WebView at all (bot protection / CAPTCHA); whether `arl` is set there; Google sign-in is very likely refused in an embedded WebView (Google blocks them) and must then be reported as unsupported, not worked around; Apple and Facebook unknown. If sign-in is blocked, the app says so and offers manual ARL. iOS needs its own investigation (`WKHTTPCookieStore`); not started.

## Part 3 – Transfer to the TV (designed, NOT implemented)

Requirement: authenticated, encrypted, one-time, time-limited; the ARL never in a QR code, cleartext HTTP, logs or analytics.

What exists:
- The old LAN sync that moved ARLs is being **retired for security reasons** (Draft PRs #181, #182, #188). It must not be reused.
- **AuraConnect AC-1** (Draft PR #183, `packages/auraconnect-core`): J-PAKE pairing + pinned TLS, 48 security tests. It is the right foundation: a short code shown on the TV authenticates the pairing without the code ever being sent, and the channel is then pinned to both devices' keys.
- It is **not ready to carry a credential**: its own report says `gradle check` was never run (dependency resolution was blocked where it was written), the independent security review has not happened, pairing is disabled by default, and no app uses it. The Android adapter both apps need (AC-2: Keystore identity, trust store, discovery, lifecycle) is only a plan. It also adds BouncyCastle as a dependency.

Proposed flow once AC-1 is approved and AC-2 exists:
1. TV: Settings → Connections → Eclipse → "Connect via phone". The TV opens a pairing window of 2 minutes and shows a one-time code (and a QR code that contains only the TV's AuraConnect id and the pairing hint, never the ARL).
2. Phone: scans or types the code, sees the TV's name and chooses the target Aura profile on the TV; confirms.
3. J-PAKE proves both sides know the code; a wrong code fails without revealing it; the code is single-use and expires.
4. Over the pinned TLS channel the phone sends one message: target profile + ARL. The TV requires its Administrator session (as for manual entry), stores the ARL encrypted for that profile, runs the three-level check and answers with the level reached, never with the ARL.
5. Renewal is the same flow; replacing stops playback preparation as in Part 1.

## Part 3a – TV receiver "Connect via phone" (implemented in F6i, source + JVM, off by default)

Branch `auraosv2/f6i-auraconnect-arl-receiver` (stacked on F6h). The owner asked for this to proceed before the independent security review of AuraConnect AC-1; it therefore ships **switched off**: the option only exists in a private debug/perf build whose git-ignored `local.properties` contains `AURACONNECT_PAIRING=true`. Release is always off.

How it works on the TV (Settings → Connections → Eclipse → Connect via phone, Administrator PIN required):
1. The TV starts an AuraConnect receiver bound to its home-network address only, port 47121, and shows that address and a six-digit one-time code (two minutes). No QR code yet.
2. The phone pairs with the code (J-PAKE: the code is never sent; a wrong code fails without a prompt).
3. The TV asks "Allow <phone>?" with the phone's key fingerprint and the target profile. Allowing grants one permission, credential receive, for that one profile.
4. The phone reconnects on the key-pinned TLS channel and sends `credential.offer` `{kind: "deezer-arl", value}`. The TV re-checks that the Administrator session is still open and the same adult profile is active, stops any stream being prepared, stores the ARL encrypted for the profile, runs the three-level Eclipse check and answers with the level only.
5. One sign-in per session. Trust is kept in memory only: closing the sheet, changing profile or leaving the app ends the session, and the next transfer needs a new code and a new approval. Nothing listens outside this flow.

Verified: `ArlReceiveTest` runs the real protocol over loopback TLS between two software identities: correct code + approval delivers once; wrong code, denied prompt and disabled build deliver nothing; invalid values, another profile, a closed Administrator session and a storage failure are refused; a rejected ARL reports "renew"; an earlier pairing cannot be reused in a later session. 523 JVM tests in total, R8 `perf` build succeeds (about +1.2 MB with BouncyCastle).

**Not verified:** anything on Android. Open device questions: the Keystore-backed key in Android's TLS stack, BouncyCastle under R8, binding to the LAN address on the Streamer, and the sheets with a DPAD. There is no phone-side sender yet, so the flow cannot be completed end to end until Part 2 exists.

New dependency in AuraOS: `packages/auraconnect-android` + `-core` (included builds) and BouncyCastle 1.80.2 (jar hash verified by the core build).

## Decisions needed from the owner

1. **AuraConnect gate.** Carrying an ARL over AuraConnect means lifting the standing rule "AuraConnect stays disabled until independently verified". Proposal: (a) run `gradle check` for `packages/auraconnect-core` on the Mac, (b) ChatGPT does the independent security review of #183, (c) then build AC-2 and this transfer. Not before.
2. **Mobile app is outside the AuraOS v2 work area** (`apps/` is treated as read-only reference). Building Part 2 needs your go-ahead to change `apps/aura-amp`, on its own branch and Draft PR, coordinated with the open mobile PRs (#173, #188).
3. **Who tests sign-in on a phone.** Part 2 cannot be validated without a real phone; the WebView result decides whether the feature exists or stays manual.

## Acceptance tests still open (owner, device)

Mobile sign-in or the honest fallback; pairing with a correct, wrong and expired code; profile boundaries; restart; ARL replacement; disconnect; rejected ARL; then real audio on phone and TV with play/pause, seek and next track. Of these, only the TV side of rejected-ARL, replacement and disconnect has code today, and none of it is device-verified.
