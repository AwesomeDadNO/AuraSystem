import java.util.Properties

plugins {
    id("com.android.library") version "8.10.1"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

group = "no.aura.os"
version = "1.0.0"

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun devValue(name: String): String = (localProps.getProperty(name) ?: System.getenv(name) ?: "").replace("\\", "").replace("\"", "").trim()

android {
    namespace = "no.aura.os.v2"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "BUILD_SHA", "\"unknown\"")
        buildConfigField("boolean", "DIAG", "false")
        buildConfigField("boolean", "AURACONNECT_PAIRING", "false")
        buildConfigField("String", "YOUTUBE_API_KEY", "\"${devValue("YOUTUBE_API_KEY")}\"")
        buildConfigField("String", "TMDB_READ_TOKEN", "\"${devValue("TMDB_READ_TOKEN")}\"")
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"${devValue("SIMKL_CLIENT_ID")}\"")
        buildConfigField("String", "GOOGLE_TV_OAUTH_CLIENT_ID", "\"${devValue("GOOGLE_TV_OAUTH_CLIENT_ID")}\"")
        buildConfigField("String", "GOOGLE_TV_OAUTH_CLIENT_SECRET", "\"${devValue("GOOGLE_TV_OAUTH_CLIENT_SECRET")}\"")
        buildConfigField("String", "AURAOS_UPDATE_MANIFEST_URL", "\"${devValue("AURAOS_UPDATE_MANIFEST_URL")}\"")
        buildConfigField("String", "AURAOS_UPDATE_PUBLIC_KEY", "\"${devValue("AURAOS_UPDATE_PUBLIC_KEY")}\"")
        buildConfigField("String", "VERSION_NAME", "\"0.1.0-v2-demo\"")
        buildConfigField("String", "APPLICATION_ID", "\"no.aura.os.tv.v2demo\"")
        buildConfigField("int", "VERSION_CODE", "1")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("no.aura.connect:auraconnect-android:3.0.0-ac2")
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
}
