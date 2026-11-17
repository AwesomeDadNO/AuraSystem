package no.aura.os.v2.identity

/**
 * What AuraOS keeps of a Google account: the stable provider subject (the OpenID `sub`, never the e-mail address), an optional display name
 * and an optional https avatar URL. No e-mail, no tokens, no passwords. A Google identity is a profile label, never proof of administrator rights.
 */
class GoogleIdentity(val subject: String, val displayName: String? = null, val avatarUrl: String? = null) {
    val isValid: Boolean get() = subject.length in 1..255 && subject.all { it.code in 0x21..0x7E }
    override fun toString() = "GoogleIdentity(***)"
}

sealed interface GoogleAuthResult {
    data class Success(val identity: GoogleIdentity) : GoogleAuthResult
    /** The person dismissed the picker or consent screen. Nothing changes. */
    object Cancelled : GoogleAuthResult
    /** No supported sign-in path exists on this device or in this build (e.g. no OAuth client configured, no Google Play services). */
    data class Unavailable(val reason: String) : GoogleAuthResult
    data class Error(val message: String) : GoogleAuthResult
}

/**
 * On-demand, user-initiated Google sign-in with consent. [signIn] blocks until the person finishes, so callers run it off the main thread and
 * only from an explicit Settings action: it is never called while rendering Home and nothing polls for accounts.
 */
interface GoogleIdentityProvider { fun signIn(): GoogleAuthResult }

/**
 * Default provider. A supported TV sign-in path (OAuth 2.0 device flow or Credential Manager) needs an OAuth client that the owner has not
 * created yet, so this build reports Unavailable instead of faking a sign-in.
 */
object UnavailableGoogleProvider : GoogleIdentityProvider {
    override fun signIn() = GoogleAuthResult.Unavailable("Google sign-in isn't set up in this build yet.")
}
