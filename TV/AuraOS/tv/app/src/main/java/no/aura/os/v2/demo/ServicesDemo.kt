package no.aura.os.v2.demo

/** Illustrative state of an external app or media source on the Services page. Local demo data only: no detection, accounts or network. */
enum class ServiceState(val label: String) {
    INSTALLED("Installed"), NOT_INSTALLED("Not installed"), CONNECTED("Connected"), DISCONNECTED("Disconnected"), UNAVAILABLE("Unavailable"),
    /** Real Mode: a media source whose integration does not exist yet. Never shown as Connected. */
    NOT_CONFIGURED("Not configured"),
}

data class ServiceEntry(val name: String, val state: ServiceState) {
    /** Installed apps toggle installed / not installed, media sources toggle connected / disconnected; unavailable ones never change. */
    fun toggled(): ServiceEntry = copy(state = when (state) {
        ServiceState.INSTALLED -> ServiceState.NOT_INSTALLED
        ServiceState.NOT_INSTALLED -> ServiceState.INSTALLED
        ServiceState.CONNECTED -> ServiceState.DISCONNECTED
        ServiceState.DISCONNECTED -> ServiceState.CONNECTED
        ServiceState.UNAVAILABLE, ServiceState.NOT_CONFIGURED -> state
    })
}

object ServicesDemo {
    /** Streaming applications installed on the device (not AURA subscriptions or payments). */
    val apps = listOf(
        ServiceEntry("Netflix", ServiceState.INSTALLED), ServiceEntry("Prime Video", ServiceState.INSTALLED),
        ServiceEntry("Disney+", ServiceState.NOT_INSTALLED), ServiceEntry("Max", ServiceState.INSTALLED),
    )
    /** Connected media sources (local servers and catalogues). */
    val sources = listOf(
        ServiceEntry("Plex", ServiceState.CONNECTED), ServiceEntry("Jellyfin", ServiceState.DISCONNECTED),
        ServiceEntry("Emby", ServiceState.UNAVAILABLE), ServiceEntry("UltraMax", ServiceState.CONNECTED),
    )
}
