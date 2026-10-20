# 巡查相机 FileCamera

一款面向公路巡查、桥梁检查、管线巡检等现场工作的开源水印相机。

无广告、无付费、无隐藏条款。照片和视频都保存在应用私有目录中，按文件夹管理，不混入个人相册。

> ## 重要：先选对版本
>
> **当前 master 是 Camera2 视频水印重构版**，改动很大，包含视频边录边烧水印、视频浏览器、全新相机 UI 等能力，适合需要视频水印的场景。
>
> **如果只需要拍照 + 照片水印，不需要视频水印，建议直接使用稳定旧版：**
>
> **[巡查相机 v20260825-02 APK（稳定旧版）](https://gitee.com/zjywchenhui/filecamera/releases/download/v20260825-02/%E5%B7%A1%E6%9F%A5%E7%9B%B8%E6%9C%BAv20260825-02.apk)**
>
> 旧版经过长期现场使用，拍照、水印、路线、天气、文件夹管理等流程稳定。
> 当前重构版请先小范围验证，再批量部署。
>
> [查看全部历史版本](https://gitee.com/zjywchenhui/filecamera/releases)

> **当前重构版发行版：v20261020-01**
> [下载新版 APK（视频水印重构版）](https://gitee.com/zjywchenhui/filecamera/releases/download/v20261020-01/%E5%B7%A1%E6%9F%A5%E7%9B%B8%E6%9C%BAv20261020-01.apk)
>
> 代码库根目录 APK：[巡查相机v20261020-01.apk](https://gitee.com/zjywchenhui/filecamera/blob/master/%E5%B7%A1%E6%9F%A5%E7%9B%B8%E6%9C%BAv20261020-01.apk)

## 相对旧版的主要变化

### 视频能力

- 相机管线由 CameraX 迁移到 **Camera2**
- 视频采用 **MediaCodec + Media3 Muxer** 直接编码为标准 MP4
- 支持录像音频、方向元数据、异常中断后的会话恢复
- **视频水印在录制时直接烧进画面**，不再依赖播放页二次叠加
- 新增视频浏览器，支持播放、上一页/下一页、删除、分享、导出

### 拍照与照片

- 保留照片水印、二维码、GPS、天气、地址、路线匹配、桩号计算
- 拍照会话模式：进入相机后可连续拍摄，退出时统一交付本次新增照片
- 照片水印尺寸改为按整屏长边换算，避免相机 UI 增加顶栏/底部面板后水印被放大
- 视频缩略图直接生成带半透明播放三角的封面

### 界面与交互

- 相机 UI 重做：深色控制面板、琥珀黄快门/模式条、变焦胶囊
- 图片浏览器和视频浏览器拆成两个独立页面容器
- 图片和视频各自拥有独立的删除、分享、导出、翻页逻辑
- 新增统一震动反馈：按钮、文件行、菜单、弹窗、相机控件均有轻震动
- 前端桥接继续使用统一契约：`callAndroidMethod(task_id + JSON)` + `handlePromiseCallback`

### 安全与稳定性

- 文件路径增加校验，避免越权访问
- 清理旧播放器 Activity，视频统一使用 WebView 播放
- 修复首次授权后相机黑屏、视频删除误用图片数组等问题

## 核心功能

### 水印管理

- 页眉、主体、页脚三区独立配置
- 文字内容、颜色、字号、图标可调
- 支持路线名称、桩号、坐标、时间、天气、地址等动态字段
- 配置可导出为 ZIP，批量部署到多台设备

### 文件管理

- 以文件夹树管理照片和视频
- 新建、重命名、移动、复制、删除、排序
- 导入/导出文件或整个文件夹
- 内置缩略图、图片浏览器、视频浏览器
- 照片和视频默认保存在应用私有目录，需要时再导出

### 路线与现场信息

- 搭配 [路线管理](https://gitee.com/zjywchenhui/road_manage) 导入 GeoJSON 路线
- 拍照时自动匹配最近路线并计算桩号
- 配置高德 Web 服务 API Key 后，自动获取天气、逆地理编码地址
- 支持 adcode 一键获取

### 二维码导航

- 照片可生成包含经纬度的高德导航二维码
- 分享到微信后扫码即可导航到拍摄位置

### 相机

- 16:9 / 4:3 / 1:1 画幅
- 触摸对焦、闪光灯、前后摄切换、变焦档位
- 可选快门声音
- 可选保存无水印原图（保留 EXIF/GPS）
- 可选同步一份到系统相册
- 录像模式支持标准 MP4 + 音频 + 水印

## 软件架构

采用 WebView + Java 原生混合架构：

- **WebView / H5**：文件列表、设置、路线管理、图片/视频浏览器
- **Java 原生层**：相机、录像、水印合成、文件索引、定位、天气、路线计算
- **统一 JS 桥**：`task_id + JSON` 请求，原生通过 `handlePromiseCallback` 回执

```text
WebView (HTML/CSS/JS)
  ├─ index.html / setting.js / view.js / menu.js / file_server.js
  ├─ Android_js_bridge      统一 JS → Native 桥
  ├─ BackServer             文件索引与内置 HTTP 文件服务
  └─ FilePickerBridge       文件选择、配置导入导出、分享

Camera2
  ├─ Camera2Controller      预览、拍照、录像会话
  ├─ Camera2GlRenderer      相机帧 + 水印 → 编码器 Surface
  ├─ Camera2MuxerSink       MediaCodec + Media3 Muxer → MP4
  └─ WatermarkBitmapRenderer 水印位图绘制
```

## 技术栈

| 类别 | 技术 |
|------|------|
| 最低支持 | Android 10（API 29） |
| 编译/目标 SDK | 34 |
| 相机 | Camera2 |
| 视频编码 | MediaCodec + Media3 Muxer |
| 二维码 | ZXing 3.5.3 |
| 前端 | WebView（HTML5 / CSS3 / JavaScript） |
| 原生 | Java 17 |
| 构建 | AGP 9.x / Gradle 9.x |
| 主要依赖 | AppCompat 1.6.1、Material 1.11.0、ExifInterface 1.3.7、WebKit 1.11.0 |

## 快速开始

### 直接安装稳定旧版（推荐，不需要视频水印时）

1. 下载 [巡查相机 v20260825-02 APK](https://gitee.com/zjywchenhui/filecamera/releases/download/v20260825-02/%E5%B7%A1%E6%9F%A5%E7%9B%B8%E6%9C%BAv20260825-02.apk)
2. 在 Android 10 及以上设备安装即可

> 天气、地址、路线等功能需要配置高德 API Key 和路线数据，见下文。

### 从源码编译当前重构版

```bash
git clone https://gitee.com/zjywchenhui/filecamera.git
cd filecamera/source/filecamera
./gradlew assembleDebug
```

Windows：

```powershell
.\gradlew.bat assembleDebug
```

产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

环境要求：Android Studio（支持 AGP 9.x）| SDK 34 | JDK 17+

## 使用指南

### 配置天气与地址

1. 在 [高德开放平台](https://lbs.amap.com/) 申请 **Web 服务** API Key
2. App 内进入 **设置 → 密钥管理**，填入 Key
3. 在天气字段的 adcode 输入框旁点击 **获取**，通过 GPS 自动获取城市编码

### 配置路线与桩号

1. 使用 [路线管理](https://gitee.com/zjywchenhui/road_manage) 导出 GeoJSON 路线
2. App 内进入 **路线管理**，导入 GeoJSON
3. 拍照时自动匹配最近路线并计算桩号

### 批量部署水印配置

1. 在一台设备上配置好水印
2. **设置 → 导出设置包**，保存 ZIP
3. 在目标设备 **设置 → 导入设置包**，选择 ZIP

## 版本说明

- **v20261020-01**：Camera2 视频水印重构版，含视频水印、视频浏览器、新相机 UI 和震动反馈；建议先小范围验证
- **v20260825-02**：稳定旧版，不含视频水印，建议只需要照片水印的用户使用
- 当前源码包：[v20261020-01.zip](https://gitee.com/zjywchenhui/filecamera/archive/refs/tags/v20261020-01.zip)
- 历史版本：[Gitee Releases](https://gitee.com/zjywchenhui/filecamera/releases)

## 开源协议

[MIT License](./LICENSE) © 2025 zjywchenhui

## 关联项目

- [路线管理](https://gitee.com/zjywchenhui/road_manage) — 路线数据管理，生成巡查相机所需的 GeoJSON 路线文件
