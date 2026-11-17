pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "AuraOS-v2-TV"
include(":app")
// AuraCortex shared logic and engines (built from source).
// Musikkmotoren TV-appen er bygget mot (no.aura.music:auramusic-engine) ligger i AURA-Ecosystem.
includeBuild("../../../../AURA-Ecosystem/packages/auramusic-engine")
includeBuild("../../../AuraCortex/AuraOSEngine")
