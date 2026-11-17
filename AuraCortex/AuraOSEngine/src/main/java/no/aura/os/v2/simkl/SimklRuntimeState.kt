package no.aura.os.v2.simkl

/** In-memory verified pairing state. A stored token alone is not a successful network verification. */
class SimklRuntimeState {
    private val verified = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun verifiedAt(profileId: String): Long? = verified[profileId]
    fun markVerified(profileId: String) { verified[profileId] = System.currentTimeMillis() }
    fun clear(profileId: String) { verified.remove(profileId) }
}
