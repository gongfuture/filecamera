# 巡查相机（FileCamera）

一款纯粹、公益、无广告的开源水印相机，致力于为一线工作人员提供清爽、高效的拍照与管理体验。

> **最新版本**：v20260820-02 | [下载 APK](./巡查相机v20260820-02.apk) | [所有版本](https://gitee.com/zjywchenhui/filecamera/releases)
>
> v20260820-02：简化天气自动模式——adcode 留空时不再单独缓存 adcode，每次启动后首次打开相机从 GPS 解析 adcode 并与缓存天气比对：同城且未过期直接复用，城市变化或已过期按新 adcode 拉取（城市变化重启 App 即可刷新）

## 为什么做这个

市面上的水印相机广告繁多，一线工人师傅常因误触下载无关应用甚至意外扣费。巡查相机应运而生——**无广告、无付费、无隐藏条款**，干净、可靠、易用。

## 核心功能

### 📸 水印管理
- **高度自定义水印**：页眉、主体、页脚三区域独立配置，文字内容、颜色、字号随意调整
- **自定义水印图标**：支持替换页眉图标，满足企业品牌需求
- **动态字段**：支持路线名称、桩号、坐标、时间、天气、地址等动态填充
- **配置导入/导出**：将水印配置打包为 ZIP，批量部署到多台设备——配置一次，到处使用

### 📁 文件夹管理
- 像电脑一样以文件夹形式整理照片，告别手机相册的混乱
- 支持新建、重命名、移动、删除文件夹
- 照片存储在应用私有目录，**与个人相册隔离**，工作归工作，生活归生活
- 需要分享时，可导出到公共目录

### 🌤 天气与地理位置
- 配置[高德 Web 服务 API Key](https://lbs.amap.com/)后，自动获取拍摄地天气和精确地址
- **adcode 一键获取**：配置好密钥后，点击按钮即可通过 GPS 自动解析，无需手动上网查找

### 🛣 路线匹配与桩号计算
- 搭配 [路线管理](https://gitee.com/zjywchenhui/road_manage) 导出 GeoJSON 路线数据
- 导入后拍照时自动匹配最近路线并计算桩号，适用于公路巡检、管线巡查等场景

### 📍 二维码导航
- 拍摄照片自动生成含经纬度的高德导航二维码
- 图片分享到微信后，识别二维码即可一键导航到拍摄位置

### 📷 相机功能
- 多种画幅比例（16:9 / 4:3 / 1:1）
- 触控对焦、闪光灯控制、GPS 定位
- **同步相册**：开启后拍摄时自动复制一份到 DCIM/Camera 目录

## 软件架构

采用 WebView + Java 混合开发模式，WebView 负责 UI 展示与用户交互，Java 原生层负责相机调用、图像处理、水印绘制及文件管理等核心逻辑。

```
前端 (WebView / JS)             后端 (Java)
───────────────────             ─────────────────────
index.html          ←──→        MainActivity.java        主入口、定位服务
setting.js          ←──→        WatermarkConfigManager   水印配置管理（导入/导出）
view.js             ←──→        CameraActivity.java      相机预览、拍摄、水印渲染
menu.js             ←──→        BackServer.java          内置 HTTP 文件服务
file_server.js      ←──→        FilePickerBridge.java    文件选择、配置 zip 打包
key_manage.js       ←──→        GaodeApi.java            高德天气、地理逆编码、坐标转换
road_data.js        ←──→        RoadApiHelper.java       路线匹配引擎
                                RoadDatabaseHelper.java  路线数据库
                                DatabaseHelper.java      文件索引数据库
                                Android_js_bridge.java   JS ↔ Native 桥接
```

## 技术栈

| 类别 | 技术 |
|------|------|
| 最低支持 | Android 10（API 29） |
| 编译 SDK | 34 |
| 相机 | CameraX 1.3.3 |
| 二维码 | ZXing 3.5.3 |
| 前端 | WebView（HTML5 / CSS3 / JavaScript） |
| 后端 | Java 17 |
| 依赖库 | AppCompat 1.6.1, Material 1.11.0, ExifInterface 1.3.7, WebKit 1.11.0 |

## 快速开始

### 直接安装（推荐）

1. 下载最新 [APK 安装包](./巡查相机v20260820-02.apk)
2. 在 Android 设备上安装即可使用

> 天气、地理位置等联网功能需配置高德 API Key，见下方说明。

### 从源码编译

```bash
git clone https://gitee.com/zjywchenhui/filecamera.git
```

用 Android Studio 打开 `source/filecamera` 目录，等待 Gradle 同步完成后编译。

环境要求：Android Studio Hedgehog+ | SDK 34 | JDK 17+

## 使用指南

### 开启天气与地理位置

1. 前往[高德开放平台](https://lbs.amap.com/)注册并申请 **Web 服务** API Key
2. 在 App 中进入 **设置 → 密钥管理**，填入 API Key
3. 在天气字段的 adcode 输入框旁点击 **获取** 按钮，通过 GPS 自动获取城市编码

### 实现路线桩号匹配

1. 使用 [路线管理](https://gitee.com/zjywchenhui/road_manage) 导出 GeoJSON 路线文件
2. 在 App 中进入 **路线管理**，导入 GeoJSON 文件
3. 拍照时自动匹配最近路线并计算桩号

### 批量部署水印配置

1. 在一台设备上配置好水印（图标、文字、颜色、布局等）
2. 进入设置点击 **导出设置包**，保存生成的 ZIP
3. 在目标设备上进入设置点击 **导入设置包**，选择 ZIP 文件即可

## 开源协议

[MIT License](./LICENSE) © 2025 zjywchenhui

## 关联项目

- [路线管理](https://gitee.com/zjywchenhui/road_manage) — 路线数据管理，生成巡查相机所需的 GeoJSON 路线文件
