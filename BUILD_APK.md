# NOVA — Build APK Guide

This document explains how to build an installable **APK** from the NOVA Android source code.

---

## Project Overview

| Property | Value |
|---|---|
| **App name** | NOVA OS |
| **Package ID** | `com.nova.launcher` |
| **Version** | 2.0 (`versionCode` 2) |
| **Min Android** | API 24 (Android 7.0) |
| **Target Android** | API 34 (Android 14) |
| **Project type** | Native Android (Gradle) |
| **Languages** | Java, Kotlin, HTML/JS (WebView UI), Python 3.11 (Chaquopy) |

### What this project is

NOVA is a **native Android app**, not a web app or React Native project. You do **not** "convert" source files into an APK manually — Android Studio / Gradle **compile and package** the source into an APK automatically.

The app is a robot companion launcher for **Robomiracle** with:

- **WebView UI** — HTML pages in `app/src/main/assets/`
- **Local AI** — Google LiteRT-LM (`LocalAiEngine.kt`)
- **Text-to-Speech** — Sherpa-ONNX native libraries
- **Embedded Python** — Chaquopy runtime for on-device scripting
- **OTA updates** — UI/logic can be updated over-the-air without reinstalling the APK (see `ota_repo/`)

### Key source locations

```
NOVA/
├── app/
│   ├── build.gradle              # App build config
│   ├── libs/
│   │   └── sherpa-onnx-1.13.2.aar
│   └── src/main/
│       ├── AndroidManifest.xml   # Permissions & activities
│       ├── assets/               # HTML, JS, prompts (WebView UI)
│       ├── java/com/nova/launcher/  # Main Java/Kotlin code
│       └── jniLibs/              # Native .so files (arm64, armv7, x86)
├── build.gradle                  # Root Gradle config
├── settings.gradle
├── gradle.properties
├── local.properties              # Android SDK path (machine-specific)
└── gradlew.bat                   # Windows build script
```

---

## Prerequisites

Install the following on your Windows machine:

1. **Android Studio** (latest stable)  
   Download: https://developer.android.com/studio

2. **Android SDK** (installed via Android Studio SDK Manager)
   - Android SDK Platform **API 34**
   - Android SDK Build-Tools **34.0.0**
   - NDK (optional but recommended for native libs)

3. **JDK 17**  
   Android Studio ships with JDK 17 (JBR). This project requires Java 17.

4. **Disk space**  
   Expect **~3–5 GB** free for SDK, Gradle cache, and build artifacts.  
   The final APK is large (~**250 MB**) due to Python runtime, ONNX libraries, and multi-ABI native binaries.

---

## Step 1 — Configure your environment

### 1.1 Set Android SDK path

Open `local.properties` and set `sdk.dir` to **your** Android SDK location:

```properties
sdk.dir=C\:\\Users\\YOUR_USERNAME\\AppData\\Local\\Android\\Sdk
```

> The current file points to another user's path (`nadhi`). You must update this.

Android Studio usually creates/updates this file automatically when you open the project.

### 1.2 Set JDK path (if needed)

In `gradle.properties`, this line may need updating for your machine:

```properties
org.gradle.java.home=C:\\Program Files\\Android\\Android Studio\\jbr
```

Alternatively, in Android Studio:  
**File → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** → select **JDK 17**.

### 1.3 Avoid OneDrive sync issues

This project has `org.gradle.vfs.watch=false` in `gradle.properties` to reduce issues when the project lives inside OneDrive. If builds fail with file-lock errors, move the project to a local folder (e.g. `C:\Projects\NOVA`).

---

## Step 2 — Open the project in Android Studio

1. Launch **Android Studio**
2. Click **Open** and select the `NOVA` folder (the one containing `build.gradle` and `settings.gradle`)
3. Wait for **Gradle Sync** to finish (first sync may take 10–20 minutes — it downloads dependencies and sets up Chaquopy/Python)
4. If prompted, accept SDK licenses and install missing components

---

## Step 3 — Build a Debug APK (for testing)

A debug APK is signed automatically and is suitable for installing on your own device or emulator.

### Option A — Android Studio (GUI)

1. Menu: **Build → Build Bundle(s) / APK(s) → Build APK(s)**
2. Wait for the build to complete
3. Click **locate** in the notification, or find the APK at:

```
app/build/outputs/apk/debug/app-debug.apk
```

### Option B — Command line (Windows)

Open PowerShell or CMD in the project root (`NOVA` folder):

```bat
gradlew.bat assembleDebug
```

Output:

```
app\build\outputs\apk\debug\app-debug.apk
```

> A debug APK was previously built successfully at this path (~247 MB).

---

## Step 4 — Build a Release APK (for distribution)

Release APKs must be **signed** with your own keystore. The project does not include a signing configuration yet.

### 4.1 Create a keystore (one-time)

```bat
keytool -genkey -v -keystore nova-release.keystore -alias nova -keyalg RSA -keysize 2048 -validity 10000
```

Store the keystore file and passwords securely. **Do not commit the keystore to git.**

### 4.2 Add signing config

Create `keystore.properties` in the project root (add to `.gitignore`):

```properties
storeFile=../nova-release.keystore
storePassword=YOUR_STORE_PASSWORD
keyAlias=nova
keyPassword=YOUR_KEY_PASSWORD
```

Add to `app/build.gradle` inside the `android { }` block:

```gradle
def keystorePropertiesFile = rootProject.file("keystore.properties")
def keystoreProperties = new Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(new FileInputStream(keystorePropertiesFile))
}

signingConfigs {
    release {
        keyAlias keystoreProperties['keyAlias']
        keyPassword keystoreProperties['keyPassword']
        storeFile file(keystoreProperties['storeFile'])
        storePassword keystoreProperties['storePassword']
    }
}

buildTypes {
    release {
        signingConfig signingConfigs.release
        minifyEnabled false
        proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
    }
}
```

### 4.3 Build release APK

**Android Studio:**  
**Build → Generate Signed Bundle / APK → APK →** select your keystore → **release**

**Command line:**

```bat
gradlew.bat assembleRelease
```

Output:

```
app\build\outputs\apk\release\app-release.apk
```

---

## Step 5 — Install the APK on a device

### Via USB (ADB)

1. Enable **Developer options** and **USB debugging** on the Android device
2. Connect via USB
3. Run:

```bat
adb install app\build\outputs\apk\debug\app-debug.apk
```

To replace an existing install:

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Via file transfer

Copy the `.apk` file to the device and open it. You may need to allow **Install from unknown sources**.

### Set as default launcher

NOVA registers as a **HOME launcher** (`AndroidManifest.xml`). After install, press Home and select **NOVA** as the default launcher.

---

## Build variants summary

| Command | Output | Use case |
|---|---|---|
| `gradlew.bat assembleDebug` | `app-debug.apk` | Development & testing |
| `gradlew.bat assembleRelease` | `app-release.apk` | Distribution (requires signing) |
| `gradlew.bat clean` | — | Delete previous build artifacts |
| `gradlew.bat clean assembleDebug` | Fresh debug APK | Fix corrupted builds |

---

## APK vs OTA updates

| Update type | What changes | How |
|---|---|---|
| **APK rebuild** | Native code, permissions, new libraries, manifest | Rebuild & reinstall APK |
| **OTA update** | HTML/JS/CSS in `assets/` | Push `update.zip` + bump `update.json` in `ota_repo/` |

For UI-only changes (HTML in `app/src/main/assets/`), you can use the OTA pipeline described in `ota_repo/README.md` without rebuilding the APK.

---

## Troubleshooting

### Gradle sync fails — wrong SDK path

**Error:** `SDK location not found`  
**Fix:** Update `local.properties` with your correct `sdk.dir`.

### JDK version mismatch

**Error:** `invalid source release: 17`  
**Fix:** Use JDK 17 in Android Studio Gradle settings.

### Missing `sherpa-onnx` AAR

**Error:** `Could not find sherpa-onnx-1.13.2.aar`  
**Fix:** Ensure `app/libs/sherpa-onnx-1.13.2.aar` exists (~54 MB).

### Missing native libraries

**Error:** `UnsatisfiedLinkError` for `libonnxruntime.so`  
**Fix:** Verify `.so` files exist under `app/src/main/jniLibs/` for each ABI folder.

### Chaquopy / Python build errors

**Error:** Python or pip install failures during build  
**Fix:**
- Ensure internet access during first build (Chaquopy downloads Python)
- Increase memory: `org.gradle.jvmargs=-Xmx4g` is already set in `gradle.properties`
- Run `gradlew.bat clean` and rebuild

### Build is very slow or hangs

- First build compiles Python, native libs, and all ABIs — can take **15–30+ minutes**
- Disable antivirus scanning on the project folder temporarily
- Move project out of OneDrive-synced folders

### APK too large

The APK includes native libraries for **4 CPU architectures** (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`). For robot devices (ARM only), you can reduce size by limiting ABIs in `app/build.gradle`:

```gradle
ndk {
    abiFilters "armeabi-v7a", "arm64-v8a"
}
```

---

## Quick start checklist

- [ ] Install Android Studio + SDK API 34
- [ ] Update `local.properties` with your SDK path
- [ ] Open `NOVA` folder in Android Studio
- [ ] Wait for Gradle sync to complete
- [ ] Run `gradlew.bat assembleDebug` or use **Build APK**
- [ ] Install `app/build/outputs/apk/debug/app-debug.apk` on device
- [ ] (Optional) Create keystore and build signed release APK

---

## Technical build details

| Component | Version |
|---|---|
| Android Gradle Plugin | 8.7.0 |
| Gradle | 8.10 |
| Kotlin | 1.9.24 |
| Chaquopy | 17.0.0 |
| Python (embedded) | 3.11 |
| Compile SDK | 34 |

**Main dependencies:**
- `androidx.appcompat`, `androidx.core`, `multidex`
- `com.google.ai.edge.litertlm:litertlm-android` (local AI)
- `org.nanohttpd:nanohttpd` (embedded HTTP server)
- `sherpa-onnx-1.13.2.aar` (local TTS)

---

*© Robomiracle Technologies — NOVA OS Build Guide*
