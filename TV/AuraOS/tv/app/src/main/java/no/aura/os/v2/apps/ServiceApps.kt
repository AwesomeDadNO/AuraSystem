package no.aura.os.v2.apps

/** A streaming app shown on the Services page. Package names are constants here; they never come from untrusted content. */
data class StreamingApp(val name: String, val packages: List<String>)

object ServiceApps {
    /** External streaming apps (installed or not). Declared in the manifest `<queries>` so Android 11+ package visibility allows detection. */
    val streaming = listOf(
        StreamingApp("Netflix", listOf("com.netflix.ninja")),
        StreamingApp("Prime Video", listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod")),
        StreamingApp("Disney+", listOf("com.disney.disneyplus")),
        StreamingApp("Max", listOf("com.wbd.stream", "com.hbo.hbonow")),
    )
    /** Media sources. No integration exists yet, so they are always "Not configured" in Real Mode, whether or not their apps are installed. */
    val mediaSources = listOf("Plex", "Jellyfin", "Emby", "UltraMax")

    val allPackages: Set<String> = streaming.flatMap { it.packages }.toSet()
}
