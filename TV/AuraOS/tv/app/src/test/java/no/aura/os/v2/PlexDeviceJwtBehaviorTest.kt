package no.aura.os.v2
import no.aura.os.v2.mediaserver.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
class PlexDeviceJwtBehaviorTest {
    @Test fun deviceJwtIsSignedWithRegisteredKidAudienceAndFreshNonce() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key = object : PlexDeviceSigningKey {
            override val clientIdentifier = "profile-device"
            override val kid = "registered-key"
            override val jwk = emptyMap<String,String>()
            override fun sign(input: ByteArray): ByteArray = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(input); sign() }
        }
        val jwt = PlexDeviceJwt.sign(key, "fresh-nonce", 1000000)
        val parts = jwt.split('.')
        val header = String(Base64.getUrlDecoder().decode(parts[0]))
        val payload = String(Base64.getUrlDecoder().decode(parts[1]))
        assertTrue(header.contains("registered-key")); assertTrue(header.contains("RS256"))
        assertTrue(payload.contains("fresh-nonce")); assertTrue(payload.contains("plex.tv")); assertTrue(payload.contains("profile-device"))
        assertTrue(Signature.getInstance("SHA256withRSA").run { initVerify(pair.public); update((parts[0]+"."+parts[1]).toByteArray(Charsets.US_ASCII)); verify(Base64.getUrlDecoder().decode(parts[2])) })
        assertFalse(jwt.contains("="))
    }
    @Test fun pinProofDoesNotInventNonce() {
        val key = object : PlexDeviceSigningKey {
            override val clientIdentifier = "device"; override val kid = "key"; override val jwk = emptyMap<String,String>()
            override fun sign(input: ByteArray) = byteArrayOf(1,2,3)
        }
        val payload = String(Base64.getUrlDecoder().decode(PlexDeviceJwt.sign(key,null,1000000).split('.')[1]))
        assertFalse(payload.contains("nonce"))
        assertTrue(payload.contains("\"iat\":1000")); assertTrue(payload.contains("\"exp\":1060"))
    }
}

class PlexDeviceKeyIsolationTest {
    @Test fun keysAreScopedToBothProfileAndInstallation() {
        val first = PlexDeviceJwt.profileIdentifier("install-a", "profile-a")
        assertEquals(first, PlexDeviceJwt.profileIdentifier("install-a", "profile-a"))
        assertNotEquals(first, PlexDeviceJwt.profileIdentifier("install-a", "profile-b"))
        assertNotEquals(first, PlexDeviceJwt.profileIdentifier("install-b", "profile-a"))
        assertFalse(first.contains("profile-a"))
    }
}

class PlexDeviceWireBehaviorTest {
    private val key = object : PlexDeviceSigningKey {
        override val clientIdentifier = "profile-device"; override val kid = "key"
        override val jwk = mapOf("kty" to "RSA", "kid" to "key", "alg" to "RS256", "n" to "modulus", "e" to "AQAB")
        override fun sign(input: ByteArray) = byteArrayOf(1,2,3)
    }
    @Test fun modernPinRegistersJwkAndApprovalProofWithoutLegacyToken() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<MediaRequest>()
        val transport = object : MediaHttp {
            override suspend fun send(request: MediaRequest): MediaResponse {
                requests.add(request)
                return if (requests.size == 1) MediaResponse(201,"""{"id":42,"code":"ABCD1234","expiresIn":120}""")
                else MediaResponse(200,"""{"authToken":"a-returned-plex-jwt-token"}""")
            }
        }
        val client = PlexDeviceJwtClient(transport,key,{1000000})
        val pin = (client.createPin() as MediaResult.Ok).value
        assertTrue(client.checkPin(pin) is MediaResult.Ok)
        assertEquals("https://clients.plex.tv/api/v2/pins",requests[0].url)
        assertTrue(requests[0].jsonBody!!.contains("\"jwk\"")); assertTrue(requests[0].jsonBody!!.contains("\"strong\":true"))
        assertTrue(requests[1].url.startsWith("https://clients.plex.tv/api/v2/pins/42?deviceJWT="))
        assertFalse(requests.any { "X-Plex-Token" in it.headers })
    }
    @Test fun refreshObtainsFreshNonceAndExchangesSignedProof() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<MediaRequest>()
        val transport = object : MediaHttp {
            override suspend fun send(request: MediaRequest): MediaResponse {
                requests.add(request)
                return if (requests.size == 1) MediaResponse(200,"""{"nonce":"fresh-nonce"}""") else MediaResponse(200,"""{"auth_token":"renewed-plex-jwt-token"}""")
            }
        }
        assertTrue(PlexDeviceJwtClient(transport,key,{1000000}).refresh() is MediaResult.Ok)
        assertEquals("https://clients.plex.tv/api/v2/auth/nonce",requests[0].url)
        assertEquals("POST",requests[1].method)
        assertEquals("https://clients.plex.tv/api/v2/auth/token",requests[1].url)
        assertTrue(requests[1].jsonBody!!.startsWith("{\"jwt\":"))
    }
}

class PlexModernLifecycleBehaviorTest {
    private class Modern : PlexModernClient {
        override val clientIdentifier = "aura-jwt-profile"
        var refreshed = 0
        override suspend fun createPin(): MediaResult<PlexPin> = MediaResult.Failure(MediaError.PENDING)
        override fun approvalUrl(pin: PlexPin) = "https://app.plex.tv/auth"
        override suspend fun checkPin(pin: PlexPin): MediaResult<String> = MediaResult.Failure(MediaError.PENDING)
        override suspend fun refresh(): MediaResult<String> { refreshed++; return MediaResult.Ok("transient-account-jwt") }
        override suspend fun authorizedResources(token: String, identity: ClientIdentity): MediaResult<Pair<PlexUser,List<PlexServer>>> =
            MediaResult.Ok(PlexUser("user","Name",token) to listOf(PlexServer("machine123","Server",true,"fresh-resource-token",listOf(PlexConnection("https://media.example.com",false,false,true)))))
    }
    private fun account() = ServerAccount(ServerKind.PLEX,"machine123","Server","https://media.example.com",false,"user","Name","old-resource-token","aura-jwt-profile")
    @Test fun refreshReplacesOnlyServerResourceCredentialInEncryptedStore() = kotlinx.coroutines.runBlocking {
        val store = MemoryMediaAccountStore(); store.save("profile",listOf(account()))
        val requests = mutableListOf<MediaRequest>(); val modern = Modern()
        val http = object : MediaHttp {
            override suspend fun send(request: MediaRequest): MediaResponse { requests.add(request); return if (request.url.endsWith("/identity")) MediaResponse(200,"""{"MediaContainer":{"machineIdentifier":"machine123"}}""") else MediaResponse(200,"""{"MediaContainer":{"Directory":[]}}""") }
        }
        val service = MediaServerService(store,http,ClientIdentity("install","1"),modernPlex={ _,_ -> modern },mayManage={true})
        val resolved = (service.authorizedAccount("profile",account()) as MediaResult.Ok).value
        assertEquals("fresh-resource-token",resolved.token)
        assertEquals("fresh-resource-token",store.load("profile").single().token)
        assertFalse(store.load("profile").any { it.token == "transient-account-jwt" })
        assertTrue(requests.filterNot { it.url.endsWith("/identity") }.all { it.headers["X-Plex-Token"] == "fresh-resource-token" })
        service.authorizedAccount("profile",account()); assertEquals(1,modern.refreshed)
    }
    @Test fun lastModernBindingRemovalForgetsKeyButSharedBindingKeepsIt() = kotlinx.coroutines.runBlocking {
        val store = MemoryMediaAccountStore(); val a = account(); val b = a.copy(serverId="machine456")
        store.save("profile",listOf(a,b)); var forgotten = 0
        val http = object : MediaHttp { override suspend fun send(request: MediaRequest) = MediaResponse(500,null) }
        val service = MediaServerService(store,http,ClientIdentity("install","1"),forgetModernPlex={ forgotten++; true },mayManage={true})
        assertTrue(service.remove("profile",a)); assertEquals(0,forgotten)
        assertTrue(service.remove("profile",b)); assertEquals(1,forgotten)
    }
    @Test fun modernKeyAndCredentialMutationsRequireCurrentManagementAuthorization() = kotlinx.coroutines.runBlocking {
        val store = MemoryMediaAccountStore(); store.save("profile",listOf(account())); var created = false
        val http = object : MediaHttp { override suspend fun send(request: MediaRequest) = MediaResponse(500,null) }
        val service = MediaServerService(store,http,ClientIdentity("install","1"),modernPlex={ _,_ -> created=true; Modern() },mayManage={false})
        assertNull(service.modernClient("profile",true)); assertFalse(created)
        assertFalse(service.add("profile",account())); assertFalse(service.remove("profile",account()))
    }
}
