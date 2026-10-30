package com.netpulse.app.net

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

data class AccessPoint(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val centerFreq: Int,
    val channel: Int,
    val band: Band,
    val widthMhz: Int,
    val security: String,
    val standard: String?,
    val connected: Boolean,
    val hidden: Boolean,
)

data class ApState(
    val scanning: Boolean = false,
    val aps: List<AccessPoint> = emptyList(),
    val lastScan: Long? = null,
    val throttled: Boolean = false,
    val error: String? = null,
)

class WifiScanner(context: Context) {
    private val app = context.applicationContext
    private val wm = app.getSystemService(WifiManager::class.java)

    /** Requests a fresh scan; Android throttles apps to 4 scans per 2 minutes, so we fall back to cached results. */
    suspend fun scan(): Pair<List<AccessPoint>, Boolean> {
        val ready = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                ready.complete(Unit)
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            @Suppress("DEPRECATION")
            val started = wm.startScan()
            if (started) withTimeoutOrNull(8000) { ready.await() }
            return results() to !started
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun results(): List<AccessPoint> {
        val connected = wm.connectionInfo?.bssid
        return wm.scanResults.orEmpty().map { r ->
            val width = when (r.channelWidth) {
                ScanResult.CHANNEL_WIDTH_40MHZ -> 40
                ScanResult.CHANNEL_WIDTH_80MHZ -> 80
                ScanResult.CHANNEL_WIDTH_160MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
                5 -> 320
                else -> 20
            }
            val band = NetUtils.bandOf(r.frequency)
            val ssid = r.SSID.orEmpty()
            AccessPoint(
                ssid = ssid.ifBlank { "Hidden network" },
                bssid = r.BSSID.orEmpty().uppercase(),
                rssi = r.level,
                frequency = r.frequency,
                centerFreq = if (width > 20 && r.centerFreq0 > 0) r.centerFreq0 else r.frequency,
                channel = NetUtils.freqToChannel(r.frequency),
                band = band,
                widthMhz = width,
                security = securityOf(r.capabilities.orEmpty()),
                standard = if (Build.VERSION.SDK_INT >= 30) NetUtils.standardName(r.wifiStandard, band) else null,
                connected = connected != null && r.BSSID.equals(connected, ignoreCase = true),
                hidden = ssid.isBlank(),
            )
        }.sortedByDescending { it.rssi }.distinctBy { it.bssid }
    }

    private fun securityOf(caps: String): String = when {
        caps.contains("SAE") && caps.contains("PSK") -> "WPA2/WPA3"
        caps.contains("SAE") -> "WPA3"
        caps.contains("EAP") -> "Enterprise"
        caps.contains("OWE") -> "OWE"
        caps.contains("RSN") || caps.contains("WPA2") -> "WPA2"
        caps.contains("WPA") -> "WPA"
        caps.contains("WEP") -> "WEP"
        else -> "Open"
    }
}

/** Picks the least congested channel, weighting neighbours by signal strength and spectral overlap. */
fun recommendChannel(aps: List<AccessPoint>, band: Band): Int {
    val candidates = when (band) {
        Band.B24 -> listOf(1, 6, 11)
        Band.B5 -> listOf(36, 40, 44, 48, 149, 153, 157, 161, 165)
        Band.B6 -> listOf(5, 37, 69, 101, 133, 165, 197)
    }
    val inBand = aps.filter { it.band == band }
    return candidates.minBy { ch ->
        val f = NetUtils.channelCenter(band, ch)
        inBand.sumOf { ap ->
            val weight = ((ap.rssi + 100).coerceAtLeast(1) / 70.0).let { it * it }
            val overlap = if (band == Band.B24) {
                val d = abs(ap.channel - ch)
                if (d < 5) (5 - d) / 5.0 else 0.0
            } else {
                if (abs(ap.centerFreq - f) < ap.widthMhz / 2f + 10f) 1.0 else 0.0
            }
            weight * overlap
        }
    }
}
