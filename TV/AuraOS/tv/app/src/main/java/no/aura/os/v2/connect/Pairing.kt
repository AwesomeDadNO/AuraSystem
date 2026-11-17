package no.aura.os.v2.connect

import no.aura.connect.core.DeviceIds
import no.aura.connect.core.Permission
import no.aura.connect.core.Spki
import no.aura.connect.core.TrustRecord
import no.aura.connect.core.TrustStore

/**
 * What the TV shows for "Pair a device": the phone needs the TV's home-network address, port, the TV's device id and a one-time code.
 * Encoded as a compact `auraconnect://pair?...` URI so a QR code can carry it; the same values are also shown as text for manual entry.
 *
 * The code is single-use and expires in two minutes, and pairing still requires an explicit Allow on the TV, so carrying it in the QR is
 * not a bearer secret: J-PAKE proves the code without sending it, and a scan alone cannot pair. No ARL, token or other credential is ever
 * part of this payload.
 */
data class PairingPayload(val host: String, val port: Int, val deviceId: String, val code: String?) {
    fun toUri(): String {
        val q = buildString {
            append("v=").append(VERSION)
            append("&host=").append(enc(host)); append("&port=").append(port); append("&id=").append(deviceId)
            if (code != null) { append("&code=").append(code) }
        }
        return "$SCHEME://pair?$q"
    }
    override fun toString() = "PairingPayload($host:$port, id=${deviceId.take(8)}…, code=${if (code == null) "none" else "present"})"

    companion object {
        const val SCHEME = "auraconnect"
        const val VERSION = 3
        private const val MAX_HOST = 253
        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

        /** Strict parse of a scanned/typed payload. Null for anything that is not a v3 pairing URI with a valid id, port and (optional) code. */
        fun parse(raw: String): PairingPayload? = runCatching {
            val text = raw.trim()
            if (text.length > 512 || !text.startsWith("$SCHEME://pair?")) return null
            val uri = java.net.URI(text)
            if (!uri.scheme.equals(SCHEME, true) || uri.host != "pair") return null
            val q = (uri.rawQuery ?: return null).split('&').mapNotNull { p ->
                val i = p.indexOf('='); if (i <= 0) null else java.net.URLDecoder.decode(p.substring(0, i), "UTF-8") to java.net.URLDecoder.decode(p.substring(i + 1), "UTF-8")
            }.toMap()
            if (q["v"] != VERSION.toString()) return null
            val host = q["host"]?.takeIf { it.isNotBlank() && it.length <= MAX_HOST && it.none(Char::isWhitespace) } ?: return null
            val port = q["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val id = q["id"]?.takeIf(DeviceIds::isValid) ?: return null
            val code = q["code"]?.let { c -> c.takeIf { Regex("[0-9]{6}").matches(it) } ?: return null }
            PairingPayload(host, port, id, code)
        }.getOrNull()
    }
}

/** One row in "Paired devices" on the TV. Carries no secret: a key fingerprint, not the key. */
data class TrustedDeviceRow(
    val deviceId: String, val name: String, val profileId: String, val pairedAtMs: Long, val revoked: Boolean, val fingerprint: String,
    val permissions: Set<Permission>,
)

/** Lists and revokes trusted devices for the "Paired devices" screen. Reads the same [TrustStore] the receiver authorizes against. */
object TrustedDevices {
    /** Active devices first, then revoked ones, each newest first. */
    fun rows(store: TrustStore): List<TrustedDeviceRow> = store.list().map { r ->
        TrustedDeviceRow(r.deviceId, r.name, r.profileId, r.pairedAtMs, r.isRevoked, fingerprint(r), r.permissions)
    }.sortedWith(compareBy({ it.revoked }, { -it.pairedAtMs }))

    /** The devices granted to a profile that is being deleted (so the caller can revoke them with the profile). */
    fun forProfile(store: TrustStore, profileId: String): List<TrustedDeviceRow> = rows(store).filter { it.profileId == profileId && !it.revoked }

    /** Short, human-comparable form of the pinned key, shown on both devices so the person can check they match. */
    fun fingerprint(record: TrustRecord): String = Spki.hex(record.spkiSha256).take(16).uppercase().chunked(4).joinToString("-")
}
