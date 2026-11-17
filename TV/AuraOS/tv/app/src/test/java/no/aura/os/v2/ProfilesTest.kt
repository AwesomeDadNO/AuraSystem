package no.aura.os.v2

import no.aura.os.v2.admin.*
import no.aura.os.v2.profiles.*
import no.aura.os.v2.settings.*
import org.junit.Assert.*
import org.junit.Test

class ProfilesTest {
    private var now = 1_000_000L
    private var n = 0
    private val auth = FakeAuth()
    private fun svc(store: ProfileStore = MemoryProfileStore()) = ProfileService(store, FakeHasher, { now }, { "id${++n}" }, auth)
    private fun denied(o: Outcome) = (o as Outcome.Denied).reason

    @Test fun starts_with_one_profile_and_persists() {
        val store = MemoryProfileStore(); val s = svc(store)
        assertEquals(listOf("Main"), s.state.profiles.map { it.name }); assertFalse(s.state.active.isChild)
        s.create("Anna", ProfileKind.STANDARD)
        assertEquals(2, svc(store).state.profiles.size)         // reloaded from the store
    }

    @Test fun create_validates_name_limits_and_child_age() {
        val s = svc()
        assertEquals(Denial.INVALID_NAME, denied(s.create("  ", ProfileKind.STANDARD)))
        assertEquals(Denial.INVALID_NAME, denied(s.create("x".repeat(25), ProfileKind.STANDARD)))
        assertEquals(Denial.DUPLICATE_NAME, denied(s.create("main", ProfileKind.STANDARD)))      // case-insensitive
        assertEquals(Denial.INVALID_AGE, denied(s.create("Kid", ProfileKind.CHILD)))
        assertEquals(Denial.INVALID_AGE, denied(s.create("Kid", ProfileKind.CHILD, 40)))
        assertEquals(Denial.INVALID_PIN, denied(s.create("Pin", ProfileKind.STANDARD, pin = "12")))
        repeat(ProfileState.MAX_PROFILES - 1) { assertTrue(s.create("P$it", ProfileKind.STANDARD) is Outcome.Ok) }
        assertEquals(Denial.LIMIT_REACHED, denied(s.create("Extra", ProfileKind.STANDARD)))
    }

    @Test fun management_needs_an_open_admin_session_whichever_profile_is_active() {
        val s = svc()
        s.create("Kid", ProfileKind.CHILD, 7); s.create("Anna", ProfileKind.STANDARD)
        val kid = s.state.profiles.first { it.name == "Kid" }.id; val anna = s.state.profiles.first { it.name == "Anna" }.id
        auth.on = false
        // no session: denied for the Main profile, for a standard profile and for a child profile alike
        listOf("main", anna, kid).forEach { id ->
            s.switchTo(id)
            listOf(s.create("X", ProfileKind.STANDARD), s.rename(anna, "Z"), s.delete(anna), s.setPin(anna, "1234"), s.setChildAgeLimit(kid, 16), s.importGoogle(no.aura.os.v2.identity.GoogleIdentity("g1")), s.unlinkGoogle(anna))
                .forEach { assertEquals(Denial.ADMIN_PIN_REQUIRED, denied(it)) }
        }
        // with a session any profile may manage (the PIN is what matters), and nothing about the profile kinds changed
        auth.on = true; s.switchTo(anna)
        assertTrue(s.create("Ben", ProfileKind.STANDARD) is Outcome.Ok); assertTrue(s.delete(kid) is Outcome.Ok)
    }

    @Test fun last_non_child_profile_cannot_be_deleted_and_deleting_the_active_profile_falls_back_to_an_adult() {
        val s = svc()
        assertEquals(Denial.LAST_ADULT, denied(s.delete("main")))
        s.create("Anna", ProfileKind.STANDARD); val anna = s.state.profiles.last().id
        assertTrue(s.delete(anna) is Outcome.Ok); assertEquals("main", s.state.activeId)
        assertEquals(Denial.NOT_FOUND, denied(s.delete("nope")))
    }

    @Test fun pin_required_to_switch_with_lockout_and_recovery() {
        val s = svc(); s.create("Anna", ProfileKind.STANDARD); val anna = s.state.profiles.last().id
        s.setPin(anna, "1234")
        assertEquals(Denial.PIN_REQUIRED, denied(s.switchTo(anna)))
        assertEquals(Denial.WRONG_PIN, denied(s.switchTo(anna, "0000")))
        assertEquals("main", s.state.activeId)
        repeat(3) { s.switchTo(anna, "1111") }
        val locked = s.switchTo(anna, "1111") as Outcome.Denied            // 5th failure
        assertEquals(Denial.LOCKED, locked.reason); assertTrue(locked.retryInSeconds > 0)
        assertEquals(Denial.LOCKED, denied(s.switchTo(anna, "1234")))        // even the right PIN is refused while locked
        now += ProfileService.LOCKOUT_MS + 1
        assertTrue(s.switchTo(anna, "1234") is Outcome.Ok); assertEquals(anna, s.state.activeId)
    }

    @Test fun pin_validation_removal_and_real_hasher() {
        val s = svc(); s.create("Anna", ProfileKind.STANDARD); val anna = s.state.profiles.last().id
        assertEquals(Denial.INVALID_PIN, denied(s.setPin(anna, "12a4"))); assertEquals(Denial.INVALID_PIN, denied(s.setPin(anna, "1234567")))
        s.setPin(anna, "1234"); assertNotNull(s.state.find(anna)!!.pin); s.setPin(anna, null); assertNull(s.state.find(anna)!!.pin)
        assertTrue(s.switchTo(anna) is Outcome.Ok)                           // no PIN any more
        val h = Pbkdf2PinHasher(iterations = 1000); val c = h.create("4821")
        assertTrue(h.verify("4821", c)); assertFalse(h.verify("4822", c)); assertNotEquals(c, h.create("4821"))   // salted
        assertFalse(c.hash.contains("4821")); assertFalse(c.toString().contains(c.hash))
        assertFalse(h.verify("4821", PinCredential("zz", "zz")))
    }

    @Test fun codec_round_trips_and_rejects_corrupt_data() {
        val s = svc(); s.create("Kid", ProfileKind.CHILD, 12); s.create("Anna", ProfileKind.STANDARD, pin = "1234")
        assertEquals(s.state, ProfileCodec.decode(ProfileCodec.encode(s.state)))
        listOf(null, "", "garbage", "main\u001Eonly-one-field", "ghost\u001Emain\u001FMain\u001FSTANDARD\u001F\u001F\u001F").forEach { assertNull(it, ProfileCodec.decode(it)) }
        assertEquals("Main", svc(MemoryProfileStore()).state.profiles.single().name)
    }

    @Test fun child_policy_filters_at_the_data_layer_and_blocks_unknown() {
        val kid = Profile("k", "Kid", ProfileKind.CHILD, maxAge = 12); val adult = Profile("a", "A", ProfileKind.STANDARD)
        assertEquals(listOf("G", "PG", "TV-Y7"), ChildPolicy.filter(kid, listOf("G", "PG", "PG-13", "R", "TV-MA", "TV-Y7", "NR", "", " ", "weird")) { it })
        assertEquals(10, ChildPolicy.filter(adult, listOf("G", "PG", "PG-13", "R", "TV-MA", "TV-Y7", "NR", "", " ", "weird")) { it }.size)   // adults unfiltered
        assertFalse(ChildPolicy.allows(kid, null)); assertFalse(ChildPolicy.allows(kid, "NC-17"))
        assertTrue(ChildPolicy.allows(kid, " pg ")); assertTrue(ChildPolicy.allows(kid, "12")); assertFalse(ChildPolicy.allows(kid, "15"))
        assertThrows(IllegalArgumentException::class.java) { Profile("x", "x", ProfileKind.CHILD) }
    }

    @Test fun module_prefs_are_isolated_per_profile_and_cleared_on_delete() {
        val store = MemoryModulePrefsStore(); val s = svc()
        s.create("Anna", ProfileKind.STANDARD); val anna = s.state.profiles.last().id
        store.save("main", ModulePrefs(auraAmp = false)); store.save(anna, ModulePrefs(sports = false))
        assertFalse(store.load("main").auraAmp); assertTrue(store.load("main").sports)
        assertTrue(store.load(anna).auraAmp); assertFalse(store.load(anna).sports)
        store.clear(anna); assertEquals(ModulePrefs(), store.load(anna)); assertFalse(store.load("main").auraAmp)
        assertNotEquals(ProfileScope.key("a", "watchlist"), ProfileScope.key("b", "watchlist"))
    }

    // ---- UsersFlow ----------------------------------------------------------------------------------------------------------------
    private class Harness(val google: no.aura.os.v2.identity.GoogleIdentityProvider = no.aura.os.v2.identity.UnavailableGoogleProvider) {
        var n = 0; var now = 0L
        val gate = AdminGate(MemoryAdminPinStore(), FakeHasher, { now }, { "b" }, 60_000L).also { it.setUp("2468") }   // session open, as after first-run setup
        val service = ProfileService(MemoryProfileStore(), FakeHasher, { now }, { "id${++n}" }, gate)
        val modules = MemoryModulePrefsStore()
        var sheet: Sheet? = null; var changes = 0
        val admin = AdminFlow(gate, { sheet = it }, { changes++ })
        val flow = UsersFlow(service, modules, admin, google, { it() }, { sheet = it }, { changes++ })
        fun choice() = sheet as Sheet.Choice
        fun pick(label: String) = choice().options.first { it.label.startsWith(label) }.onPick()
        fun message() = (sheet as Sheet.Message).lines.joinToString(" ")
    }

    @Test fun flow_create_child_and_standard() {
        val h = Harness()
        h.flow.start("users.create"); h.pick("Child"); h.pick("Up to age 7")
        assertEquals("Child profile created (up to age 7).", h.message())
        val kid = h.service.state.profiles.last(); assertEquals(ProfileKind.CHILD, kid.kind); assertEquals(7, kid.maxAge); assertEquals("Profile 2", kid.name)
        h.flow.start("users.create"); h.pick("Standard"); assertEquals("Profile 3", h.service.state.profiles.last().name)
        assertEquals(2, h.changes)
        h.flow.start("users.create"); assertEquals(listOf("Standard profile", "Child profile", "Import Google Account"), h.choice().options.map { it.label })
    }

    @Test fun flow_switch_with_pin_wrong_pin_message_and_switch_ends_the_admin_session() {
        val h = Harness()
        h.service.create("Anna", ProfileKind.STANDARD, pin = "1234"); val anna = h.service.state.profiles.last()
        h.flow.start("users.switch"); h.pick("Anna")
        val pin = h.sheet as Sheet.PinEntry
        pin.onSubmit("0000"); assertEquals("Wrong PIN.", h.message()); assertEquals("main", h.service.state.activeId); assertTrue(h.gate.isAuthorized())
        h.flow.start("users.switch"); h.pick("Anna"); (h.sheet as Sheet.PinEntry).onSubmit("1234")
        assertEquals(anna.id, h.service.state.activeId); assertFalse(h.gate.isAuthorized())      // the next person starts locked
    }

    @Test fun flow_delete_confirms_and_clears_that_profiles_settings() {
        val h = Harness()
        h.service.create("Anna", ProfileKind.STANDARD); val anna = h.service.state.profiles.last()
        h.modules.save(anna.id, ModulePrefs(sports = false)); h.modules.save("main", ModulePrefs(liveTv = false))
        h.flow.start("users.delete"); h.pick("Anna")
        assertTrue(h.choice().title.startsWith("Delete")); h.pick("Cancel")
        assertNotNull(h.service.state.find(anna.id))                         // cancel changes nothing
        h.flow.start("users.delete"); h.pick("Anna"); h.pick("Delete")
        assertNull(h.service.state.find(anna.id)); assertEquals(ModulePrefs(), h.modules.load(anna.id)); assertFalse(h.modules.load("main").liveTv)
        h.flow.start("users.delete"); h.pick("Main"); h.pick("Delete")
        assertEquals("The last non-child profile can't be deleted.", h.message())
    }

    @Test fun flow_pin_and_kids_and_edit() {
        val h = Harness()
        h.service.create("Kid", ProfileKind.CHILD, 7); val kid = h.service.state.profiles.last()
        h.flow.start("users.pin"); h.pick("Kid"); h.pick("Set PIN"); (h.sheet as Sheet.PinEntry).onSubmit("1234")
        assertNotNull(h.service.state.find(kid.id)!!.pin)
        h.flow.start("users.pin"); h.pick("Kid"); h.pick("Remove PIN"); assertNull(h.service.state.find(kid.id)!!.pin)
        h.flow.start("users.kids"); h.pick("Kid"); h.pick("Up to age 16"); assertEquals(16, h.service.state.find(kid.id)!!.maxAge)
        val noKids = Harness(); noKids.flow.start("users.kids"); assertTrue(noKids.message().contains("no child profiles"))
        h.flow.start("users.edit"); h.pick("Kid"); assertTrue(h.choice().title == "Allowed content")
    }

    @Test fun protected_flows_ask_for_the_admin_pin_first_and_run_after_it_is_entered() {
        val h = Harness(); h.gate.revoke()
        h.flow.start("users.create")
        assertEquals("Enter the Administrator PIN", (h.sheet as Sheet.PinEntry).title)
        (h.sheet as Sheet.PinEntry).onSubmit("0000"); assertEquals("Wrong PIN.", h.message()); assertEquals(1, h.service.state.profiles.size)
        h.flow.start("users.create"); (h.sheet as Sheet.PinEntry).onSubmit("2468")
        assertEquals("Add user", h.choice().title)                                    // the requested action continues after unlock
        assertEquals("users.switch", "users.switch").also { h.gate.revoke() }
        h.flow.start("users.switch"); assertEquals("Switch profile", h.choice().title)   // switching never asks for the system PIN
    }

    @Test fun first_run_setup_flow_creates_confirms_and_continues() {
        val gate = AdminGate(MemoryAdminPinStore(), FakeHasher, { 0L })
        var sheet: Sheet? = null
        val service = ProfileService(MemoryProfileStore(), FakeHasher, { 0L }, { "i" }, gate)
        val flow = UsersFlow(service, MemoryModulePrefsStore(), AdminFlow(gate, { sheet = it }, {}), show = { sheet = it }, changed = {})
        flow.start("users.create")
        assertTrue((sheet as Sheet.PinEntry).title.startsWith("Create the Administrator PIN"))
        (sheet as Sheet.PinEntry).onSubmit("2468"); (sheet as Sheet.PinEntry).onSubmit("1111")
        assertEquals("PINs don't match", (sheet as Sheet.Message).title); assertFalse(gate.isSetUp)
        flow.start("users.create"); (sheet as Sheet.PinEntry).onSubmit("2468"); (sheet as Sheet.PinEntry).onSubmit("2468")
        assertTrue(gate.isSetUp); assertEquals("Add user", (sheet as Sheet.Choice).title)
    }

    @Test fun settings_rows_are_selectable_for_every_profile_and_admin_state_is_shown() {
        val s = svc(); s.create("Kid", ProfileKind.CHILD, 7); s.create("Anna", ProfileKind.STANDARD)
        fun rows(cat: SettingsCategory, st: AdminStatus = AdminStatus.LOCKED) = SettingsPresenter.rows(cat, SettingsState(ModulePrefs(), no.aura.os.v2.data.DataMode.DEMO, true, LauncherStatus(null, null), DeviceInfo("1", 1, "m", "14", 34), NotConfiguredConnections.statuses(), s.state, st))
        for (who in listOf("main", s.state.profiles.first { it.name == "Anna" }.id, s.state.profiles.first { it.name == "Kid" }.id)) {
            s.switchTo(who)
            assertTrue(rows(SettingsCategory.USERS).filter { it.item.id != "users.active" }.all { it.enabled })
            assertTrue(rows(SettingsCategory.MODULES).filter { it.item.kind == ItemKind.TOGGLE }.all { it.enabled })     // selectable: asks for the PIN when used
        }
        assertEquals("Not set up", rows(SettingsCategory.USERS, AdminStatus.NOT_SET_UP).first { it.item.id == "users.admin" }.value)
        assertEquals("Locked", rows(SettingsCategory.USERS).first { it.item.id == "users.admin" }.value)
        assertEquals("Unlocked", rows(SettingsCategory.USERS, AdminStatus.UNLOCKED).first { it.item.id == "users.admin" }.value)
    }
}
