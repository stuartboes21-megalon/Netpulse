package com.netpulse.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DeviceUnknown
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netpulse.app.AppViewModel
import com.netpulse.app.net.DeviceKind
import com.netpulse.app.net.Diagnostics
import com.netpulse.app.net.LanDevice
import com.netpulse.app.net.LanScanner
import com.netpulse.app.net.NetUtils
import com.netpulse.app.net.PortScanState
import com.netpulse.app.net.ScanState
import com.netpulse.app.ui.theme.Semantic

fun kindIcon(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.Router -> Icons.Rounded.Router
    DeviceKind.ThisDevice, DeviceKind.Phone -> Icons.Rounded.Smartphone
    DeviceKind.Apple -> Icons.Rounded.PhoneIphone
    DeviceKind.Computer -> Icons.Rounded.Computer
    DeviceKind.Printer -> Icons.Rounded.Print
    DeviceKind.Media -> Icons.Rounded.Tv
    DeviceKind.Camera -> Icons.Rounded.Videocam
    DeviceKind.Server -> Icons.Rounded.Storage
    DeviceKind.Unknown -> Icons.Rounded.DeviceUnknown
}

@Composable
fun DevicesScreen(vm: AppViewModel, padding: PaddingValues) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<LanDevice?>(null) }

    LaunchedEffect(link.lanCapable) {
        if (link.lanCapable && !scan.running && scan.finishedAt == null && scan.devices.isEmpty()) vm.scanLan()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "hero") {
            ScanHero(scan, onScan = vm::scanLan, onStop = vm::stopLanScan)
        }
        scan.error?.let { err ->
            item(key = "error") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Info, null)
                        Spacer(Modifier.width(12.dp))
                        Text(err, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (scan.devices.isNotEmpty()) {
            item(key = "header") {
                Text(
                    "${scan.devices.size} devices on ${scan.subnet ?: "your network"}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            }
        }
        items(scan.devices, key = { it.ip }) { device ->
            DeviceRow(device, Modifier.animateItem()) { selected = device }
        }
    }

    selected?.let { DeviceSheet(it) { selected = null } }
}

@Composable
private fun ScanHero(scan: ScanState, onScan: () -> Unit, onStop: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val blips = remember(scan.devices) {
        scan.devices.filter { !it.isSelf }.map { d ->
            val octet = d.ip.substringAfterLast('.').toIntOrNull() ?: 0
            Blip(
                angle = (octet * 137.5f) % 360f,
                radius = 0.3f + 0.62f * ((d.latencyMs ?: 60L).coerceIn(0L, 200L) / 200f),
                color = if (d.isGateway) primary else tertiary,
            )
        }
    }
    val progress by animateFloatAsState(
        if (scan.total > 0) scan.scanned / scan.total.toFloat() else 0f, label = "scanProgress",
    )

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Radar(scan.running, blips, Modifier.size(230.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.85f),
                ) {
                    Column(
                        Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RollingText("${scan.devices.size}", MaterialTheme.typography.displaySmall)
                        Text(
                            if (scan.devices.size == 1) "device" else "devices",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            AnimatedVisibility(
                visible = scan.running,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        strokeCap = StrokeCap.Round,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Probing ${scan.scanned} of ${scan.total} addresses on ${scan.subnet ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (scan.running) {
                FilledTonalButton(onClick = onStop) {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Stop scan")
                }
            } else {
                Button(onClick = onScan) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (scan.finishedAt == null) "Scan network" else "Scan again")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceRow(d: LanDevice, modifier: Modifier, onClick: () -> Unit) {
    val highlight = d.isSelf || d.isGateway
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (d.isGateway) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(
                kindIcon(d.kind),
                if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                size = 46,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        d.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (d.isSelf) Pill("You", container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
                    if (d.isGateway) Pill("Gateway", container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
                }
                Text(
                    listOfNotNull(d.ip, d.mac).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (d.openPorts.isNotEmpty()) {
                    Text(
                        d.openPorts.take(4).joinToString("  ") { NetUtils.serviceName(it) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            d.latencyMs?.takeIf { !d.isSelf }?.let { ms ->
                Spacer(Modifier.width(8.dp))
                LatencyBadge(ms)
            }
        }
    }
}

@Composable
private fun LatencyBadge(ms: Long) {
    val color = when {
        ms < 20 -> Semantic.Excellent
        ms < 80 -> Semantic.Good
        ms < 200 -> Semantic.Fair
        else -> Semantic.Poor
    }
    Pill("$ms ms", container = color.copy(alpha = 0.16f), content = color)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DeviceSheet(d: LanDevice, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ports by remember(d.ip) { LanScanner.portScan(d.ip) }
        .collectAsStateWithLifecycle(PortScanState(0, NetUtils.commonPorts.size, d.openPorts))
    val ping by produceState<String?>(initialValue = null, d.ip) {
        value = Diagnostics.quickPing(d.ip)?.let { "%.1f ms".format(it) } ?: "No ICMP reply"
    }
    val open = (ports.open + d.openPorts).distinct().sorted()
    val webPort = listOf(80, 8080, 443, 8443, 5000).firstOrNull { it in open }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(kindIcon(d.kind), MaterialTheme.colorScheme.primary, size = 60)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(d.kind.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            InfoRow(Icons.Rounded.Language, "IP address", d.ip, mono = true)
            InfoRow(Icons.Rounded.Fingerprint, "MAC address", d.mac ?: "Hidden by Android", mono = d.mac != null)
            InfoRow(Icons.Rounded.Dns, "Hostname", d.hostname ?: "No reverse DNS")
            InfoRow(Icons.Rounded.Timer, "Average ping", ping ?: "Measuring…")

            Spacer(Modifier.height(16.dp))
            Text("Open ports", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))
            Spacer(Modifier.height(8.dp))
            AnimatedVisibility(visible = ports.done < ports.total) {
                Column {
                    LinearProgressIndicator(
                        progress = { ports.done / ports.total.toFloat() },
                        modifier = Modifier.fillMaxWidth().clip(CircleShape),
                        strokeCap = StrokeCap.Round,
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (open.isEmpty() && ports.done >= ports.total) {
                Text(
                    "No common ports are open — this device is likely firewalled.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                open.forEach { p ->
                    Pill(
                        "$p · ${NetUtils.serviceName(p)}",
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            if (webPort != null) {
                Spacer(Modifier.height(20.dp))
                FilledTonalButton(
                    onClick = {
                        val scheme = if (webPort == 443 || webPort == 8443) "https" else "http"
                        openUrl(context, "$scheme://${d.ip}:$webPort")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open web interface")
                }
            }
        }
    }
}

