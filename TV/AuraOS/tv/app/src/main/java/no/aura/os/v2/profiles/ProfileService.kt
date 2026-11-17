package no.aura.os.v2.profiles

import no.aura.os.v2.admin.Authorizer
import no.aura.os.v2.admin.NoAuthorizer
import no.aura.os.v2.identity.GoogleIdentity
import java.util.UUID

enum class Denial { ADMIN_PIN_REQUIRED, INVALID_NAME, DUPLICATE_NAME, LIMIT_REACHED, LAST_ADULT, NOT_FOUND, INVALID_PIN, INVALID_AGE, PIN_REQUIRED, WRONG_PIN, LOCKED, DUPLICATE_ACCOUNT, NOT_LINKED, INVALID_IDENTITY }

sealed interface Outcome {
    data class Ok(val state: ProfileState) : Outcome
    data class Denied(val reason: Denial, val retryInSeconds: Long = 0) : Outcome
}

/**
 * Profile rules, enforced here (not by hiding buttons): create/edit/delete/set PINs/link accounts need an open System Administrator session
 * ([authorizer]), whichever profile is active; the last non-child profile cannot be deleted; child restrictions never depend on the session; switching into a PIN-protected profile needs the PIN, with a lockout after repeated failures. Pure Kotlin, injectable clock/hasher/ids.
 */
class ProfileService(
    private val store: ProfileStore, private val hasher: PinHasher = Pbkdf2PinHasher(),
    private val clock: () -> Long = System::currentTimeMillis, private val newId: () -> String = { UUID.randomUUID().toString() },
    private val authorizer: Authorizer = NoAuthorizer,
) {
    /** Written by PIN operations that run off the main thread, hence volatile. Only one profile operation runs at a time (the PIN pad blocks re-entry). */
    @Volatile var state: ProfileState = store.load() ?: ProfileState.initial().also(store::save)
        private set

    private val failures = mutableMapOf<String, Int>()
    private val lockedUntil = mutableMapOf<String, Long>()

    private fun commit(s: ProfileState): Outcome { state = s; store.save(s); return Outcome.Ok(s) }
    private fun requireAdmin(): Outcome.Denied? = if (authorizer.isAuthorized()) { authorizer.touch(); null } else Outcome.Denied(Denial.ADMIN_PIN_REQUIRED)

    fun create(name: String, kind: ProfileKind, maxAge: Int? = null, pin: String? = null): Outcome {
        requireAdmin()?.let { return it }
        validateName(name)?.let { return it }
        if (state.profiles.size >= ProfileState.MAX_PROFILES) return Outcome.Denied(Denial.LIMIT_REACHED)
        if (kind == ProfileKind.CHILD && (maxAge == null || maxAge !in 3..18)) return Outcome.Denied(Denial.INVALID_AGE)
        if (pin != null && !PinPolicy.isValid(pin)) return Outcome.Denied(Denial.INVALID_PIN)
        val p = Profile(newId(), name.trim(), kind, if (kind == ProfileKind.CHILD) maxAge else null, pin?.let(hasher::create))
        return commit(state.copy(profiles = state.profiles + p))
    }

    fun rename(id: String, name: String): Outcome {
        requireAdmin()?.let { return it }
        val p = state.find(id) ?: return Outcome.Denied(Denial.NOT_FOUND)
        validateName(name, except = id)?.let { return it }
        return commit(state.copy(profiles = state.profiles.map { if (it.id == id) p.copy(name = name.trim()) else it }))
    }

    fun setChildAgeLimit(id: String, maxAge: Int): Outcome {
        requireAdmin()?.let { return it }
        val p = state.find(id) ?: return Outcome.Denied(Denial.NOT_FOUND)
        if (p.kind != ProfileKind.CHILD || maxAge !in 3..18) return Outcome.Denied(Denial.INVALID_AGE)
        return commit(state.copy(profiles = state.profiles.map { if (it.id == id) p.copy(maxAge = maxAge) else it }))
    }

    /** [pin] null removes the PIN. */
    fun setPin(id: String, pin: String?): Outcome {
        requireAdmin()?.let { return it }
        val p = state.find(id) ?: return Outcome.Denied(Denial.NOT_FOUND)
        if (pin != null && !PinPolicy.isValid(pin)) return Outcome.Denied(Denial.INVALID_PIN)
        return commit(state.copy(profiles = state.profiles.map { if (it.id == id) p.copy(pin = pin?.let(hasher::create)) else it }))
    }

    fun delete(id: String): Outcome {
        requireAdmin()?.let { return it }
        val p = state.find(id) ?: return Outcome.Denied(Denial.NOT_FOUND)
        if (!p.isChild && state.profiles.count { !it.isChild } == 1) return Outcome.Denied(Denial.LAST_ADULT)
        val rest = state.profiles - p
        val active = if (state.activeId == id) rest.first { !it.isChild }.id else state.activeId
        failures.remove(id); lockedUntil.remove(id)
        return commit(ProfileState(rest, active))
    }

    /** Switching to the already active profile is a no-op. A PIN-protected target needs [pin]. */
    fun switchTo(id: String, pin: String? = null): Outcome {
        val target = state.find(id) ?: return Outcome.Denied(Denial.NOT_FOUND)
        if (id == state.activeId) return Outcome.Ok(state)
        target.pin?.let { cred ->
            val now = clock()
            lockedUntil[id]?.takeIf { it > now }?.let { return Outcome.Denied(Denial.LOCKED, (it - now + 999) / 1000) }
            if (pin == null) return Outcome.Denied(Denial.PIN_REQUIRED)
            if (!hasher.verify(pin, cred)) {
                val n = (failures[id] ?: 0) + 1
                failures[id] = n
                if (n >= MAX_ATTEMPTS) { failures[id] = 0; lockedUntil[id] = now + LOCKOUT_MS; return Outcome.Denied(Denial.LOCKED, LOCKOUT_MS / 1000) }
                return Outcome.Denied(Denial.WRONG_PIN)
            }
            failures.remove(id); lockedUntil.remove(id)
        }
        return commit(state.copy(activeId = id))
    }

    /**
     * Creates a standard profile from a Google identity (display name when usable, otherwise a generated name). One profile per subject.
     * The avatar is stored as a URL only; nothing is downloaded here.
     */
    fun importGoogle(identity: GoogleIdentity): Outcome {
        requireAdmin()?.let { return it }
        if (!identity.isValid) return Outcome.Denied(Denial.INVALID_IDENTITY)
        if (state.profiles.any { it.googleSubject == identity.subject }) return Outcome.Denied(Denial.DUPLICATE_ACCOUNT)
        if (state.profiles.size >= ProfileState.MAX_PROFILES) return Outcome.Denied(Denial.LIMIT_REACHED)
        val p = Profile(newId(), uniqueName(identity.displayName), ProfileKind.STANDARD, googleSubject = identity.subject, avatarUrl = safeAvatar(identity.avatarUrl))
        return commit(state.copy(profiles = state.profiles + p))
    }

    /** Links a Google identity to an existing profile (a local profile stays valid without one). A subject can be linked to one profile only. */
    fun linkGoogle(id: String, identity: GoogleIdentity): Outcome {
        requireAdmin()?.let { return it }
        val p = state.find(id) ?: return Outcome.Denied(Denial.NOT_FOUND)
        if (!identity.isValid) return Outcome.Denied(Denial.INVALID_IDENTITY)
        if (state.profiles.any { it.id != id && it.googleSubject == identity.subject }) return Outcome.Denied(Denial.DUPLICATE_ACCOUNT)
        return commit(state.copy(profiles = state.profiles.map { if (it.id == id) p.copy(googleSubject = identity.subject, avatarUrl = safeAvatar(identity.avatarUrl)) else it }))
    }

    /** Removes the link and the stored avatar; the profile, its name and its data remain. */
    fun unlinkGoogle(id: String): Outcome {
        requireAdmin()?.let { return it }
        val p = state.find(id) ?: return Outcome.Denied(Denial.NOT_FOUND)
        if (p.googleSubject == null) return Outcome.Denied(Denial.NOT_LINKED)
        return commit(state.copy(profiles = state.profiles.map { if (it.id == id) p.copy(googleSubject = null, avatarUrl = null) else it }))
    }

    private fun safeAvatar(url: String?): String? = url?.takeIf { it.startsWith("https://") && it.length <= 512 && it.none { c -> c.isISOControl() || c.isWhitespace() } }

    private fun uniqueName(preferred: String?): String {
        val base = preferred?.trim()?.filter { !it.isISOControl() }?.take(24)?.trim().orEmpty()
        if (base.isEmpty()) return suggestName()
        if (validateName(base) == null) return base
        return generateSequence(2) { it + 1 }.map { "${base.take(20)} $it" }.first { validateName(it) == null }
    }

    private fun validateName(name: String, except: String? = null): Outcome.Denied? {
        val n = name.trim()
        if (n.isEmpty() || n.length > 24 || n.any { it.isISOControl() }) return Outcome.Denied(Denial.INVALID_NAME)
        if (state.profiles.any { it.id != except && it.name.equals(n, ignoreCase = true) }) return Outcome.Denied(Denial.DUPLICATE_NAME)
        return null
    }

    /** First free "Profile N" name, for profile creation without text entry. */
    fun suggestName(): String = generateSequence(2) { it + 1 }.map { "Profile $it" }.first { c -> state.profiles.none { it.name.equals(c, true) } }

    companion object { const val MAX_ATTEMPTS = 5; const val LOCKOUT_MS = 30_000L }
}
