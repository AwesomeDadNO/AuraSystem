package no.aura.os.v2.launcher

/** Denied and cancelled requests have no special success path: the resolved Home handler remains authority. */
object HomeSetupPolicy {
    /** A successful launch means only that settings/request UI was started, never that the role was granted. */
    fun <T> attempt(candidates: List<T>, launch: (T) -> Unit): Boolean {
        for (candidate in candidates) if (runCatching { launch(candidate) }.isSuccess) return true
        return false
    }
    fun state(ownPackage: String, handler: String?, canRequest: Boolean): HomeSetupState {
        val held = handler == ownPackage
        return HomeSetupState(held, handler?.takeUnless { it == "android" }, canRequest,
            if (held) "AuraOS is the default Home app. Use Android Home settings to change it back."
            else if (canRequest) "Choose AuraOS in Android's Home app settings. Cancelling keeps your current launcher."
            else "This device does not expose Home setup here. Open Android Settings, then Apps, Default apps, Home app. Availability varies by device.")
    }
}
