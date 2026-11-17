package no.aura.os.v2

import no.aura.os.v2.admin.Authorizer
import no.aura.os.v2.profiles.PinCredential
import no.aura.os.v2.profiles.PinHasher

/** Fast, deterministic hasher for tests (real PBKDF2 is covered separately). */
internal object FakeHasher : PinHasher {
    override fun create(pin: String) = PinCredential("00", "h$pin")
    override fun verify(pin: String, credential: PinCredential) = credential.hash == "h$pin"
}

internal class FakeAuth(var on: Boolean = true) : Authorizer {
    override fun isAuthorized() = on
    override fun touch() {}
}
