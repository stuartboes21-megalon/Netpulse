package com.netpulse.app.ui

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netpulse.app.AppViewModel
import com.netpulse.app.net.AccessPoint
import com.netpulse.app.net.Band
import com.netpulse.app.net.NetUtils
import com.netpulse.app.net.recommendChannel
import com.netpulse.app.ui.theme.Semantic

private fun isLocationEnabled(context: Context): Boolean {
    val lm = context.getSystemService(LocationManager::class.java) ?: return true
    return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
    else lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) || lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessPointsScreen(
    vm: AppViewModel,
    padding: PaddingValues,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
) {
    val context = LocalContext.current
    val state by vm.aps.collectAsStateWithLifecycle()
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    var locationOn by remember { mutableStateOf(isLocationEnabled(context)) }
    var band by rememberSaveable { mutableStateOf(wifi?.band ?: Band.B24) }

    LifecycleResumeEffect(Unit) {
        locationOn = isLocationEnabled(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(hasPermission) {
        if (hasPermission && state.lastScan == null) vm.scanAccessPoints()
    }

    val inBand = state.aps.filter { it.band == band }
    val connectedHere = inBand.firstOrNull { it.connected }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!hasPermission) {
            item(key = "perm") { PermissionCard(onRequestPermission) }
            return@LazyColumn
        }

        if (!locationOn) {
            item(key = "loc") {
                NoticeCard(
                    icon = Icons.Rounded.LocationOn,
                    text = "Location services are off. Android needs them on to return Wi-Fi scan results.",
                    action = "Turn on",
                    onAction = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                )
            }
        }

        item(key = "bands") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Band.entries.forEachIndexed { i, b ->
                    val count = state.aps.count { it.band == b }
                    SegmentedButton(
                        selected = band == b,
                        onClick = { band = b },
                        shape = SegmentedButtonDefaults.itemShape(i, Band.entries.size),
                        icon = {},
                        label = { Text("${b.label} · $count", maxLines = 1) },
                    )
                }
            }
        }

        item(key = "graph") {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Channel map", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            "dBm vs channel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (inBand.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (state.scanning) "Scanning…" else "No networks found on ${band.label}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        ChannelGraph(inBand, band, Modifier.fillMaxWidth().height(220.dp))
                    }
                }
            }
        }

        if (inBand.isNotEmpty()) {
            item(key = "best") {
                val best = recommendChannel(state.aps, band)
                val sub = when {
                    connectedHere == null -> "Fewest overlapping networks in range."
                    connectedHere.channel == best -> "Your router is already on the clearest channel."
                    else -> "Your router uses channel ${connectedHere.channel}. Switching in its admin page could reduce interference."
                }
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(Icons.Rounded.FlashOn, MaterialTheme.colorScheme.onTertiaryContainer, size = 44)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Best channel: $best", style = MaterialTheme.typography.titleMedium)
                            Text(sub, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        if (state.throttled) {
            item(key = "throttle") {
                NoticeCard(
                    icon = Icons.Rounded.Info,
                    text = "Android limits apps to 4 Wi-Fi scans every 2 minutes. Showing the latest results.",
                )
            }
        }
        state.error?.let { err ->
            item(key = "err") { NoticeCard(icon = Icons.Rounded.WifiOff, text = err) }
        }

        items(inBand, key = { it.bssid }) { ap ->
            ApRow(ap, Modifier.animateItem())
        }
    }
}

@Composable
private fun ApRow(ap: AccessPoint, modifier: Modifier) {
    val color = apColor(ap.bssid)
    val level = NetUtils.signalLevel(ap.rssi)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (ap.connected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Rounded.Wifi, color, size = 46)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        ap.ssid,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (ap.hidden) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (ap.connected) {
                        Pill("Connected", container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Text(
                    "Ch ${ap.channel} · ${ap.widthMhz} MHz · ${ap.frequency} MHz",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (ap.security == "Open") Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                        null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        listOfNotNull(ap.security, ap.standard).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    ap.bssid,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SignalBars(level, Semantic.forLevel(level), Modifier.size(22.dp))
                Spacer(Modifier.height(4.dp))
                Text("${ap.rssi}", style = MaterialTheme.typography.titleMedium, color = Semantic.forLevel(level))
                Text("dBm", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "perm").animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                PulseRings(MaterialTheme.colorScheme.primary, Modifier.fillMaxSize(), innerFraction = 0.32f)
                IconBubble(Icons.Rounded.Wifi, MaterialTheme.colorScheme.primary, size = 72, modifier = Modifier.scale(pulse))
            }
            Spacer(Modifier.height(12.dp))
            Text("See nearby networks", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Android requires location access to scan Wi-Fi access points and show your network's name. " +
                    "NetPulse never stores or shares your location.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onGrant) { Text("Allow access") }
        }
    }
}

@Composable
private fun NoticeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onAction) { Text(action) }
            }
        }
    }
}
