# CastIt Receiver

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Fire%20TV-green.svg)](https://developer.android.com/tv)
[![Version](https://img.shields.io/badge/Version-1.4.0-brightgreen.svg)](app/build.gradle.kts)

**CastIt Receiver** is an open-source TV receiver application built for **Android TV** and **Amazon Fire TV** devices. It enables your big screen to receive **Web Video Casting** with synchronized **Live Danmaku (bullet comments)**, as well as native **AirPlay 2 Screen Mirroring** directly from iOS, iPadOS, and macOS devices.

---

## 📺 Key Features

- **AirPlay 2 Screen Mirroring**:
  - Full-screen real-time mirroring from iPhone, iPad, and Mac without needing any third-party app installed on the sender.
  - Hardware-accelerated H.264 video decoding using Android `SurfaceView` with automatic aspect ratio scaling.
  - Apple Lossless Audio Codec (ALAC) decoding via high-performance native JNI bridge.
  - Discrete, Apple-style transient connection pill in the corner that automatically fades out after 4 seconds.

- **Web Video Casting**:
  - Cast online videos, movies, and episodic series directly to your TV.
  - Seamless handling of dynamic streams (HLS/m3u8, MP4, DASH).
  - Built-in video state synchronization and remote control navigation.

- **Live Danmaku (Bullet Comments)**:
  - Integrated high-performance Danmaku rendering engine ([DanmakuFlameMaster](https://github.com/bilibili/DanmakuFlameMaster)).
  - Real-time bullet comments floating smoothly across the big screen in sync with the video.

- **Zero-Config Discovery**:
  - Broadcasts Bonjour/mDNS services (`_airplay._tcp`, `_raop._tcp`) for immediate AirPlay discovery in Apple Control Center.
  - SSDP / DIAL protocol support for smart detection across local Wi-Fi.

- **Leanback 10-Foot UI**:
  - Designed specifically for TV remotes with intuitive D-pad navigation.
  - Ambient standby screen displaying network status and device connection readiness.

---

## 🏗 Architecture & Tech Stack

| Component | Technology | Description |
| :--- | :--- | :--- |
| **UI Framework** | Jetpack Compose for TV | Modern declarative UI styled for dark living room environments |
| **Media Player** | AndroidX Media3 / ExoPlayer | High-performance media playback engine for web streams |
| **AirPlay Protocol** | Netty / Java NIO | Asynchronous RTSP and HTTP server handling AirPlay handshakes |
| **Crypto & Decoders** | JNI Native Libraries | C/C++ native libraries (`libplayfair.so`, `libalac.so`) for FairPlay stream keys and audio decoding |
| **Danmaku View** | SafeDanmakuView | Hardened Bilibili DanmakuFlameMaster rendering surface |
| **Discovery** | Android `NsdManager` | Zero-configuration local network discovery |

---

## 🚀 Building from Source

### Prerequisites
- **JDK 17** or newer
- **Android SDK** (API Level 35+, Build Tools 35.0.0+)
- **CMake & Android NDK** (included precompiled `.so` native libraries support `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`)

### Build Steps

1. **Clone the repository**:
   ```bash
   git clone https://github.com/YOUR_USERNAME/castit-receiver.git
   cd castit-receiver
   ```

2. **Build the Debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```
   The resulting APK will be located at:
   `app/build/outputs/apk/debug/app-debug.apk`

3. **Install to Fire TV / Android TV via ADB**:
   Enable Developer Options and ADB Debugging on your TV, then run:
   ```bash
   adb connect <YOUR_TV_IP_ADDRESS>:5555
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 📄 License & Attribution

This project is licensed under the **GNU General Public License v3.0 (GPLv3)** — see the [LICENSE](LICENSE) file for complete details.

### Third-Party & Open Source Attributions:
- **PhairPlay / RPiPlay**: AirPlay 2 protocol implementation and FairPlay stream crypto reverse-engineering.
- **DanmakuFlameMaster**: Bilibili's open-source Android Danmaku rendering engine.
- **Netty**: High-performance asynchronous event-driven network application framework.
- **AndroidX Media3**: Google's unified media framework and ExoPlayer engine.
