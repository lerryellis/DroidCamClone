# DroidCam Clone — Build & Usage Guide

## Requirements
- Android Studio Hedgehog (2023.1) or newer
- Android SDK 34
- Android phone, API 21+ (Android 5.0+)
- Phone and PC on the same WiFi network

---

## Build

### Option A — Android Studio (easiest)
1. File → Open → select `/Users/ellis/Documents/GitHub/DroidCamClone`
2. Wait for Gradle sync
3. Connect phone via USB with USB Debugging enabled
4. Click ▶ Run (Shift+F10)

### Option B — Command line
```bash
cd /Users/ellis/Documents/GitHub/DroidCamClone
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

> **Note:** Android Studio generates the `mipmap/ic_launcher` resources automatically.
> If building from CLI first, run File → New → Image Asset in Android Studio to create placeholder icons, or the build will fail on the manifest reference.

---

## First Run
Grant Camera and Notifications permissions when prompted.

---

## How to Use

1. Open the app — live camera preview starts
2. Your WiFi IP is shown at the bottom
3. Tap **Start Stream** — both MJPEG server and NDI sender start
4. Two URLs/names appear:
   - **MJPEG**: `http://192.168.x.x:8080/video`
   - **NDI**: `DroidCam Clone (Android)` *(if NDI SDK is installed)*
5. Use **Switch Camera** for front/back toggle
6. Use the **Resolution** spinner (480p / 720p / 1080p)
7. Open `http://192.168.x.x:8080/` in a browser for the web portal
8. Tap **Stop Stream** when done

---

## Connecting in OBS Studio

### MJPEG (works without NDI SDK)
1. Sources → **+** → **Media Source**
2. Name it → OK
3. Uncheck **Local File**
4. **Input**: `http://192.168.x.x:8080/video`
5. **Input Format**: `mjpeg`
6. Uncheck Loop & Reconnect → OK

### NDI (requires NDI SDK + OBS NDI Plugin)
1. Install [OBS NDI Plugin](https://github.com/obs-project/obs-ndi/releases) → restart OBS
2. Sources → **+** → **NDI Source**
3. Select **DroidCam Clone (Android)** from the dropdown

---

## NDI Setup (enabling NDI output)

NDI SDK requires a free account and license. See **NDI_SETUP.md** for full details.

### Quick steps:
1. Download NDI SDK for Android from https://ndi.video/for-developers/ndi-sdk/
2. Extract and copy `.so` files into:
   ```
   app/libs/jni/arm64-v8a/libndi.so
   app/libs/jni/armeabi-v7a/libndi.so
   ```
3. In `app/build.gradle`, change:
   ```gradle
   buildConfigField "boolean", "NDI_ENABLED", "false"
   ```
   to:
   ```gradle
   buildConfigField "boolean", "NDI_ENABLED", "true"
   ```
4. Also uncomment in `app/build.gradle` dependencies:
   ```gradle
   implementation(name: 'ndi', ext: 'aar')
   ```
   (and place the AAR in `app/libs/` if the SDK ships one)
5. Gradle sync → Rebuild → reinstall APK

> Without NDI SDK: the app compiles and runs MJPEG streaming with no changes needed.

---

## Web Portal
The embedded HTTP server serves these pages on the phone's IP:

| URL | Content |
|-----|---------|
| `http://<ip>:8080/` | Live preview + OBS setup instructions |
| `http://<ip>:8080/downloads` | Links: OBS NDI Plugin, NDI Tools, VLC, NDI SDK |
| `http://<ip>:8080/video` | Raw MJPEG stream |
| `http://<ip>:8080/info` | JSON: ip, port, ndi_name, resolution, fps |

---

## Other Clients

**VLC**: Media → Open Network Stream → `http://<ip>:8080/video`

**NDI Studio Monitor** (from [NDI Tools](https://ndi.video/tools/)): select **DroidCam Clone (Android)** in the dropdown

**NDI Virtual Input** (from NDI Tools): makes the NDI source appear as a virtual webcam device in Windows/Mac — usable in Zoom, Teams, etc.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Stream not visible | Ensure phone and PC are on same WiFi (disable AP isolation) |
| Black screen | Restart app, check camera permission |
| NDI source not in OBS | Install OBS NDI Plugin; verify NDI_ENABLED=true and libs present |
| Low FPS | Switch to 480p in resolution spinner |
| Port 8080 in use | Force-close the app and relaunch |
