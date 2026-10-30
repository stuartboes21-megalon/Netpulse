package com.netpulse.app.net

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

enum class DeviceKind(val label: String) {
    Router("Router"), ThisDevice("This device"), Phone("Phone"), Apple("Apple device"),
    Computer("Computer"), Printer("Printer"), Media("Media / TV"), Camera("Camera"),
    Server("Server / NAS"), Unknown("Device"),
}

data class LanDevice(
    val ip: String,
    val hostname: String? = null,
    val mac: String? = null,
    val openPorts: List<Int> = emptyList(),
    val latencyMs: Long? = null,
    val isSelf: Boolean = false,
    val isGateway: Boolean = false,
    val kind: DeviceKind = DeviceKind.Unknown,
) {
    val title: String
        get() = when {
            isSelf -> hostname ?: "This device"
            hostname != null -> hostname
            isGateway -> "Router"
            else -> kind.label
        }
}

data class ScanState(
    val running: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
    val devices: List<LanDevice> = emptyList(),
    val subnet: String? = null,
    val error: String? = null,
    val finishedAt: Long? = null,
)

data class PortScanState(val done: Int, val total: Int, val open: List<Int>)

object LanScanner {
    private val livenessPorts = intArrayOf(80, 443, 22, 445, 62078, 8080, 139, 53)
    private val extraPorts = intArrayOf(554, 631, 9100, 8008, 8009, 7000, 5000, 3389, 1883, 32400)

    fun scan(selfIp: String, prefix: Int, gateway: String?): Flow<ScanState> = channelFlow {
        val p = prefix.coerceIn(24, 30) // never sweep more than a /24
        val hosts = hostsFor(selfIp, p)
        val subnet = "${NetUtils.networkAddress(selfIp, p)}/$p"
        val found = ConcurrentHashMap<String, LanDevice>()
        val done = AtomicInteger(if (selfIp in hosts) 1 else 0)
        found[selfIp] = LanDevice(
            ip = selfIp, hostname = Build.MODEL, isSelf = true,
            kind = DeviceKind.ThisDevice, latencyMs = 0,
        )

        fun snapshot(running: Boolean) = ScanState(
            running = running,
            scanned = done.get(),
            total = hosts.size,
            devices = found.values.sortedWith(
                compareByDescending<LanDevice> { it.isGateway }.thenBy { NetUtils.ipToLong(it.ip) }
            ),
            subnet = subnet,
        )

        val ticker = launch {
            while (isActive) {
                send(snapshot(true))
                delay(120)
            }
        }

        val sem = Semaphore(64)
        coroutineScope {
            hosts.filter { it != selfIp }.forEach { ip ->
                launch(Dispatchers.IO) {
                    sem.withPermit { probe(ip, ip == gateway)?.let { found[ip] = it } }
                    done.incrementAndGet()
                }
            }
        }
        ticker.cancel()

        // Android 10+ hides the ARP table from apps; on older devices this fills in MAC addresses.
        readArp().forEach { (ip, mac) -> found.computeIfPresent(ip) { _, d -> d.copy(mac = mac) } }
        send(snapshot(false).copy(finishedAt = System.currentTimeMillis()))
    }.flowOn(Dispatchers.Default)

    fun portScan(ip: String): Flow<PortScanState> = channelFlow {
        val ports = NetUtils.commonPorts
        val open = CopyOnWriteArrayList<Int>()
        val done = AtomicInteger()
        val sem = Semaphore(16)
        coroutineScope {
            ports.forEach { port ->
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        if (NetUtils.tcpProbe(ip, port, 700).first == PortResult.OPEN) open += port
                    }
                    send(PortScanState(done.incrementAndGet(), ports.size, open.sorted()))
                }
            }
        }
        send(PortScanState(ports.size, ports.size, open.sorted()))
    }

    private fun hostsFor(ip: String, prefix: Int): List<String> {
        val mask = NetUtils.maskFor(prefix)
        val net = NetUtils.ipToLong(ip) and mask
        val broadcast = net or (mask.inv() and 0xFFFFFFFFL)
        return ((net + 1) until broadcast).map { NetUtils.longToIp(it) }
    }

    private fun probe(ip: String, isGateway: Boolean): LanDevice? {
        val addr = try {
            InetAddress.getByName(ip)
        } catch (e: Exception) {
            return null
        }
        var alive = false
        var latency: Long? = null
        val open = mutableListOf<Int>()

        val t0 = System.nanoTime()
        try {
            if (addr.isReachable(400)) {
                alive = true
                latency = (System.nanoTime() - t0) / 1_000_000
            }
        } catch (_: Exception) {
        }

        for (port in livenessPorts) {
            val (result, ms) = NetUtils.tcpProbe(ip, port, if (alive) 300 else 200)
            if (result != PortResult.FILTERED) {
                alive = true
                if (latency == null) latency = ms
            }
            if (result == PortResult.OPEN) open += port
        }
        if (!alive && !isGateway) return null

        for (port in extraPorts) {
            if (NetUtils.tcpProbe(ip, port, 250).first == PortResult.OPEN) open += port
        }

        val host = try {
            addr.canonicalHostName.takeIf { it != ip && !NetUtils.isIpv4(it) }
        } catch (_: Exception) {
            null
        }
        val clean = host
            ?.removeSuffix(".local")?.removeSuffix(".lan")
            ?.removeSuffix(".home")?.removeSuffix(".localdomain")

        return LanDevice(
            ip = ip,
            hostname = clean,
            openPorts = open.sorted(),
            latencyMs = latency,
            isGateway = isGateway,
            kind = inferKind(clean, open, isGateway),
        )
    }

    private fun inferKind(host: String?, ports: List<Int>, isGateway: Boolean): DeviceKind {
        val h = host?.lowercase().orEmpty()
        fun hostHas(vararg words: String) = words.any { h.contains(it) }
        return when {
            isGateway -> DeviceKind.Router
            62078 in ports || hostHas("iphone", "ipad", "macbook", "imac", "apple") -> DeviceKind.Apple
            hostHas("android", "galaxy", "pixel", "phone", "oneplus", "xiaomi") -> DeviceKind.Phone
            9100 in ports || 631 in ports || hostHas("printer", "epson", "brother", "canon", "hp-", "laserjet") -> DeviceKind.Printer
            8008 in ports || 8009 in ports || 7000 in ports || 32400 in ports ||
                hostHas("tv", "roku", "chromecast", "firetv", "shield", "sonos", "appletv") -> DeviceKind.Media
            554 in ports || hostHas("cam", "doorbell", "ring-") -> DeviceKind.Camera
            3389 in ports || 445 in ports || 139 in ports || hostHas("desktop", "laptop", "pc") -> DeviceKind.Computer
            22 in ports || 5000 in ports || hostHas("nas", "synology", "server", "raspberry", "pi") -> DeviceKind.Server
            else -> DeviceKind.Unknown
        }
    }

    private fun readArp(): Map<String, String> {
        val out = HashMap<String, String>()
        runCatching {
            File("/proc/net/arp").readLines().drop(1).forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 4 && parts[3] != "00:00:00:00:00:00") out[parts[0]] = parts[3].uppercase()
            }
        }
        if (out.isEmpty()) runCatching {
            val proc = ProcessBuilder("ip", "neigh").redirectErrorStream(true).start()
            val pattern = Regex("""^(\d+\.\d+\.\d+\.\d+) .*lladdr ([0-9a-fA-F:]{17})""")
            proc.inputStream.bufferedReader().readLines().forEach { line ->
                pattern.find(line)?.let { out[it.groupValues[1]] = it.groupValues[2].uppercase() }
            }
            proc.waitFor()
        }
        return out
    }
}
