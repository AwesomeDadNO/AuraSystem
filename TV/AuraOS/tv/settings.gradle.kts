pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "AuraOS-v2-TV"
include(":app")
// AuraCortex shared logic and engines (built from source).
includeBuild("../../../AuraCortex/AuraConnect")
includeBuild("../../../AuraCortex/AuraMusicEngine")
includeBuild("../../../AuraCortex/AuraOSEngine")
