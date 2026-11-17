package no.aura.os.v2

import no.aura.os.v2.admin.*
import no.aura.os.v2.identity.*
import no.aura.os.v2.profiles.*
import no.aura.os.v2.settings.*
import org.junit.Assert.*
import org.junit.Test

class AdminAndIdentityTest {
    private var now = 1_000_000L
    private var boot = "boot-1"
    private var n = 0
    private val adminStore = MemoryAdminPinStore()
    private fun gate(store: AdminPinStore = adminStore) = AdminGate(store, FakeHasher, { now }, { boot }, sessionTtlMs = 60_000L)
    private fun svc(g: Authorizer, store: ProfileStore = MemoryProfileStore()) = ProfileService(store, FakeHasher, { now }, { "id${++n}" }, g)
    private fun denied(o: Outcome) = (o as Outcome.Denied).reason
    private fun adminDenied(r: AdminResult) = (r as AdminResult.Denied)

    // ---- system PIN ---------------------------------------------------------------------------------------------------------------
    @Test fun no_default_pin_and_setup_only_once() {
        val g = gate()
        assertFalse(g.isSetUp); assertFalse(g.isAuthorized())
        listOf("0000", "1234", "1111", "").forEach { assertEquals(AdminDenial.NOT_SET_UP, adminDenied(g.unlock(it)).reason) }     // nothing unlocks before setup
        assertEquals(AdminDenial.INVALID_PIN, adminDenied(g.setUp("12")).reason)
        assertEquals(AdminResult.Ok, g.setUp("2468")); assertTrue(g.isSetUp)
        assertEquals(AdminDenial.ALREADY_SET_UP, adminDenied(g.setUp("1357")).reason)       // setup can never replace an existing PIN
        assertEquals(AdminResult.Ok, g.unlock("2468"))
    }

    @Test fun system_pin_is_independent_of_profile_identity_and_all_profiles_can_unlock() {
        val g = gate(); g.setUp("2468"); g.revoke()
        val s = svc(g); g.unlock("2468")
        s.create("Kid", ProfileKind.CHILD, 7); s.create("Anna", ProfileKind.STANDARD)
        for (p in s.state.profiles) {
            s.switchTo(p.id); g.revoke()
            assertTrue("${p.name} can unlock", g.unlock("2468") == AdminResult.Ok)            // whoever is active
            assertTrue(s.rename(p.id, p.name) is Outcome.Ok)
        }
        // deleting / recreating profiles never touches the credential
        assertTrue(s.delete(s.state.profiles.last().id) is Outcome.Ok)
        assertTrue(gate().unlock("2468") == AdminResult.Ok)
        // the profile data holds no admin credential at all
        val encoded = ProfileCodec.encode(s.state)
        assertFalse(encoded.contains(adminStore.load().credential!!.hash)); assertFalse(encoded.contains("2468"))
    }

    @Test fun profile_pin_does_not_unlock_system_settings() {
        val g = gate(); g.setUp("2468"); g.revoke()
        val s = svc(g); g.unlock("2468"); s.create("Anna", ProfileKind.STANDARD, pin = "1357"); val anna = s.state.profiles.last().id; g.revoke()
        assertTrue(s.switchTo(anna, "1357") is Outcome.Ok)                                  // the profile PIN opens that profile only
        assertFalse(g.isAuthorized())
        assertEquals(AdminDenial.WRONG_PIN, adminDenied(g.unlock("1357")).reason)           // and is not an Administrator PIN
        assertEquals(Denial.ADMIN_PIN_REQUIRED, denied(s.create("X", ProfileKind.STANDARD)))
        assertEquals(Denial.ADMIN_PIN_REQUIRED, denied(s.setPin(anna, null)))
    }

    @Test fun admin_session_expires_extends_on_activity_and_can_be_revoked() {
        val g = gate(); g.setUp("2468")
        assertTrue(g.isAuthorized()); assertTrue(g.secondsLeft() in 1..60)
        now += 50_000; assertTrue(g.isAuthorized()); g.touch()                                // activity extends it
        now += 50_000; assertTrue(g.isAuthorized())
        now += 61_000; assertFalse(g.isAuthorized()); g.touch(); assertFalse(g.isAuthorized())   // expired: touch can't revive it
        assertEquals(AdminResult.Ok, g.unlock("2468")); assertTrue(g.isAuthorized())
        g.revoke(); assertFalse(g.isAuthorized())
        val s = svc(g); assertEquals(Denial.ADMIN_PIN_REQUIRED, denied(s.create("X", ProfileKind.STANDARD)))
        g.unlock("2468"); now += 61_000
        assertEquals(Denial.ADMIN_PIN_REQUIRED, denied(s.create("X", ProfileKind.STANDARD)))   // protected action refused after expiry
    }

    @Test fun a_restart_always_starts_locked() {
        val g = gate(); g.setUp("2468"); assertTrue(g.isAuthorized())
        assertFalse(gate().isAuthorized()); assertTrue(gate().isSetUp)                          // same store, new process
    }

    @Test fun wrong_pins_lock_out_escalate_and_survive_restart() {
        val g = gate(); g.setUp("2468"); g.revoke()
        repeat(4) { assertEquals(AdminDenial.WRONG_PIN, adminDenied(g.unlock("0000")).reason) }
        val locked = adminDenied(g.unlock("0000")); assertEquals(AdminDenial.LOCKED, locked.reason); assertEquals(30L, locked.retryInSeconds)
        assertEquals(AdminDenial.LOCKED, adminDenied(g.unlock("2468")).reason)                  // the right PIN is refused while locked
        assertEquals(AdminDenial.LOCKED, adminDenied(gate().unlock("2468")).reason)             // and a restart does not clear the lockout
        assertTrue(g.lockedForSeconds() > 0)
        now += 31_000
        repeat(4) { g.unlock("0000") }
        assertEquals(60L, adminDenied(g.unlock("0000")).retryInSeconds)                         // second lockout doubles
        assertEquals(900_000L, AdminGate.lockoutMs(20)); assertEquals(480_000L, AdminGate.lockoutMs(4))   // capped at 15 minutes
        now += 61_000
        assertEquals(AdminResult.Ok, g.unlock("2468"))
        repeat(4) { g.unlock("0000") }; assertEquals(AdminResult.Ok, g.unlock("2468"))           // success resets the counter: 4 misses did not lock
    }

    @Test fun change_pin_needs_the_current_pin_and_counts_failures() {
        val g = gate(); g.setUp("2468")
        assertEquals(AdminDenial.WRONG_PIN, adminDenied(g.changePin("1111", "1357")).reason)
        assertEquals(AdminDenial.INVALID_PIN, adminDenied(g.changePin("2468", "12")).reason)
        assertEquals(AdminResult.Ok, g.changePin("2468", "1357")); g.revoke()
        assertEquals(AdminDenial.WRONG_PIN, adminDenied(g.unlock("2468")).reason); assertEquals(AdminResult.Ok, g.unlock("1357"))
        repeat(4) { g.changePin("0000", "9999") }
        assertEquals(AdminDenial.LOCKED, adminDenied(g.changePin("0000", "9999")).reason)       // change attempts feed the same lockout
    }

    @Test fun real_hasher_is_used_by_default_stores_salted_hash_and_never_prints_the_pin() {
        val st = MemoryAdminPinStore(); val g = AdminGate(st, Pbkdf2PinHasher(iterations = 1000), { now }, { "b" }); g.setUp("2468")
        assertTrue(AdminGate.ADMIN_ITERATIONS > 60_000)                                         // admin PIN is hardened beyond profile PINs
        val cred = st.load().credential!!
        assertFalse(cred.hash.contains("2468")); assertEquals("PinCredential(***)", cred.toString())
        assertFalse(st.load().toString().contains(cred.hash))
        g.revoke(); assertEquals(AdminResult.Ok, g.unlock("2468")); assertEquals(AdminDenial.WRONG_PIN, adminDenied(g.unlock("2469")).reason)
    }

    // ---- migration & isolation ----------------------------------------------------------------------------------------------------
    @Test fun f3_profile_data_migrates_without_losing_ids_names_or_pins() {
        val f = '\u001F'; val r = '\u001E'
        val f3 = "kid1$r" + listOf("main", "Main", "ADMIN", "", "", "").joinToString("$f") + r +
            listOf("kid1", "Kid", "CHILD", "7", "ab", "cd").joinToString("$f") + r + listOf("a2", "Anna", "STANDARD", "", "", "").joinToString("$f")
        val s = ProfileCodec.decode(f3)!!
        assertEquals(listOf("main", "kid1", "a2"), s.profiles.map { it.id }); assertEquals(listOf("Main", "Kid", "Anna"), s.profiles.map { it.name })
        assertEquals(ProfileKind.STANDARD, s.find("main")!!.kind); assertEquals(7, s.find("kid1")!!.maxAge); assertEquals(PinCredential("ab", "cd"), s.find("kid1")!!.pin)
        assertEquals("kid1", s.activeId); assertNull(s.find("main")!!.googleSubject)
        assertEquals(s, ProfileCodec.decode(ProfileCodec.encode(s)))                             // re-saved in the F3b format, identical
        // existing adult with no Administrator PIN can complete first-run setup and then manage
        val g = gate(); assertFalse(g.isSetUp)
        val svc = svc(g, MemoryProfileStore(s)); assertEquals(Denial.ADMIN_PIN_REQUIRED, denied(svc.create("X", ProfileKind.STANDARD)))
        assertEquals(AdminResult.Ok, g.setUp("2468")); assertTrue(svc.create("X", ProfileKind.STANDARD) is Outcome.Ok)
        assertEquals(listOf("main", "kid1", "a2"), svc.state.profiles.take(3).map { it.id })
    }

    @Test fun module_prefs_default_for_f3_data_and_survive_a_restart_per_profile() {
        val profiles = MemoryProfileStore(); val modules = MemoryModulePrefsStore(); val g = gate(); g.setUp("2468")
        var s = svc(g, profiles); s.create("Anna", ProfileKind.STANDARD); val anna = s.state.profiles.last().id
        val sw = ModuleSwitches(modules, g)
        val a = (sw.toggle("main", modules.load("main"), "module.AURATUBE") as ModuleToggle.Changed).prefs
        assertFalse(a.auraTube); assertTrue(modules.load(anna).auraTube)                          // per profile
        s = svc(gate(), profiles)                                                               // "restart"
        assertEquals(listOf("main", anna), s.state.profiles.map { it.id }); assertFalse(modules.load("main").auraTube); assertTrue(modules.load(anna).auraTube)
        assertTrue(ModulePrefs().auraTube)                                                      // missing key (F3 data) = enabled
    }

    // ---- access rules -------------------------------------------------------------------------------------------------------------
    @Test fun module_toggles_need_the_session() {
        val g = gate(); g.setUp("2468"); g.revoke(); val m = MemoryModulePrefsStore(); val sw = ModuleSwitches(m, g)
        assertEquals(ModuleToggle.NeedsAdmin, sw.toggle("main", ModulePrefs(), "module.SPORTS")); assertTrue(m.load("main").sports)
        g.unlock("2468"); assertFalse((sw.toggle("main", ModulePrefs(), "module.SPORTS") as ModuleToggle.Changed).prefs.sports)
        assertEquals(ModuleToggle.Unknown, sw.toggle("main", ModulePrefs(), "module.NOPE"))
    }

    @Test fun access_policy_lists_exactly_the_protected_items() {
        val protectedIds = SettingsCatalog.all.filter(AccessPolicy::requiresAdmin).map { it.id }.toSet()
        assertEquals(setOf(SettingsCatalog.DATA_MODE) + ModuleKey.values().map { "module.${it.name}" } +
            setOf("users.create", "users.edit", "users.delete", "users.pin", "users.kids", "users.google"), protectedIds)
        assertFalse("users.switch" in protectedIds); assertFalse("users.admin" in protectedIds)   // switching is open; the PIN item runs its own flow
        assertFalse("general.launcher_status" in protectedIds)                                    // read-only and ordinary values stay open
    }

    @Test fun child_restrictions_survive_a_temporary_admin_session() {
        val g = gate(); g.setUp("2468"); val s = svc(g)
        s.create("Kid", ProfileKind.CHILD, 7); val kid = s.state.profiles.last()
        s.switchTo(kid.id); assertTrue(g.isAuthorized())                                         // an adult unlocked while the child profile is active
        val active = s.state.active
        assertEquals(ProfileKind.CHILD, active.kind); assertEquals(7, active.maxAge)
        assertEquals(listOf("G"), ChildPolicy.filter(active, listOf("G", "R", "TV-MA", "NR", "")) { it })   // still filtered
        assertFalse(ChildPolicy.allows(active, null))
        assertTrue(s.rename(kid.id, "Kiddo") is Outcome.Ok)                                      // management works...
        assertEquals(ProfileKind.CHILD, s.state.find(kid.id)!!.kind); assertEquals(7, s.state.find(kid.id)!!.maxAge)   // ...but never converts the profile
        assertFalse(ChildPolicy.allows(s.state.active, "R"))
        g.revoke(); assertFalse(ChildPolicy.allows(s.state.active, "R"))
    }

    // ---- Google identity ------------------------------------------------------------------------------------------------------------
    private fun id(sub: String, name: String? = "Alex", avatar: String? = "https://lh3.example/a.png") = GoogleIdentity(sub, name, avatar)

    @Test fun import_link_unlink_and_dedupe_by_subject() {
        val g = gate(); g.setUp("2468"); val s = svc(g)
        assertTrue(s.importGoogle(id("sub-1")) is Outcome.Ok)
        val alex = s.state.profiles.last(); assertEquals("Alex", alex.name); assertEquals("sub-1", alex.googleSubject); assertEquals(ProfileKind.STANDARD, alex.kind)
        assertEquals(Denial.DUPLICATE_ACCOUNT, denied(s.importGoogle(id("sub-1", "Other"))))      // no second profile for the same identity
        assertEquals(2, s.state.profiles.size)
        assertTrue(s.importGoogle(id("sub-2")) is Outcome.Ok); assertEquals("Alex 2", s.state.profiles.last().name)   // same display name, still unique
        val local = s.state.profiles.first()                                                      // Main stays a valid local profile
        assertNull(local.googleSubject)
        assertEquals(Denial.DUPLICATE_ACCOUNT, denied(s.linkGoogle(local.id, id("sub-1"))))
        assertTrue(s.linkGoogle(local.id, id("sub-3")) is Outcome.Ok); assertEquals("sub-3", s.state.find(local.id)!!.googleSubject)
        assertTrue(s.unlinkGoogle(local.id) is Outcome.Ok); assertNull(s.state.find(local.id)!!.googleSubject); assertNull(s.state.find(local.id)!!.avatarUrl)
        assertEquals("Main", s.state.find(local.id)!!.name)                                       // unlinking keeps the local profile
        assertEquals(Denial.NOT_LINKED, denied(s.unlinkGoogle(local.id)))
        assertTrue(s.linkGoogle(alex.id, id("sub-1")) is Outcome.Ok)                              // re-linking the same account to its own profile is fine
    }

    @Test fun identity_is_validated_sanitised_and_never_printed() {
        val g = gate(); g.setUp("2468"); val s = svc(g)
        assertEquals(Denial.INVALID_IDENTITY, denied(s.importGoogle(GoogleIdentity(""))))
        assertEquals(Denial.INVALID_IDENTITY, denied(s.importGoogle(GoogleIdentity("has space"))))
        assertEquals(Denial.INVALID_IDENTITY, denied(s.importGoogle(GoogleIdentity("x".repeat(300)))))
        s.importGoogle(GoogleIdentity("s1", "  ", "http://insecure/a.png")); val p = s.state.profiles.last()
        assertEquals("Profile 2", p.name); assertNull(p.avatarUrl)                                // blank name -> generated, non-https avatar dropped
        s.importGoogle(GoogleIdentity("s2", "Very Long Display Name That Exceeds Limits", "https://ok/a b")); assertTrue(s.state.profiles.last().name.length <= 24); assertNull(s.state.profiles.last().avatarUrl)
        assertFalse(GoogleIdentity("secret-subject", "A").toString().contains("secret"))
        // the stored format carries subject and avatar and round-trips
        s.importGoogle(id("s3", "Zed")); assertEquals(s.state, ProfileCodec.decode(ProfileCodec.encode(s.state)))
    }

    private class FakeGoogle(var result: GoogleAuthResult) : GoogleIdentityProvider { var calls = 0; override fun signIn(): GoogleAuthResult { calls++; return result } }

    @Test fun google_flow_success_cancel_unavailable_and_error() {
        val fake = FakeGoogle(GoogleAuthResult.Success(id("sub-9", "Mia")))
        var sheet: Sheet? = null
        val g = gate(); g.setUp("2468"); val service = svc(g)
        val flow = UsersFlow(service, MemoryModulePrefsStore(), AdminFlow(g, { sheet = it }, {}), fake, { it() }, { sheet = it }, {})
        fun pick(label: String) = (sheet as Sheet.Choice).options.first { it.label.startsWith(label) }.onPick()
        flow.start("users.create"); pick("Import Google Account")
        assertTrue((sheet as Sheet.Choice).title.contains("Mia")); assertEquals(1, service.state.profiles.size)     // consent step: nothing created yet
        assertTrue((sheet as Sheet.Choice).note!!.contains("not your e-mail"))
        pick("Cancel"); assertEquals(1, service.state.profiles.size)
        flow.start("users.create"); pick("Import Google Account"); pick("Create profile")
        assertEquals("Mia", service.state.profiles.last().name)
        flow.start("users.create"); pick("Import Google Account"); pick("Create profile")                      // same account again
        assertEquals("That Google account is already linked to a profile.", (sheet as Sheet.Message).lines.single()); assertEquals(2, service.state.profiles.size)
        fake.result = GoogleAuthResult.Cancelled; flow.start("users.create"); pick("Import Google Account")
        assertEquals("Cancelled", (sheet as Sheet.Message).title); assertEquals(2, service.state.profiles.size)
        fake.result = GoogleAuthResult.Unavailable("No Google Play services."); flow.start("users.create"); pick("Import Google Account")
        assertEquals("Google sign-in unavailable", (sheet as Sheet.Message).title); assertTrue((sheet as Sheet.Message).lines.contains("No Google Play services."))
        fake.result = GoogleAuthResult.Error("Network"); flow.start("users.create"); pick("Import Google Account"); assertEquals("Google sign-in failed", (sheet as Sheet.Message).title)
        val throwing = object : GoogleIdentityProvider { override fun signIn(): GoogleAuthResult = throw IllegalStateException("boom") }
        UsersFlow(service, MemoryModulePrefsStore(), AdminFlow(g, { sheet = it }, {}), throwing, { it() }, { sheet = it }, {}).also { it.start("users.create"); pick("Import Google Account") }
        assertEquals("Google sign-in failed", (sheet as Sheet.Message).title)                                     // a crashing provider can't crash the UI
    }

    @Test fun default_provider_is_unavailable_and_link_unlink_menu_works() {
        assertTrue(UnavailableGoogleProvider.signIn() is GoogleAuthResult.Unavailable)
        val g = gate(); g.setUp("2468"); val service = svc(g); var sheet: Sheet? = null
        val fake = FakeGoogle(GoogleAuthResult.Success(id("sub-5", "Lee")))
        val flow = UsersFlow(service, MemoryModulePrefsStore(), AdminFlow(g, { sheet = it }, {}), fake, { it() }, { sheet = it }, {})
        fun pick(label: String) = (sheet as Sheet.Choice).options.first { it.label.startsWith(label) }.onPick()
        flow.start("users.google"); pick("Unlink"); assertEquals("No profile has a linked Google account.", (sheet as Sheet.Message).lines.single())
        flow.start("users.google"); pick("Link"); pick("Main"); pick("Link")
        assertEquals("sub-5", service.state.find("main")!!.googleSubject)
        flow.start("users.google"); pick("Unlink"); pick("Main"); pick("Unlink"); assertNull(service.state.find("main")!!.googleSubject)
        assertEquals(1, fake.calls)                                                                // sign-in ran once, only because the person chose Link
    }
}
