package com.netpulse.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netpulse.app.AppViewModel
import com.netpulse.app.net.LineKind
import com.netpulse.app.net.LinkInfo
import com.netpulse.app.net.NetUtils
import com.netpulse.app.net.Tool
import com.netpulse.app.net.Transport
import com.netpulse.app.net.WifiDetails
import kotlinx.coroutines.delay

@Composable
fun RouterScreen(
    vm: AppViewModel,
    padding: PaddingValues,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
) {
    val link by vm.link.collectAsStateWithLifecycle()
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    val pub by vm.publicInfo.collectAsStateWithLifecycle()

    // Live signal strength while this screen is visible.
    LaunchedEffect(link.transport) {
        while (true) {
            vm.pollWifi()
            delay(2000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "hero") { RouterHero(link, wifi, hasPermission, onRequestPermission) }

        item(key = "internet") {
            SectionCard("Internet", Icons.Rounded.Public, trailing = {
                Pill(
                    when {
                        !link.connected -> "Offline"
                        link.validated -> "Online"
                        else -> "No internet"
                    },
                    container = if (link.validated) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
                    content = if (link.validated) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onErrorContainer,
                )
            }) {
                InfoRow(Icons.Rounded.Public, "Public IP", pub?.ip, mono = true)
                InfoRow(Icons.Rounded.Business, "Provider", pub?.isp)
                InfoRow(
                    Icons.Rounded.LocationOn, "Location",
                    pub?.let { listOfNotNull(it.city, it.region, it.country).joinToString(", ").ifBlank { null } },
                )
                InfoRow(Icons.Rounded.Cloud, "Nearest edge", pub?.colo?.let { "Cloudflare $it" })
                InfoRow(Icons.Rounded.NetworkCheck, "Metered", if (link.metered) "Yes" else "No")
            }
        }

        item(key = "lan") {
            SectionCard("Local network", Icons.Rounded.SettingsEthernet, trailing = {
                Pill(link.transport.label)
            }) {
                InfoRow(Icons.Rounded.Language, "IPv4 address", link.ipv4, mono = true)
                InfoRow(Icons.Rounded.SettingsEthernet, "Subnet mask", link.subnetMask?.let { "$it  /${link.prefix}" }, mono = true)
                InfoRow(Icons.Rounded.Router, "Gateway", link.gateway, mono = true)
                InfoRow(Icons.Rounded.Dns, "DNS servers", link.dns.joinToString("\n").ifBlank { null }, mono = true)
                if (link.privateDns != null) InfoRow(Icons.Rounded.Lock, "Private DNS", link.privateDns)
                InfoRow(Icons.Rounded.Language, "IPv6", link.ipv6.joinToString("\n").ifBlank { "Not available" }, mono = link.ipv6.isNotEmpty())
                wifi?.dhcpServer?.let { InfoRow(Icons.Rounded.Dns, "DHCP server", it, mono = true) }
                wifi?.leaseSeconds?.let { InfoRow(Icons.Rounded.Schedule, "DHCP lease", fmtDuration(it)) }
                InfoRow(Icons.Rounded.Memory, "Interface", link.interfaceName, mono = true)
                link.mtu?.let { InfoRow(Icons.Rounded.Straighten, "MTU", "$it bytes") }
            }
        }

        wifi?.let { w ->
            item(key = "wifi") {
                SectionCard("Wi-Fi link", Icons.Rounded.Wifi) {
                    InfoRow(Icons.Rounded.Wifi, "Network", w.ssid ?: "Permission needed")
                    InfoRow(Icons.Rounded.Fingerprint, "BSSID", w.bssid ?: "Permission needed", mono = w.bssid != null)
                    InfoRow(Icons.Rounded.SettingsInputAntenna, "Frequency", "${w.frequency} MHz · ch ${w.channel}")
                    InfoRow(Icons.Rounded.NetworkCheck, "Signal", "${w.rssi} dBm · ${NetUtils.signalLabel(w.rssi)}")
                    InfoRow(Icons.Rounded.Speed, "Link speed", "${w.linkSpeed} Mbps")
                    if (w.txSpeed != null || w.rxSpeed != null) {
                        InfoRow(Icons.Rounded.SwapVert, "Tx / Rx", "${w.txSpeed ?: "?"} / ${w.rxSpeed ?: "?"} Mbps")
                    }
                    w.standard?.let { InfoRow(Icons.Rounded.Router, "Standard", it) }
                    w.security?.let { InfoRow(Icons.Rounded.Security, "Security", it) }
                }
            }
        }

        item(key = "diag") { DiagnosticsCard(vm, link.gateway) }
    }
}

@Composable
private fun RouterHero(
    link: LinkInfo,
    wifi: WifiDetails?,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
) {
    val context = LocalContext.current
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = onContainer,
        ),
    ) {
        Box {
            WaveBackdrop(onContainer.copy(alpha = 0.10f), Modifier.matchParentSize())
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(Icons.Rounded.Router, onContainer, size = 52)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            "Default gateway",
                            style = MaterialTheme.typography.labelLarge,
                            color = onContainer.copy(alpha = 0.75f),
                        )
                        Text(
                            link.gateway ?: if (link.connected) "No gateway" else "Not connected",
                            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }

                if (wifi != null) {
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SignalRing(wifi.rssi, track = onContainer.copy(alpha = 0.15f))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                wifi.ssid ?: "Network name hidden",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                            )
                            Text(
                                "${wifi.band.label} · Channel ${wifi.channel} · ${wifi.linkSpeed} Mbps",
                                style = MaterialTheme.typography.bodyMedium,
                                color = onContainer.copy(alpha = 0.8f),
                            )
                            Text(
                                "Signal ${NetUtils.signalLabel(wifi.rssi).lowercase()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = onContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                } else if (link.transport != Transport.Offline) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Connected over ${link.transport.label}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = onContainer.copy(alpha = 0.8f),
                    )
                }

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (link.gateway != null) {
                        Button(
                            onClick = { openUrl(context, "http://${link.gateway}") },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = onContainer,
                                contentColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Admin page")
                        }
                    }
                    if (!hasPermission && link.transport == Transport.Wifi) {
                        FilledTonalButton(onClick = onRequestPermission) { Text("Show network name") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WaveBackdrop(color: Color, modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "waves").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "t",
    )
    Canvas(modifier) {
        val origin = Offset(size.width * 0.95f, size.height * 0.08f)
        val maxR = size.width * 0.95f
        for (i in 0 until 4) {
            val p = (t + i / 4f) % 1f
            drawCircle(color.copy(alpha = color.alpha * (1f - p)), maxR * p, origin, style = Stroke(2.dp.toPx()))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsCard(vm: AppViewModel, gateway: String?) {
    val console by vm.console.collectAsStateWithLifecycle()
    val running by vm.toolRunning.collectAsStateWithLifecycle()
    var tool by rememberSaveable { mutableStateOf(Tool.Ping) }
    var target by rememberSaveable { mutableStateOf("") }
    var ports by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()
    val run = { vm.runTool(tool, target, ports) }

    LaunchedEffect(console.size) {
        delay(16)
        scroll.animateScrollTo(scroll.maxValue)
    }

    SectionCard("Diagnostics", Icons.Rounded.Code) {
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Tool.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = tool == t,
                        onClick = { tool = t },
                        shape = SegmentedButtonDefaults.itemShape(i, Tool.entries.size),
                        icon = {},
                        label = { Text(t.label, maxLines = 1) },
                    )
                }
            }

            OutlinedTextField(
                value = target,
                onValueChange = { target = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Host or IP") },
                placeholder = { Text(gateway ?: "1.1.1.1") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { run() }),
                trailingIcon = {
                    if (target.isNotEmpty()) {
                        IconButton(onClick = { target = "" }) { Icon(Icons.Rounded.Clear, "Clear") }
                    }
                },
            )

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOfNotNull(gateway, "1.1.1.1", "8.8.8.8", "google.com").distinct().forEach { preset ->
                    SuggestionChip(
                        onClick = { target = preset },
                        label = { Text(if (preset == gateway) "Router" else preset) },
                    )
                }
            }

            AnimatedVisibility(
                visible = tool == Tool.Ports,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                OutlinedTextField(
                    value = ports,
                    onValueChange = { ports = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Ports (blank = common)") },
                    placeholder = { Text("22, 80, 8000-8100") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { run() }),
                )
            }

            Button(onClick = { if (running) vm.stopTool() else run() }, modifier = Modifier.fillMaxWidth()) {
                Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (running) "Stop" else "Run ${tool.label.lowercase()}")
            }

            Surface(color = ConsoleBg, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .heightIn(min = 140.dp, max = 300.dp)
                        .verticalScroll(scroll)
                        .padding(14.dp),
                ) {
                    if (console.isEmpty()) {
                        ConsoleText("Results appear here.", ConsoleDim)
                    }
                    console.forEach { line ->
                        val (prefix, color) = when (line.kind) {
                            LineKind.Cmd -> "$ " to ConsoleCmd
                            LineKind.Ok -> "" to ConsoleOk
                            LineKind.Err -> "" to ConsoleErr
                            LineKind.Dim -> "" to ConsoleDim
                            LineKind.Out -> "" to ConsoleOut
                        }
                        ConsoleText(prefix + line.text, color)
                    }
                    if (running) BlinkingCursor()
                }
            }
        }
    }
}

private val ConsoleBg = Color(0xFF0E1517)
private val ConsoleCmd = Color(0xFF7FDBCA)
private val ConsoleOut = Color(0xFFD6DEEB)
private val ConsoleOk = Color(0xFF9BE59B)
private val ConsoleErr = Color(0xFFFF7A85)
private val ConsoleDim = Color(0xFF7A8A99)

@Composable
private fun ConsoleText(text: String, color: Color) {
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
private fun BlinkingCursor() {
    val alpha by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(530), RepeatMode.Reverse),
        label = "blink",
    )
    ConsoleText("▌", ConsoleCmd.copy(alpha = alpha))
}
