package com.netpulse.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

enum class Tool(val label: String) { Ping("Ping"), Trace("Trace"), Dns("DNS"), Ports("Ports") }

enum class LineKind { Cmd, Out, Ok, Err, Dim }

data class ConsoleLine(val text: String, val kind: LineKind)

object Diagnostics {
    private const val PING = "/system/bin/ping"

    fun run(tool: Tool, host: String, ports: String): Flow<ConsoleLine> = when (tool) {
        Tool.Ping -> ping(host)
        Tool.Trace -> traceroute(host)
        Tool.Dns -> dns(host)
        Tool.Ports -> portScan(host, NetUtils.parsePorts(ports))
    }

    fun ping(host: String, count: Int = 6): Flow<ConsoleLine> = flow {
        emit(ConsoleLine("ping -c $count $host", LineKind.Cmd))
        val proc = ProcessBuilder(PING, "-c", "$count", "-W", "2", host).redirectErrorStream(true).start()
        try {
            val reader = proc.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) emit(ConsoleLine(line, classify(line)))
            }
            proc.waitFor()
        } finally {
            proc.destroy()
        }
    }.flowOn(Dispatchers.IO).catch { emit(ConsoleLine(it.message ?: "ping failed", LineKind.Err)) }

    fun traceroute(host: String, maxHops: Int = 24): Flow<ConsoleLine> = flow {
        emit(ConsoleLine("traceroute $host", LineKind.Cmd))
        val target = InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress
            ?: throw IOException("No IPv4 address for $host")
        emit(ConsoleLine("Route to $target, $maxHops hops max", LineKind.Dim))
        val fromRe = Regex("""[Ff]rom ([0-9.]+)""")
        for (ttl in 1..maxHops) {
            val out = exec(PING, "-c", "1", "-W", "1", "-t", "$ttl", target)
            val label = ttl.toString().padStart(2)
            when {
                out.contains("bytes from") -> {
                    emit(ConsoleLine("$label  $target  ${timeOf(out) ?: "?"} ms  ✓ reached", LineKind.Ok))
                    return@flow
                }
                fromRe.containsMatchIn(out) -> {
                    val hop = fromRe.find(out)!!.groupValues[1]
                    val rtt = timeOf(exec(PING, "-c", "1", "-W", "1", hop))
                    val name = runCatching { InetAddress.getByName(hop).canonicalHostName }
                        .getOrNull()?.takeIf { it != hop }
                    val shown = if (name != null) "$hop ($name)" else hop
                    emit(ConsoleLine("$label  $shown  ${rtt ?: "*"} ms", LineKind.Out))
                }
                else -> emit(ConsoleLine("$label  * * *", LineKind.Dim))
            }
        }
        emit(ConsoleLine("Gave up after $maxHops hops", LineKind.Err))
    }.flowOn(Dispatchers.IO).catch { emit(ConsoleLine(it.message ?: "traceroute failed", LineKind.Err)) }

    fun dns(host: String): Flow<ConsoleLine> = flow {
        emit(ConsoleLine("nslookup $host", LineKind.Cmd))
        val t0 = System.nanoTime()
        val all = InetAddress.getAllByName(host)
        val ms = (System.nanoTime() - t0) / 1e6
        if (NetUtils.isIpv4(host)) {
            val name = all.first().canonicalHostName
            emit(
                if (name != host) ConsoleLine("PTR   $name", LineKind.Ok)
                else ConsoleLine("No reverse DNS record", LineKind.Dim)
            )
        } else {
            all.forEach { a ->
                val type = if (a is Inet6Address) "AAAA" else "A   "
                emit(ConsoleLine("$type  ${a.hostAddress}", LineKind.Ok))
            }
        }
        emit(ConsoleLine("Answered in %.1f ms by the system resolver".format(ms), LineKind.Dim))
    }.flowOn(Dispatchers.IO).catch { emit(ConsoleLine("Lookup failed: ${it.message}", LineKind.Err)) }

    fun portScan(host: String, ports: List<Int>): Flow<ConsoleLine> = channelFlow {
        send(ConsoleLine("portscan $host  (${ports.size} ports)", LineKind.Cmd))
        val ip = withContext(Dispatchers.IO) { InetAddress.getByName(host).hostAddress }
            ?: throw IOException("Can't resolve $host")
        val open = AtomicInteger()
        val verbose = ports.size <= 8
        val sem = Semaphore(32)
        coroutineScope {
            ports.forEach { p ->
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        val (r, ms) = NetUtils.tcpProbe(ip, p, 800)
                        val port = "$p/tcp".padEnd(10)
                        when {
                            r == PortResult.OPEN -> {
                                open.incrementAndGet()
                                send(ConsoleLine("$port open      ${NetUtils.serviceName(p).padEnd(10)} $ms ms", LineKind.Ok))
                            }
                            verbose -> send(
                                ConsoleLine(
                                    "$port ${if (r == PortResult.CLOSED) "closed" else "filtered"}",
                                    LineKind.Dim,
                                )
                            )
                        }
                    }
                }
            }
        }
        send(ConsoleLine("Done — ${open.get()} open of ${ports.size} scanned", LineKind.Out))
    }.catch { emit(ConsoleLine(it.message ?: "Scan failed", LineKind.Err)) }

    suspend fun quickPing(host: String): Double? = withContext(Dispatchers.IO) {
        val out = runCatching { exec(PING, "-c", "3", "-i", "0.3", "-W", "1", host) }.getOrNull()
            ?: return@withContext null
        Regex("""= [0-9.]+/([0-9.]+)/""").find(out)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun exec(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        return try {
            p.inputStream.bufferedReader().use { it.readText() }.also { p.waitFor() }
        } finally {
            p.destroy()
        }
    }

    private fun timeOf(out: String): String? = Regex("""time=([0-9.]+)""").find(out)?.groupValues?.get(1)

    private fun classify(line: String): LineKind = when {
        line.contains("bytes from") -> LineKind.Ok
        line.contains("unknown host", true) || line.contains("unreachable", true) ||
            line.contains("100% packet loss") || line.contains("exceeded", true) -> LineKind.Err
        line.startsWith("---") -> LineKind.Dim
        else -> LineKind.Out
    }
}
