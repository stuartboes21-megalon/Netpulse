package com.netpulse.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netpulse.app.AppViewModel
import com.netpulse.app.net.Phase
import com.netpulse.app.net.SpeedState
import com.netpulse.app.net.Transport

@Composable
fun SpeedScreen(vm: AppViewModel, padding: PaddingValues) {
    val s by vm.speed.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    val pub by vm.publicInfo.collectAsStateWithLifecycle()

    val downColor = MaterialTheme.colorScheme.primary
    val upColor = MaterialTheme.colorScheme.tertiary
    val phaseColor by animateColorAsState(
        if (s.phase == Phase.Upload) upColor else downColor, tween(600), label = "phaseColor",
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "conn") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (icon, name) = when (link.transport) {
                    Transport.Wifi -> Icons.Rounded.Wifi to (wifi?.ssid ?: "Wi-Fi")
                    Transport.Ethernet -> Icons.Rounded.SettingsEthernet to "Ethernet"
                    Transport.Cellular -> Icons.Rounded.SignalCellularAlt to "Cellular"
                    Transport.Vpn -> Icons.Rounded.VpnKey to "VPN"
                    Transport.Offline -> Icons.Rounded.WifiOff to "Offline"
                    Transport.Other -> Icons.Rounded.Cloud to "Connected"
                }
                Pill(name, icon = icon, modifier = Modifier.weight(1f, fill = false))
                val isp = s.isp ?: pub?.isp
                if (isp != null) {
                    Pill(
                        isp,
                        icon = Icons.Rounded.Cloud,
                        container = MaterialTheme.colorScheme.surfaceContainerHigh,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }

        item(key = "hero") {
            Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = s.phase == Phase.Idle,
                    transitionSpec = {
                        (fadeIn(tween(450)) + scaleIn(tween(450), initialScale = 0.8f)) togetherWith
                            (fadeOut(tween(200)) + scaleOut(tween(250), targetScale = 1.12f))
                    },
                    contentAlignment = Alignment.Center,
                    label = "hero",
                ) { idle ->
                    if (idle) {
                        GoButton(onClick = vm::startSpeedTest)
                    } else {
                        GaugePanel(s, phaseColor)
                    }
                }
            }
        }

        item(key = "stepper") {
            AnimatedVisibility(
                visible = s.phase != Phase.Idle,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                PhaseStepper(s, phaseColor)
            }
        }

        item(key = "stats") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        Icons.Rounded.ArrowDownward, "Download", fmtMbps(s.downMbps), "Mbps",
                        downColor, s.phase == Phase.Download, Modifier.weight(1f),
                    )
                    StatTile(
                        Icons.Rounded.ArrowUpward, "Upload", fmtMbps(s.upMbps), "Mbps",
                        upColor, s.phase == Phase.Upload, Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        Icons.Rounded.Timer, "Ping", fmtMs(s.pingMs), "ms",
                        MaterialTheme.colorScheme.secondary, s.phase == Phase.Latency, Modifier.weight(1f),
                    )
                    StatTile(
                        Icons.Rounded.GraphicEq, "Jitter", fmtMs(s.jitterMs), "ms",
                        MaterialTheme.colorScheme.secondary, s.phase == Phase.Latency, Modifier.weight(1f),
                    )
                }
            }
        }

        item(key = "chart") {
            AnimatedVisibility(
                visible = s.downSamples.size > 1,
                enter = fadeIn(tween(500)) + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Throughput", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Legend("Down", downColor)
                            Spacer(Modifier.width(12.dp))
                            Legend("Up", upColor)
                        }
                        Spacer(Modifier.height(12.dp))
                        LiveChart(
                            series = listOf(s.downSamples to downColor, s.upSamples to upColor),
                            modifier = Modifier.fillMaxWidth().height(130.dp),
                        )
                        s.server?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Server: $it",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item(key = "result") {
            ResultArea(s, onRetry = vm::startSpeedTest, onCancel = vm::cancelSpeedTest)
        }
    }
}

@Composable
private fun GaugePanel(s: SpeedState, color: Color) {
    val (value, number, unit, caption) = when (s.phase) {
        Phase.Latency -> Quad(0f, fmtMs(s.pingMs), "ms", "Measuring latency…")
        Phase.Download -> Quad(s.liveMbps.toFloat(), fmtMbps(s.liveMbps), "Mbps", "Download")
        Phase.Upload -> Quad(s.liveMbps.toFloat(), fmtMbps(s.liveMbps), "Mbps", "Upload")
        Phase.Done -> Quad((s.downMbps ?: 0.0).toFloat(), fmtMbps(s.downMbps), "Mbps down", "Complete")
        else -> Quad(0f, "—", "", "Test failed")
    }
    SpeedGauge(
        value = value,
        accent = color,
        active = s.running,
        modifier = Modifier.size(310.dp),
    ) {
        Text(caption, style = MaterialTheme.typography.labelLarge, color = color)
        Text(
            number,
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
        Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class Quad(val a: Float, val b: String, val c: String, val d: String)

private fun phaseIndex(p: Phase) = when (p) {
    Phase.Latency -> 1
    Phase.Download -> 2
    Phase.Upload -> 3
    Phase.Done -> 4
    else -> 0
}

@Composable
private fun PhaseStepper(s: SpeedState, color: Color) {
    val steps = listOf(
        Triple(Phase.Latency, "Ping", Icons.Rounded.Timer),
        Triple(Phase.Download, "Download", Icons.Rounded.ArrowDownward),
        Triple(Phase.Upload, "Upload", Icons.Rounded.ArrowUpward),
    )
    val current = phaseIndex(s.phase)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEach { (phase, label, icon) ->
            val idx = phaseIndex(phase)
            val state = when {
                current > idx -> 2
                current == idx -> 1
                else -> 0
            }
            val progress by animateFloatAsState(
                when (state) {
                    2 -> 1f
                    1 -> s.progress
                    else -> 0f
                },
                label = "step",
            )
            val tint by animateColorAsState(
                if (state > 0) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                label = "stepTint",
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (state == 2) Icons.Rounded.CheckCircle else icon, null, tint = tint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultArea(s: SpeedState, onRetry: () -> Unit, onCancel: () -> Unit) {
    AnimatedContent(
        targetState = when {
            s.running -> 1
            s.phase == Phase.Done -> 2
            s.phase == Phase.Error -> 3
            else -> 0
        },
        transitionSpec = { (fadeIn(tween(400)) + expandVertically()) togetherWith fadeOut(tween(150)) },
        label = "result",
    ) { mode ->
        when (mode) {
            1 -> OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel test") }
            2 -> {
                val (title, body) = verdict(s.downMbps ?: 0.0, s.upMbps ?: 0.0, s.pingMs ?: 999.0)
                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null)
                            Spacer(Modifier.width(10.dp))
                            Text(title, style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(body, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                        FilledTonalButton(onClick = onRetry) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Test again")
                        }
                    }
                }
            }
            3 -> Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Error, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Test failed", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(s.error ?: "Something went wrong.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onRetry) { Text("Try again") }
                }
            }
            else -> Text(
                "Measures latency, then download and upload over 4 parallel connections to the nearest Cloudflare edge.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

private fun verdict(down: Double, up: Double, ping: Double): Pair<String, String> {
    val base = when {
        down >= 200 && ping < 30 -> "Blazing fast" to "4K on every screen, huge downloads in seconds and competitive gaming — all at once."
        down >= 50 && ping < 60 -> "Great connection" to "Smooth 4K streaming, crisp video calls and lag-free gaming."
        down >= 15 -> "Good for everyday use" to "HD streaming and video calls run well; large downloads take a little longer."
        down >= 5 -> "Fair" to "Fine for browsing and SD video. Video calls may stutter when others are online."
        else -> "Slow connection" to "Expect buffering. Try moving closer to your router or restarting it."
    }
    return if (up < 3 && down >= 15) base.first to base.second + " Upload is limited, so sending large files or streaming yourself will be slow."
    else base
}
