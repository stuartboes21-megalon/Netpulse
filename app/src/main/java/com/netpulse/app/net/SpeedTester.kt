package com.netpulse.app.net

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

enum class Phase { Idle, Latency, Download, Upload, Done, Error }

data class SpeedState(
    val phase: Phase = Phase.Idle,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
    val liveMbps: Double = 0.0,
    val progress: Float = 0f,
    val downSamples: List<Float> = emptyList(),
    val upSamples: List<Float> = emptyList(),
    val server: String? = null,
    val isp: String? = null,
    val error: String? = null,
) {
    val running: Boolean get() = phase == Phase.Latency || phase == Phase.Download || phase == Phase.Upload
}

/**
 * Multi-connection throughput test against Cloudflare's public speed endpoints.
 * Latency is measured over a warm keep-alive connection with server processing time subtracted.
 */
class SpeedTester(
    private val connections: Int = 4,
    private val downloadMs: Long = 10_000,
    private val uploadMs: Long = 8_000,
) {
    private val base = "https://speed.cloudflare.com"

    fun run(): Flow<SpeedState> = channelFlow {
        var state = SpeedState(phase = Phase.Latency)
        send(state)

        val meta = runCatching { fetchPublicInfo() }.getOrNull()
        state = state.copy(
            server = listOfNotNull("Cloudflare", meta?.colo, meta?.city).joinToString(" · "),
            isp = meta?.isp,
        )

        // ---- Latency ----
        val rtts = mutableListOf<Double>()
        val rounds = 14
        for (i in 0 until rounds) {
            val rtt = measureRtt()
            if (i >= 2 && rtt != null) rtts += rtt // first two warm up DNS + TLS
            val sorted = rtts.sorted()
            state = state.copy(
                progress = (i + 1f) / rounds,
                pingMs = sorted.getOrNull(sorted.size / 2),
                jitterMs = jitter(rtts),
            )
            send(state)
        }
        if (rtts.isEmpty()) throw IOException("Couldn't reach the test server. Check your connection.")

        // ---- Download ----
        state = state.copy(phase = Phase.Download, progress = 0f, liveMbps = 0.0)
        send(state)
        val down = transfer(upload = false, durationMs = downloadMs) { mbps, p, samples ->
            state = state.copy(liveMbps = mbps, progress = p, downSamples = samples)
            send(state)
        }
        state = state.copy(downMbps = down, liveMbps = down, progress = 1f)
        send(state)
        delay(500)

        // ---- Upload ----
        state = state.copy(phase = Phase.Upload, progress = 0f, liveMbps = 0.0)
        send(state)
        val up = transfer(upload = true, durationMs = uploadMs) { mbps, p, samples ->
            state = state.copy(liveMbps = mbps, progress = p, upSamples = samples)
            send(state)
        }
        state = state.copy(upMbps = up, liveMbps = up, progress = 1f, phase = Phase.Done)
        send(state)
    }.flowOn(Dispatchers.IO)

    private fun jitter(values: List<Double>): Double? =
        if (values.size < 2) null else values.zipWithNext { a, b -> abs(a - b) }.average()

    private fun measureRtt(): Double? = try {
        val c = URL("$base/__down?bytes=0").openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        c.useCaches = false
        c.setRequestProperty("Cache-Control", "no-cache")
        val t0 = System.nanoTime()
        val code = c.responseCode
        val totalMs = (System.nanoTime() - t0) / 1e6
        c.inputStream.use { it.readBytes() }
        val serverMs = c.getHeaderField("Server-Timing")
            ?.let { Regex("""dur=([0-9.]+)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
            ?: 0.0
        if (code in 200..299) (totalMs - serverMs).coerceAtLeast(0.1) else null
    } catch (e: IOException) {
        null
    }

    private suspend fun transfer(
        upload: Boolean,
        durationMs: Long,
        onTick: suspend (mbps: Double, progress: Float, samples: List<Float>) -> Unit,
    ): Double = coroutineScope {
        val bytes = AtomicLong(0)
        val start = SystemClock.elapsedRealtime()
        val deadline = start + durationMs

        val workers = List(connections) {
            launch(Dispatchers.IO) {
                val buf = ByteArray(64 * 1024)
                if (upload) Random.nextBytes(buf)
                while (isActive && SystemClock.elapsedRealtime() < deadline) {
                    try {
                        if (upload) uploadOnce(buf, bytes, deadline) else downloadOnce(buf, bytes, deadline)
                    } catch (e: IOException) {
                        delay(200)
                    }
                }
            }
        }

        // Sample a rolling ~1.2 s window for the live needle; ignore the TCP ramp-up for the final figure.
        val samples = ArrayList<Float>()
        val window = ArrayDeque<Pair<Long, Long>>()
        val warmup = durationMs / 5
        var stableBytes = -1L
        var stableTime = 0L
        while (true) {
            delay(150)
            val now = SystemClock.elapsedRealtime()
            val b = bytes.get()
            window.addLast(now to b)
            while (window.size > 2 && now - window.first().first > 1200) window.removeFirst()
            val (t0, b0) = window.first()
            val mbps = if (now > t0) (b - b0) * 8.0 / ((now - t0) / 1000.0) / 1e6 else 0.0
            if (stableBytes < 0 && now - start >= warmup) {
                stableBytes = b
                stableTime = now
            }
            samples += mbps.toFloat()
            onTick(mbps, ((now - start).toFloat() / durationMs).coerceIn(0f, 1f), samples.toList())
            if (now >= deadline) break
        }

        val end = SystemClock.elapsedRealtime()
        val total = bytes.get()
        workers.forEach { it.cancel() }
        if (stableBytes >= 0 && end > stableTime) {
            (total - stableBytes) * 8.0 / ((end - stableTime) / 1000.0) / 1e6
        } else {
            total * 8.0 / ((end - start).coerceAtLeast(1) / 1000.0) / 1e6
        }
    }

    private fun downloadOnce(buf: ByteArray, counter: AtomicLong, deadline: Long) {
        val c = URL("$base/__down?bytes=$DOWN_CHUNK").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.useCaches = false
        c.setRequestProperty("Accept-Encoding", "identity")
        try {
            c.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    counter.addAndGet(n.toLong())
                    if (SystemClock.elapsedRealtime() >= deadline) break
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun uploadOnce(buf: ByteArray, counter: AtomicLong, deadline: Long) {
        val c = URL("$base/__up").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 8000
        c.doOutput = true
        c.requestMethod = "POST"
        c.setFixedLengthStreamingMode(UP_CHUNK)
        c.setRequestProperty("Content-Type", "application/octet-stream")
        try {
            c.outputStream.use { out ->
                var sent = 0L
                while (sent < UP_CHUNK) {
                    val n = min(buf.size.toLong(), UP_CHUNK - sent).toInt()
                    out.write(buf, 0, n)
                    sent += n
                    counter.addAndGet(n.toLong())
                    if (SystemClock.elapsedRealtime() >= deadline) return
                }
            }
            c.responseCode
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val DOWN_CHUNK = 25_000_000
        const val UP_CHUNK = 4_000_000L
    }
}
