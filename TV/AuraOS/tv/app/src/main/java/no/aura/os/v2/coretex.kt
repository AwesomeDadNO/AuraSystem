package no.aura.os.v2

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
// AuraConnect med paring, synk og fjernkontroll-logikk
// ---------------------------------------------------------
class AuraConnect {

    // --- 1. Paring & Sikkerhet ---
    data class PairingSession(val code: String, val qrData: String, val isPaired: Boolean)

    fun generatePairingCode(): PairingSession {
        val randomCode = (100000..999999).random().toString()
        // Her genereres f.eks. data for QR-koden som inneholder enheten sin lokale IP og sesjonsnøkkel
        val qrPayload = "auraconnect://pair?code=$randomCode&device=AuraAmp"
        return PairingSession(code = randomCode, qrData = qrPayload, isPaired = false)
    }

    fun verifyAndPair(inputCode: String, expectedCode: String): Boolean {
        // Sjekker koden og godkjenner paring via AuraGuard hvis den stemmer
        return inputCode == expectedCode
    }

    // --- 2. Sender-data (Deezer ARL, Brukerinfo) ---
    fun syncUserData(deezerArl: String, userInfo: Map<String, String>) {
        // Sender over ARL-token og brukerprofil til mottakeren over sikker lokal tilkobling
    }

    // --- 3. AuraPlay (AirPlay / Cast-funksjonalitet) ---
    fun streamAudioStream(streamUrl: String) {
        // Starter lokal strømming av lyd fra enhet til enhet
    }

    // --- 4. Fjernkontroll for AuraAmp (Kø og avspilling) ---
    fun sendRemoteCommand(command: RemoteCommand) {
        when (command) {
            is RemoteCommand.Play -> AuraCortex.music.play()
            is RemoteCommand.Pause -> AuraCortex.music.pause()
            is RemoteCommand.Next -> AuraCortex.music.next()
            is RemoteCommand.Prev -> AuraCortex.music.prev()
            is RemoteCommand.ClearQueue -> AuraCortex.music.clearQueue()
            is RemoteCommand.AddToQueue -> AuraCortex.music.addToQueue(command.songId)
            is RemoteCommand.PlayNext -> AuraCortex.music.playNext(command.songId)
        }
    }
}

// Støtteklasser for fjernkontroll-kommandoer
sealed class RemoteCommand {
    object Play : RemoteCommand()
    object Pause : RemoteCommand()
    object Next : RemoteCommand()
    object Prev : RemoteCommand()
    object ClearQueue : RemoteCommand()
    data class AddToQueue(val songId: String) : RemoteCommand()
    data class PlayNext(val songId: String) : RemoteCommand()
}

// Stub-klasser for øvrige systemer i AuraCortex inntil de implementeres
class AuraGuard
class AuraMusicEngine {
    fun play() {}
    fun pause() {}
    fun next() {}
    fun prev() {}
    fun clearQueue() {}
    fun addToQueue(songId: String) {}
    fun playNext(songId: String) {}
}
class AuraVideoEngine
class AuraOSEngine
class AuraTVEngine
class AuraSports
