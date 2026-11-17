package no.aura.os.v2.settings

import no.aura.os.v2.admin.AdminDenial
import no.aura.os.v2.admin.AdminGate
import no.aura.os.v2.admin.AdminResult
import no.aura.os.v2.identity.GoogleAuthResult
import no.aura.os.v2.identity.GoogleIdentity
import no.aura.os.v2.identity.GoogleIdentityProvider
import no.aura.os.v2.identity.UnavailableGoogleProvider
import no.aura.os.v2.profiles.*

/** Plain-data description of a modal sheet (no Compose types) so flows are unit-testable. Rendered by overlay/SheetHost. */
sealed interface Sheet {
    data class Choice(val title: String, val options: List<SheetOption>, val note: String? = null) : Sheet
    data class PinEntry(val title: String, val onSubmit: (String) -> Unit) : Sheet
    data class Message(val title: String, val lines: List<String>) : Sheet
    data class AuraConnectSheet(
        val title: String,
        val qrData: String,
        val code: String,
        val host: String,
        val port: Int,
        val activeModules: List<String>,
        val onNewCode: () -> Unit,
        val onPairedDevices: () -> Unit,
        val onDeezer: (() -> Unit)?,
        val onMediaServer: (() -> Unit)?,
    ) : Sheet
    /** Letters/digits code entry (on-screen grid). [onSubmit] receives exactly [length] characters. */
    data class CodeEntry(val title: String, val length: Int, val onSubmit: (String) -> Unit) : Sheet
    /**
     * Free text typed with the TV system keyboard (server address, user name, password). [onSubmit] receives the text exactly as typed
     * (trimmed unless it is a password). A [Keyboard.PASSWORD] entry is masked on screen and is never echoed anywhere else.
     */
    data class TextEntry(
        val title: String, val hint: String = "", val note: String? = null, val keyboard: Keyboard = Keyboard.TEXT,
        val maxLength: Int = 512, val allowEmpty: Boolean = false, val onSubmit: (String) -> Unit,
    ) : Sheet
    enum class Keyboard { TEXT, URL, PASSWORD }
}
data class SheetOption(val label: String, val onPick: () -> Unit)

/** Administrator PIN workflows: first-run setup, unlock, lock and change. PIN verification runs where [Sheet.PinEntry.onSubmit] runs (off the main thread). */
class AdminFlow(private val gate: AdminGate, private val show: (Sheet?) -> Unit, private val changed: () -> Unit) {

    /** Runs [then] once an Administrator session is open: immediately when it already is, otherwise after setup (first run) or unlock. */
    fun ensure(then: () -> Unit) {
        when {
            gate.isCorrupt -> show(Sheet.Message("Administrator PIN unavailable", listOf(denialText(AdminResult.Denied(AdminDenial.STORAGE_CORRUPT)))))
            gate.isAuthorized() -> { gate.touch(); then() }
            !gate.isSetUp -> setUp(then)
            else -> unlock(then)
        }
    }

    /** The "Administrator PIN" settings item. */
    fun manage() {
        if (gate.isCorrupt) return show(Sheet.Message("Administrator PIN unavailable", listOf(denialText(AdminResult.Denied(AdminDenial.STORAGE_CORRUPT)))))
        if (!gate.isSetUp) return setUp(null)
        val unlocked = gate.isAuthorized()
        show(Sheet.Choice("Administrator PIN", listOf(
            if (unlocked) SheetOption("Lock now") { lock(); show(Sheet.Message("Locked", listOf("Protected settings need the Administrator PIN again."))) }
            else SheetOption("Unlock") { unlock(null) },
            SheetOption("Change PIN") { changePin() },
        ), note = "One PIN for the whole device, separate from profile PINs. There is no recovery PIN: if it is forgotten, clear AuraOS data in Android Settings → Apps (this also removes profiles)."))
    }

    fun lock() { gate.revoke(); changed() }

    private fun setUp(then: (() -> Unit)?) = show(Sheet.PinEntry("Create the Administrator PIN (4–6 digits)") { first ->
        show(Sheet.PinEntry("Confirm the Administrator PIN") { second ->
            if (first != second) show(Sheet.Message("PINs don't match", listOf("No PIN was created. Try again.")))
            else when (val r = gate.setUp(first)) {
                AdminResult.Ok -> { changed(); if (then != null) { show(null); then() } else show(Sheet.Message("Done", listOf("Administrator PIN created. Protected settings stay unlocked for a few minutes."))) }
                is AdminResult.Denied -> show(Sheet.Message("Not allowed", listOf(denialText(r))))
            }
        })
    })

    private fun unlock(then: (() -> Unit)?) = show(Sheet.PinEntry("Enter the Administrator PIN") { pin ->
        when (val r = gate.unlock(pin)) {
            AdminResult.Ok -> { changed(); if (then != null) { show(null); then() } else show(Sheet.Message("Unlocked", listOf("Protected settings are unlocked for a few minutes."))) }
            is AdminResult.Denied -> show(Sheet.Message("Not allowed", listOf(denialText(r))))
        }
    })

    private fun changePin() = show(Sheet.PinEntry("Current Administrator PIN") { current ->
        show(Sheet.PinEntry("New Administrator PIN (4–6 digits)") { new ->
            show(Sheet.PinEntry("Confirm the new PIN") { again ->
                if (new != again) show(Sheet.Message("PINs don't match", listOf("The PIN was not changed.")))
                else when (val r = gate.changePin(current, new)) {
                    AdminResult.Ok -> { changed(); show(Sheet.Message("Done", listOf("Administrator PIN changed."))) }
                    is AdminResult.Denied -> show(Sheet.Message("Not allowed", listOf(denialText(r))))
                }
            })
        })
    })

    companion object {
        fun denialText(d: AdminResult.Denied): String = when (d.reason) {
            AdminDenial.NOT_SET_UP -> "The Administrator PIN hasn't been created yet."
            AdminDenial.ALREADY_SET_UP -> "An Administrator PIN already exists. Use Change PIN."
            AdminDenial.INVALID_PIN -> "A PIN needs ${PinPolicy.MIN}–${PinPolicy.MAX} digits."
            AdminDenial.WRONG_PIN -> "Wrong PIN."
            AdminDenial.LOCKED -> "Too many attempts. Try again in ${d.retryInSeconds} seconds."
            AdminDenial.STORAGE_ERROR -> "The Administrator PIN couldn't be saved. Nothing was changed. Try again."
            AdminDenial.STORAGE_CORRUPT -> "The stored Administrator PIN data is unreadable. Clear AuraOS data in Android Settings → Apps to reset (this also removes profiles)."
        }
    }
}

/**
 * The Users settings actions as a sequence of sheets over [ProfileService]. Every action except switching profile first asks for the
 * Administrator PIN ([admin]); the service enforces the same rule on its own. [background] runs Google sign-in off the main thread (tests
 * pass a direct runner). [show] displays a sheet (null closes), [changed] tells the UI that profile or admin state changed.
 */
class UsersFlow(
    private val service: ProfileService, private val modules: ModulePrefsStore, private val admin: AdminFlow,
    private val google: GoogleIdentityProvider = UnavailableGoogleProvider, private val background: (() -> Unit) -> Unit = { it() },
    private val show: (Sheet?) -> Unit, private val changed: () -> Unit,
    private val onProfileDeleted: (String) -> Unit = {},
) {
    fun start(itemId: String) {
        when (itemId) {
            "users.switch" -> chooseProfile("Switch profile", service.state.profiles) { pickSwitch(it) }
            "users.admin" -> admin.manage()
            else -> admin.ensure { run(itemId) }
        }
    }

    private fun run(itemId: String) {
        when (itemId) {
            "users.create" -> chooseKind()
            "users.edit" -> chooseProfile("Edit profile", service.state.profiles) { editProfile(it) }
            "users.delete" -> chooseProfile("Delete profile", service.state.profiles) { confirmDelete(it) }
            "users.pin" -> chooseProfile("Profile PIN", service.state.profiles) { choosePinAction(it) }
            "users.google" -> googleMenu()
            "users.kids" -> {
                val kids = service.state.profiles.filter { it.kind == ProfileKind.CHILD }
                if (kids.isEmpty()) show(Sheet.Message("Child content restrictions", listOf("There are no child profiles yet. Create one under Users → Add user.")))
                else chooseProfile("Child content restrictions", kids) { chooseAge(it) }
            }
        }
    }

    private fun chooseProfile(title: String, profiles: List<Profile>, then: (Profile) -> Unit) =
        show(Sheet.Choice(title, profiles.map { p -> SheetOption(p.label + if (p.pin != null) " 🔒" else "") { then(p) } }))

    private fun pickSwitch(p: Profile) {
        // A profile switch always ends any Administrator session: the next person starts locked.
        if (p.id != service.state.activeId && p.pin != null) show(Sheet.PinEntry("Enter PIN for ${p.name}") { pin -> switched(service.switchTo(p.id, pin), p) })
        else switched(service.switchTo(p.id), p)
    }

    private fun switched(o: Outcome, p: Profile) { if (o is Outcome.Ok) admin.lock(); report(o, "Switched to ${p.name}.") }

    private fun chooseKind() = show(Sheet.Choice("Add user", listOf(
        SheetOption("Standard profile") { report(service.create(service.suggestName(), ProfileKind.STANDARD), "Standard profile created.") },
        SheetOption("Child profile") { chooseAge(null) },
        SheetOption("Import Google Account") { importGoogle() },
    ), note = "Names are generated (Profile 2, Profile 3 …) unless imported from Google; renaming needs text entry and comes later."))

    /** [existing] null = creating a new child profile. */
    private fun chooseAge(existing: Profile?) = show(Sheet.Choice("Allowed content", listOf(7, 12, 16).map { age ->
        SheetOption("Up to age $age") {
            if (existing == null) report(service.create(service.suggestName(), ProfileKind.CHILD, age), "Child profile created (up to age $age).")
            else report(service.setChildAgeLimit(existing.id, age), "${existing.name}: allowed up to age $age.")
        }
    }, note = "Unrated or unknown content is always blocked for child profiles."))

    private fun editProfile(p: Profile) {
        if (p.kind == ProfileKind.CHILD) chooseAge(p)
        else show(Sheet.Message("Edit ${p.name}", listOf("Renaming needs text entry and is not available yet.", "PIN protection is under Users → Profile PIN.")))
    }

    private fun confirmDelete(p: Profile) = show(Sheet.Choice("Delete ${p.name}?", listOf(
        SheetOption("Delete") {
            val id = p.id
            val r = service.delete(id)
            if (r is Outcome.Ok) { modules.clear(id); onProfileDeleted(id) }   // wipe all per-profile local data
            report(r, "${p.name} deleted.")
        },
        SheetOption("Cancel") { show(null) },
    ), note = "This removes the profile and its settings. Other profiles are not affected."))

    private fun choosePinAction(p: Profile) = show(Sheet.Choice("PIN for ${p.name}", listOfNotNull(
        SheetOption(if (p.pin == null) "Set PIN" else "Change PIN") { show(Sheet.PinEntry("New PIN (4–6 digits)") { pin -> report(service.setPin(p.id, pin), "PIN saved.") }) },
        if (p.pin != null) SheetOption("Remove PIN") { report(service.setPin(p.id, null), "PIN removed.") } else null,
    ), note = "A profile PIN only protects entering that profile. It does not unlock system settings."))

    // ---- Google accounts -------------------------------------------------------------------------------------------------------------------

    private fun googleMenu() = show(Sheet.Choice("Google accounts", listOf(
        SheetOption("Link a Google account to a profile") { chooseProfile("Link to which profile?", service.state.profiles.filter { it.googleSubject == null }) { linkTo(it) } },
        SheetOption("Unlink a Google account") {
            val linked = service.state.profiles.filter { it.googleSubject != null }
            if (linked.isEmpty()) show(Sheet.Message("Google accounts", listOf("No profile has a linked Google account."))) else chooseProfile("Unlink from which profile?", linked) { confirmUnlink(it) }
        },
    ), note = "A Google account is only a profile identity. It never grants administrator access."))

    private fun importGoogle() = signIn { id ->
        show(Sheet.Choice("Create a profile for ${id.displayName ?: "this Google account"}?", listOf(
            SheetOption("Create profile") { report(service.importGoogle(id), "Profile created from your Google account.") },
            SheetOption("Cancel") { show(null) },
        ), note = CONSENT_NOTE))
    }

    private fun linkTo(p: Profile) = signIn { id ->
        show(Sheet.Choice("Link ${id.displayName ?: "this Google account"} to ${p.name}?", listOf(
            SheetOption("Link") { report(service.linkGoogle(p.id, id), "Google account linked to ${p.name}.") },
            SheetOption("Cancel") { show(null) },
        ), note = CONSENT_NOTE))
    }

    private fun confirmUnlink(p: Profile) = show(Sheet.Choice("Unlink Google from ${p.name}?", listOf(
        SheetOption("Unlink") { report(service.unlinkGoogle(p.id), "Google account unlinked. ${p.name} keeps its data as a local profile.") },
        SheetOption("Cancel") { show(null) },
    )))

    /** One user-initiated sign-in, off the main thread. Nothing runs until the person picks the action; nothing is retried or polled. */
    private fun signIn(onSuccess: (GoogleIdentity) -> Unit) = background {
        when (val r = runCatching { google.signIn() }.getOrElse { GoogleAuthResult.Error("Sign-in failed.") }) {
            is GoogleAuthResult.Success -> onSuccess(r.identity)
            GoogleAuthResult.Cancelled -> show(Sheet.Message("Cancelled", listOf("No Google account was linked.")))
            is GoogleAuthResult.Unavailable -> show(Sheet.Message("Google sign-in unavailable", listOf(r.reason, "Local profiles work without a Google account.")))
            is GoogleAuthResult.Error -> show(Sheet.Message("Google sign-in failed", listOf(r.message)))
        }
    }

    private fun report(outcome: Outcome, success: String) {
        when (outcome) {
            is Outcome.Ok -> { changed(); show(Sheet.Message("Done", listOf(success))) }
            is Outcome.Denied -> show(Sheet.Message("Not allowed", listOf(denialText(outcome))))
        }
    }

    companion object {
        private const val CONSENT_NOTE = "AuraOS keeps only the account name, picture link and a stable ID — not your e-mail address, password or access tokens."
        fun denialText(d: Outcome.Denied): String = when (d.reason) {
            Denial.ADMIN_PIN_REQUIRED -> "The Administrator PIN is required for this."
            Denial.INVALID_NAME -> "That name isn't valid."
            Denial.DUPLICATE_NAME -> "A profile with that name already exists."
            Denial.LIMIT_REACHED -> "The maximum of ${ProfileState.MAX_PROFILES} profiles has been reached."
            Denial.LAST_ADULT -> "The last non-child profile can't be deleted."
            Denial.NOT_FOUND -> "That profile no longer exists."
            Denial.INVALID_PIN -> "A PIN needs ${PinPolicy.MIN}–${PinPolicy.MAX} digits."
            Denial.INVALID_AGE -> "That age limit isn't valid."
            Denial.PIN_REQUIRED -> "A PIN is required."
            Denial.WRONG_PIN -> "Wrong PIN."
            Denial.LOCKED -> "Too many attempts. Try again in ${d.retryInSeconds} seconds."
            Denial.DUPLICATE_ACCOUNT -> "That Google account is already linked to a profile."
            Denial.NOT_LINKED -> "That profile has no linked Google account."
            Denial.INVALID_IDENTITY -> "Google returned an account AuraOS can't use."
        }
    }
}
