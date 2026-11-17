# AuraOS v2 development credential policy

Owner decision (2026-11-11): the supplied TMDB read token and Simkl application credential are **authorized development / internal RC credentials**. The repository is private; new credentials will be issued before any public release. They do not block TMDB or Simkl work.

## Rules
1. **Where they live:** only in git-ignored `AuraOS/tv/local.properties` (keys `TMDB_READ_TOKEN`, `SIMKL_CLIENT_ID`, `YOUTUBE_API_KEY`) or approved CI secrets / environment variables of the same names. Never in Kotlin sources, committed Gradle files, fixtures, tests, test reports, screenshots, commit messages or GitHub comments. `local.properties` is already covered by `AuraOS/tv/.gitignore`.
2. **Injection:** Gradle reads the values and writes them to `BuildConfig` for **debug/internal builds only**. Release builds receive empty strings.
3. **Separation:** debug/internal and public release builds stay separate (different build types; the v2 application id `no.aura.os.tv.v2demo` is kept for testing).
4. **Release gate:** a Gradle check fails any release/public build if a development credential is present or if the build is not explicitly marked as using production credentials. Public distribution requires replacing the development credentials and a production distribution strategy (the token is extractable from any APK).
5. **Logging:** never log complete tokens, Simkl PIN authorization tokens, UltraMax connection codes or Eclipse ARLs, nor URLs that contain them. Errors show status classes, not values.
6. **Scope of the credentials:** the TMDB token is read-only catalogue access, not an account credential. The Simkl value is an application identifier; private user data is reachable only through the supported PIN flow (F9) with a per-profile user token.
7. **Robustness:** every integration implements explicit error states, rate-limit handling (429 / `Retry-After`) and offline behaviour. Demo Mode stays available and needs no credentials.

## Status
Policy recorded. Gradle injection, the release gate and the TMDB/Simkl integrations are **not implemented yet**; they belong to F4 (TMDB), F9 (Simkl) and the F12 security pass, in the approved order. No credential value exists in this repository.


## F3b addition
`YOUTUBE_API_KEY` follows the same rules. `app/build.gradle.kts` reads it from `local.properties`/environment into **debug** `BuildConfig` only; release builds receive `""`. A unit test scans the source tree for key/token patterns and pins the release gate. Restrict the key to the YouTube Data API v3 (and, where possible, to the app) in the Google Cloud console; a client-side key is extractable from a debug APK.


## F6c addition: private `perf` build type
A third build type, `perf`, exists for owner-run performance testing: release-like (R8, not debuggable) but it receives the developer values from git-ignored `local.properties`, exactly like `debug`, so Real Mode works. Rules 1, 5 and 6 apply unchanged. The `release` type still receives empty strings and remains the only type that may ever be distributed. A `perf` APK contains extractable developer values: it is private, stays on the owner's devices and is never attached to a PR, issue, release or message. Nothing about the values is committed; a unit test pins that `release` has no `devValue` and that `perf` is the only additional type that does.
