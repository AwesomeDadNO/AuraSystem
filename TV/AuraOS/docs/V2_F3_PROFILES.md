> **Superseded in part by F3b (`V2_F3B_ADMIN_AUTHORIZATION.md`):** the per-profile Admin role described below was replaced by the system-wide Administrator PIN. Profile ids, names, PINs and per-profile isolation are unchanged.

# V2-F3: profiles, isolation and persistent settings

Status: **implemented in source, not built with Gradle, not run on an emulator or device.**

**Stack (parents):** `AuraOSv2` ← #174 (design lock `02a9d17`) ← #175 (F1 `2e66092`) ← #176 (F2 `16d725e`) ← this branch `auraosv2/f3-profiles`. Merge order #174, #175, #176, F3.

## Functions
- `Profile` (stable id, name, kind Admin / Standard / Child, child age limit, optional salted PIN), `ProfileState` (invariants: at least one admin, unique ids, active exists, max 8). Persisted by `PrefsProfileStore` through a dependency-free codec; corrupt data falls back to a single admin profile "Main".
- `ProfileService` enforces the rules (not the UI): only the active admin creates/edits/deletes/sets PINs; the last admin cannot be deleted; deleting the active profile falls back to an admin; switching into a PIN-protected profile needs the PIN, with a 5-attempt / 30 s lockout. PINs are 4–6 digits, hashed with salted PBKDF2-HMAC-SHA256 (60 000 iterations), compared in constant time, never stored or logged.
- `ChildPolicy` / `ContentRatings`: child filtering at the data layer (`ChildPolicy.filter`) for TMDB-style certifications (US movie/TV, Norwegian, numeric). **Unknown or unrated content is blocked for child profiles.** Repositories (F4) must call it before results reach the UI.
- Isolation: `ProfileScope.key(profileId, name)` namespaces every per-profile value. Module switches are now per profile (`ModulePrefsStore.load/save/clear(profileId)`); deleting a profile clears its settings. Watchlist, Continue Watching and service credentials (later tasks) must use the same scope.
- Settings → Users: Switch / Create / Edit / Delete (with confirmation) / PIN / Child restrictions, through `UsersFlow` and the new `SheetHost` (choice lists and a DPAD PIN pad, same look as the existing dialogs). Child profiles cannot change module switches or Demo/Real; standard users keep their own switches but cannot manage profiles. The Profile page's "Switch profile" and "Parental controls" now use the same flows.
- Limits (honest): profile names are generated ("Profile 2" …) because text entry is not built; rename is "coming later". "Watch history" and "Sign out" on the Profile page remain demo dialogs.

## Optimization review (mandatory)
| Check | Finding |
|---|---|
| Main thread | PIN hashing/verification (PBKDF2) is **not** run on the main thread: the PIN pad executes `onSubmit` on `Dispatchers.Default` and blocks re-entry while busy. Measured on the cloud JVM: 82 ms per 60 000-iteration derivation (average of 5); a TV SoC is likely several times slower, hence the offload. Not measured on a device. |
| Concurrency | `ProfileService.state` is `@Volatile`; only one profile operation runs at a time. |
| I/O | SharedPreferences `apply()` (async) for every save; no disk work in composition. No network. |
| Memory | Profile list ≤ 8; no caches added; no retained listeners. |
| Recomposition | Settings rows are derived once per (category, state) with `remember`; stable keys in the lazy list. |
| Requests | 0 network requests. |
| Security | No secrets in code or logs; `PinCredential.toString()` is masked; PIN digits live only in the pad's state. |
| Not measured | Cold/warm start, jank, memory on emulator: pending Gemini's runs. No performance improvement is claimed. |

## Tests
JVM, cloud harness: 95 pass (14 new in `ProfilesTest`): service rules and denials, lockout and recovery, PBKDF2 round trip/salting, codec round trip and corruption, child filtering (unknown blocked, adults unfiltered), per-profile isolation and delete cleanup, `UsersFlow` scenarios, row locking by profile kind.

## Needs the emulator (Gemini)
Create child/standard profiles, set a PIN and switch with right/wrong PIN, delete with confirmation, per-profile module switches after switching, DPAD in the sheets and the PIN pad, focus restored to the Settings row after a sheet closes, no jank when verifying a PIN, state survives an app restart.
