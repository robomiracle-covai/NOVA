# NOVA OS

NOVA is the Android launcher and control app for **Robomiracle** companion robots.

## Features

- WebView-based robot dashboard UI
- AI Core voice interface (LiteRT-LM)
- Local TTS via Sherpa-ONNX
- Teach & Train robot movement sequences
- Face tracking and manual motor control
- OTA updates for UI assets

## Build

See [BUILD_APK.md](BUILD_APK.md) for full APK build instructions.

Quick debug build:

```bat
gradlew.bat assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Repository

- **GitHub:** https://github.com/robomiracle-covai/NOVA
- **Maintainer:** robomiracle-covai (`ragunath.robomiracle@gmail.com`)

## Project info

| Item | Value |
|---|---|
| Package | `com.nova.launcher` |
| Min SDK | 24 |
| Target SDK | 34 |
| Version | 2.0 |

---

© Robomiracle Technologies
