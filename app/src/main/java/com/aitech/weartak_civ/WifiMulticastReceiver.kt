package com.aitech.weartak_civ

import com.aitech.weartak_civ.service.DiagnosticResource
import com.aitech.weartak_civ.service.DiagnosticResources

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log
import com.aitech.weartak_civ.cot.TakServerCotEnvelope
import com.aitech.weartak_civ.cot.TakDatagramDecoder
import com.aitech.weartak_civ.data.takserver.TakDataPackageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import org.xml.sax.SAXException

class WifiMulticastReceiver internal constructor(
    private val subscriptions: List<Subscription>,
    private val logPacket: (String) -> Unit = {},
    private val localAddresses: Set<String> = emptySet(),
    private val releaseLock: () -> Unit
) : Closeable {
    internal data class Subscription(
        val endpoint: MulticastEndpoint,
        val socket: MulticastSocket
    )

    suspend fun receive(
        onPeerPacket: () -> Unit = {},
        onCot: suspend (TakServerCotEnvelope) -> Unit
    ) = coroutineScope {
        subscriptions.forEach { subscription ->
            launch(Dispatchers.IO) {
                val buffer = ByteArray(65535)
                val packet = DatagramPacket(buffer, buffer.size)
                while (currentCoroutineContext().isActive) {
                    // receive() shrinks the packet length; restore it before reusing the buffer.
                    packet.length = buffer.size
                    try {
                        subscription.socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    currentCoroutineContext().ensureActive()
                    logPacket(packetSummary(subscription.endpoint, packet))
                    if (isSelfEcho(packet.address?.hostAddress, localAddresses)) {
                        logPacket("Ignoring multicast self-echo group=${subscription.endpoint.sourceId}")
                        continue
                    }
                    onPeerPacket()
                    val xml = try {
                        TakDatagramDecoder.decode(packet.data, packet.offset, packet.length)
                    } catch (error: IOException) {
                        Log.w("WearTAK-MulticastRx", "Rejecting invalid TAK datagram")
                        continue
                    } catch (error: SAXException) {
                        Log.w("WearTAK-MulticastRx", "Rejecting invalid TAK detail XML")
                        continue
                    }
                    if (xml == null) {
                        logPacket("Received control-only TAK message group=${subscription.endpoint.sourceId}")
                        continue
                    }
                    if (BuildConfig.DEBUG) {
                        TakDataPackageRequest.localOfferSummary(xml, packet.address?.hostAddress)?.let(logPacket)
                    }
                    onCot(
                        TakServerCotEnvelope(
                            sourceServerId = subscription.endpoint.sourceId,
                            sourceServerAddress = subscription.endpoint.address,
                            receivedAt = System.currentTimeMillis(),
                            rawXml = xml,
                            sourcePeerAddress = packet.address?.hostAddress
                        )
                    )
                }
            }
        }
    }

    override fun close() {
        subscriptions.forEach { it.socket.close() }
        releaseLock()
    }

    companion object {
        internal fun isSelfEcho(fromAddress: String?, localAddresses: Set<String>): Boolean =
            fromAddress != null && fromAddress in localAddresses

        internal fun packetSummary(endpoint: MulticastEndpoint, packet: DatagramPacket): String {
            val prefix = (0 until minOf(3, packet.length)).joinToString(" ") { index ->
                "%02x".format(packet.data[packet.offset + index].toInt() and 0xff)
            }
            return "Multicast packet group=${endpoint.address}:${endpoint.port} " +
                "sender=${packet.address?.hostAddress}:${packet.port} bytes=${packet.length} " +
                "prefixHex=[$prefix]"
        }

        fun open(
            context: Context,
            network: Network,
            address: String,
            port: Int
        ): WifiMulticastReceiver {
            val endpoints = TakMulticastEndpoints.receiveEndpoints(address, port)
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
                ?: throw IOException("ConnectivityManager unavailable")
            val interfaceName = connectivity.getLinkProperties(network)?.interfaceName
                ?: throw IOException("Wi-Fi network has no interface")
            val networkInterface = NetworkInterface.getByName(interfaceName)
                ?: throw IOException("Wi-Fi interface $interfaceName unavailable")
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
                ?: throw IOException("WifiManager unavailable")
            val lock = wifi.createMulticastLock("WearTAK-MulticastReceiver").apply {
                setReferenceCounted(false)
            }
            val subscriptions = mutableListOf<Subscription>()
            var opened = false
            try {
                DiagnosticResources.acquire(DiagnosticResource.MULTICAST_LOCK, lock) { lock.acquire() }
                for (endpoint in endpoints) {
                    val group = InetAddress.getByName(endpoint.address)
                    require(group is Inet4Address && group.isMulticastAddress) {
                        "Not an IPv4 multicast group: ${endpoint.address}"
                    }
                    val socket = MulticastSocket(null)
                    subscriptions += Subscription(endpoint, socket)
                    socket.reuseAddress = true
                    socket.soTimeout = 1000
                    socket.bind(InetSocketAddress(endpoint.port))
                    network.bindSocket(socket)
                    socket.networkInterface = networkInterface
                    socket.joinGroup(InetSocketAddress(group, endpoint.port), networkInterface)
                }
                val receiver = WifiMulticastReceiver(
                    subscriptions = subscriptions,
                    localAddresses = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
                        .flatMap { java.util.Collections.list(it.inetAddresses) }
                        .mapNotNull { it.hostAddress }
                        .toSet(),
                    releaseLock = {
                        if (lock.isHeld) DiagnosticResources.release(DiagnosticResource.MULTICAST_LOCK, lock) { lock.release() }
                        else DiagnosticResources.end(DiagnosticResource.MULTICAST_LOCK, lock, expired = true)
                    },
                    logPacket = { summary ->
                        if (BuildConfig.DEBUG) Log.d("WearTAK-MulticastRx", summary)
                    }
                )
                opened = true
                return receiver
            } finally {
                if (!opened) {
                    subscriptions.forEach { it.socket.close() }
                    if (lock.isHeld) DiagnosticResources.release(DiagnosticResource.MULTICAST_LOCK, lock) { lock.release() }
                    else DiagnosticResources.end(DiagnosticResource.MULTICAST_LOCK, lock, expired = true)
                }
            }
        }
    }
}
