package com.netpulse.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netpulse.app.AppViewModel

enum class Dest(val label: String, val title: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Speed("Speed", "Speed test", Icons.Outlined.Speed, Icons.Rounded.Speed),
    Devices("Devices", "Devices", Icons.Outlined.Devices, Icons.Rounded.Devices),
    Router("Router", "Router", Icons.Outlined.Router, Icons.Rounded.Router),
    Wifi("Wi-Fi", "Access points", Icons.Outlined.Wifi, Icons.Rounded.Wifi),
}

fun wifiPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
}.toTypedArray()

fun hasWifiPermission(context: Context): Boolean {
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
        (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.NEARBY_WIFI_DEVICES))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetPulseApp(vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    var dest by rememberSaveable { mutableStateOf(Dest.Speed) }
    var hasPermission by remember { mutableStateOf(hasWifiPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = hasWifiPermission(context)
        vm.refreshNetwork(fetchPublic = false)
        if (hasPermission) vm.scanAccessPoints()
    }
    val requestPermission: () -> Unit = { launcher.launch(wifiPermissions()) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    AnimatedContent(
                        targetState = dest.title,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                        label = "title",
                    ) { Text(it) }
                },
                actions = { TopActions(dest, vm) },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            NavigationBar {
                Dest.entries.forEach { d ->
                    NavigationBarItem(
                        selected = d == dest,
                        onClick = { dest = d },
                        icon = { Icon(if (d == dest) d.selectedIcon else d.icon, contentDescription = d.label) },
                        label = { Text(d.label) },
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = dest,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (fadeIn(tween(240, delayMillis = 60)) +
                    slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { it / 10 * dir }) togetherWith
                    (fadeOut(tween(140)) +
                        slideOutHorizontally(tween(340, easing = FastOutSlowInEasing)) { -it / 10 * dir })
            },
            label = "screen",
        ) { d ->
            when (d) {
                Dest.Speed -> SpeedScreen(vm, padding)
                Dest.Devices -> DevicesScreen(vm, padding)
                Dest.Router -> RouterScreen(vm, padding, hasPermission, requestPermission)
                Dest.Wifi -> AccessPointsScreen(vm, padding, hasPermission, requestPermission)
            }
        }
    }
}

@Composable
private fun TopActions(dest: Dest, vm: AppViewModel) {
    when (dest) {
        Dest.Speed -> {
            val speed by vm.speed.collectAsStateWithLifecycle()
            if (speed.running) {
                IconButton(onClick = vm::cancelSpeedTest) { Icon(Icons.Rounded.Close, "Cancel test") }
            }
        }
        Dest.Devices -> {
            val scan by vm.scan.collectAsStateWithLifecycle()
            IconButton(onClick = { if (scan.running) vm.stopLanScan() else vm.scanLan() }) {
                Icon(if (scan.running) Icons.Rounded.Stop else Icons.Rounded.Refresh, "Rescan")
            }
        }
        Dest.Router -> IconButton(onClick = { vm.refreshNetwork() }) { Icon(Icons.Rounded.Refresh, "Refresh") }
        Dest.Wifi -> {
            val aps by vm.aps.collectAsStateWithLifecycle()
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (aps.scanning) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                } else {
                    IconButton(onClick = vm::scanAccessPoints) { Icon(Icons.Rounded.Refresh, "Scan") }
                }
            }
        }
    }
}
