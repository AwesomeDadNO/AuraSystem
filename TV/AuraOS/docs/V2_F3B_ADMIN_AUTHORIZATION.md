# V2-F3b — System Administrator PIN, Google identity, AuraTube, YouTube data layer

Stacked on F3 (`8d85a85`). Supersedes the "Admin = profile role" model of F3. Nothing here is verified on a device: the cloud has no Android SDK. Evidence is JVM unit tests only.

## 1. Authorization model
Two separate credentials:

| | System Administrator PIN | Profile PIN (optional) |
|---|---|---|
| Scope | whole device | one profile |
| Created | first-run setup (first protected action, or Settings → Users → Administrator PIN) | Users → Profile PIN (needs the admin session) |
| Grants | a temporary in-memory session for protected actions | entry into that profile only |
| Never | tied to a profile, Google account or profile kind | grants administrator access |

* `ProfileKind.ADMIN` is gone. F3 data with `ADMIN` decodes as `STANDARD`; ids, names, PINs, order and the active profile are preserved (test: `f3_profile_data_migrates_without_losing_ids_names_or_pins`). The codec writes 8 fields (adds Google subject and avatar URL) and still reads F3's 6.
* Invariant "at least one admin" became "at least one non-child profile" (`LAST_ADULT`).
* `AdminGate` (`admin/`): PBKDF2-HMAC-SHA256, 120 000 iterations (profile PINs 60 000), 16-byte random salt, constant-time compare, PIN 4–6 digits. Session TTL 5 minutes of inactivity, extended by use (`touch`), never persisted, so every restart is locked.
* Lockout: 5 wrong PINs → 30 s, doubling per lockout, capped at 15 min; counter, level and lockout are persisted (synchronous `commit()`), so killing the app does not reset them. Change-PIN attempts feed the same counter. See "Corrections" below for time/reboot/storage rules.
* No default PIN, no bypass. `setUp` works only while no PIN exists, so it can never replace one. **Recovery** is deliberately not in-app: clear AuraOS data in Android Settings → Apps (which also removes the profiles). A real recovery flow (e.g. proof of ownership through a linked account) needs a product decision and is listed under blockers.
* The PIN, its hash and the salt are never logged; `PinCredential.toString()` is masked; `allowBackup=false` already keeps the file out of backups.
* The session ends: when leaving Settings/Profile, on any profile switch, on a profile deletion that changes the active one, on `MainActivity.onStop`, or on expiry.
* Enforcement is in the services (`ProfileService`, `ModuleSwitches`), not only the UI. `ProfileService` defaults to `NoAuthorizer` (deny), so a service built without a gate cannot manage anything.

### Access rules (`AccessPolicy`, pinned by a test)
Need the Administrator PIN: enable/disable modules, Real/Demo mode, add/edit/delete users, Profile PIN, child content restrictions, Google link/unlink/import, (future) external-service configuration. Open: switching profile, Administrator PIN item (own flow), read-only status rows. Child restrictions come from the profile kind and age limit only; an admin session never changes them (`child_restrictions_survive_a_temporary_admin_session`).

First-run behaviour: there is no blocking setup screen. The first protected action starts "Create the Administrator PIN → Confirm" and then continues with the action. Child profiles cannot be created before the PIN exists because creation is itself protected. A device upgraded from F3 simply has no PIN yet and goes through the same flow.

## 2. Google account import / linking
Path: Settings → Users → Add user → Import Google Account; Users → Google accounts → Link / Unlink.
* Stored per profile: stable subject (`sub`), optional display name, optional https avatar URL. Never e-mail, tokens or passwords. One profile per subject (`DUPLICATE_ACCOUNT`); unlinking keeps the local profile and its data; local profiles never need an account.
* Consent step: the sheet names what is kept before anything is created.
* `GoogleIdentityProvider.signIn()` is blocking and runs off the main thread, only after an explicit choice. Results: Success / Cancelled / Unavailable / Error; a throwing provider is contained. Nothing polls, nothing runs while Home renders.
* **This build ships `UnavailableGoogleProvider`.** It says "Google sign-in isn't set up in this build yet" and creates nothing. No fake sign-in exists.

Evaluation of the real implementation (to be confirmed on a Google TV before choosing; I have not run any of these):
1. **OAuth 2.0 for TV / limited-input devices (device flow)** — the documented path for TVs: the TV shows a code, the person approves on a phone, scopes `openid profile` return the stable `sub`, name and picture. Needs an OAuth client in a Google Cloud project owned by the project and a small HTTP client. Works without Google Play services UI on the TV. Best fit.
2. **Credential Manager / Sign in with Google** — needs a web client ID and Google Play services; availability and UI on Google TV must be checked on the Streamer before relying on it.
3. **AccountManager** — accounts are not generally visible to apps (visibility is per-app since Android 8; `GET_ACCOUNTS` is restricted), it returns names rather than a verified stable identity, and there is no consent for profile data. Rejected as the identity source.
Decision needed from the owner: create the OAuth client (TV/limited-input type) so option 1 can be built and tested.

### Corrections after the first Gradle attempt (see commit after `0e2224d`)
* **Build:** `java.util.Properties` was unresolvable in the Kotlin DSL once the Android plugin is applied; the script now has `import java.util.Properties`. Reproduced and verified for the script header with Gradle 8.14.3 offline; the `android {}` block could not be compiled in the cloud (no AGP).
* **Clock:** the gate no longer reads the wall clock. Session and lockout use a monotonic clock (`SystemClock.elapsedRealtime`, which counts deep sleep), so changing the system date/time neither extends a session nor shortens a lockout.
* **Reboot:** a lockout is stored as *duration + monotonic deadline + boot id* (`Settings.Global boot_count`). Same boot: the remaining time is kept (deadline clamped to the stored duration). Different or unknown boot: the lockout restarts in **full** from the new boot, so a reboot never shortens it. Failure counter and escalation level survive restarts and reboots. Expired lockouts are cleared in storage so they are not resurrected after a reboot.
* **`commit()` failures:** `save()` now reports success. A failed setup/PIN change changes nothing and opens no session (`STORAGE_ERROR`); a failed-attempt save locks immediately instead of trusting a counter a restart would forget.
* **Corrupt storage:** half-written, malformed, wrong-typed or implausible data (negative/oversized values, counters without a PIN) yields `STORAGE_CORRUPT`: everything is denied, and it never reads as "no PIN yet", so damaging the file cannot be used to create a new PIN. Recovery is still clearing app data.
* **Session expiry:** unchanged model (5 min inactivity, checked at use, no timers); now on the monotonic clock.
* **Main thread:** `isSetUp`/`isAuthorized`/`lockedForSeconds` are lock-free volatile reads. Before this, `isSetUp` was `@Synchronized` and Settings recomposition could wait for a PBKDF2 verification running on another thread; a test now pins this.
* **Disk writes:** none on unlock/touch/status; one `commit()` per wrong attempt; one after a miss-then-success to clear the counter; none on a clean success.
* Not covered by JVM tests (Android-only): `PrefsAdminPinStore` parsing, `AndroidBootId`, `boot_count` availability on the Streamer.

## 3. AuraTube
* Module `AuraTube` (Settings → Modules), per profile, default on; route between AuraAmp and Services; heading "AURA TUBE" through the shared `AuraBrandLabel` (orange label, blue focus); landing page = the shared `CinematicHero` plus an honest status panel. No fake videos or Connected state.
* Actions: "Open YouTube" (Real Mode: launches `com.google.android.youtube.tv` through the existing `AppsService` allowlist, which needs no new manifest entry because the Leanback `<queries>` already covers it; Demo Mode: explains) and "Connection status".
* `ModuleRegistry` lists every optional module with route, connection and capabilities; AuraTube declares `HOME_SHELF` and `GLOBAL_SEARCH` but both are inactive behind `FeatureFlags` (F4c).
* Settings → Connections gains **YouTube** (after TMDB). A developer key present = still "Not configured — not verified yet".
* Top navigation width: the estimate with all nine items is **1872 of 1920 px** (conservative text-width factor). `NavMetrics.itemPadding` keeps the approved 23 px padding and only shrinks it if a row would leave less than 24 px free. *The emulator must confirm there is no clipping.*

## 4. YouTube Data API v3 — interfaces only
`youtube/`: models (video, channel, playlist, thumbnails, pages), `YouTubeRepository` (search, videos, channel, channelPlaylists, playlist, playlistVideos; all `suspend`), `YouTubeError`/`YtResult`, `NotConfiguredYouTubeRepository`, `QuotaBudget` (10 000 units/day, Pacific-midnight reset, search 100 / list 1, local guard + exhausted-after-Google-error), `TtlCache` (bounded LRU), `CachingYouTubeRepository` (validation, cache, 5-min negative cache for NotFound, quota guard), `SearchQuery` (normalise, 400 ms debounce constant), `PlaybackHandoff` (validated 11-char id → official VIEW intent spec; no stream extraction or proxying). **No HTTP code exists**; F4c adds it.
Key plumbing: `YOUTUBE_API_KEY` is read from git-ignored `AuraOS/tv/local.properties` (or the environment) into **debug** `BuildConfig` only; release hard-codes `""`. A test scans the source tree for key/token patterns and pins the release gate. The key has not been supplied, so nothing was run against YouTube.

## 5. Performance review
| Area | Finding |
|---|---|
| Main thread | PIN verification (PBKDF2) runs in the PIN pad's `Dispatchers.Default`; Google sign-in runs in a `Dispatchers.Default` launch from the shell scope (cancelled with it). JVM timing of one derivation on the cloud JVM, best of 7 including JIT warm-up: ~25 ms (120k) / ~50 ms (60k) — warm-up noise dominates, so treat as "tens of ms"; a TV SoC will be slower. Not measured on a device. |
| Startup | `AdminGate` reads one small SharedPreferences file in `onCreate` (no hashing); `DevConnections` is a pure map. No new startup network or discovery. |
| Recomposition | Connection statuses are read once per resume (`remember(resumeTick)`) instead of on every recomposition (previously they were rebuilt each time). Admin status is read once per `adminTick` change. |
| I/O | Admin record saved with `commit()` only on PIN attempts/changes (off main thread); module/profile saves unchanged (`apply()`). |
| Memory | `TtlCache` ≤ 200 entries; `QuotaBudget` two ints; no image loader added; Google avatar stored as a URL only (not downloaded). |
| Network | 0 requests in this task. Cache + budget guard are in place for F4c. |
| Duplicate requests | Sequential duplicates are cached; concurrent in-flight de-duplication is **not** implemented (note for F4c). |
| DPAD | Nav pill padding unchanged while it fits; PIN pad and sheets reuse the existing DPAD components. |
| Baseline | Cold start ≈1.2 s, warm ≈0.4 s, profile switch ≈40 ms, heap ≈34 MB (Gemini, F3). **Not re-measured**; no improvement or regression is claimed until Gemini re-runs it on this build. |

## 6. Security findings
1. No bypass or default PIN; setup cannot overwrite; wrong-PIN lockout persists across restarts.
2. Authorization is session-based and enforced in services; UI cannot grant it. Fails closed for unknown settings ids and for services built without a gate.
3. A profile PIN, a Google account and the child/standard distinction all have **no** path to administrator rights (tests).
4. Time, reboot, failed-write and corrupt-storage behaviour: see Corrections. A 4–6 digit PIN is brute-forceable offline if someone extracts the preferences file from a rooted device; the 120k-iteration hash and lockout slow online attempts, not offline ones. Hardware-backed storage (Android Keystore wrapping) is a possible later step; the TV is a shared-household device, so this matches the threat model but should be a conscious choice.
5. The session lives in process memory only and ends on stop; a screen left on in Settings can be used for ≤ 5 minutes after the last protected action.
6. Google: no e-mail/tokens stored; identity is not an authorizer; the sign-in provider is a stub until an OAuth client exists.
7. YouTube key: build-time, debug only, not in source; a client-side key on a TV app is extractable from a debug APK, so it must be API-restricted in the Google Cloud console and never shipped in release.

## 7. Tests (JVM, cloud harness): 140 pass (95 before F3b; +13 hardening tests, mutation-checked)
Admin gate (no default, setup once, session expiry/extension/revoke, restart locks, lockout + doubling + persistence, change PIN, real hasher), independence from profiles, profile PIN ≠ system PIN, child restrictions after admin session, F3 migration, per-profile module state after restart, access-policy list, module switches, Google import/link/unlink/dedupe/validation, flow for success/cancel/unavailable/error/throwing provider, first-run setup flow, AuraTube registry/nav/brand/inactive contracts, honest connection state, YouTube validation/cache/quota/negative cache/LRU, playback handoff, secret scan and release gate.

## 8. Needs the emulator (Gemini)
First-run PIN creation and confirmation by DPAD; unlock then toggle a module; wrong PIN ×5 lockout message; session expiry after 5 min and on leaving Settings; profile switch locks; add user → child/standard/Google (shows "unavailable"); AuraTube page layout, focus (Open YouTube / Connection status, Left → rail, Up → nav), no clipping of the nav at 1920×1080 with all nine items; AuraTube toggle persists per profile across restart; Real Mode "Open YouTube" with and without the YouTube app installed; F3 → F3b upgrade on a device that already has profiles; performance re-measurement.

## 9. Remaining blockers / decisions
* `YOUTUBE_API_KEY` not supplied (no real calls possible until F4c).
* Google OAuth client (TV/limited-input) must be created before real Google sign-in can be built.
* Admin PIN recovery policy (currently: clear app data).
* Everything above marked "needs the emulator".
