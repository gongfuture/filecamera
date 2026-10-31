# 巡查相机 FileCamera

一款面向公路巡查、桥梁检查、管线巡检等现场工作的开源水印相机。

无广告、无付费、无隐藏条款。照片和视频保存在应用私有目录中，按文件夹管理，不混入个人相册。

## 下载与版本

| 版本 | 适用场景 | 下载 |
| --- | --- | --- |
| **v20261031-01（当前版）** | 修复单个文件分享被微信误判为“多文件分享”的问题 | [FileCamera-v20261031-01.apk](https://gitee.com/zjywchenhui/filecamera/releases/download/v20261031-01/FileCamera-v20261031-01.apk) |
| v20260825-02（稳定旧版） | 仅需拍照和照片水印，长期现场验证稳定 | [巡查相机v20260825-02.apk](https://gitee.com/zjywchenhui/filecamera/releases/download/v20260825-02/%E5%B7%A1%E6%9F%A5%E7%9B%B8%E6%9C%BAv20260825-02.apk) |

- 当前 `master` 为重构版；不需要视频水印时，优先使用稳定旧版。
- 重构版改动较大，建议先小范围验证，再批量部署。
- APK 显示版本为 **1.0**（versionCode `1`）；如果手机已安装更高 versionCode 的版本，请使用 `adb install -r -d` 强制安装。
- [历史版本](https://gitee.com/zjywchenhui/filecamera/releases) · [当前源码 v20261031-01.zip](https://gitee.com/zjywchenhui/filecamera/archive/refs/tags/v20261031-01.zip)

## 核心功能

- **水印**：页眉、主体、页脚三区独立配置，文字、颜色、字号、图标可调；支持路线名称、桩号、坐标、时间、天气、地址等动态字段；配置可导出 ZIP 批量部署。
- **文件管理**：以文件夹树管理照片和视频，支持新建、重命名、移动、复制、删除、排序、导入导出、缩略图与浏览器。
- **路线与桩号**：配合[路线管理](https://gitee.com/zjywchenhui/road_manage)导入 GeoJSON，拍照时自动匹配最近路线并计算桩号。
- **现场信息**：配置高德 Web 服务 API Key 后获取天气和逆地理编码地址，支持 adcode 一键获取。
- **二维码导航**：照片生成包含经纬度的高德导航二维码，分享后扫码直达拍摄位置。
- **相机**：16:9 / 4:3 / 1:1 画幅，触摸对焦、闪光灯、前后摄、变焦档位、快门声、无水印原图（保留 EXIF/GPS）、同步系统相册；录像为标准 MP4 + 音频 + 水印。

## 重构版主要变化

- 相机管线由 CameraX 迁移到 **Camera2**，录像使用 **MediaCodec + Media3 Muxer** 直接输出标准 MP4，支持音频、方向元数据和中断恢复。
- **视频水印在录制时烧进画面**，新增视频浏览器，支持播放、翻页、删除、分享和导出。
- 拍照会话支持连续拍摄，退出相机时统一交付新增照片；照片水印尺寸按整屏长边换算。
- 相机 UI 重做，图片/视频浏览器拆分，按钮、文件行、菜单、弹窗和相机控件统一震动反馈。

## 快速开始

### 安装 APK

1. 从上方下载当前版或稳定旧版。
2. 在 Android 10（API 29）及以上设备安装。

### 从源码构建

```bash
git clone https://gitee.com/zjywchenhui/filecamera.git
cd filecamera/source/filecamera
./gradlew assembleDebug
```

Windows：

```powershell
.\gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

环境要求：Android Studio（支持 AGP 9.x）| SDK 34 | JDK 17+

## 使用指南

### 配置天气与地址

1. 在[高德开放平台](https://lbs.amap.com/)申请 **Web 服务** API Key。
2. App 内进入 **设置 → 密钥管理**，填入 **高德Web服务秘钥**。
3. 在天气字段的 adcode 输入框旁点击 **获取**，通过 GPS 自动获取城市编码。

### 配置路线与桩号

1. 使用[路线管理](https://gitee.com/zjywchenhui/road_manage)导出 GeoJSON 路线。
2. App 内进入 **路线管理**，导入 GeoJSON。
3. 拍照时自动匹配最近路线并计算桩号。

### 路线数据自动更新

密钥管理页的 **路线数据服务地址** 和 **路线数据服务密钥** 用于从路线数据服务更新路线数据，不填写不影响本地路线导入和使用。

### 批量部署水印配置

1. 在一台设备上配置好水印。
2. **设置 → 导出设置包**，保存 ZIP。
3. 在目标设备 **设置 → 导入设置包**，选择 ZIP。

## 技术栈与架构

WebView + Java 混合架构：H5 负责文件列表、设置、路线管理和图片/视频浏览器；Java 负责相机、录像、水印合成、文件索引、定位、天气和路线计算。前后端通过统一 JS 桥（`task_id + JSON`）通信。

| 类别 | 技术 |
| --- | --- |
| 最低支持 | Android 10（API 29） |
| 编译/目标 SDK | 34 |
| 相机 | Camera2 |
| 视频编码 | MediaCodec + Media3 Muxer |
| 二维码 | ZXing 3.5.3 |
| 前端 | WebView（HTML5 / CSS3 / JavaScript） |
| 原生 | Java 17 |
| 构建 | AGP 9.x / Gradle 9.x |
| 主要依赖 | AppCompat 1.6.1、Material 1.11.0、ExifInterface 1.3.7、WebKit 1.11.0 |

## 版本说明

- **v20261031-01**：修复单个视频或非图片文件分享到微信时被误判为“多文件分享”并提示「仅支持照片格式」的问题；单个文件改走系统单文件分享，多文件按实际类型分享。
- **v20261026-01**：修复后置录像画面方向；视频水印补齐 Logo，统一 Logo、圆角、文字、内边距和行距缩放；发行 APK 不包含定制配置。
- **v20261024-02**：修复设置页默认图标预览写死 `icon.png` 的问题；存在定制配置时，预览跟随当前配置的 `custom_icon.png`，与实际水印图标一致。
- **v20261024-01**：密钥管理页参数改为“路线数据服务地址/密钥”，新增“用于路线数据更新”的填写提示，并与高德密钥分组；路线管理未配置提示同步；README 精简，重新打包发行版。
- **v20261023-01**：默认配置改为 default/custom 整文件互斥选择；开源版不再携带定制配置；路线网络请求下沉 Java；自动更新改为 1 小时冷却；变焦手感与滑图震动修复。
- **v20261020-02**：Camera2 视频水印重构版，含视频水印、视频浏览器、新相机 UI、震动反馈、4:3 全画幅预览等；建议先小范围验证。
- **v20260825-02**：稳定旧版，仅照片水印，推荐不需要视频水印的用户。

## 开源协议

[MIT License](./LICENSE) © 2025 zjywchenhui

## 关联项目

- [路线管理](https://gitee.com/zjywchenhui/road_manage) — 生成巡查相机所需的 GeoJSON 路线文件
