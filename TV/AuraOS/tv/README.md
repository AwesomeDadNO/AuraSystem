# AuraOS v2 — Android TV demo (Demo Task 01: shell + Home)

Fresh Compose for TV project. Application id `no.aura.os.tv.v2demo` (side by side with legacy `no.aura.os.tv`). **Demo Mode only**: fixtures, procedural placeholder artwork, no network, no permissions, no live integrations.

## Build

Requirements: Android Studio (Koala or newer) or JDK 17 + Android SDK (platform 34, build-tools). This project has its **own** Gradle wrapper (Gradle 8.7, compatible with AGP 8.5.2). Do not use the legacy `AuraOS` wrapper for this project.

1. Open the `AuraOS/tv` folder in Android Studio (it creates `local.properties` with your SDK path; that file is git-ignored and must never be committed). Without Studio: set `ANDROID_HOME` or create `local.properties` with `sdk.dir=...` yourself.
2. `./gradlew :app:assembleDebug :app:testDebugUnitTest`
3. The owner installs and tests. Optimised private test build: `./gradlew :app:assemblePerf` (see `../docs/V2_F6C_PERFORMANCE_USABILITY.md`; a perf APK carries developer values and must stay private).

Pinned toolchain: AGP 8.5.2, Kotlin 1.9.24, Compose compiler 1.5.14, Compose BOM 2024.10.01, compile/target SDK 34, min SDK 26, Java 17.

## Gradle wrapper provenance

- `gradle/wrapper/gradle-wrapper.jar` is taken unmodified from the official `gradle/gradle` repository at tag `v8.7.0` (`gradle/wrapper/gradle-wrapper.jar`), SHA-256 `cb0da6751c2b753a16ac168bb354870ebb1e162e9083f116729cec9c781156b8`. `gradlew` and `gradlew.bat` come from the same tag.
- `gradle-wrapper.properties` points to `https://services.gradle.org/distributions/gradle-8.7-bin.zip` (HTTPS, `validateDistributionUrl=true`).
- Not yet done: the jar hash was not compared with the checksum list on gradle.org (that site was unreachable from the authoring environment), and no `distributionSha256Sum` is pinned. Compare the jar hash with https://gradle.org/release-checksums/ and add the 8.7-bin checksum from there before relying on this in CI.

Status of every acceptance gate: `../docs/V2_DEMO_ACCEPTANCE.md`.
