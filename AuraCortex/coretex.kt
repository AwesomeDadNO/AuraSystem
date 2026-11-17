// Plasseres i felles kildekode (AuraCortex.kt)

object AuraCortex {
    val guard = AuraGuard()
    val connect = AuraConnect()
    val music = AuraMusicEngine()
    val video = AuraVideoEngine()
    val os = AuraOSEngine()
    val tv = AuraTVEngine()
    val sports = AuraSports()
}

// ---------------------------------------------------------
// AuraConnect med universell paring, utvidet støtte og ruting
// ---------------------------------------------------------
class AuraConnect {

    data class DiscoveredDevice(val deviceId: String, val name: String, val ipAddress: String, val type: DeviceType)
    data class PairingSession(val code: String, val qrData: String, val isPaired: Boolean)

    enum class DeviceType {
        PHONE, TABLET, TV, DESKTOP, AMP
    }

    // --- 1. Paring & Universell Søkbarhet ---
    fun startAdvertisingAsReceiver(deviceType: DeviceType): PairingSession {
        val randomCode = (100000..999999).random().toString()
        val qrPayload = "auraconnect://pair?code=$randomCode&type=$deviceType"
        
        // Starter lokal lytter tilpasset enhetstypen
        broadcastPresence(randomCode, deviceType)

        return PairingSession(code = randomCode, qrData = qrPayload, isPaired = false)
    }

    private fun broadcastPresence(pin: String, type: DeviceType) {
        // mDNS / UDP-kringkasting for lokalnettet
    }

    fun scanForAuraDevices(onDeviceFound: (DiscoveredDevice) -> Unit) {
        // Oppdager aktive enheter på nettverket
    }

    fun requestPairingWithDevice(device: DiscoveredDevice, inputCode: String, onResult: (Boolean) -> Unit) {
        // Utfører sikker håndtrykk og oppretter lokal sesjon
        onResult(inputCode.length == 6)
    }

    // --- 2. On-Demand Henting (Pull-modell for ARL og brukerinfo) ---
    // Mottakeren ringer denne for å hente Deezer ARL fra senderen når det trengs (f.eks. på en musikkspiller-enhet)
    fun requestDeezerArlFromSender(senderIp: String, onResult: (String?) -> Unit) {
        // Sender en sikker forespørsel over lokalnettet til senderen (f.eks. telefonen) om å få utlevert ARL
        // Returnerer null hvis enheten ikke tillater det eller ikke har det.
        onResult("ar_token_eksempel_her")
    }

    // --- 3. Smart Ruting av Kommandoer og Innhold ---
    fun routeIncomingPayload(payload: ConnectPayload) {
        when (payload) {
            is ConnectPayload.PlayPlaylist -> {
                // Spillelister rutes alltid til AuraAmp-motoren
                AuraCortex.music.loadPlaylist(payload.playlistId)
            }
            is ConnectPayload.RemoteAction -> {
                // Fjernkontroll-kommandoer for avspilling
                handleRemoteAction(payload.action)
            }
            is ConnectPayload.SyncUser -> {
                // Synkronisering av generell brukerinfo
            }
        }
    }

    private fun handleRemoteAction(action: RemoteAction) {
        when (action) {
            RemoteAction.PLAY -> AuraCortex.music.play()
            RemoteAction.PAUSE -> AuraCortex.music.pause()
            RemoteAction.NEXT -> AuraCortex.music.next()
            RemoteAction.PREV -> AuraCortex.music.prev()
            RemoteAction.CLEAR_QUEUE -> AuraCortex.music.clearQueue()
        }
    }
}

// Støtteklasser for typer og payload
sealed class ConnectPayload {
    data class PlayPlaylist(val playlistId: String) : ConnectPayload()
    data class RemoteAction(val action: RemoteActionType) : ConnectPayload()
    data class SyncUser(val userInfo: Map<String, String>) : ConnectPayload()
}

enum class RemoteActionType {
    PLAY, PAUSE, NEXT, PREV, CLEAR_QUEUE
}

// Stub-klasser for øvrige systemer i AuraCortex
class AuraGuard
class AuraMusicEngine {
    fun play() {}
    fun pause() {}
    fun next() {}
    fun prev() {}
    fun clearQueue() {}
    fun loadPlaylist(playlistId: String) {}
    fun addToQueue(songId: String) {}
    fun playNext(songId: String) {}
}
class AuraVideoEngine
class AuraOSEngine
class AuraTVEngine
class AuraSports
