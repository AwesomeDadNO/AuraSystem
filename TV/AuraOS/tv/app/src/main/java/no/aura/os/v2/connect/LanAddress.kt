package no.aura.os.v2.connect

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** The TV's own address on the home network: the receive session binds to this one address only (never to all interfaces). */
object LanAddress {
    /** A private (RFC 1918) IPv4 address of an interface that is up, or null when the TV is not on a home network. */
    fun pick(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .firstOrNull { it is Inet4Address && isPrivate(it.address) }
    }.getOrNull()

    fun isPrivate(a: ByteArray): Boolean {
        if (a.size != 4) return false
        val b0 = a[0].toInt() and 255; val b1 = a[1].toInt() and 255
        return b0 == 10 || (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168)
    }
    /** Fixed port so the phone only needs the address and the code. Registered range, not used by a common service. */
    const val PORT = 47121
}
