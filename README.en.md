# FileCamera (Patrol Camera)

An open-source watermark camera for highway patrols, bridge inspections, pipeline surveys, and similar fieldwork.

No ads, no fees, no hidden terms. Photos and videos are stored in the app's private folder tree instead of the personal gallery.

> ## Choose the right version first
>
> **The current `master` branch is a large Camera2 video-watermark rewrite.** It adds video watermarking during recording, a video browser, and a redesigned camera UI.
>
> **If you only need photo capture + photo watermarks and do not need video watermarks, use the stable legacy build:**
>
> **[FileCamera v20260825-02 APK (stable legacy)](https://gitee.com/zjywchenhui/filecamera/releases/download/v20260825-02/%E5%B7%A1%E6%9F%A5%E7%9B%B8%E6%9C%BAv20260825-02.apk)**
>
> The legacy version has been used in the field for a long time and is stable for photos, watermarks, route matching, weather, and folder management.
> Validate the current rewrite on a small scale before rolling it out broadly.
>
> [All releases](https://gitee.com/zjywchenhui/filecamera/releases)

> **Current rewrite release: v20261020-01**
> [Download the new APK (video-watermark rewrite)](https://gitee.com/zjywchenhui/filecamera/releases/download/v20261020-01/%E5%B7%A1%E6%9F%A5%E7%9B%B8%E6%9C%BAv20261020-01.apk)
> The APK displays version **1.0**; its internal versionCode is `20261021`, so it can be installed over the previous release.

## What changed from the legacy version

### Video

- Camera pipeline migrated from CameraX to **Camera2**
- Video encoded directly to standard MP4 with **MediaCodec + Media3 Muxer**
- Audio, rotation metadata, and interrupted-session recovery
- **Video watermark is burned into the recorded frames**, not overlaid during playback
- New video browser with playback, previous/next, delete, share, and export

### Photos

- Photo watermarks, QR codes, GPS, weather, address, route matching, and pile numbers retained
- Continuous capture session: take multiple photos, then deliver new records when exiting the camera
- Photo watermark scaling now uses the screen's longer side, avoiding watermark enlargement after the new camera UI reduced the preview area
- Video thumbnails are generated with a translucent play triangle

### UI and interaction

- Redesigned dark camera panel with amber shutter/mode bar and zoom capsule
- Separate image viewer and video viewer containers
- Independent delete, share, export, and paging logic for photos and videos
- Haptic feedback for buttons, file rows, menus, dialogs, and native camera controls
- Front preview now uses a single horizontal flip with no rotation compensation, avoiding reversed rotation
- Preview is fixed to the full 4:3 sensor frame; photo/video still use the configured aspect ratio, with matrix-based cover and clipping
- Front final photos get one horizontal mirror so they match the preview
- Zoom capsule text auto-sizes between 9sp and 16sp; presets follow the device CONTROL_ZOOM_RATIO_RANGE
- Video watermark margins are normalized to a 1920 reference frame to match photo edge spacing
- Watermarks use the preview container coordinates directly
- Unified JS bridge contract remains: `callAndroidMethod(task_id + JSON)` + `handlePromiseCallback`

### Security and stability

- File-path validation
- Removed the legacy player activity; videos now play in the WebView
- Fixed first-permission camera black screen and video deletion incorrectly using the image array

## Features

### Watermark

- Independent header, body, and footer configuration
- Adjustable text, colors, font sizes, and icon
- Dynamic fields: route name, pile number, coordinates, time, weather, address
- Export/import watermark settings as ZIP for batch deployment

### File management

- Folder-tree management for photos and videos
- Create, rename, move, copy, delete, and sort
- Import/export files or entire folders
- Thumbnails, image viewer, and video browser
- Private app storage by default, export when needed

### Routes and field data

- Import GeoJSON routes from [Road Manager](https://gitee.com/zjywchenhui/road_manage)
- Automatically match the nearest route and calculate pile numbers
- Optional Amap Web Service API key for weather and reverse geocoding
- One-tap adcode lookup

### QR navigation

- Photos can include an Amap navigation QR code with coordinates
- Scan after sharing to navigate to the capture location

### Camera

- 16:9 / 4:3 / 1:1 aspect ratios
- Touch focus, flash, front/rear camera, zoom presets
- Optional shutter sound
- Optional watermark-free original with EXIF/GPS
- Optional copy to the system gallery
- Video mode with standard MP4 + audio + watermark

## Architecture

Hybrid WebView + Java architecture:

- **WebView / H5**: file list, settings, route management, image/video viewers
- **Java native layer**: camera, recording, watermark compositing, file index, location, weather, route calculations
- **Unified JS bridge**: `task_id + JSON` requests; native replies through `handlePromiseCallback`

```text
WebView (HTML/CSS/JS)
  ├─ index.html / setting.js / view.js / menu.js / file_server.js
  ├─ Android_js_bridge      unified JS → Native bridge
  ├─ BackServer             file index and built-in HTTP file server
  └─ FilePickerBridge       file picking, config import/export, sharing

Camera2
  ├─ Camera2Controller      preview, capture, recording session
  ├─ Camera2GlRenderer      camera frame + watermark → encoder Surface
  ├─ Camera2MuxerSink       MediaCodec + Media3 Muxer → MP4
  └─ WatermarkBitmapRenderer watermark bitmap rendering
```

## Tech stack

| Category | Technology |
|----------|------------|
| Minimum | Android 10 (API 29) |
| Compile/target SDK | 34 |
| Camera | Camera2 |
| Video encoding | MediaCodec + Media3 Muxer |
| QR code | ZXing 3.5.3 |
| Frontend | WebView (HTML5 / CSS3 / JavaScript) |
| Native | Java 17 |
| Build | AGP 9.x / Gradle 9.x |
| Main dependencies | AppCompat 1.6.1, Material 1.11.0, ExifInterface 1.3.7, WebKit 1.11.0 |

## Getting started

### Install the stable legacy build (recommended if video watermark is not needed)

1. Download [FileCamera v20260825-02 APK](https://gitee.com/zjywchenhui/filecamera/releases/download/v20260825-02/%E5%B7%A1%E6%9F%A5%E7%9B%B8%E6%9C%BAv20260825-02.apk)
2. Install on Android 10 or later

### Build the current rewrite from source

```bash
git clone https://gitee.com/zjywchenhui/filecamera.git
cd filecamera/source/filecamera
./gradlew assembleDebug
```

Windows:

```powershell
.\gradlew.bat assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Requirements: Android Studio with AGP 9.x support | SDK 34 | JDK 17+

## Usage

### Weather and address

1. Apply for an Amap **Web Service** API key at [Amap Open Platform](https://lbs.amap.com/)
2. In the app, open **Settings → Key Management** and enter the key
3. Tap **Fetch** next to the weather adcode field to resolve the city code via GPS

### Routes and pile numbers

1. Export GeoJSON routes from [Road Manager](https://gitee.com/zjywchenhui/road_manage)
2. In the app, open **Route Management** and import the GeoJSON file
3. Photos are matched to the nearest route and pile numbers are calculated automatically

### Batch-deploy watermark settings

1. Configure the watermark on one device
2. Open **Settings → Export Settings Package** and save the ZIP
3. On target devices, use **Settings → Import Settings Package**

## Version notes

- **v20261020-01**: Camera2 video-watermark rewrite with video watermarking, video browser, new camera UI, haptics, 4:3 full-frame preview, decoupled photo/video ratios, and front-photo mirroring; validate on a small scale first
- **v20260825-02**: stable legacy version without video watermark; recommended for photo-only use
- Current source archive: [v20261020-01.zip](https://gitee.com/zjywchenhui/filecamera/archive/refs/tags/v20261020-01.zip)
- All versions: [Gitee Releases](https://gitee.com/zjywchenhui/filecamera/releases)

## License

[MIT License](./LICENSE) © 2025 zjywchenhui

## Related projects

- [Road Manager](https://gitee.com/zjywchenhui/road_manage) — route data management and GeoJSON generation
