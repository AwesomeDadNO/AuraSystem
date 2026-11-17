package no.aura.os.v2.profiles

/** There is no per-profile admin role: system authorization is the separate Administrator PIN (see admin/AdminGate). */
enum class ProfileKind(val label: String) { STANDARD("Standard"), CHILD("Child") }

/** Salted PIN hash (never the PIN). Both values are hex strings. */
data class PinCredential(val salt: String, val hash: String) {
    override fun toString() = "PinCredential(***)"
}

/** A stable profile. [id] never changes (it namespaces all per-profile data); [maxAge] is required for child profiles. */
data class Profile(
    val id: String, val name: String, val kind: ProfileKind, val maxAge: Int? = null, val pin: PinCredential? = null,
    val googleSubject: String? = null, val avatarUrl: String? = null,
) {
    init { require(kind != ProfileKind.CHILD || maxAge != null) { "Child profiles need an age limit" } }
    val isChild get() = kind == ProfileKind.CHILD
    val label get() = if (isChild) "$name (Child)" else name
}

/** All profiles plus the active one. Invariants: at least one non-child profile, active exists, unique ids, at most [MAX_PROFILES]. */
data class ProfileState(val profiles: List<Profile>, val activeId: String) {
    init {
        require(profiles.isNotEmpty() && profiles.size <= MAX_PROFILES)
        require(profiles.map { it.id }.toSet().size == profiles.size)
        require(profiles.any { !it.isChild }) { "At least one non-child profile is required" }
        require(profiles.any { it.id == activeId }) { "Active profile must exist" }
    }
    val active: Profile get() = profiles.first { it.id == activeId }
    fun find(id: String) = profiles.firstOrNull { it.id == id }
    companion object {
        const val MAX_PROFILES = 8
        const val DEFAULT_ID = "main"
        fun initial() = ProfileState(listOf(Profile(DEFAULT_ID, "Main", ProfileKind.STANDARD)), DEFAULT_ID)
    }
}

/** Namespacing for every per-profile value (preferences, watchlist, continue watching, service credentials). No value is ever read across profiles. */
object ProfileScope {
    fun key(profileId: String, name: String) = "p.$profileId.$name"
}

interface ProfileStore { fun load(): ProfileState?; fun save(state: ProfileState) }
class MemoryProfileStore(private var state: ProfileState? = null) : ProfileStore {
    override fun load() = state
    override fun save(state: ProfileState) { this.state = state }
}
