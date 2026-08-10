# FileCamera (Patrol Camera)

A clean, free, ad-free open-source watermark camera. Designed to provide field workers with a distraction-free, efficient photo capture and management experience.

> **Latest Version**: v20260810-01 | [Download APK](./巡查相机v20260810-01.apk) | [All Releases](https://gitee.com/zjywchenhui/filecamera/releases)
>
> v20260810-01: Fix installation failure / crash on newer Android versions (16 KB page-size devices) by upgrading CameraX to 1.4.2

## Why This Project

Most watermark cameras on the market are bloated with ads. Workers in the field often accidentally tap ads, leading to unwanted app installs or charges. FileCamera is the answer — **no ads, no fees, no hidden terms**. Clean, reliable, and easy to use.

## Features

### 📸 Watermark
- **Highly customizable**: Header, body, and footer zones — each with independent text, color, font size, and visibility settings
- **Custom icon**: Replace the header icon to match your organization's branding
- **Dynamic fields**: Auto-populate route name, pile number, GPS coordinates, timestamp, weather, and address
- **Config import/export**: Export watermark settings as a ZIP file for batch deployment across devices — configure once, deploy everywhere

### 📁 Folder Management
- Organize photos into folders just like on a desktop — no more cluttered gallery
- Create, rename, move, and delete folders
- Photos are stored in the app's private directory, **isolated from your personal albums**
- Export photos to public directories when you need to share them

### 🌤 Weather & Geocoding
- Configure an [Amap (高德) Web Service API Key](https://lbs.amap.com/) to automatically fetch weather data (temperature, humidity, wind) and precise address via reverse geocoding
- **One-tap adcode**: After setting up the API key, tap the fetch button to get the city adcode via GPS — no need to look it up online

### 🛣 Route Matching & Pile Numbering
- Use with [Road Manager (路线管理)](https://gitee.com/zjywchenhui/road_manage) to export GeoJSON route data
- Import the route data and the app will automatically match your photo location to the nearest route and calculate the pile number — ideal for highway inspections, pipeline patrols, and similar fieldwork

### 📍 QR Code Navigation
- Each photo embeds a QR code containing Amap navigation coordinates
- Share a photo via WeChat, scan the QR code, and navigate directly to the shooting location

### 📷 Camera
- Multiple aspect ratios (16:9 / 4:3 / 1:1)
- Touch to focus, flash control, GPS positioning
- **Sync to album**: Automatically save a copy to DCIM/Camera when enabled

## Architecture

Hybrid development: WebView (HTML5/CSS3/JS) for UI + Java (Android native) for camera, image processing, watermark rendering, and file management.

```
Frontend (WebView / JS)           Backend (Java)
───────────────────               ─────────────────────
index.html          ←──→          MainActivity.java        Entry point & location services
setting.js          ←──→          WatermarkConfigManager   Watermark settings (import/export)
view.js             ←──→          CameraActivity.java      Camera preview, capture, watermark
menu.js             ←──→          BackServer.java          Built-in HTTP file server
file_server.js      ←──→          FilePickerBridge.java    File picker & config zip packaging
key_manage.js       ←──→          GaodeApi.java            Weather, geocoding, coordinate transform
road_data.js        ←──→          RoadApiHelper.java       Route matching engine
                                  RoadDatabaseHelper.java  Route database
                                  DatabaseHelper.java      File index database
                                  Android_js_bridge.java   JS ↔ Native bridge
```

## Tech Stack

| Category | Technology |
|----------|------------|
| Min API Level | Android 10 (API 29) |
| Compile SDK | 34 |
| Camera | CameraX 1.3.3 |
| QR Code | ZXing 3.5.3 |
| Frontend | WebView (HTML5 / CSS3 / JavaScript) |
| Backend | Java 17 |
| Dependencies | AppCompat 1.6.1, Material 1.11.0, ExifInterface 1.3.7, WebKit 1.11.0 |

## Getting Started

### Direct Install (Recommended)

1. Download the latest [APK](./巡查相机v20260720-02.apk)
2. Install on your Android device — ready to use

> Weather and geocoding features require an Amap API Key. See instructions below.

### Build from Source

```bash
git clone https://gitee.com/zjywchenhui/filecamera.git
```

Open `source/filecamera` in Android Studio, wait for Gradle sync, then build.

Requirements: Android Studio Hedgehog+ | SDK 34 | JDK 17+

## Usage

### Enable Weather & Geocoding

1. Sign up at [Amap Open Platform](https://lbs.amap.com/) and apply for a **Web Service** API Key
2. In the app, go to **Settings → Key Management** and enter your API Key
3. In the weather field's adcode input, tap the **Fetch** button to auto-detect your city code via GPS

### Enable Route & Pile Number Matching

1. Use [Road Manager](https://gitee.com/zjywchenhui/road_manage) to export a GeoJSON route file
2. In the app, go to **Route Management** and import the GeoJSON file
3. Photos will automatically be matched to the nearest route with pile numbers calculated

### Batch Deploy Watermark Config

1. Configure the watermark on one device (icon, text, colors, layout, etc.)
2. Go to Settings, tap **Export Settings Package**, and save the generated ZIP
3. On target devices, go to Settings, tap **Import Settings Package**, and select the ZIP file

## License

[MIT License](./LICENSE) © 2025 zjywchenhui

## Related Projects

- [Road Manager (路线管理)](https://gitee.com/zjywchenhui/road_manage) — Route data management tool for generating GeoJSON files used by FileCamera
