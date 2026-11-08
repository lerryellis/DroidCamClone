# NDI SDK Setup

The NDI SDK requires a free account and license acceptance from NewTek/Vizrt before download.

## Steps to Enable NDI Streaming

### 1. Download the NDI SDK for Android
1. Go to https://ndi.video/for-developers/ndi-sdk/
2. Click **Download** → select **Android**
3. Create a free account (or log in) and accept the license
4. Download the SDK zip (e.g. `NDI SDK for Android.zip`)

### 2. Extract and copy SDK files into this project

After extracting the zip, copy the following into `app/libs/`:

```
app/libs/
├── ndi.aar                        ← the Android AAR (or ndisdk.aar — name may vary)
└── jni/
    ├── arm64-v8a/
    │   └── libndi.so
    ├── armeabi-v7a/
    │   └── libndi.so
    └── x86_64/
        └── libndi.so
```

The exact filenames depend on the SDK version. Typical structure inside the zip:
- `Bin/Android/` contains the `.so` files per ABI
- `Lib/Android/` may contain the `.aar`

If the SDK ships only as `.so` files (no AAR), place them in:
```
app/src/main/jniLibs/arm64-v8a/libndi.so
app/src/main/jniLibs/armeabi-v7a/libndi.so
```

### 3. Enable NDI in the build
In `app/build.gradle`, the NDI flag defaults to `false` until you place the libs:

```gradle
buildConfigField "boolean", "NDI_ENABLED", "true"
```

Change `"false"` → `"true"`, then do a Gradle sync and rebuild.

### 4. Verify
- Run the app and tap **Start Stream**
- Status should show both MJPEG and NDI lines
- In OBS: Sources → Add → NDI Source → look for **"DroidCam Clone (Android)"**
- In NDI Studio Monitor (from NDI Tools): the source should appear in the dropdown

## NDI Tools (free — recommended)
Download from https://ndi.video/tools/

Includes:
- **NDI Studio Monitor** — verify your NDI source is visible on the network
- **NDI Virtual Input** — makes the NDI source appear as a webcam device in Windows/Mac apps (Zoom, Teams, etc.)
- **NDI Test Patterns** — useful for debugging

## OBS NDI Plugin
Required to receive NDI sources in OBS Studio:
https://github.com/obs-project/obs-ndi/releases

Install the plugin matching your OBS version, then restart OBS.
In OBS: Sources → Add → **NDI Source** → select **"DroidCam Clone (Android)"**.

## Without the NDI SDK
The app compiles and works fine without NDI — MJPEG streaming still works.
Only set `NDI_ENABLED = true` after you've placed the `.so`/`.aar` files in `app/libs/`.
