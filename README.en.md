# FileCamera (Patrol Camera)

An open-source watermark camera for highway patrols, bridge inspections, pipeline surveys, and similar fieldwork.

No ads, no fees, no hidden terms. Photos and videos stay in the app's private folder tree instead of the personal gallery.

## Downloads

| Version | Best for | Download |
| --- | --- | --- |
| **v20261024-01 (current)** | Camera2 video-watermark rewrite with video watermarking, video browser, and redesigned camera UI | [FileCamera-v20261024-01.apk](https://gitee.com/zjywchenhui/filecamera/releases/download/v20261024-01/FileCamera-v20261024-01.apk) |
| v20260825-02 (stable legacy) | Photo capture + photo watermarks only; field-proven | [FileCamera v20260825-02 APK](https://gitee.com/zjywchenhui/filecamera/releases/download/v20260825-02/%E5%B7%A1%E6%9F%A5%E7%9B%B8%E6%9C%BAv20260825-02.apk) |

- `master` is the rewrite. Prefer the stable legacy build if you do not need video watermarks.
- The rewrite changes a lot; validate on a small scale before broad rollout.
- The APK reports version **1.0** (versionCode `1`). If a higher versionCode is already installed, use `adb install -r -d`.
- [All releases](https://gitee.com/zjywchenhui/filecamera/releases) · [Source archive v20261024-01.zip](https://gitee.com/zjywchenhui/filecamera/archive/refs/tags/v20261024-01.zip)

## Features

- **Watermark**: independent header/body/footer sections with adjustable text, colors, font sizes, and icon; dynamic route name, pile number, coordinates, time, weather, and address; ZIP export/import for batch deployment.
- **File management**: folder-tree management for photos and videos, including create, rename, move, copy, delete, sort, import/export, thumbnails, and viewers.
- **Routes and pile numbers**: import GeoJSON from [Road Manager](https://gitee.com/zjywchenhui/road_manage); photos are matched to the nearest route and pile numbers are calculated automatically.
- **Field data**: optional Amap Web Service key for weather and reverse geocoding; one-tap adcode lookup.
- **QR navigation**: Amap navigation QR code with coordinates, ready to scan after sharing.
- **Camera**: 16:9 / 4:3 / 1:1, touch focus, flash, front/rear camera, zoom presets, shutter sound, watermark-free original with EXIF/GPS, gallery copy; video as standard MP4 + audio + watermark.

## Rewrite highlights

- Camera pipeline migrated to **Camera2**; video uses **MediaCodec + Media3 Muxer** to produce standard MP4 with audio, rotation metadata, and interrupted-session recovery.
- **Video watermarks are burned into the recorded frames**; new video browser with playback, paging, delete, share, and export.
- Continuous photo sessions; exit the camera to deliver all new photos at once. Photo watermark scaling uses the screen's longer side.
- Redesigned camera UI, separate photo/video viewers, and haptic feedback for controls, rows, menus, dialogs, and camera widgets.
- Fixed 4:3 full-frame preview; photo/video ratios are configured independently; front-mirror and +90° watermark placement fixes.
- File-path validation, legacy player cleanup, permission black-screen fix, and video-deletion fix.

## Getting started

### Install

1. Download the current or stable legacy APK above.
2. Install on Android 10 (API 29) or later.

### Build from source

```bash
git clone https://gitee.com/zjywchenhui/filecamera.git
cd filecamera/source/filecamera
./gradlew assembleDebug
```

Windows:

```powershell
.\gradlew.bat assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Requirements: Android Studio with AGP 9.x support | SDK 34 | JDK 17+

## Usage

### Weather and address

1. Apply for an Amap **Web Service** API key at [Amap Open Platform](https://lbs.amap.com/).
2. In the app, open **Settings → Key Management** and enter the **Amap Web Service key**.
3. Tap **Fetch** next to the weather adcode field to resolve the city code via GPS.

### Routes and pile numbers

1. Export GeoJSON routes from [Road Manager](https://gitee.com/zjywchenhui/road_manage).
2. In the app, open **Route Management** and import the GeoJSON file.
3. Photos are matched to the nearest route and pile numbers are calculated automatically.

### Automatic route-data updates

The **Route Data Service Address** and **Route Data Service Key** in Key Management are used to update route data from a route data service. Leaving them blank does not affect local route import or use.

### Batch-deploy watermark settings

1. Configure the watermark on one device.
2. Open **Settings → Export Settings Package** and save the ZIP.
3. On target devices, use **Settings → Import Settings Package** and select the ZIP.

## Tech stack and architecture

Hybrid WebView + Java architecture: H5 handles file lists, settings, route management, and photo/video viewers; Java handles camera, recording, watermark compositing, file indexing, location, weather, and route calculations. Both sides communicate through a unified JS bridge (`task_id + JSON`).

| Category | Technology |
| --- | --- |
| Minimum | Android 10 (API 29) |
| Compile/target SDK | 34 |
| Camera | Camera2 |
| Video encoding | MediaCodec + Media3 Muxer |
| QR code | ZXing 3.5.3 |
| Frontend | WebView (HTML5 / CSS3 / JavaScript) |
| Native | Java 17 |
| Build | AGP 9.x / Gradle 9.x |
| Main dependencies | AppCompat 1.6.1, Material 1.11.0, ExifInterface 1.3.7, WebKit 1.11.0 |

## Version notes

- **v20261024-01**: key management now uses "Route Data Service Address/Key", with an optional-fill hint and a visual divider from the Amap key; the route-page warning is updated; README streamlined and the release repackaged.
- **v20261023-01**: default config is now an exclusive whole-file choice between default and custom; the open-source build no longer ships custom config; road-data network fetch moved to Java; auto-update checks at most once per hour; zoom responsiveness and image-swipe vibration fixed.
- **v20261020-02**: Camera2 video-watermark rewrite with video watermarking, video browser, new camera UI, haptics, and 4:3 full-frame preview; validate on a small scale first.
- **v20260825-02**: stable legacy version with photo watermarks only; recommended if video watermarking is not needed.

## License

[MIT License](./LICENSE) © 2025 zjywchenhui

## Related projects

- [Road Manager](https://gitee.com/zjywchenhui/road_manage) — route data management and GeoJSON generation
