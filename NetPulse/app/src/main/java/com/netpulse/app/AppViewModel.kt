package com.netpulse.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netpulse.app.net.ApState
import com.netpulse.app.net.ConsoleLine
import com.netpulse.app.net.Diagnostics
import com.netpulse.app.net.LanScanner
import com.netpulse.app.net.LineKind
import com.netpulse.app.net.LinkInfo
import com.netpulse.app.net.NetworkInspector
import com.netpulse.app.net.Phase
import com.netpulse.app.net.PublicInfo
import com.netpulse.app.net.ScanState
import com.netpulse.app.net.SpeedState
import com.netpulse.app.net.SpeedTester
import com.netpulse.app.net.Tool
import com.netpulse.app.net.Transport
import com.netpulse.app.net.WifiDetails
import com.netpulse.app.net.WifiScanner
import com.netpulse.app.net.fetchPublicInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val inspector = NetworkInspector(app)
    private val wifiScanner = WifiScanner(app)
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    private val _speed = MutableStateFlow(SpeedState())
    val speed: StateFlow<SpeedState> = _speed.asStateFlow()

    private val _link = MutableStateFlow(LinkInfo())
    val link: StateFlow<LinkInfo> = _link.asStateFlow()

    private val _wifi = MutableStateFlow<WifiDetails?>(null)
    val wifi: StateFlow<WifiDetails?> = _wifi.asStateFlow()

    private val _public = MutableStateFlow<PublicInfo?>(null)
    val publicInfo: StateFlow<PublicInfo?> = _public.asStateFlow()

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    private val _aps = MutableStateFlow(ApState())
    val aps: StateFlow<ApState> = _aps.asStateFlow()

    private val _console = MutableStateFlow<List<ConsoleLine>>(emptyList())
    val console: StateFlow<List<ConsoleLine>> = _console.asStateFlow()

    private val _toolRunning = MutableStateFlow(false)
    val toolRunning: StateFlow<Boolean> = _toolRunning.asStateFlow()

    private var speedJob: Job? = null
    private var scanJob: Job? = null
    private var apJob: Job? = null
    private var toolJob: Job? = null
    private var refreshJob: Job? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleRefresh()
        override fun onLost(network: Network) = scheduleRefresh()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = scheduleRefresh()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // Fires on every signal change; only the cheap Wi-Fi poll is needed here.
            pollWifi()
        }
    }

    init {
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        refreshNetwork()
    }

    override fun onCleared() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(600)
            refreshNetwork()
        }
    }

    fun refreshNetwork(fetchPublic: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            val l = inspector.link()
            _link.value = l
            _wifi.value = if (l.transport == Transport.Wifi || l.transport == Transport.Vpn) {
                runCatching { inspector.wifi() }.getOrNull()
            } else null
            if (!l.connected) {
                _public.value = null
            } else if (fetchPublic) {
                runCatching { fetchPublicInfo() }.getOrNull()?.let { _public.value = it }
            }
        }
    }

    fun pollWifi() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_link.value.transport == Transport.Wifi || _link.value.transport == Transport.Vpn) {
                runCatching { inspector.wifi() }.getOrNull()?.let { _wifi.value = it }
            }
        }
    }

    // ---------------- Speed test ----------------

    fun startSpeedTest() {
        speedJob?.cancel()
        speedJob = viewModelScope.launch {
            SpeedTester().run()
                .catch { e ->
                    _speed.value = _speed.value.copy(phase = Phase.Error, error = e.message ?: "Test failed")
                }
                .collect { _speed.value = it }
        }
    }

    fun cancelSpeedTest() {
        speedJob?.cancel()
        _speed.value = SpeedState()
    }

    // ---------------- LAN scan ----------------

    fun scanLan() {
        val l = _link.value
        val ip = l.ipv4
        val prefix = l.prefix
        if (!l.lanCapable || ip == null || prefix == null) {
            _scan.value = ScanState(error = "Connect to Wi-Fi or Ethernet to discover devices on your network.")
            return
        }
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            LanScanner.scan(ip, prefix, l.gateway)
                .catch { e -> _scan.value = _scan.value.copy(running = false, error = e.message) }
                .collect { _scan.value = it }
        }
    }

    fun stopLanScan() {
        scanJob?.cancel()
        _scan.value = _scan.value.copy(running = false)
    }

    // ---------------- Access points ----------------

    fun scanAccessPoints() {
        if (apJob?.isActive == true) return
        apJob = viewModelScope.launch {
            _aps.value = _aps.value.copy(scanning = true, error = null)
            _aps.value = try {
                val (list, throttled) = wifiScanner.scan()
                ApState(scanning = false, aps = list, lastScan = System.currentTimeMillis(), throttled = throttled)
            } catch (e: SecurityException) {
                _aps.value.copy(scanning = false, error = "Location permission is required to scan.")
            } catch (e: Exception) {
                _aps.value.copy(scanning = false, error = e.message ?: "Scan failed")
            }
        }
    }

    // ---------------- Diagnostics ----------------

    fun runTool(tool: Tool, target: String, ports: String) {
        toolJob?.cancel()
        val host = target.trim().ifEmpty { _link.value.gateway ?: "1.1.1.1" }
        _console.value = emptyList()
        toolJob = viewModelScope.launch {
            _toolRunning.value = true
            try {
                Diagnostics.run(tool, host, ports).collect { line ->
                    _console.value = (_console.value + line).takeLast(400)
                }
            } finally {
                _toolRunning.value = false
            }
        }
    }

    fun stopTool() {
        if (toolJob?.isActive == true) {
            toolJob?.cancel()
            _console.value = _console.value + ConsoleLine("^C", LineKind.Dim)
        }
    }
}
