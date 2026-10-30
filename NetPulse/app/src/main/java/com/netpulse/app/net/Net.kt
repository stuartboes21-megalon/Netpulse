package com.netpulse.app.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.math.max
import kotlin.math.min

enum class Band(val label: String) { B24("2.4 GHz"), B5("5 GHz"), B6("6 GHz") }

enum class Transport(val label: String) {
    Wifi("Wi-Fi"), Ethernet("Ethernet"), Cellular("Cellular"), Vpn("VPN"), Other("Network"), Offline("Offline")
}

enum class PortResult { OPEN, CLOSED, FILTERED }

data class LinkInfo(
    val transport: Transport = Transport.Offline,
    val interfaceName: String? = null,
    val ipv4: String? = null,
    val prefix: Int? = null,
    val ipv6: List<String> = emptyList(),
    val gateway: String? = null,
    val dns: List<String> = emptyList(),
    val privateDns: String? = null,
    val mtu: Int? = null,
    val domains: String? = null,
    val validated: Boolean = false,
    val metered: Boolean = false,
    val downKbps: Int? = null,
    val upKbps: Int? = null,
) {
    val connected: Boolean get() = transport != Transport.Offline
    val subnetMask: String? get() = prefix?.let { NetUtils.prefixToMask(it) }
    val lanCapable: Boolean
        get() = ipv4 != null && prefix != null &&
            (transport == Transport.Wifi || transport == Transport.Ethernet)
}

data class WifiDetails(
    val ssid: String?,
    val bssid: String?,
    val rssi: Int,
    val frequency: Int,
    val channel: Int,
    val band: Band,
    val linkSpeed: Int,
    val txSpeed: Int?,
    val rxSpeed: Int?,
    val standard: String?,
    val security: String?,
    val leaseSeconds: Int?,
    val dhcpServer: String?,
)

data class PublicInfo(
    val ip: String,
    val isp: String?,
    val city: String?,
    val region: String?,
    val country: String?,
    val colo: String?,
)

object NetUtils {
    private val ipv4Regex = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun isIpv4(s: String) = ipv4Regex.matches(s)

    fun ipToLong(ip: String): Long =
        ip.split('.').fold(0L) { acc, part -> (acc shl 8) or (part.toLong() and 0xFF) }

    fun longToIp(v: Long): String =
        "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"

    fun maskFor(prefix: Int): Long =
        if (prefix <= 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

    fun prefixToMask(prefix: Int): String = longToIp(maskFor(prefix))

    fun networkAddress(ip: String, prefix: Int): String = longToIp(ipToLong(ip) and maskFor(prefix))

    /** DhcpInfo stores addresses as little-endian ints. */
    fun intToIpLE(v: Int): String =
        "${v and 0xFF}.${(v shr 8) and 0xFF}.${(v shr 16) and 0xFF}.${(v ushr 24) and 0xFF}"

    /** OPEN = handshake completed, CLOSED = RST (host alive), FILTERED = timeout/unreachable. */
    fun tcpProbe(host: String, port: Int, timeoutMs: Int): Pair<PortResult, Long> {
        val t0 = System.nanoTime()
        val result = try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            PortResult.OPEN
        } catch (e: ConnectException) {
            val msg = e.message.orEmpty()
            if (msg.contains("ECONNREFUSED") || msg.contains("refused", ignoreCase = true)) PortResult.CLOSED
            else PortResult.FILTERED
        } catch (e: Exception) {
            PortResult.FILTERED
        }
        return result to (System.nanoTime() - t0) / 1_000_000
    }

    val services: Map<Int, String> = mapOf(
        21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 53 to "DNS", 80 to "HTTP",
        110 to "POP3", 139 to "NetBIOS", 143 to "IMAP", 443 to "HTTPS", 445 to "SMB",
        548 to "AFP", 554 to "RTSP", 631 to "IPP", 1883 to "MQTT", 3306 to "MySQL",
        3389 to "RDP", 5000 to "UPnP", 5900 to "VNC", 7000 to "AirPlay", 8008 to "Cast",
        8009 to "Cast", 8080 to "HTTP-alt", 8443 to "HTTPS-alt", 9100 to "Printer",
        32400 to "Plex", 62078 to "iOS sync",
    )

    val commonPorts: List<Int> = services.keys.sorted()

    fun serviceName(port: Int): String = services[port] ?: "unknown"

    fun parsePorts(text: String): List<Int> {
        if (text.isBlank()) return commonPorts
        val out = sortedSetOf<Int>()
        text.split(',', ' ').filter { it.isNotBlank() }.forEach { token ->
            val range = token.split('-')
            val a = range[0].trim().toIntOrNull() ?: return@forEach
            val b = range.getOrNull(1)?.trim()?.toIntOrNull() ?: a
            for (p in min(a, b)..max(a, b)) {
                if (p in 1..65535 && out.size < 1024) out += p
            }
        }
        return out.toList().ifEmpty { commonPorts }
    }

    fun freqToChannel(f: Int): Int = when {
        f == 2484 -> 14
        f in 2412..2472 -> (f - 2407) / 5
        f in 5160..5885 -> (f - 5000) / 5
        f in 5955..7115 -> (f - 5950) / 5
        else -> 0
    }

    fun bandOf(f: Int): Band = when {
        f < 3000 -> Band.B24
        f < 5925 -> Band.B5
        else -> Band.B6
    }

    fun channelCenter(band: Band, ch: Int): Float = when (band) {
        Band.B24 -> if (ch == 14) 2484f else 2407f + 5 * ch
        Band.B5 -> 5000f + 5 * ch
        Band.B6 -> 5950f + 5 * ch
    }

    fun signalLevel(rssi: Int): Int = when {
        rssi >= -55 -> 4
        rssi >= -65 -> 3
        rssi >= -75 -> 2
        rssi >= -85 -> 1
        else -> 0
    }

    fun signalQuality(rssi: Int): Float = ((rssi + 100) / 65f).coerceIn(0f, 1f)

    fun signalLabel(rssi: Int): String =
        arrayOf("Very weak", "Weak", "Fair", "Good", "Excellent")[signalLevel(rssi)]

    fun standardName(std: Int, band: Band): String? = when (std) {
        1 -> "Legacy (802.11a/b/g)"
        4 -> "Wi-Fi 4 (802.11n)"
        5 -> "Wi-Fi 5 (802.11ac)"
        6 -> if (band == Band.B6) "Wi-Fi 6E (802.11ax)" else "Wi-Fi 6 (802.11ax)"
        7 -> "WiGig (802.11ad)"
        8 -> "Wi-Fi 7 (802.11be)"
        else -> null
    }
}

class NetworkInspector(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wm = context.applicationContext.getSystemService(WifiManager::class.java)

    fun link(): LinkInfo {
        val net = cm.activeNetwork ?: return LinkInfo()
        val caps = cm.getNetworkCapabilities(net) ?: return LinkInfo()
        val lp = cm.getLinkProperties(net)

        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.Vpn
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.Ethernet
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.Cellular
            else -> Transport.Other
        }
        val v4 = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        val v6 = lp?.linkAddresses
            ?.filter { it.address is Inet6Address }
            ?.mapNotNull { it.address.hostAddress?.substringBefore('%') }
            .orEmpty()
        val gateway = lp?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
        val privateDns = if (Build.VERSION.SDK_INT >= 28 && lp != null && lp.isPrivateDnsActive) {
            lp.privateDnsServerName ?: "Automatic"
        } else null

        return LinkInfo(
            transport = transport,
            interfaceName = lp?.interfaceName,
            ipv4 = v4?.address?.hostAddress,
            prefix = v4?.prefixLength,
            ipv6 = v6,
            gateway = gateway,
            dns = lp?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') }.orEmpty(),
            privateDns = privateDns,
            mtu = if (Build.VERSION.SDK_INT >= 29) lp?.mtu?.takeIf { it > 0 } else null,
            domains = lp?.domains,
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            downKbps = caps.linkDownstreamBandwidthKbps.takeIf { it > 0 },
            upKbps = caps.linkUpstreamBandwidthKbps.takeIf { it > 0 },
        )
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun wifi(): WifiDetails? {
        val info = wm.connectionInfo ?: return null
        if (info.frequency <= 0) return null
        val band = NetUtils.bandOf(info.frequency)
        val ssid = info.ssid?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        val bssid = info.bssid?.takeIf { it != "02:00:00:00:00:00" }?.uppercase()
        val dhcp = runCatching { wm.dhcpInfo }.getOrNull()
        return WifiDetails(
            ssid = ssid,
            bssid = bssid,
            rssi = info.rssi,
            frequency = info.frequency,
            channel = NetUtils.freqToChannel(info.frequency),
            band = band,
            linkSpeed = info.linkSpeed,
            txSpeed = if (Build.VERSION.SDK_INT >= 29) info.txLinkSpeedMbps.takeIf { it > 0 } else null,
            rxSpeed = if (Build.VERSION.SDK_INT >= 29) info.rxLinkSpeedMbps.takeIf { it > 0 } else null,
            standard = if (Build.VERSION.SDK_INT >= 30) NetUtils.standardName(info.wifiStandard, band) else null,
            security = if (Build.VERSION.SDK_INT >= 31) securityName(info.currentSecurityType) else null,
            leaseSeconds = dhcp?.leaseDuration?.takeIf { it > 0 },
            dhcpServer = dhcp?.serverAddress?.takeIf { it != 0 }?.let { NetUtils.intToIpLE(it) },
        )
    }

    private fun securityName(type: Int): String? = when (type) {
        0 -> "Open"
        1 -> "WEP"
        2 -> "WPA2-Personal"
        3 -> "WPA2-Enterprise"
        4 -> "WPA3-Personal"
        5, 9 -> "WPA3-Enterprise"
        6 -> "Enhanced Open (OWE)"
        7, 8 -> "WAPI"
        11, 12 -> "Passpoint"
        13 -> "DPP"
        else -> null
    }
}

/** Public IP, ISP and the nearest Cloudflare edge — the same server the speed test uses. */
fun fetchPublicInfo(): PublicInfo {
    val c = URL("https://speed.cloudflare.com/meta").openConnection() as HttpURLConnection
    c.connectTimeout = 5000
    c.readTimeout = 5000
    try {
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        fun opt(key: String) = j.optString(key).takeIf { it.isNotBlank() }
        return PublicInfo(
            ip = j.optString("clientIp"),
            isp = opt("asOrganization"),
            city = opt("city"),
            region = opt("region"),
            country = opt("country"),
            colo = opt("colo"),
        )
    } finally {
        c.disconnect()
    }
}
