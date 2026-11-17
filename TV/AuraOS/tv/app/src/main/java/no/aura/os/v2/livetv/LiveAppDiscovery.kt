package no.aura.os.v2.livetv

import no.aura.os.v2.apps.LaunchableApp

/** Live-capable app candidates, not channel inventories, subscriptions or promised programme deep links. */
object LiveAppDiscovery {
    val providers = linkedMapOf(
        "TV 2 Play" to setOf("no.tv2.sumo", "no.tv2.tv2play"),
        "NRK TV" to setOf("no.nrk.tv", "no.nrk.tvhybrid"),
        "Altibox" to setOf("no.altibox.tv"),
        "RiksTV" to setOf("no.rikstv.app"),
        "Telia Play" to setOf("no.get.play.tv"),
        "Allente" to setOf("allente.b2c.tvclient"),
        "Viaplay" to setOf("com.viaplay.android"),
        "Strim" to setOf("com.strimmobile"),
        "TOD" to setOf("com.todtv.tod"),
        "discovery+" to setOf("com.discovery.dplay"),
        "Pluto TV" to setOf("tv.pluto.android"),
        "YouTube TV" to setOf("com.google.android.youtube.tvunplugged"),
        "Plex" to setOf("com.plexapp.android"),
        "Emby" to setOf("tv.emby.embyatv", "com.mb.android"),
    )
    fun pick(installed: List<LaunchableApp>, child: Boolean = false): List<LaunchableApp> {
        if (child) return emptyList()
        val byPackage = installed.associateBy { it.packageName }
        return providers.values.mapNotNull { ids -> ids.firstNotNullOfOrNull { byPackage[it] } }
    }
    fun appFor(provider: String, installed: List<LaunchableApp>): LaunchableApp? =
        providers[provider]?.let { ids -> installed.firstOrNull { it.packageName in ids } }
}

data class LiveProgrammePreview(val id: String, val title: String, val provider: String, val time: String, val art: String)
/** Fixed, explicitly illustrative data. Not current listings, fetched EPG or proof of an airing/right. */
object LiveSchedulePreview {
    val programmes = listOf(
        LiveProgrammePreview("dance", "Skal vi danse", "TV 2 Play", "20:00", "romance"),
        LiveProgrammePreview("news", "Dagsrevyen", "NRK TV", "19:00", "documentary"),
        LiveProgrammePreview("sport", "Sportskveld", "Viaplay", "21:00", "action"),
    )
}
