package com.callbell

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.text.DateFormat
import java.util.Date

/**
 * Sends one event to the Mac over UDP and waits for its encrypted acknowledgement.
 * Targets: the Mac's last known IP, an optional manual IP, and Wi-Fi broadcast,
 * so no setup is needed and a changed Mac IP is found again automatically.
 */
object Sender {
    const val PORT = 47823
    private const val ATTEMPTS = 6
    private const val WAIT_MS = 700L

    data class Result(val ok: Boolean, val message: String)

    fun send(context: Context, event: JSONObject): Result {
        val prefs = Prefs(context)
        val result = try {
            sendInner(context, prefs, event)
        } catch (e: Exception) {
            Result(false, "Error: ${e.message}")
        }
        val who = event.optString("name").takeIf { it.isNotEmpty() && it != "null" }
            ?: event.optString("number").takeIf { it.isNotEmpty() && it != "null" }
        val label = event.optString("type") + (who?.let { " ($it)" } ?: "")
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        prefs.lastResult = "Last: $label at $time — ${if (result.ok) "delivered" else "NOT delivered"}"
        return result
    }

    private fun sendInner(context: Context, prefs: Prefs, event: JSONObject): Result {
        if (Crypto.normalize(prefs.code).length < 8) return Result(false, "Enter the pairing code from your Mac first.")
        val key = Crypto.keyFromCode(prefs.code)
        val id = event.getString("id")
        val packet = Crypto.seal(key, Crypto.MAGIC_EVENT, event.toString().toByteArray(Charsets.UTF_8))

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifi = findLocalNetwork(cm)

        val targets = LinkedHashSet<InetAddress>()
        listOf(prefs.manualIp, prefs.cachedIp).map { it.trim() }.filter { it.isNotEmpty() }.forEach { ip ->
            runCatching { InetAddress.getByName(ip) }.getOrNull()?.let(targets::add)
        }
        if (wifi != null) targets += broadcastAddresses(cm, wifi)
        runCatching { targets += InetAddress.getByName("255.255.255.255") }

        DatagramSocket().use { socket ->
            socket.broadcast = true
            // Make sure we go out over Wi-Fi even if Android prefers mobile data.
            if (wifi != null) runCatching { wifi.bindSocket(socket) }
            val buf = ByteArray(2048)
            repeat(ATTEMPTS) {
                for (t in targets) runCatching { socket.send(DatagramPacket(packet, packet.size, t, PORT)) }
                val deadline = System.currentTimeMillis() + WAIT_MS
                while (true) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    socket.soTimeout = left.toInt().coerceAtLeast(1)
                    val dp = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(dp)
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                    val plain = Crypto.open(key, Crypto.MAGIC_ACK, buf.copyOf(dp.length)) ?: continue
                    val ack = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: continue
                    if (ack.optString("id") != id) continue
                    val ip = dp.address.hostAddress ?: ""
                    prefs.cachedIp = ip
                    val host = ack.optString("host").ifEmpty { "Mac" }
                    return Result(true, "Delivered to $host ($ip)")
                }
            }
        }
        return Result(
            false,
            if (wifi == null) "Phone is not on Wi-Fi. Turn Wi-Fi on and join the same network as the Mac."
            else "The Mac did not answer. Check that CallBell is running on the Mac, both are on the same Wi-Fi, and the code matches."
        )
    }

    @Suppress("DEPRECATION")
    private fun findLocalNetwork(cm: ConnectivityManager): Network? =
        cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }

    private fun broadcastAddresses(cm: ConnectivityManager, network: Network): List<InetAddress> =
        cm.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { la ->
            val addr = la.address as? Inet4Address ?: return@mapNotNull null
            val prefix = la.prefixLength
            if (prefix !in 8..30) return@mapNotNull null
            val ip = ByteBuffer.wrap(addr.address).int
            val hostBits = (-1 ushr prefix)
            InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(ip or hostBits).array())
        }
}
