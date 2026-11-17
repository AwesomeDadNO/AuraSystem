package no.aura.os.v2

import kotlinx.coroutines.runBlocking
import no.aura.os.v2.mediaserver.*
import no.aura.os.v2.settings.ConnectionId
import no.aura.os.v2.settings.ConnectionState
import org.junit.Assert.*
import org.junit.Test

/** Records every request and answers from a route table ("METHOD url-prefix" -> response). Unknown routes answer 404. */
private class FakeMediaHttp(val routes: MutableMap<String, MediaResponse> = mutableMapOf()) : MediaHttp {
    val sent = mutableListOf<MediaRequest>()
    override suspend fun send(request: MediaRequest): MediaResponse {
        sent.add(request)
        return routes.entries.filter { (k, _) -> "${request.method} ${request.url}".startsWith(k) }.maxByOrNull { it.key.length }?.value ?: MediaResponse(404, null)
    }
    fun tokenRecipients(token: String) = sent.filter { r -> r.headers.values.any { token in it } || token in r.url || (r.jsonBody?.contains(token) == true) }.map { it.url }
}

/** F6h part A foundation: address policy, sign-in flows, identity pinning, token confinement, encrypted-store codec, honest connection states. */
class MediaServerTest {
    private val identity = ClientIdentity("0f8fad5b-d9cb-469f-a165-70867728950e", "0.1.0")
    private val sid = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private val uid = "0123456789abcdef0123456789abcdef"
    private val tok = "tokenTOKENtoken0123456789abcdef"
    private fun addr(s: String, lan: Boolean = false) = (ServerAddress.parse(s, lan) as ServerAddress.Parse.Ok).address
    private val info = ServerInfo(sid, "Home", "10.9.0", "Jellyfin Server")
    private val publicInfo = MediaResponse(200, """{"Id":"$sid","ServerName":"Home","Version":"10.9.0","ProductName":"Jellyfin Server"}""")
    private val authBody = MediaResponse(200, """{"User":{"Id":"$uid","Name":"Arne"},"AccessToken":"$tok","ServerId":"$sid"}""")

    // ---- address policy ------------------------------------------------------------------------------------------------------------------------
    @Test fun server_address_policy() {
        assertEquals("https://media.example.com", addr("media.example.com").base)                       // https is the default
        assertEquals("https://media.example.com:8920/jf", addr("https://media.example.com:8920/jf/").base)
        fun rejected(s: String, lan: Boolean = true) = assertTrue(s, ServerAddress.parse(s, lan) is ServerAddress.Parse.Rejected)
        rejected("https://user:pw@media.example.com"); rejected("https://media.example.com/?api_key=x"); rejected("https://media.example.com/#x")
        rejected("ftp://media.example.com"); rejected("https://localhost:8096"); rejected("http://127.0.0.1:8096"); rejected("https://[::1]:8096")
        rejected("http://169.254.10.10:8096"); rejected("https://0.0.0.0"); rejected(""); rejected("https://a b.example"); rejected("https://media.example.com/a/../b")
        rejected("http://media.example.com")                                                           // plain http to a public host: never
        rejected("http://172.32.0.5:8096")                                                             // not a private range
        // plain http on the home network: only after explicit approval
        assertTrue(ServerAddress.parse("http://192.168.1.20:8096", lanHttpApproved = false) is ServerAddress.Parse.NeedsLanHttpApproval)
        assertTrue(ServerAddress.parse("http://nas.local:8096", lanHttpApproved = false) is ServerAddress.Parse.Rejected)
        val lan = addr("http://192.168.1.20:8096", lan = true); assertFalse(lan.https); assertTrue(lan.lan); assertEquals("http://192.168.1.20:8096", lan.origin)
        assertTrue(addr("http://172.16.4.2:8096", lan = true).lan); assertTrue(ServerAddress.parse("http://nas:8096", true) is ServerAddress.Parse.Rejected)
        assertEquals("https://media.example.com:443", addr("https://media.example.com/emby").origin)
    }

    @Test fun lan_http_rejects_numeric_aliases_and_unresolved_names() {
        listOf("2130706433", "134744072", "0x7f000001", "0177.0.0.1", "nas", "nas.local", "nas.lan").forEach { host ->
            assertTrue(host, ServerAddress.parse("http://$host:8096", true) is ServerAddress.Parse.Rejected)
        }
    }

    // ---- Jellyfin ------------------------------------------------------------------------------------------------------------------------------
    @Test fun jellyfin_quick_connect_flow_and_headers() = runBlocking {
        val a = addr("https://jf.example.com")
        val http = FakeMediaHttp(mutableMapOf(
            "GET https://jf.example.com/System/Info/Public" to publicInfo, "GET https://jf.example.com/QuickConnect/Enabled" to MediaResponse(200, "true"),
            "POST https://jf.example.com/QuickConnect/Initiate" to MediaResponse(200, """{"Code":"123456","Secret":"abcdef0123456789abcdef0123456789"}"""),
            "GET https://jf.example.com/QuickConnect/Connect" to MediaResponse(200, """{"Authenticated":false}"""),
            "POST https://jf.example.com/Users/AuthenticateWithQuickConnect" to authBody))
        val c = JellyfinEmbyClient(ServerKind.JELLYFIN, http, identity)
        assertEquals(info, (c.publicInfo(a) as MediaResult.Ok).value)
        assertTrue((c.quickConnectEnabled(a) as MediaResult.Ok).value)
        val qc = (c.quickConnectStart(a) as MediaResult.Ok).value
        assertEquals("123456", qc.code); assertFalse(qc.toString().contains("abcdef0123456789"))
        assertFalse((c.quickConnectApproved(a, qc) as MediaResult.Ok).value)                              // still waiting: nothing is "connected"
        http.routes["GET https://jf.example.com/QuickConnect/Connect"] = MediaResponse(200, """{"Authenticated":true}""")
        assertTrue((c.quickConnectApproved(a, qc) as MediaResult.Ok).value)
        val acc = (c.quickConnectFinish(a, false, info, qc) as MediaResult.Ok).value
        assertEquals(ServerKind.JELLYFIN, acc.kind); assertEquals(sid, acc.serverId); assertEquals(uid, acc.userId); assertEquals("Arne", acc.userName)
        assertFalse("a token is never printed", acc.toString().contains(tok))
        val h = http.sent.first().headers["Authorization"]!!
        assertTrue(h.startsWith("MediaBrowser Client=\"AuraOS\"")); assertTrue(h.contains("DeviceId=\"${identity.deviceId}\"")); assertFalse(h.contains("Token="))
    }
    @Test fun jellyfin_quick_connect_disabled_and_expired() = runBlocking {
        val a = addr("https://jf.example.com")
        val c = JellyfinEmbyClient(ServerKind.JELLYFIN, FakeMediaHttp(mutableMapOf("GET https://jf.example.com/QuickConnect/Enabled" to MediaResponse(200, "false"),
            "POST https://jf.example.com/QuickConnect/Initiate" to MediaResponse(401, null), "GET https://jf.example.com/QuickConnect/Initiate" to MediaResponse(401, null))), identity)
        assertFalse((c.quickConnectEnabled(a) as MediaResult.Ok).value)
        assertEquals(MediaError.QUICK_CONNECT_DISABLED, (c.quickConnectStart(a) as MediaResult.Failure).error)
        assertEquals(MediaError.EXPIRED, (c.quickConnectApproved(a, QuickConnect("1", "abcdef0123456789abcdef0123456789")) as MediaResult.Failure).error)
        // Emby has no Quick Connect
        assertFalse((JellyfinEmbyClient(ServerKind.EMBY, FakeMediaHttp(), identity).quickConnectEnabled(a) as MediaResult.Ok).value)
    }
    @Test fun manual_sign_in_failures_and_identity_pinning() = runBlocking {
        val a = addr("https://jf.example.com")
        val http = FakeMediaHttp(mutableMapOf("POST https://jf.example.com/Users/AuthenticateByName" to MediaResponse(401, null)))
        val c = JellyfinEmbyClient(ServerKind.JELLYFIN, http, identity)
        assertEquals(MediaError.AUTH_FAILED, (c.signIn(a, false, info, "arne", "wrong") as MediaResult.Failure).error)
        // a sign-in answered by a DIFFERENT server id than the one we looked at is refused
        http.routes["POST https://jf.example.com/Users/AuthenticateByName"] = MediaResponse(200, """{"User":{"Id":"$uid","Name":"Arne"},"AccessToken":"$tok","ServerId":"ffffffffffffffffffffffffffffffff"}""")
        assertEquals(MediaError.IDENTITY_MISMATCH, (c.signIn(a, false, info, "arne", "pw") as MediaResult.Failure).error)
        http.routes["POST https://jf.example.com/Users/AuthenticateByName"] = MediaResponse(200, """{"User":{"Id":"$uid"},"AccessToken":"x"}""")
        assertEquals(MediaError.BAD_RESPONSE, (c.signIn(a, false, info, "arne", "pw") as MediaResult.Failure).error)                 // implausible token
        assertEquals(MediaError.AUTH_FAILED, (c.signIn(a, false, info, "", "pw") as MediaResult.Failure).error)
        assertEquals(MediaError.UNREACHABLE, (JellyfinEmbyClient(ServerKind.JELLYFIN, FakeMediaHttp(mutableMapOf("GET https://jf.example.com/System/Info/Public" to MediaResponse(0, null))), identity).publicInfo(a) as MediaResult.Failure).error)
        assertEquals(MediaError.BAD_RESPONSE, (JellyfinEmbyClient(ServerKind.JELLYFIN, FakeMediaHttp(mutableMapOf("GET https://jf.example.com/System/Info/Public" to MediaResponse(302, null))), identity).publicInfo(a) as MediaResult.Failure).error)
    }
    @Test fun verify_checks_the_server_before_it_ever_sends_the_token() = runBlocking {
        val acc = ServerAccount(ServerKind.JELLYFIN, sid, "Home", "https://jf.example.com", false, uid, "Arne", tok, identity.deviceId)
        // another machine now answers at the saved address: it must not receive the token
        val other = FakeMediaHttp(mutableMapOf("GET https://jf.example.com/System/Info/Public" to MediaResponse(200, """{"Id":"ffffffffffffffffffffffffffffffff","ServerName":"Evil"}""")))
        assertEquals(MediaError.IDENTITY_MISMATCH, (JellyfinEmbyClient(ServerKind.JELLYFIN, other, identity).verify(acc) as MediaResult.Failure).error)
        assertTrue(other.tokenRecipients(tok).isEmpty())
        val ok = FakeMediaHttp(mutableMapOf("GET https://jf.example.com/System/Info/Public" to publicInfo, "GET https://jf.example.com/Users/Me" to MediaResponse(200, """{"Id":"$uid","Name":"Arne"}""")))
        assertTrue(JellyfinEmbyClient(ServerKind.JELLYFIN, ok, identity).verify(acc) is MediaResult.Ok)
        assertTrue(ok.tokenRecipients(tok).all { it.startsWith("https://jf.example.com/") })
        // the token is valid but belongs to another user -> not this account
        val wrongUser = FakeMediaHttp(mutableMapOf("GET https://jf.example.com/System/Info/Public" to publicInfo, "GET https://jf.example.com/Users/Me" to MediaResponse(200, """{"Id":"ffffffffffffffffffffffffffffffff"}""")))
        assertEquals(MediaError.IDENTITY_MISMATCH, (JellyfinEmbyClient(ServerKind.JELLYFIN, wrongUser, identity).verify(acc) as MediaResult.Failure).error)
        val revoked = FakeMediaHttp(mutableMapOf("GET https://jf.example.com/System/Info/Public" to publicInfo, "GET https://jf.example.com/Users/Me" to MediaResponse(401, null)))
        assertEquals(MediaError.AUTH_FAILED, (JellyfinEmbyClient(ServerKind.JELLYFIN, revoked, identity).verify(acc) as MediaResult.Failure).error)
    }

    // ---- Emby ----------------------------------------------------------------------------------------------------------------------------------
    @Test fun emby_uses_its_own_headers_libraries_items_and_resume() = runBlocking {
        val base = "http://192.168.1.20:8096"
        val http = FakeMediaHttp(mutableMapOf(
            "POST $base/Users/AuthenticateByName" to authBody, "GET $base/System/Info/Public" to publicInfo, "GET $base/Users/$uid" to MediaResponse(200, """{"Id":"$uid"}"""),
            "GET $base/Users/$uid/Views" to MediaResponse(200, """{"Items":[{"Id":"1111aaaa2222bbbb","Name":"Films","CollectionType":"movies"},{"Id":"3333cccc4444dddd","Name":"Series","CollectionType":"tvshows"},{"Id":"x","Name":"bad id"}]}"""),
            "GET $base/Users/$uid/Items?" to MediaResponse(200, """{"Items":[{"Id":"aaaa1111bbbb2222","Name":"Heat","Type":"Movie","ProductionYear":1995,"RunTimeTicks":102000000000,"ImageTags":{"Primary":"abcdef1234567890"},"UserData":{"PlaybackPositionTicks":6000000000,"Played":false}},{"Name":"no id"}],"TotalRecordCount":412}"""),
            "GET $base/Users/$uid/Items/Resume" to MediaResponse(200, """{"Items":[{"Id":"cccc1111dddd2222","Name":"Pilot","Type":"Episode","SeriesName":"Dark","UserData":{"PlaybackPositionTicks":12000000000}}]}"""),
            "POST $base/Sessions/Logout" to MediaResponse(204, null)))
        val c = JellyfinEmbyClient(ServerKind.EMBY, http, identity)
        val acc = (c.signIn(addr(base, lan = true), true, info, "arne", "pw") as MediaResult.Ok).value
        assertTrue(acc.lanHttpApproved); assertEquals(base, acc.base)
        val signIn = http.sent.last(); assertNotNull(signIn.headers["X-Emby-Authorization"]); assertNull(signIn.headers["Authorization"]); assertTrue(signIn.jsonBody!!.contains("\"Pw\":\"pw\""))
        assertFalse("requests are never printed with their credentials", signIn.toString().contains("pw\""))
        assertTrue(c.verify(acc) is MediaResult.Ok)
        assertEquals(tok, http.sent.last().headers["X-Emby-Token"])
        val libs = (c.libraries(acc) as MediaResult.Ok).value
        assertEquals(listOf(LibraryType.MOVIES, LibraryType.SHOWS), libs.map { it.type }); assertEquals(2, libs.size)
        val page = (c.items(acc, libs[0], start = 60) as MediaResult.Ok).value
        assertEquals(412, page.total); assertEquals(1, page.items.size)
        val heat = page.items[0]; assertEquals(EntryType.MOVIE, heat.type); assertEquals(1995, heat.year); assertEquals(10_200_000L, heat.durationMs); assertEquals(600_000L, heat.resumeMs)
        assertTrue(heat.imagePath!!.startsWith("/Items/aaaa1111bbbb2222/Images/Primary")); assertFalse(heat.imagePath!!.contains(tok))
        assertTrue(http.sent.last().url.contains("StartIndex=60")); assertTrue(http.sent.last().url.contains("IncludeItemTypes=Movie&"))
        val resume = (c.resume(acc) as MediaResult.Ok).value
        assertEquals("Dark", resume[0].seriesTitle); assertEquals(1_200_000L, resume[0].resumeMs)
        assertTrue(c.signOut(acc) is MediaResult.Ok)
        assertTrue("the token only ever goes to its own server", http.tokenRecipients(tok).all { it.startsWith("$base/") })
        // a stored account whose plain-http approval was withdrawn is not contacted at all
        val before = http.sent.size
        assertEquals(MediaError.INSECURE_ADDRESS, (c.libraries(acc.copy(lanHttpApproved = false)) as MediaResult.Failure).error); assertEquals(before, http.sent.size)
        assertEquals(1_000L, JellyfinEmbyClient.ticksToMs(10_000_000.0)); assertNull(JellyfinEmbyClient.ticksToMs(-1.0)); assertNull(JellyfinEmbyClient.ticksToMs(Double.NaN))
    }

    // ---- Plex ----------------------------------------------------------------------------------------------------------------------------------
    private val machine = "0123456789abcdef0123456789abcdef01234567"
    private val accountToken = "ACCOUNTtokenACCOUNT1234"
    private val serverToken = "SERVERtokenSERVER567890"
    private fun resources(vararg conns: String) = MediaResponse(200, """[{"name":"NAS","clientIdentifier":"$machine","provides":"server","owned":true,"accessToken":"$serverToken","connections":[${conns.joinToString(",")}]},
        {"name":"Phone","clientIdentifier":"aaaaaaaaaaaaaaaa","provides":"client,player","accessToken":"$serverToken","connections":[{"uri":"https://x.example","local":false,"relay":false}]}]""")
    private val direct = """{"uri":"https://192-168-1-20.abc.plex.direct:32400","local":true,"relay":false}"""
    private val relay = """{"uri":"https://relay.plex.direct:8443","local":false,"relay":true}"""
    private val lanHttp = """{"uri":"http://192.168.1.20:32400","local":true,"relay":false}"""
    private val identityOk = MediaResponse(200, """{"MediaContainer":{"machineIdentifier":"$machine"}}""")
    private val sections = MediaResponse(200, """{"MediaContainer":{"Directory":[{"key":"1","title":"Movies","type":"movie"},{"key":"2","title":"TV","type":"show"},{"key":"../x","title":"bad"}]}}""")

    @Test fun plex_pin_link_flow() = runBlocking {
        var now = 1_000_000L
        val http = FakeMediaHttp(mutableMapOf("POST https://plex.tv/api/v2/pins" to MediaResponse(201, """{"id":4711,"code":"AB3D","expiresIn":900,"authToken":null}"""),
            "GET https://plex.tv/api/v2/pins/4711" to MediaResponse(200, """{"id":4711,"code":"AB3D","authToken":null}""")))
        val c = PlexClient(http, identity) { now }
        val pin = (c.createPin() as MediaResult.Ok).value
        assertEquals("AB3D", pin.code); assertEquals("https://plex.tv/link", PlexPin.LINK_URL)
        assertEquals(identity.deviceId, http.sent.last().headers["X-Plex-Client-Identifier"]); assertNull(http.sent.last().headers["X-Plex-Token"])
        assertEquals(MediaError.PENDING, (c.checkPin(pin) as MediaResult.Failure).error)
        http.routes["GET https://plex.tv/api/v2/pins/4711"] = MediaResponse(200, """{"id":4711,"authToken":"$accountToken"}""")
        http.routes["GET https://plex.tv/api/v2/user"] = MediaResponse(200, """{"id":9,"uuid":"abcdef0123456789","username":"arne","friendlyName":"Arne"}""")
        val user = (c.checkPin(pin) as MediaResult.Ok).value
        assertEquals("Arne", user.name); assertEquals("abcdef0123456789", user.id); assertFalse(user.toString().contains(accountToken))
        now += 901_000; assertEquals(MediaError.EXPIRED, (c.checkPin(pin) as MediaResult.Failure).error)
        assertEquals(MediaError.AUTH_FAILED, (PlexClient(FakeMediaHttp(mutableMapOf("GET https://plex.tv/api/v2/user" to MediaResponse(401, null))), identity).user(accountToken) as MediaResult.Failure).error)
    }
    @Test fun plex_discovery_connects_only_to_the_listed_machine_and_keeps_tokens_apart() = runBlocking {
        val user = PlexUser("abcdef0123456789", "Arne", accountToken)
        val http = FakeMediaHttp(mutableMapOf("GET https://clients.plex.tv/api/v2/resources" to resources(lanHttp, relay, direct),
            "GET https://192-168-1-20.abc.plex.direct:32400/identity" to identityOk, "GET https://192-168-1-20.abc.plex.direct:32400/library/sections" to sections,
            "GET https://192-168-1-20.abc.plex.direct:32400/library/sections/1/all" to MediaResponse(200, """{"MediaContainer":{"totalSize":2,"Metadata":[{"ratingKey":"55","title":"Heat","type":"movie","year":1995,"duration":10200000,"viewOffset":600000,"thumb":"/library/metadata/55/thumb/1700000000"},{"ratingKey":"56","title":"Ronin","type":"movie","thumb":"https://evil.example/x.jpg"}]}}"""),
            "GET https://192-168-1-20.abc.plex.direct:32400/library/onDeck" to MediaResponse(200, """{"MediaContainer":{"Metadata":[{"ratingKey":"77","title":"Pilot","type":"episode","grandparentTitle":"Dark","viewOffset":120000}]}}""")))
        val c = PlexClient(http, identity)
        val servers = (c.servers(user) as MediaResult.Ok).value
        assertEquals(1, servers.size); assertEquals("NAS", servers[0].name); assertEquals(3, servers[0].connections.size); assertFalse(servers[0].toString().contains(serverToken))
        // order: secure direct first, secure relay second, plain http last and only when approved
        assertEquals(listOf(true, true), c.candidates(servers[0], allowLanHttp = false).map { it.second.https })
        assertEquals(3, c.candidates(servers[0], allowLanHttp = true).size); assertFalse(c.candidates(servers[0], true).last().second.https)
        val acc = (c.connect(user, servers[0], allowLanHttp = false) as MediaResult.Ok).value
        assertEquals(ServerKind.PLEX, acc.kind); assertEquals(machine, acc.serverId); assertEquals(serverToken, acc.token); assertFalse(acc.lanHttpApproved)
        assertTrue("the ACCOUNT token only goes to Plex's own account hosts", http.tokenRecipients(accountToken).all { it.startsWith("https://clients.plex.tv/") || it.startsWith("https://plex.tv/") })
        assertTrue("the SERVER token only goes to the verified server", http.tokenRecipients(serverToken).all { it.startsWith("https://192-168-1-20.abc.plex.direct:32400/") })
        assertTrue("identity is asked without any token", http.sent.first { it.url.endsWith("/identity") }.headers["X-Plex-Token"] == null)
        assertTrue("tokens travel in a header, never in a URL", http.sent.none { serverToken in it.url || accountToken in it.url })
        val libs = (c.libraries(acc) as MediaResult.Ok).value; assertEquals(listOf("1", "2"), libs.map { it.id })
        val page = (c.items(acc, libs[0]) as MediaResult.Ok).value
        assertEquals(2, page.total); assertEquals(600_000L, page.items[0].resumeMs); assertEquals("/library/metadata/55/thumb/1700000000", page.items[0].imagePath)
        assertNull("artwork must be a server-relative path, never a URL supplied by the server", page.items[1].imagePath)
        assertEquals("Dark", (c.resume(acc) as MediaResult.Ok).value[0].seriesTitle)
        assertTrue(c.verify(acc) is MediaResult.Ok)
    }
    @Test fun plex_never_offers_a_token_to_an_unexpected_machine() = runBlocking {
        val user = PlexUser("abcdef0123456789", "Arne", accountToken)
        val http = FakeMediaHttp(mutableMapOf("GET https://clients.plex.tv/api/v2/resources" to resources(direct),
            "GET https://192-168-1-20.abc.plex.direct:32400/identity" to MediaResponse(200, """{"MediaContainer":{"machineIdentifier":"ffffffffffffffffffffffffffffffffffffffff"}}""")))
        val c = PlexClient(http, identity)
        val server = (c.servers(user) as MediaResult.Ok).value[0]
        assertEquals(MediaError.IDENTITY_MISMATCH, (c.connect(user, server, false) as MediaResult.Failure).error)
        assertTrue(http.tokenRecipients(serverToken).isEmpty())
        // only a plain-http LAN connection is listed and it was not approved: nothing is contacted
        val lanOnly = FakeMediaHttp(mutableMapOf("GET https://clients.plex.tv/api/v2/resources" to resources(lanHttp)))
        val c2 = PlexClient(lanOnly, identity); val s2 = (c2.servers(user) as MediaResult.Ok).value[0]
        assertEquals(MediaError.UNREACHABLE, (c2.connect(user, s2, allowLanHttp = false) as MediaResult.Failure).error)
        assertTrue(lanOnly.sent.none { it.url.startsWith("http://") })
    }

    // ---- store codec and honest connection states ------------------------------------------------------------------------------------------------
    @Test fun account_codec_round_trip_is_strict() {
        val a = ServerAccount(ServerKind.EMBY, sid, "Home \"NAS\"\n", "http://192.168.1.20:8096", true, uid, "Arne", tok, identity.deviceId)
        val b = ServerAccount(ServerKind.PLEX, machine, "NAS", "https://192-168-1-20.abc.plex.direct:32400", false, "u1", "Arne", serverToken, identity.deviceId)
        assertEquals(listOf(a, b), AccountCodec.decode(AccountCodec.encode(listOf(a, b))))
        assertTrue(AccountCodec.decode(null).isEmpty()); assertTrue(AccountCodec.decode("not json").isEmpty()); assertTrue(AccountCodec.decode("x".repeat(AccountCodec.MAX_CHARS + 1)).isEmpty())
        // a tampered store cannot aim a token at a forbidden address, or use plain http without the recorded approval
        assertTrue(AccountCodec.decode(AccountCodec.encode(listOf(a.copy(base = "http://127.0.0.1:8096")))).isEmpty())
        assertTrue(AccountCodec.decode(AccountCodec.encode(listOf(a.copy(lanHttpApproved = false)))).isEmpty())
        assertTrue(AccountCodec.decode(AccountCodec.encode(listOf(a.copy(base = "https://user:pw@media.example.com")))).isEmpty())
        assertEquals(AccountCodec.MAX_ACCOUNTS, AccountCodec.decode(AccountCodec.encode(List(20) { a.copy(userId = "u$it") })).size)
        assertEquals("{\"a\":\"q\\\"\\\\\\n\",\"n\":3,\"b\":true,\"z\":null,\"l\":[1,\"x\"]}", JsonOut.obj("a" to "q\"\\\n", "n" to 3, "b" to true, "z" to null, "l" to listOf(1, "x")))
    }
    @Test fun connected_only_after_a_verification_in_this_process_and_per_profile() = runBlocking {
        var now = 5_000L
        val store = MemoryMediaAccountStore()
        val http = FakeMediaHttp(mutableMapOf("GET https://jf.example.com/System/Info/Public" to publicInfo, "GET https://jf.example.com/Users/Me" to MediaResponse(200, """{"Id":"$uid"}"""), "POST https://jf.example.com/Sessions/Logout" to MediaResponse(204, null)))
        val acc = ServerAccount(ServerKind.JELLYFIN, sid, "Home", "https://jf.example.com", false, uid, "Arne", tok, identity.deviceId)
        val svc = MediaServerService(store, http, identity) { now }
        assertEquals(ConnectionState.NOT_CONFIGURED, svc.status("p1", ServerKind.JELLYFIN).state)
        assertTrue(svc.add("p1", acc))
        val s = svc.status("p1", ConnectionId.JELLYFIN)!!; assertEquals(ConnectionState.CONNECTED, s.state); assertEquals(5_000L, s.verifiedAtEpochMs)
        assertEquals(ConnectionState.NOT_CONFIGURED, svc.status("p2", ServerKind.JELLYFIN).state)            // another profile sees nothing
        assertTrue(svc.accounts("p2").isEmpty()); assertEquals(ConnectionState.NOT_CONFIGURED, svc.status("p1", ServerKind.PLEX).state)
        assertNull(svc.status("p1", ConnectionId.TMDB))
        // a NEW process: the sign-in is saved, but nothing has been verified -> not Connected
        val restarted = MediaServerService(store, http, identity) { now }
        assertEquals(ConnectionState.NOT_CONFIGURED, restarted.status("p1", ServerKind.JELLYFIN).state); assertTrue(restarted.status("p1", ServerKind.JELLYFIN).message!!.contains("not been checked"))
        restarted.accounts("p1"); assertEquals(ConnectionState.NOT_CONFIGURED, restarted.status("p1", ServerKind.JELLYFIN).state); assertTrue(restarted.status("p1", ServerKind.JELLYFIN).message!!.contains("not been verified"))
        now = 9_000L; restarted.verifyAll("p1"); assertEquals(9_000L, restarted.status("p1", ServerKind.JELLYFIN).verifiedAtEpochMs)
        // the server revokes the token -> Authentication failed, never a stale Connected
        http.routes["GET https://jf.example.com/Users/Me"] = MediaResponse(401, null); restarted.verifyAll("p1")
        assertEquals(ConnectionState.AUTH_FAILED, restarted.status("p1", ServerKind.JELLYFIN).state)
        http.routes["GET https://jf.example.com/System/Info/Public"] = MediaResponse(0, null); restarted.verifyAll("p1")
        assertEquals(ConnectionState.UNREACHABLE, restarted.status("p1", ServerKind.JELLYFIN).state)
        // sign out: server session ended, local credential gone
        assertTrue(restarted.remove("p1", acc)); assertTrue(http.sent.last().url.endsWith("/Sessions/Logout")); assertFalse(store.has("p1"))
        assertEquals(ConnectionState.NOT_CONFIGURED, restarted.status("p1", ServerKind.JELLYFIN).state)
        // limits and profile deletion
        repeat(AccountCodec.MAX_ACCOUNTS) { assertTrue(svc.add("p3", acc.copy(userId = "user-$it"))) }; assertFalse(svc.add("p3", acc.copy(userId = "one-too-many")))
        svc.purge("p3"); assertFalse(store.has("p3")); assertEquals(ConnectionState.NOT_CONFIGURED, svc.status("p3", ServerKind.JELLYFIN).state)
        assertFalse(svc.add("", acc))
    }
    @Test fun media_server_code_never_logs_and_settings_gets_its_states_from_the_service() {
        java.io.File("src/main/java/no/aura/os/v2/mediaserver").listFiles()!!.forEach { f ->
            assertFalse("${f.name} must not log", Regex("Log\\.[dviwe]\\(|println\\(").containsMatchIn(f.readText()))
            assertFalse("${f.name} must not disable TLS checks", Regex("TrustManager|HostnameVerifier|setSSLSocketFactory").containsMatchIn(f.readText()))
        }
        assertTrue(java.io.File("src/main/java/no/aura/os/v2/MainActivity.kt").readText().contains("media = { id -> mediaServers.status(profileService.state.activeId, id) }"))
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue("owner approved LAN HTTP with per-server warning; address-policy tests enforce the gate", manifest.contains("usesCleartextTraffic=\"true\""))
    }
}
