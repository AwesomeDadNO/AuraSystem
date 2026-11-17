package no.aura.os.v2.simkl

import androidx.lifecycle.ViewModel

/** Configuration-change retention only. Device grants stay in memory, never plain saved state/disk. */
class SimklPairingMemory : ViewModel() {
    data class Pending(val profileId: String, val pin: SimklPin)
    var pending: Pending? = null
}
