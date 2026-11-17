import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Developer-only values come from git-ignored local.properties (or the environment on CI). They are injected into the debug BuildConfig and
// the private perf BuildConfig only; release builds always get empty strings. Never commit a value; see docs/V2_CREDENTIAL_POLICY.md.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun devValue(name: String): String = (localProps.getProperty(name) ?: System.getenv(name) ?: "").replace("\\", "").replace("\"", "").trim()

// Exact source revision of this build, shown in Settings > About. "-dirty" marks uncommitted changes; "unknown" when git is unavailable.
fun gitOutput(vararg args: String): String? = runCatching {
    val p = ProcessBuilder(listOf("git") + args).directory(rootDir).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    if (p.waitFor() == 0) out else null
}.getOrNull()
val buildSha: String = (gitOutput("rev-parse", "HEAD")?.takeIf { Regex("[0-9a-f]{40}").matches(it) }?.let { sha ->
    if (gitOutput("status", "--porcelain", "--untracked-files=no").isNullOrEmpty()) sha else "$sha-dirty"
} ?: "unknown")

android {
    namespace = "no.aura.os.v2"
    compileSdk = 34
    defaultConfig {
        // Distinct from the legacy app id (no.aura.os.tv): both can be installed side by side. Not a production identifier.
        applicationId = "no.aura.os.tv.v2demo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-v2-demo"
        buildConfigField("String", "BUILD_SHA", "\"$buildSha\"")
        // Diagnostics (AuraDiag counters/timings; fixed stage names only). On for debug and the private perf build, off for release.
        buildConfigField("boolean", "DIAG", "false")
        // AuraConnect pairing (receiving a Deezer sign-in from the phone). OFF unless the owner opts in for a private debug/perf build with
        // AURACONNECT_PAIRING=true in git-ignored local.properties. Release is always off.
        buildConfigField("boolean", "AURACONNECT_PAIRING", "false")
    }
    buildTypes {
        debug {
            buildConfigField("String", "YOUTUBE_API_KEY", "\"${devValue("YOUTUBE_API_KEY")}\"")
            buildConfigField("String", "SIMKL_CLIENT_ID", "\"${devValue("SIMKL_CLIENT_ID")}\"")
            buildConfigField("String", "GOOGLE_TV_OAUTH_CLIENT_ID", "\"${devValue("GOOGLE_TV_OAUTH_CLIENT_ID")}\"")
            buildConfigField("String", "GOOGLE_TV_OAUTH_CLIENT_SECRET", "\"${devValue("GOOGLE_TV_OAUTH_CLIENT_SECRET")}\"")
            // Development read token for the TMDB demo. Debug only; an APK-embedded token is extractable (see docs/V2_F4_TMDB.md).
            buildConfigField("String", "TMDB_READ_TOKEN", "\"${devValue("TMDB_READ_TOKEN")}\"")
            buildConfigField("String", "AURAOS_UPDATE_MANIFEST_URL", "\"${devValue("AURAOS_UPDATE_MANIFEST_URL")}\"")
            buildConfigField("String", "AURAOS_UPDATE_PUBLIC_KEY", "\"${devValue("AURAOS_UPDATE_PUBLIC_KEY")}\"")
            buildConfigField("boolean", "DIAG", "true")
            buildConfigField("boolean", "AURACONNECT_PAIRING", (devValue("AURACONNECT_PAIRING") == "true").toString())
        }
        release {
            buildConfigField("String", "AURAOS_UPDATE_MANIFEST_URL", "\"\"")
            buildConfigField("String", "AURAOS_UPDATE_PUBLIC_KEY", "\"\"")
            buildConfigField("String", "SIMKL_CLIENT_ID", "\"\"")
            buildConfigField("String", "GOOGLE_TV_OAUTH_CLIENT_ID", "\"\"")
            buildConfigField("String", "GOOGLE_TV_OAUTH_CLIENT_SECRET", "\"\"")
            buildConfigField("String", "YOUTUBE_API_KEY", "\"\"")   // release gate: no developer key ever ships
            buildConfigField("String", "TMDB_READ_TOKEN", "\"\"")   // ... and no TMDB token either
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        // PRIVATE performance-test build (F6c): the same R8 / non-debuggable code path as release, so device measurements are meaningful,
        // but with the developer values from git-ignored local.properties so Real Mode works. It is NOT a distribution build: a value
        // compiled into an APK is extractable, so this APK must never leave the owner's devices. The public release type above is unchanged.
        // Same application id and the debug signing key, so it installs over the debug build and keeps profiles, PINs and settings.
        create("perf") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isProfileable = true          // lets the owner capture Perfetto / simpleperf traces without a debuggable build
            isMinifyEnabled = true
            isShrinkResources = false     // demo artwork is resolved by name (getIdentifier); keep every drawable
            buildConfigField("String", "YOUTUBE_API_KEY", "\"${devValue("YOUTUBE_API_KEY")}\"")
            buildConfigField("String", "SIMKL_CLIENT_ID", "\"${devValue("SIMKL_CLIENT_ID")}\"")
            buildConfigField("String", "GOOGLE_TV_OAUTH_CLIENT_ID", "\"${devValue("GOOGLE_TV_OAUTH_CLIENT_ID")}\"")
            buildConfigField("String", "GOOGLE_TV_OAUTH_CLIENT_SECRET", "\"${devValue("GOOGLE_TV_OAUTH_CLIENT_SECRET")}\"")
            buildConfigField("String", "TMDB_READ_TOKEN", "\"${devValue("TMDB_READ_TOKEN")}\"")
            buildConfigField("String", "AURAOS_UPDATE_MANIFEST_URL", "\"${devValue("AURAOS_UPDATE_MANIFEST_URL")}\"")
            buildConfigField("String", "AURAOS_UPDATE_PUBLIC_KEY", "\"${devValue("AURAOS_UPDATE_PUBLIC_KEY")}\"")
            buildConfigField("boolean", "DIAG", "true")
            buildConfigField("boolean", "AURACONNECT_PAIRING", (devValue("AURACONNECT_PAIRING") == "true").toString())
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    // Media3 1.4.1 officially compiles with SDK 34; keep the existing SDK/AGP/Kotlin toolchain.
    // AuraConnect adapter (brings the shared core and BouncyCastle 1.80.2, whose jar hash the core build verifies).
    implementation("no.aura.music:auramusic-engine:1.0.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.zxing:core:3.5.3")
    // Demo Task 01 has no network, no image loader, no player: Demo Mode draws its artwork procedurally.
    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM for unit tests (Android's copy is a stub there); used by the AURA Connect v1 import test.
    testImplementation("org.json:json:20240303")
}
