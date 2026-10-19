# 巡查相机 · 交接文档（新会话从这里开始）

> 最后更新：2026-10-19（本地 `master` @ `b4aad81`）
> 当前状态：**Camera2 迁移 + 边录边烧已完成**；录像直接输出标准 `.mp4`（带水印 + 音频），
> 分片封装保证录制中被杀也能恢复。旧的 `.wmp4` 只保留只读播放兼容。
> 下一步：真机回归竖拍/横拍/前后摄/闪光/变焦，以及第 13 节的收尾项。

---

## 1. 项目与环境

| 项 | 值 |
|---|---|
| 仓库根目录 | `D:\gitee\filecamera` |
| 工程目录 | `D:\gitee\filecamera\source\filecamera` |
| 包名 | `com.filecamera.apk` |
| 构建 | AGP 9.2.1 / Gradle 9.4.1 / JDK 21 / minSdk 29 / targetSdk 34 |
| SDK | `%LOCALAPPDATA%\Android\Sdk` |
| JDK | `C:\Program Files\Android\Android Studio\jbr` |
| 真机 | serial `QV710FXJ5F`（Sony XQ-AT72，Android 12，arm64） |
| 模拟器 | `emulator-5554`（MuMu，x86_64 + houdini，**GL/编码器不可靠，只能验 UI 逻辑**） |
| 应用形态 | WebView 壳 + 原生相机/播放器/导入 |

### 构建与安装

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
cd D:\gitee\filecamera\source\filecamera
.\gradlew.bat :app:assembleDebug --console=plain

# 真机
adb -s QV710FXJ5F install -r app\build\outputs\apk\debug\app-debug.apk
adb -s QV710FXJ5F shell am force-stop com.filecamera.apk
adb -s QV710FXJ5F shell am start -n com.filecamera.apk/.MainActivity
adb -s QV710FXJ5F logcat -c ; adb -s QV710FXJ5F logcat -d -v time -s FileImporter CameraActivity VideoPlayerActivity
```

> 注意：手机息屏时 `screencap` 是全黑，操作前先解锁。adb 会偶发 `device unauthorized`，重新 `adb devices` 即可恢复。

---

## 2. 当前架构总览

```
WebView(assets/index.html + js)  ── bridge ──┐
                                             ├─ BackServer       （列表/目录/文件等通用能力）
                                             ├─ FilePickerBridge （相机、播放器、导入、选文件）
                                             └─ Android          （杂项：wakelock 等）

原生：
  MainActivity       WebView 宿主；启动自愈；原生文件/文件夹导入（系统选择器 + 进度遮罩）
  CameraActivity     相机（Camera2）：预览 / 拍照 / 录像 / 水印 / 会话
  Camera2Controller  Camera2 设备/会话/拍照/对焦/变焦/闪光/录像生命周期
  Camera2GlRenderer  相机帧 + 水印 → OpenGL 合成到编码器 Surface
  Camera2MuxerSink   视频/音频样本 → 分片 MP4（Media3 FragmentedMp4Muxer）
  VideoPlayerActivity 播放器：自定义控制条、音量；旧 .wmp4 仍可叠印
  FileImporter       【唯一入库实现】照片 / 录像 / 启动恢复
  WatermarkBitmapRenderer 把水印画到 Bitmap（拍照缩略图 + 录像边录边烧）
  WatermarkFileCodec 旧 .wmp4 尾部水印数据只读兼容（不再写入）
  CameraSessionStore 拍照会话临时目录
  DatabaseHelper     SQLite（v4）
  IdGenerator        get_id_by_time() → "<毫秒时间戳>-<同毫秒序号>"
```

### 数据库（v4，4 张表）

| 表 | 用途 |
|---|---|
| `dir` | 目录（id/name/parent_dir_id/type/createtime/filesortmode/dirsortmode/seqnumber） |
| `file` | 文件记录（id/name/parent_dir_id/real_file_name/real_icon_name/createtime/seqnumber/size/extension） |
| `pending_file` | 两阶段入库的预备表（外部不可见），含 tmp_photo_path / tmp_icon_path |
| `android_metadata` | SQLite 内置 |

**重要：`file_watermark` 表已在 v4 删除**，视频水印数据只存在 `.wmp4` 文件尾部，数据库不保存任何水印信息（这是用户明确要求）。

---

## 3. 磁盘布局

```
files/
  app_data/<id>.<ext>              已入库的正式文件（只有完整已提交才会出现在这里）
  icons/<id>_icon.png              缩略图（图片是缩放图；视频是"带水印的首帧"）
  photo_tmp/session_<id>/          拍照会话临时目录（CameraSessionStore，启动 gc）
  video_pending/<id>/              录像待入库目录（★ 关键）
      <id>.mp4.part                录像数据（Camera2 + GL 边录边烧，分片 MP4）
```

> 已删除：`burn_tmp/`、`video_tmp/`、`meta.jsonl`。新录像不需要水印旁文件。

---

## 4. 旧 `.wmp4` 格式（仅历史文件只读兼容）

> 新录像已改为标准 `.mp4`：水印在录制时由 GL 烧进画面，不再写尾部数据。
> 下面仅用于读懂/播放历史 `.wmp4`；`WatermarkFileCodec` 只保留读取与哈希校验。

```
[ 标准 MP4（ftyp/moov/mdat） ][ 水印JSON ][ 4字节长度 ][ 魔术字 "WMP4" ]
```

- 写入：`WatermarkFileCodec.writeTrailer(file, json)`
- 读取：`readTrailer(file)` —— **在文件末尾 8KB 内倒序搜索 `WMP4`**，命中后取其前 4 字节为长度、再回溯读取 JSON（不假设紧贴 EOF）
- 哈希：`contentHash(file)` 只对"水印区之前"的字节算 SHA-256，写在 JSON 的 `content_hash` 里；播放前重算比对，不一致就不叠印
- 后缀常量：`WatermarkFileCodec.EXTENSION = "wmp4"`
- 定位：本 App 的专用格式，**分享/导出就是原文件直出，不做任何转换**；要通用视频先"烧录"再导出 `.mp4`

### 水印 JSON 结构

```json
{
  "ver": 1,
  "base_time_ms": 1792393000000,
  "scale": 0.75,
  "radius": [8,8,8,8],
  "position": {"left":10,"bottom":10},
  "header": {"...":"完整（来自相机配置）"},
  "body":   {"...":"完整"},
  "foot":   {"...":"完整"},
  "icon_ref": "file:///android_asset/icon.png",
  "pos": "left_bottom",
  "segments": [
    {"from_ms":0,   "values":{...全量...}, "pos":"left_bottom"},
    {"from_ms":1003,"values":{...全量...}, "pos":"left_bottom"}
  ],
  "keyframes": [0,1003,2056,...],
  "timeline": "keyframe",
  "content_hash": "sha256(水印区之前的字节)"
}
```

- **只在关键帧上存完整快照，帧间不存任何数据**（用户明确要求，像字幕一样"就近取上一个关键帧"）
- `pos` 四值：`left_top / left_bottom / right_top / right_bottom`；位置变化也回填
- 时间字段不进 segments：播放时用 `base_time_ms + 播放位置` 实时算
- 图标只存引用（`icon_ref`），图标本体不进数据库/不重复存

---

## 5. 关键流程

### 5.1 拍照
`CameraActivity.takePhoto()` → 拍照快照（水印位图 + GPS + 配置）→ 同步合成 → `FileImporter.importLocalFile()` → 入库 → 会话记录 → 退出时通过 `RESULT_OK` 把新增记录回传前端

### 5.2 录像（Camera2 + 边录边烧）
```
startRecording()
  ├ beginVideo(): 建 files/video_pending/<id>/，pending 行，目标 .mp4
  ├ Camera2 会话输出：预览 Surface + GL 相机 SurfaceTexture
  ├ Camera2GlRenderer：相机帧 + 当前水印位图 → MediaCodec 输入 Surface（H.264）
  ├ AudioRecord → MediaCodec AAC（按第一帧视频建立相机时钟基准，避免音画/时长错位）
  └ Camera2MuxerSink：FragmentedMp4Muxer，1 秒一个分片，写 <id>.mp4.part

stop → signalEndOfInputStream / 音频 EOS
  → muxer.close() → finishVideo():
      ① 探测可播 ② 首帧缩略图（画面已带水印）③ move 到 app_data/<id>.mp4
      ④ promote() ⑤ 删除 video_pending/<id>/
```
旋转：始终按传感器方向编码横屏，`Mp4OrientationData(jpegRotation)` 写 tkhd；
竖拍/横拍/倒置都由播放器按元数据转正，水印位图在编码前按同一角度预旋转。

### 5.3 启动自愈（MainActivity.onCreate，主线程，静默）
```
① scanPendingVideoDirs()  先扫录像（必须在照片修复之前）
     a. id 已在 file 表 → 删目录（入库成功但没删干净）
     b. 视频可播        → 补做收尾并入库 → 删目录
     c. 视频不可播/没有 → 丢弃目录 + 删 pending 行
② repairPendingImports()  照片等遗留 pending 行（跳过 .part/录像行）：主文件在→补提交；缺失→丢弃
```

### 5.4 文件 / 文件夹导入（原生统一入口）
```
JS: await callAndroidMethod(FilePicker, 'importPath', {dirId, mode:'file'|'dir'})
 → MainActivity.beginImport() → 系统选择器（OpenMultipleDocuments / OpenDocumentTree）
 → 原生弹进度遮罩（1 秒节流刷新 "导入中 n/N：文件名"）
 → 后台线程导入（文件夹用显式栈迭代收集；ensureParentDir 也用栈，自顶向下建目录）
 → 回调 {records, count} 或 {canceled:true} → JS updateTable
```

### 5.5 播放器（VideoPlayerActivity）
- 自定义控制层（不用 Media3 默认控制条）：顶部返回+文件名、中央播放键、底部音量行 + 进度行
- **水印层紧贴视频画面矩形**：按画面宽高算 letterbox，水印只在画面内定位，密度基准 = 画面宽度/360
- 水印与控制层**解耦**：控制条显隐不改变水印位置
- 播放键：**播放中隐藏、暂停/结束显示**（只由播放状态决定）
- 控制条：无操作 **5 秒**自动隐藏；任何操作重置计时；暂停不自动隐藏
- 播放结束：进度条顶到最右、时间显示总时长（容器时长可能略长于实际内容）
- 新 `.mp4` 水印已烧进画面，播放器不叠印；旧 `.wmp4` 仍按尾部数据叠印（只读兼容）
- 烧录按钮/遮罩已移除

---

## 6. 已验证过的事实（真机 Sony XQ-AT72 / Android 12）

- 拍照导入：`file`/`app_data`/`icons` 三者 1:1:1 增长，`pending_file` 收尾为 0
- 拍照：真机竖拍照片方向正确，水印/二维码正常合成，EXIF/GPS 正常
- 录像：横拍 5 秒 → 标准 `.mp4`（1920×1080 + AAC），播放器显示时长 00:04/00:05，带水印、可播
- 录像（强制竖拍元数据）：1920×1080 编码 + tkhd 90°，播放器转正后水印文字正立
- 录制中被 `am force-stop` 强杀：`video_pending/<id>/<id>.mp4.part` 保留，
  重开 App 自动恢复为 app_data 里的 `.mp4`，缩略图生成成功、可播（约丢最后不到 1 秒）
- 启动顺序修正后：录像不会再被照片修复器当成图片搬走
- 音频：MP4 内 AAC 样本正常，首个音频样本与首个视频样本相差约 16ms

---

## 7. 已知问题 / 遗留

| # | 问题 | 状态 |
|---|---|---|
| 1 | 录制中退出/被硬杀 → 视频丢失 | **已解决**：1 秒分片 + 启动扫描恢复 |
| 2 | 烧录（Media3 Transformer）在模拟器报 `GlUtil.bindTexture` | **已移除烧录功能**，不再依赖 |
| 3 | 录像比例固定 16:9，不跟随设置里的 4:3 | 待做 |
| 4 | 手机无 `sqlite3` CLI，需把 db 拉回电脑查（见第 10 节坑） | 已知 |
| 5 | 相机 Activity 仍锁竖屏：手机横持时预览/UI 是竖屏画面（录像本身会按 tkhd 转正） | 待确认是否要支持横屏 UI |
| 6 | 水印位图每秒整帧重绘 + 上传（1080p） | 可用，后续可优化为只画水印块 |
| 7 | 前置录像未做镜像（预览镜像，成片不镜像） | 按常见相机行为，待确认 |

---

## 8. 用户偏好（务必遵守）

1. **稳定优先**（工具软件），先小步可回滚，再谈优化；
2. 图片/视频处理尽量**同步**（同步处理不容易出 bug）；
3. 提示不要盖住底部操作区；修复过程要**静默**（不弹 toast）；
4. 中文注释、提交信息用中文；每个里程碑**提交到本地 git**（不 push，除非用户要求）；
5. 新录像直接输出标准 `.mp4`（水印已烧进画面），分享/导出不做转换；旧 `.wmp4` 只读兼容；
6. 水印在录制时合成，数据库不保存任何水印信息；
7. 录像按第一帧视频建立相机时钟基准，音画用同一时钟；
8. 先讨论方案再写代码（"先不写代码"是常态要求）。

---

## 9. CameraX → Camera2 迁移（已完成，2026-10-19 @ b4aad81）

> 本节保留为迁移记录；实际实现比原计划更彻底：录像直接边录边烧、输出标准 `.mp4`。
> 新实现见第 13 节。

### 9.1 为什么必须整体迁移

一台设备同一时刻只能被一套相机栈占用。Camera2 的一个 `CaptureSession` 可以同时输出"预览 Surface + 编码器 Surface"，但**无法与 CameraX 的 Preview/VideoCapture 共存**。所以：预览 + 拍照 + 录像必须全部走 Camera2。

根因需求：CameraX 的 `Recorder` 独占输出文件生命周期（取消录制时会删掉临时文件，我们拿不到任何画面），只有自己拿 Surface 才能实现 **fMP4 分片录制 → 录制中被杀也可播**。

### 9.2 API 对照

| CameraX | Camera2 |
|---|---|
| `ProcessCameraProvider.bindToLifecycle` | `CameraManager.openCamera` + `createCaptureSession` |
| `Preview` + `previewView.surfaceProvider` | `SurfaceView`/`SurfaceTexture` 的 `Surface` 作为会话输出 |
| `ImageCapture.takePicture` | `ImageReader`(JPEG) + `TEMPLATE_STILL_CAPTURE` + `JPEG_ORIENTATION` |
| `cameraControl.setZoomRatio` | `CONTROL_ZOOM_RATIO`（API30+，低版本退 `SCALER_CROP_REGION`） |
| `imageCapture.flashMode` / `enableTorch` | `CONTROL_AE_MODE` + `FLASH_MODE` / `FLASH_MODE_TORCH` |
| `FocusMeteringAction` | `CONTROL_AF_REGIONS` + `CONTROL_AF_MODE` |
| `Recorder` + `VideoCapture` | `MediaCodec.createInputSurface()` + 自选 muxer |
| `QualitySelector` | 自己按 `StreamConfigurationMap` 选尺寸 + 码率 |

### 9.3 分片封装 API（已验证）

```java
// androidx.media3:muxer（已在依赖里，Media3 1.4.1）
new FragmentedMp4Muxer.Builder(fileOutputStream)
        .setFragmentDurationMs(1000)   // 1 秒一个分片
        .build();
// 接口：addTrack(Format) → TrackToken
//       writeSampleData(token, ByteBuffer, MediaCodec.BufferInfo)
//       addMetadataEntry(Metadata.Entry)   // 可考虑把水印JSON作为 metadata（可选）
//       close()
```

### 9.4 实施阶段（建议按此顺序，每阶段一次提交）

**阶段 1：预览 + 拍照（录像按钮临时禁用）**
- 新增 `Camera2Controller`：开/关相机、会话（预览 + ImageReader）、方向、前后切换、变焦、闪光、点击对焦；
- `CameraActivity` 改为通过它驱动；
- 验收：拍照全流程与现在一致（水印合成/入库/缩略图/旋转/前后切换/变焦/闪光/点击对焦）。

**阶段 2：录像（MediaCodec + FragmentedMp4Muxer）**
- 会话输出 = 预览 Surface + `MediaCodec(H.264).createInputSurface()`；音频 `AudioRecord` → `MediaCodec(AAC)`；
- 两个 track 都写 `FragmentedMp4Muxer`（`setFragmentDurationMs(1000)`），落到
  `files/video_pending/<id>/<id>.wmp4.part`，`meta.jsonl` 继续每秒 append；
- 停止：EOS 排空 → `muxer.close()` → 走现有 `finishVideo`（写 `.wmp4` 尾部 → 搬 app_data → 缩略图 → promote → 删目录）；
- 验收：录 3~5 秒 → **强杀进程** → 重开 App → 自动恢复入库，视频可播、带水印、时长少 ≤1 秒算成功（`incomplete` 标记可留）。

**阶段 3：清理**
- 移除 camera-core / camera-camera2 / camera-lifecycle / camera-view 依赖与相关代码。

### 9.5 风险清单

1. 尺寸协商：按 `StreamConfigurationMap` 选，禁止写死；预览与编码尺寸不同时注意形变；
2. 旋转：`onConfigurationChanged` 要重建会话，`JPEG_ORIENTATION` / `MediaFormat.KEY_ROTATION` 要配对；
3. 变焦：`CONTROL_ZOOM_RATIO` 需 API 30+，低版本退化 `SCALER_CROP_REGION`；
4. 闪光：前置无闪光灯；录像补光用 `FLASH_MODE_TORCH`；
5. 生命周期：`onPause` 关设备、`onResume` 重开，避免"相机被占用"；
6. 音画同步：以视频首帧 PTS 为基准对齐音频；结束时 EOS 排空，防止最后分片损坏；
7. 首帧延迟/黑屏：`SurfaceView` 需自行处理 `AspectRatio` 与尺寸变化；
8. 真机逐项回归：录像、水印、缩放、旋转、前后切换、闪光。

---

## 10. 踩过的坑（务必记住）

### 10.1 编码/平台类
- **`MediaExtractor.readSampleData` 缓冲区必须 ≥ 样本大小**：真机 1080p 关键帧远超 64KB，固定小缓冲会抛 `IllegalArgumentException`，导致水印数据整段写不进去（模拟器 720p 不会暴露）。现在按 `getSampleSize()` 扩容（2MB 起，上限 32MB），并有关键帧读不到时"退化为 1 秒采样"的兜底。
- 模拟器（MuMu + houdini）**GL 不可靠**：CameraX 预览偶发灰屏、JPEG 编码偶发 SIGSEGV、Media3 Transformer 烧录必报 `GlUtil.bindTexture`。**任何与编码/GL 相关的结论只能在真机上下**。
- MP4 容器时长可能**略长于**实际内容，`STATE_ENDED` 时进度条会停在 80% 左右 → 已在 `onPlaybackStateChanged(STATE_ENDED)` 里把进度顶到最右。

### 10.2 工具类（血的教训）
- **绝不要用 `Get-Content`/`Set-Content -Encoding UTF8` 改源码**：会把中文注释写成乱码。统一用
  `[System.IO.File]::ReadAllText($p,[Text.Encoding]::UTF8)` + `WriteAllText($p,$t,(New-Object Text.UTF8Encoding($false)))`。
- 用 PowerShell 的"哈希表 + 嵌套数组"批量替换字符串，数组会被**展开成字符**，结果是逐字符替换、把源码写坏。复杂替换请用 `edit` 工具，或老老实实一次一处。
- 行内替换注意 `String.Replace(a,b,c)` 不存在（只有两参版）。
- `adb shell` 里拼 SQL 极易因引号失败：把 SQL 写成文件推给设备执行更稳：
  ```powershell
  "select ...;" | & $adb shell "run-as com.filecamera.apk sh -c 'cat > files/q.sql'"
  & $adb shell "run-as com.filecamera.apk sh -c 'sqlite3 databases/backserver.db < files/q.sql'"
  & $adb shell "run-as com.filecamera.apk sh -c 'rm -f files/q.sql'"
  ```
- 真机**没有 `sqlite3` CLI**（`run-as: exec failed for sqlite3`）。查库方式：`adb -s <serial> exec-out "run-as com.filecamera.apk cat databases/backserver.db" > x.db`（注意 PowerShell 重定向会破坏二进制，必要时用 `base64` 转一道再解码）。
- `adb exec-out screencap -p` 直接把二进制塞进 PowerShell 管道会得到坏图；改成设备上 `screencap -p /sdcard/x.png` 再 `adb pull`。
- 手机息屏时 `screencap` 全黑，先解锁。

### 10.3 行为类
- CameraX 取消录制会**删掉自己的临时 MP4**：`video_pending/<id>/` 里只会留下我们写的 `meta.jsonl`。
- 修复程序（`repairPendingImports`）对"视频不可播"的行会 `discard`（删 pending + 文件），这是设计行为，不是 bug。
- `pending_file` 的两阶段事务：`INSERT pending` → 搬文件 → 缩略图 → 一个事务里 `INSERT file ... ; DELETE pending`，保证"可见即完整"。

### 10.4 Camera2 / GL 录像踩坑
- **相机 PTS 与 `System.nanoTime` 可能不在同一时钟域**：相机时间戳更接近 BOOTTIME（含深度睡眠），
  `System.nanoTime()` 是 MONOTONIC。直接相减会得到几百/几千秒的偏移，表现为视频时长 77:49、
  音轨首样本时长远大于正常值。现在先等第一帧视频，记录 `videoBasePtsUs` 和
  `cameraClockOffsetUs`，音频再用同一个相机时钟打时间戳。
- **`MediaMetadataRetriever` 读 fMP4**：`mvhd duration` 可能为 0，不能只看 duration 判断可播；
  改成 `MediaExtractor` 选视频轨 + 读一个样本。
- **启动修复顺序**：必须先 `scanPendingVideoDirs()` 再 `repairPendingImports()`，
  否则照片修复器会把 `video_pending/<id>/<id>.mp4.part` 当图片搬进 app_data。
- **旋转**：不要用 GL 猜相机方向；始终横屏编码 + `Mp4OrientationData(jpegRotation)`，
  水印位图按同一角度预旋转。这样竖拍/横拍/倒置都走同一条路。
- **GL 合成**：相机 SurfaceTexture → OES 纹理 → 编码器输入 Surface；
  水印用与编码画面同尺寸的透明位图，每秒重绘一次。EGL 要带 `EGL_RECORDABLE_ANDROID`，
  每帧 `eglPresentationTimeANDROID` 必须设置，否则编码器 PTS 不对。

---

## 11. Git 状态

```
分支：master
状态：领先 origin/master 约 51 个提交，从未推送；工作区干净
最近提交：
  b4aad81 录像改为 Camera2 边录边烧：标准 MP4 + 音频 + 强杀恢复
  3bca2f8 新增交接文档 HANDOVER.md
  9bbbe92 录像改为"持久待入库目录 + meta.jsonl 流式水印 + 启动扫描恢复"
```
如需推远端：`git push origin master`（用户未要求，先别推）。

---

## 12. 新会话开局面向的三件事

1. **读这份文档** + `git log --oneline -10` 对齐状态；
2. **确认构建可过**：`.\gradlew.bat :app:assembleDebug`；
3. **真机回归**竖拍/横拍/前后摄/闪光/变焦，再按第 13 节收尾。

---

## 13. 2026-10-19 Camera2 + 边录边烧 变更记录（★当前实现）

### 13.1 新录像链路
```
CameraActivity.startRecording()
  → FileImporter.beginVideo(dir, "<id>.mp4")
  → Camera2Controller.startVideoRecording(file, jpegRotation, liveWatermark)
      ├ Camera2 预览 Surface + GL 相机 SurfaceTexture
      ├ Camera2GlRenderer：相机帧 + 水印 → MediaCodec(H.264) 输入 Surface
      ├ AudioRecord → MediaCodec(AAC)
      └ Camera2MuxerSink：FragmentedMp4Muxer(1s) → video_pending/<id>/<id>.mp4.part
  → stop：EOS → muxer.close() → finishVideo() → app_data/<id>.mp4
```

### 13.2 关键类
| 类 | 职责 |
|---|---|
| `Camera2Controller` | 开/关相机、会话、拍照 ImageReader、对焦/变焦/闪光、录像生命周期、音频 |
| `Camera2GlRenderer` | EGL/GL：OES 相机纹理 + 水印位图 → 编码器 Surface；每帧 PTS |
| `Camera2MuxerSink` | 等视频/音频格式齐 → addTrack → 分片写入；支持音频轨降级 |
| `WatermarkBitmapRenderer` | 按画面尺寸画水印；边录边烧每秒重绘 |
| `FileImporter` | 两阶段入库；录像 `beginVideo/finishVideo/abortVideo`；启动扫描恢复 |

### 13.3 输出格式
- 新录像：标准 `.mp4`，视频 H.264（1920×1080，约 10Mbps）+ 音频 AAC（44.1kHz 单声道 96kbps）；
- 横屏编码 + `tkhd` 旋转元数据（`Mp4OrientationData`），竖拍/横拍都能转正；
- 水印已烧进像素，不写 `.wmp4` 尾部、不写 `meta.jsonl`；
- 旧 `.wmp4` 仍可播放/叠印（只读兼容），但不再产生新文件。

### 13.4 已知行为
- 相机 Activity 仍锁竖屏；手机横持时预览是竖屏画面（录像会按旋转元数据转正）；
- 前置录像不做镜像（预览镜像）；
- 水印每秒整帧重绘一次，1080p 下偶发掉一帧，可后续优化为只画水印块；
- 音频拒绝授权时录像继续但无声（`audio_permission_asked` 只问一次）。

### 13.5 下一步
1. 真机回归：竖拍/横拍、前后摄、闪光、变焦、对焦、连续拍照、录像中退出/强杀；
2. 需要的话再做“横屏 UI”或“边录边烧开关”；
3. 观察长录像（>10 分钟）的音画同步、文件大小、分片恢复；
4. 如要清理：`WatermarkFileCodec` 的旧格式只读兼容可在确认没有历史文件后移除。
