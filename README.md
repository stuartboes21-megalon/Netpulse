# NetPulse

A Material 3 network toolkit for Android, built with Kotlin and Jetpack Compose.

| Tab | What it does |
|---|---|
| **Speed** | Ping, jitter, download and upload over 4 parallel connections to the nearest Cloudflare edge. Animated gauge with spring-physics needle, live throughput graph, phase stepper and a plain-English verdict. |
| **Devices** | Sweeps your subnet (ICMP plus TCP probes) behind an animated radar. Shows hostname, latency and a guessed device type. Tap a device for a full port scan, average ping and a link to its web UI. |
| **Router** | Gateway, SSID/BSSID, live signal ring, band and channel, link speed, Wi-Fi standard and security. Also shows public IP and ISP, DNS (including Private DNS), IPv6, DHCP lease and MTU, plus a one-tap admin page link. |
| **Diagnostics** (on Router) | Ping, traceroute, DNS lookup and TCP port scanner with a streaming terminal console. |
| **Wi-Fi** | Nearby access points with an animated channel map per band (2.4, 5 and 6 GHz), security and standard, and a best-channel recommendation. |

The app uses dynamic color (Material You) on Android 12+, with a hand-tuned teal scheme on older versions. Light and dark themes are both supported, and the layout runs edge to edge.

## Get the APK

### Option A: GitHub (no local setup)
1. Create a new GitHub repo and push this folder to it.
2. Open the **Actions** tab. The **Build APK** workflow runs on every push, or you can start it manually with *Run workflow*.
3. When it finishes, download the **NetPulse-apk** artifact and unzip it to get `app-release.apk`.

### Option B: Android Studio
1. Open this folder in Android Studio (Ladybug or newer).
2. Let Gradle sync, then press **Run** with a phone connected. Alternatively, use **Build → Build APK(s)**.

### Option C: Command line
You need JDK 17 and the Android SDK, with `ANDROID_HOME` set.
```
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key so it installs directly. Replace it with your own keystore before publishing.

## Requirements and notes
- Android 8.0+ (API 26). Targets Android 15.
- **Location permission** is required by Android to read the Wi-Fi network name and to scan access points. The app asks for it only when you open those features.
- Android limits apps to **4 Wi-Fi scans per 2 minutes**. When a scan is throttled, the app shows cached results.
- Android 10+ hides **MAC addresses** of other devices from apps, so MACs appear only on older Android versions.
- The speed test uses Cloudflare's public `speed.cloudflare.com` endpoints. A full run transfers roughly 100–500 MB, depending on your speed.

## Project layout
```
app/src/main/java/com/netpulse/app/
├── MainActivity.kt, AppViewModel.kt
├── net/   SpeedTester, LanScanner, WifiScanner, Diagnostics, Net (link info and utilities)
└── ui/    App (scaffold and navigation), the four screens, Graphics (gauge, radar, charts), Components, theme/
```
