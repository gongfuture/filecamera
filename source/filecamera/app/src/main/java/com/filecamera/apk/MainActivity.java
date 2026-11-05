package com.filecamera.apk;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import androidx.exifinterface.media.ExifInterface;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import android.webkit.MimeTypeMap; // 确保在文件顶部导入了这个类
import java.util.HashSet;       // 确保导入

import android.provider.Settings;
import androidx.appcompat.app.AlertDialog;
public class MainActivity extends AppCompatActivity {
	
	private static final String TAG = "MainActivity";
    private String indexurl = "https://appassets.androidplatform.net/assets/index.html";
    private String open_in_app_url_head = "https://appassets.androidplatform.net";

    // WebView 和相关组件
    private WebView webView;
    private View fullscreenView;
    private FrameLayout fullscreenContainer;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private ValueCallback<Uri[]> filePathCallback;
    private String cameraPhotoPath;

    // 定位服务
    private LocationManager locationManager;
    private LocationListener locationListener;
    private double lat, lon, alt;
    private long locationTime;
    // 最近一次 GPS 定位的时间，用于 GPS 优先策略（网络定位仅在 GPS 超时时使用）
    private long gpsLocationTime;
    private static final long GPS_PRIORITY_TIMEOUT_MS = 15000L;

    // 系统服务和 JS 桥接
    private Android_js_bridge jsBridge;
    private FilePickerBridge filePickerBridge;

    // region --- 原生侧统一导入入口（文件 / 文件夹，走同一套入库流程） ---
    /** 导入结果回调：payload 里带 records（成功）或 canceled=true（取消）。 */
    public interface ImportResult {
        void onResult(org.json.JSONObject payload);
    }

    private ImportResult pendingImportResult;
    private String pendingImportDirId = "root";
    private String pendingImportMode = "file";

    private final androidx.activity.result.ActivityResultLauncher<String[]> importFilesLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
                    uris -> runImportFiles(uris));
    private final androidx.activity.result.ActivityResultLauncher<android.net.Uri> importDirLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                    uri -> runImportDir(uri));

    /**
     * 统一导入入口：mode = "file" 选文件（可多选），mode = "dir" 选文件夹（递归导入）。
     * 调用方（WebView）等这个回调，天然就是"同步/加锁"语义；取消也要回调，保证锁能释放。
     */
    public void beginImport(String mode, String dirId, ImportResult result) {
        pendingImportResult = result;
        pendingImportDirId = (dirId == null || dirId.trim().isEmpty()) ? "root" : dirId;
        pendingImportMode = "dir".equals(mode) ? "dir" : "file";
        try {
            if ("dir".equals(pendingImportMode)) {
                importDirLauncher.launch(null);
            } else {
                importFilesLauncher.launch(new String[]{"*/*"});
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "启动导入选择器失败", e);
            finishImport("");
        }
    }
    // 通用原生进度遮罩：导入 / 导出 / 删除 / 移动 / 复制 / 路线更新共用这一层（原生）。
    // H5 不再为这些操作叠 loading，双方都通过 show/update/finish/hide 驱动同一套 UI。
    private android.view.View importOverlay;
    private android.widget.TextView importOverlayTitle;
    private android.widget.TextView importOverlayDetail;
    private android.widget.TextView importOverlayFile;
    private android.widget.ImageView importOverlaySpinner;
    private android.widget.ProgressBar importOverlayBar;
    private android.widget.ProgressBar importOverlayBarIndeterminate;
    private android.animation.ObjectAnimator importSpinnerAnimator;
    private long lastProgressPostMs = 0;
    /** 最近一次的进度值，finishProgress() 用它把进度条顶到 100%。 */
    private int lastDone = 0;
    private int lastTotal = 0;
    /**
     * 单次导入最多回传这么多条记录：前端拿到结果后本来就会 updateTable 重新拉列表，
     * 把几万条记录再逐层拼成巨型 JSON（还要在主线程 toString + evaluateJavascript）
     * 只会在结束时把 UI 卡死甚至 OOM，对界面没有任何用处。
     */
    private static final int MAX_RESULT_RECORDS = 200;

    /** 懒加载：第一次导入时才 inflate 并挂到 Activity 最上层。 */
    private void ensureImportOverlay() {
        if (importOverlay != null) return;
        android.view.View root = getLayoutInflater().inflate(R.layout.dialog_import_progress, null);
        importOverlayTitle = root.findViewById(R.id.import_title);
        importOverlayDetail = root.findViewById(R.id.import_detail);
        importOverlayFile = root.findViewById(R.id.import_file);
        importOverlaySpinner = root.findViewById(R.id.import_spinner);
        importOverlayBar = root.findViewById(R.id.import_bar);
        importOverlayBarIndeterminate = root.findViewById(R.id.import_bar_indeterminate);
        // 与 H5 的 `#loadingimg { animation: spin 2s linear infinite }` 完全一致
        importSpinnerAnimator = android.animation.ObjectAnimator.ofFloat(importOverlaySpinner, "rotation", 0f, 360f);
        importSpinnerAnimator.setDuration(2000L);
        importSpinnerAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        importSpinnerAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        root.setVisibility(android.view.View.GONE);
        addContentView(root, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        importOverlay = root;
    }

    // ===== 通用进度遮罩 API：Java 侧与 JS 桥都调这几个 =====

    /** 不确定进度：一行标题 + 一句说明，进度条用不定态样式。 */
    public synchronized void showProgress(String title, String detail) {
        // 归零节流时间戳：否则紧接着的第一条 updateProgress 会被 250ms 节流吞掉，
        // 界面会一直停在"不确定进度"的样子（复制文件夹时尤其明显）。
        lastProgressPostMs = 0;
        lastDone = 0;
        lastTotal = 0;
        // 取消可能还挂着的"延时收起"，避免把新弹出来的遮罩收掉（与下面的 render 保持投递顺序）
        runOnUiThread(() -> {
            if (importOverlay != null) importOverlay.removeCallbacks(hideOverlayTask);
        });
        renderOverlayState(title, detail, null, true, 0, 0);
    }

    /** 确定进度：标题 + "n/N · 百分比" + 当前条目名；250ms 节流。 */
    public synchronized void updateProgress(String title, int done, int total, String name) {
        lastDone = done;
        lastTotal = total;
        long now = System.currentTimeMillis();
        if (now - lastProgressPostMs < 250) return;
        lastProgressPostMs = now;
        renderOverlayState(title, progressDetail(done, total), name, false, done, total);
    }

    /** 收尾：进度顶到 100%，下面一行换成"正在刷新列表…"之类的说明，等前端收遮罩。 */
    public synchronized void finishProgress(String title, String fileLine) {
        boolean indeterminate = lastTotal <= 0;
        renderOverlayState(title, progressDetail(lastDone, lastTotal), fileLine, indeterminate, lastDone, lastTotal);
    }

    /** 收起遮罩。任何失败路径都必须走到这里，否则界面会一直被挡住。 */
    public void hideProgress() {
        hideImportOverlay();
    }

    /** 收尾文案展示一小会儿再收起：用于没有"刷新列表"这一步的操作（导出、路线更新）。 */
    public void hideProgressAfter(long delayMs) {
        runOnUiThread(() -> {
            if (importOverlay == null) return;
            importOverlay.removeCallbacks(hideOverlayTask);
            importOverlay.postDelayed(hideOverlayTask, Math.max(200L, delayMs));
        });
    }

    private static String progressDetail(int done, int total) {
        if (total <= 0) return null;
        int percent = Math.round(done * 100f / total);
        return formatCount(done) + " / " + formatCount(total) + " · " + percent + "%";
    }

    /** 起手态：弹系统选择器 / 刚开始读取时用（不确定进度条）。 */
    private void showImportOverlay(String title) {
        showProgress(title, null);
    }

    /**
     * 导入进度统一出口：只刷新原生遮罩。
     *
     * <p>H5 不再重复弹 loading：导入是原生执行的，两层遮罩叠在一起只会互相盖住。
     * 列表刷新完成后由前端调 {@link #endImportOverlay()} 收起。
     *
     * @param phase "scan" 扫描文件夹；"import" 逐个入库；"done" 收尾刷新列表
     * @param force true 时忽略节流（首条/末条必须立刻发）
     */
    private synchronized void reportImportProgress(String phase, int done, int total, String name, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastProgressPostMs < 250) return;
        lastProgressPostMs = now;
        lastDone = done;
        lastTotal = total;

        final String title;
        final String detail;
        final String fileLine;
        final boolean indeterminate;

        if ("scan".equals(phase)) {
            title = "正在扫描文件夹";
            detail = "已发现 " + formatCount(done) + " 个文件";
            fileLine = null;
            indeterminate = true;
        } else if ("done".equals(phase)) {
            title = "导入完成";
            detail = progressDetail(done, total);
            fileLine = "正在刷新列表…";
            indeterminate = false;
        } else {
            title = "正在导入";
            detail = progressDetail(done, total);
            fileLine = name;
            indeterminate = false;
        }

        renderOverlayState(title, detail, fileLine, indeterminate, done, total);
    }

    /** 按 H5 遮罩的信息层级渲染：标题 / 进度明细 / 当前文件名 / 细进度条。 */
    private void renderOverlayState(String title, String detail, String fileLine,
                                    boolean indeterminate, int done, int total) {
        runOnUiThread(() -> {
            ensureImportOverlay();
            importOverlay.setVisibility(android.view.View.VISIBLE);
            importOverlayTitle.setText(title);
            setVisibleText(importOverlayDetail, detail);
            setVisibleText(importOverlayFile, fileLine);
            importOverlayBar.setVisibility(indeterminate ? android.view.View.GONE : android.view.View.VISIBLE);
            importOverlayBarIndeterminate.setVisibility(indeterminate ? android.view.View.VISIBLE : android.view.View.GONE);
            if (!indeterminate && total > 0) {
                importOverlayBar.setMax(Math.max(1, total));
                importOverlayBar.setProgress(Math.min(Math.max(done, 0), total));
            }
            if (importSpinnerAnimator != null && !importSpinnerAnimator.isStarted()) {
                importSpinnerAnimator.start();
            }
        });
    }

    /** 文本为空时连 View 一起收起来，避免卡片里留一块空白。 */
    private static void setVisibleText(android.widget.TextView view, String text) {
        if (text == null || text.isEmpty()) {
            view.setText("");
            view.setVisibility(android.view.View.GONE);
        } else {
            view.setText(text);
            view.setVisibility(android.view.View.VISIBLE);
        }
    }

    /** 千分位，几千上万条时更容易读。 */
    private static String formatCount(int value) {
        return String.format(java.util.Locale.US, "%,d", value);
    }

    /** 前端刷新完列表后调用：收起导入遮罩。 */
    public void endImportOverlay() {
        hideImportOverlay();
    }

    private final Runnable hideOverlayTask = () -> {
        if (importOverlay != null) importOverlay.setVisibility(android.view.View.GONE);
        if (importSpinnerAnimator != null) importSpinnerAnimator.cancel();
    };

    private void hideImportOverlay() {
        runOnUiThread(() -> {
            if (importOverlay == null) return;
            // 取消兜底定时器，避免它稍后再补一次隐藏
            importOverlay.removeCallbacks(hideOverlayTask);
            hideOverlayTask.run();
        });
    }

    /** 内部存储是否已低于安全余量（低于就停止导入，避免把系统盘写满）。 */
    private boolean isStorageLow() {
        try {
            long usable = getFilesDir().getUsableSpace();
            return usable > 0 && usable < FileImporter.MIN_FREE_BYTES;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 选完目录：后台线程递归导入。 */
    private void runImportDir(android.net.Uri treeUri) {
        if (treeUri == null) {
            finishImport("");
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        showImportOverlay("正在扫描文件夹");
        new Thread(() -> {
            androidx.documentfile.provider.DocumentFile root =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri);
            importTree(root);
        }, "import-dir").start();
    }

    /**
     * 从一棵 DocumentFile 树导入整棵树：
     * ① 一次 DFS 扫完整棵树（只读一次目录）→ ② 一次性建好 dir 记录 → ③ 多 worker 并行入库。
     */
    private void importTree(androidx.documentfile.provider.DocumentFile root) {
        java.util.List<ImportFile> files = new java.util.ArrayList<>();
        try {
            if (root != null) {
                DirNode rootNode = scanTree(root);
                buildDirTree(rootNode, pendingImportDirId, files);
            }
        } catch (Throwable e) {
            // 扫描/建目录失败时清单不可信（目录可能没提交），整批放弃，别让每个文件都去报"文件夹不存在"
            files.clear();
            android.util.Log.e("MainActivity", "扫描/建目录失败", e);
        }
        importFileList(files);
    }

    /** 文件模式：选中的一批文件也走同一条并行导入。 */
    private void runImportFiles(java.util.List<android.net.Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            finishImport("");
            return;
        }
        final java.util.List<android.net.Uri> list = new java.util.ArrayList<>(uris);
        showImportOverlay("准备导入…");
        new Thread(() -> {
            java.util.List<ImportFile> files = new java.util.ArrayList<>(list.size());
            for (android.net.Uri uri : list) {
                ImportFile item = new ImportFile(uri, queryDisplayName(uri));
                item.parentDirId = pendingImportDirId;
                files.add(item);
            }
            importFileList(files);
        }, "import-files").start();
    }

    /**
     * 统一的导入收尾：多 worker 并行跑完后汇总进度/结果，再回调前端。
     */
    private void importFileList(java.util.List<ImportFile> files) {
        final int total = files.size();
        ImportOutcome outcome = new ImportOutcome();
        if (total > 0) {
            runImportWorkers(files, outcome);
        }
        org.json.JSONArray records = new org.json.JSONArray();
        synchronized (outcome.records) {
            for (org.json.JSONObject record : outcome.records) {
                records.put(record);
            }
        }
        reportImportProgress("done", outcome.ok.get(), total, null, true);
        // 有前端在等结果时才需要停在"正在刷新列表…"等它回 ack；没有前端（页面已销毁等极端情况）
        // 就直接收。这里不再做超时兜底：导入/导出视频可能很久，遮罩只由调用方负责收起。
        if (pendingImportResult == null) {
            hideImportOverlay();
        }
        finishImportWithRecords(records, total, outcome.ok.get(), outcome.failed.get(), outcome.storageFull);
    }

    /**
     * 多 worker 并行导入。
     *
     * <p>拷贝是 IO 密集、缩略图是 CPU 密集，可以并行；SQLite 的写由它自己的连接池串行化，
     * 不需要我们再加锁。真正需要互斥的只有「查重命名 + 写 pending_file」那一段，
     * 锁在 {@link FileImporter} 里（NAME_LOCK），并且 pending 行也算占名。
     * id 全部走 {@link IdGenerator#get_id_by_time()}，它自己 synchronized，并发调用也唯一。
     */
    private void runImportWorkers(java.util.List<ImportFile> files, ImportOutcome outcome) {
        final int total = files.size();
        // 2~4 个：拷贝吃 IO、缩略图吃 CPU，再多只会抢内存（大图解码峰值会叠加）
        int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        workers = Math.min(workers, total);
        final java.util.concurrent.atomic.AtomicInteger nextIndex = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(workers);
        final FileImporter importer = new FileImporter(this);
        for (int w = 0; w < workers; w++) {
            new Thread(() -> {
                try {
                    while (!outcome.storageFull) {
                        int index = nextIndex.getAndIncrement();
                        if (index >= total) break;
                        ImportFile item = files.get(index);
                        if (isStorageLow()) {
                            outcome.storageFull = true;
                            break;
                        }
                        try {
                            org.json.JSONObject record = importer.importFromContentUri(
                                    item.uri.toString(), item.parentDirId, item.name);
                            if (record != null) outcome.addRecord(record);
                        } catch (Throwable e) {
                            // 单个文件失败（含 OOM）只跳过它，继续导后面的
                            outcome.failed.incrementAndGet();
                            android.util.Log.e("MainActivity", "导入文件失败: " + item.name, e);
                        } finally {
                            // 按"已完成数"报进度，多线程下也保持单调
                            reportImportProgress("import", finished.incrementAndGet(), total, item.name, false);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }, "import-worker-" + w).start();
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 并行导入的共享结果：worker 只往里追加，收尾时单线程读。 */
    private static final class ImportOutcome {
        final java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.List<org.json.JSONObject> records =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        /** 存储不足时置位，所有 worker 立刻停下 */
        volatile boolean storageFull;

        void addRecord(org.json.JSONObject record) {
            ok.incrementAndGet();
            synchronized (records) {
                if (records.size() < MAX_RESULT_RECORDS) records.add(record);
            }
        }
    }

    /** 扫描结果里的一个文件：Uri、显示名、所属目录 id 都在扫描阶段定好，导入时不再查 SAF。 */
    private static final class ImportFile {
        final android.net.Uri uri;
        final String name;
        /** 建目录记录时回填；文件模式固定是当前文件夹 */
        String parentDirId;

        ImportFile(android.net.Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    /** 目录树节点：扫描阶段只读一次目录，之后全是内存遍历。 */
    private static final class DirNode {
        final androidx.documentfile.provider.DocumentFile dir;
        final String name;
        final java.util.List<DirNode> children = new java.util.ArrayList<>();
        final java.util.List<ImportFile> files = new java.util.ArrayList<>();
        /** 本目录（含子树）里是否有文件：没有就不建 dir 记录，保持"不导入空目录"的旧行为 */
        boolean hasContent;
        /** 建好后的库记录 id（文件挂到它下面） */
        String dbId;
        /** 建之前先记好父目录 id，自顶向下逐个落库 */
        String parentDbId;

        DirNode(androidx.documentfile.provider.DocumentFile dir, String name) {
            this.dir = dir;
            this.name = name;
        }
    }

    /**
     * 一次 DFS 把整棵树读进内存：每个目录只调一次 {@code listFiles()}，
     * 顺带按已发现文件数报扫描进度（16G 目录扫描本身就要很久）。
     */
    private DirNode scanTree(androidx.documentfile.provider.DocumentFile root) {
        DirNode rootNode = new DirNode(root, dirNameOf(root));
        java.util.ArrayDeque<DirNode> stack = new java.util.ArrayDeque<>();
        stack.push(rootNode);
        int scanned = 0;
        while (!stack.isEmpty()) {
            DirNode node = stack.pop();
            androidx.documentfile.provider.DocumentFile[] children = node.dir.listFiles();
            if (children == null) continue;
            for (androidx.documentfile.provider.DocumentFile child : children) {
                String name = child.getName();
                if (name == null || name.startsWith(".")) continue;
                if (child.isDirectory()) {
                    DirNode sub = new DirNode(child, name);
                    node.children.add(sub);
                    stack.push(sub);              // 入栈继续展开
                } else {
                    node.files.add(new ImportFile(child.getUri(), name));
                    if (++scanned % 100 == 0) {
                        reportImportProgress("scan", scanned, 0, null, false);
                    }
                }
            }
        }
        reportImportProgress("scan", scanned, 0, null, true);
        return rootNode;
    }

    /**
     * 用扫描结果一次性建好 dir 记录，并产出「文件 → 目录 id」清单。
     *
     * <p>旧实现是每个文件都从自己往上走一遍父链（每层一次 SAF 查询），几千张图就是几万次查询；
     * 这里改成：
     * <pre>
     *   ① 先序收集节点，倒序即为后序 → 标出"子树里真的有文件"的目录（空目录/空子树不建）
     *   ② 自顶向下按父 id 建记录，全部 INSERT 放在一个事务里（几千个目录也只有一次 fsync）
     *   ③ 顺手把每个文件的 parentDirId 填好
     * </pre>
     */
    private void buildDirTree(DirNode rootNode, String rootParentId, java.util.List<ImportFile> out) {
        // ① 先序列表（父一定在子之前），倒序遍历即为后序
        java.util.List<DirNode> preOrder = new java.util.ArrayList<>();
        java.util.ArrayDeque<DirNode> stack = new java.util.ArrayDeque<>();
        stack.push(rootNode);
        while (!stack.isEmpty()) {
            DirNode node = stack.pop();
            preOrder.add(node);
            for (DirNode child : node.children) stack.push(child);
        }
        for (int i = preOrder.size() - 1; i >= 0; i--) {
            DirNode node = preOrder.get(i);
            boolean has = !node.files.isEmpty();
            if (!has) {
                for (DirNode child : node.children) {
                    if (child.hasContent) {
                        has = true;
                        break;
                    }
                }
            }
            node.hasContent = has;
        }
        if (!rootNode.hasContent) return;

        // ② 自顶向下建记录 + 收文件清单，一个事务搞定
        android.database.sqlite.SQLiteDatabase db = DatabaseHelper.getInstance(this).getWritableDatabase();
        db.beginTransaction();
        try {
            rootNode.parentDbId = rootParentId;    // 树的根在库里是"当前文件夹"的子目录
            java.util.ArrayDeque<DirNode> queue = new java.util.ArrayDeque<>();
            queue.push(rootNode);
            while (!queue.isEmpty()) {
                DirNode node = queue.pop();
                node.dbId = IdGenerator.get_id_by_time();
                db.execSQL("INSERT INTO dir (id, name, parent_dir_id, type, createtime, filesortmode, dirsortmode, seqnumber) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        new Object[]{node.dbId, node.name, node.parentDbId, "dir",
                                System.currentTimeMillis(), 2, 2, 0L});
                for (ImportFile file : node.files) {
                    file.parentDirId = node.dbId;
                    out.add(file);
                }
                for (DirNode child : node.children) {
                    if (!child.hasContent) continue;
                    child.parentDbId = node.dbId;
                    queue.push(child);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 目录显示名：拿不到就给个兜底名。 */
    private static String dirNameOf(androidx.documentfile.provider.DocumentFile dir) {
        String name = dir.getName();
        return (name == null || name.isEmpty()) ? "文件夹" : name;
    }

    /** 取文件显示名（拿不到就用 uri 末段）。 */
    private String queryDisplayName(android.net.Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "imported" : last;
    }

    private void finishImportWithRecords(org.json.JSONArray records, int total, int ok, int failed,
                                         boolean storageFull) {
        try {
            org.json.JSONObject payload = new org.json.JSONObject();
            // records 只带回前 MAX_RESULT_RECORDS 条（前端用不上全量，刷新列表走 updateTable）
            payload.put("records", records == null ? new org.json.JSONArray() : records);
            payload.put("count", ok);
            payload.put("total", total);
            payload.put("failed", failed);
            if (storageFull) payload.put("storageFull", true);
            finishImport(payload.toString());
        } catch (Exception e) {
            finishImport("");
        }
    }

    private void finishImport(String payloadJson) {
        ImportResult result = pendingImportResult;
        pendingImportResult = null;
        if (result == null) return;
        try {
            if (payloadJson == null || payloadJson.isEmpty()) {
                org.json.JSONObject canceled = new org.json.JSONObject();
                canceled.put("canceled", true);
                result.onResult(canceled);
            } else {
                result.onResult(new org.json.JSONObject(payloadJson));
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "回调导入结果失败", e);
        }
    }
    // endregion

    // 后端和数据库
    private BackServer backServer;
    private DatabaseHelper dbHelper;
    private RoadDatabaseHelper roadDatabaseHelper;
    private RoadApiHelper roadApiHelper;
    // 启动修复：是否已触发过（每次启动只跑一次）

    // --- 现代化的 ActivityResult Launcher ---

    // 用于在应用启动时请求多个权限的 Launcher。
	private final ActivityResultLauncher<String[]> requestPermissionsLauncher =
			registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
				// 收集未授予的权限
				List<String> deniedPermissions = new ArrayList<>();
				
				for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
					if (!entry.getValue()) {
						deniedPermissions.add(entry.getKey());
					}
				}
				
				if (!deniedPermissions.isEmpty()) {
					// 将权限名称转换为更友好的描述
					StringBuilder message = new StringBuilder("需要以下权限以保证应用正常运行：\n\n");
					
					for (String permission : deniedPermissions) {
						String friendlyName = getPermissionFriendlyName(permission);
						message.append("• ").append(friendlyName).append("\n");
					}
					
					message.append("\n请在设置中授予这些权限。");
					
					// 显示详细的权限提示对话框
					new AlertDialog.Builder(this)
							.setTitle("权限请求")
							.setMessage(message.toString())
							.setPositiveButton("去设置", (dialog, which) -> {
								// 打开应用设置页面
								Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
								Uri uri = Uri.fromParts("package", getPackageName(), null);
								intent.setData(uri);
								startActivity(intent);
							})
							.setNegativeButton("取消", null)
							.show();
				}
			});

	/**
	 * 将系统权限名称转换为用户友好的描述
	 */
	private String getPermissionFriendlyName(String permission) {
		switch (permission) {
			case Manifest.permission.CAMERA:
				return "相机权限 - 用于拍照和录像";
			// [移除] case Manifest.permission.WRITE_EXTERNAL_STORAGE:
			// 	return "存储权限 - 用于保存照片和文件";
			// [移除] case Manifest.permission.READ_EXTERNAL_STORAGE:
			// 	return "读取存储权限 - 用于访问照片和文件";
			case Manifest.permission.ACCESS_FINE_LOCATION:
				return "精确位置权限 - 用于地理标记";
			case Manifest.permission.ACCESS_COARSE_LOCATION:
				return "大致位置权限 - 用于地理标记";
			case Manifest.permission.RECORD_AUDIO:
				return "麦克风权限 - 用于录制音频";
			case Manifest.permission.READ_PHONE_STATE:
				return "电话状态权限 - 用于设备识别";
			// 添加其他您的应用需要的权限
			default:
				// 如果没有匹配的，返回权限的最后一部分
				String[] parts = permission.split("\\.");
				return parts[parts.length - 1].replace("_", " ");
		}
	}

    // 用于处理 WebView 文件/相机选择器结果的 Launcher。
    private final ActivityResultLauncher<Intent> webViewFileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::handleWebViewFileResult);

    // 用于处理 WebView 文件夹选择器结果的 Launcher。
    private final ActivityResultLauncher<Intent> webViewFolderPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::handleWebViewFolderResult);


    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 应用启动时，异步清理上一次可能失败的媒体库幽灵记录
        cleanupPendingMediaStoreRecords();
        // App 初始化序列
        checkAndRequestPermissions();
        clearCacheOnStartup(); // [新增] 启动时清理私有缓存
        initLocationServices();
        initWebView();
        initDatabaseAndBackend();
        // 首次启动/偏好缺失时，把当前选中的默认配置（含水印配置、服务器地址/密钥、高德密钥）
        // 写入 SharedPreferences；后续设置页、秘钥页、相机水印都直接读偏好设置。
        AppConfig.ensureDefaults(this);
        setupWebViewClients();

        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState);
        }

        // 待入库录像目录：先收尾录像（探测可播、生成首帧、补提交），再修照片，
        // 避免照片修复器把录像的 .part 当成图片搬进 app_data。
        try {
            int recovered = new FileImporter(this).scanPendingVideoDirs();
            if (recovered > 0) android.util.Log.i(TAG, "启动恢复待入库录像: " + recovered + " 条");
        } catch (Exception e) {
            android.util.Log.e(TAG, "扫描待入库录像失败", e);
        }
        // 启动修复未完成的入库：主线程同步执行，且必须在加载页面前完成，
        // 这样首屏列表就已经包含"上次被打断、这次被补提交"的那张照片（全程无任何提示，用户只会看到多了一张图）
        repairPendingImports();
        // 修复之后再清导入中转目录（顺序不能反：修复要靠 pending 行里的 tmp_photo_path 补主图）
        cleanImportTempDir();

        webView.loadUrl(indexurl);
    }

    /**
     * 启动修复未完成的入库（拍照/导入时进程被杀留下的 pending_file 记录）。
     * 全程主线程同步完成、不弹任何 Toast/对话框；正常情况下没有待修复记录，开销只是一条空查询。
     */
    private void repairPendingImports() {
        try {
            int repaired = new FileImporter(this).repairPendingImports();
            if (repaired > 0) {
                Log.i(TAG, "启动修复完成，处理了 " + repaired + " 条未完成的入库记录");
            }
        } catch (Throwable e) {
            Log.e(TAG, "启动修复未完成的入库记录失败", e);
        }
    }

    /** 清掉导入中转目录里没人认领的 .tmp（被杀在拷贝阶段留下的孤儿文件）。 */
    private void cleanImportTempDir() {
        try {
            int removed = new FileImporter(this).cleanImportTempDir();
            if (removed > 0) {
                Log.i(TAG, "启动清理导入临时文件: " + removed + " 个");
            }
        } catch (Throwable e) {
            Log.e(TAG, "清理导入临时文件失败", e);
        }
    }

    /**
     * 初始化定位服务以获取 GPS 数据。
     */
    private void initLocationServices() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
                    gpsLocationTime = location.getTime();
                } else if (gpsLocationTime != 0
                        && System.currentTimeMillis() - gpsLocationTime < GPS_PRIORITY_TIMEOUT_MS) {
                    // GPS 定位仍然有效，优先使用 GPS，忽略网络定位结果
                    return;
                }
                lat = location.getLatitude();
                lon = location.getLongitude();
                alt = location.getAltitude();
                locationTime = location.getTime();
                if (backServer != null) {
                    backServer.updateGpsLocation(lat, lon);
                }
            }
        };
    }

    /**
     * 配置 WebView、其设置以及 JS 接口。
     */
    private void initWebView() {
        webView = findViewById(R.id.webview);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        // 固定 WebView 字体缩放为 100%，避免老年模式（系统字体放大）打乱 H5 界面布局
        webSettings.setTextZoom(100);

        jsBridge = new Android_js_bridge(this, webView);
        filePickerBridge = new FilePickerBridge(this, webView);

        String desktopUserAgent = "Mozilla/5.0 (Linux; Android 10; Pixel 4 XL Build/QD1A.190805.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36";
        webSettings.setUserAgentString(desktopUserAgent);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
    }

    /**
     * 设置数据库助手和后端服务的 JS 接口。
     */
    private void initDatabaseAndBackend() {
        dbHelper = DatabaseHelper.getInstance(this);
        roadDatabaseHelper = new RoadDatabaseHelper(this) ;
        backServer = new BackServer(webView, this);
        roadApiHelper = new RoadApiHelper(this, webView);
        webView.addJavascriptInterface(jsBridge, "Android");
        webView.addJavascriptInterface(filePickerBridge, "FilePicker");
        webView.addJavascriptInterface(backServer, "backServer");
        webView.addJavascriptInterface(roadApiHelper, "roadApi");
        initializeDatabaseData();
    }

    /**
     * 检查数据库中是否存在根目录，如果不存在则创建它。
     */
    private void initializeDatabaseData() {
        new Thread(() -> {
            try {
                JSONArray arr = new JSONArray();
                arr.put("root");
                JSONObject rootDir = dbHelper.db_get("SELECT * FROM dir WHERE id = ?", arr);
                if (rootDir == null) {
                    createRootDirectory();
                }
            } catch (Exception e) {
                Log.e("MainActivity", "数据库初始化失败: " + e.getMessage(), e);
            }
        }).start();
    }

    /**
     * 在数据库中创建初始的“根”目录和“默认”目录。
     */
    private void createRootDirectory() {
        try {
            JSONArray rootArr = new JSONArray();
            rootArr.put("root").put("根目录").put(JSONObject.NULL).put("dir").put("{}").put("{}").put(2).put(2).put(System.currentTimeMillis()).put(0);
            dbHelper.db_run("INSERT OR IGNORE INTO dir (id, name, parent_dir_id, type, file_obj, dir_obj, dirsortmode, filesortmode, createtime, seqnumber) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rootArr);

            String defaultId = IdGenerator.get_id_by_time();
            JSONArray defaultArr = new JSONArray();
            defaultArr.put(defaultId).put("默认文件夹").put("root").put("dir").put("{}").put("{}").put(2).put(2).put(System.currentTimeMillis()).put(1);
            dbHelper.db_run("INSERT OR IGNORE INTO dir (id, name, parent_dir_id, type, file_obj, dir_obj, dirsortmode, filesortmode, createtime, seqnumber) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", defaultArr);

            Log.i("MainActivity", "数据库初始化完成");
        } catch (Exception e) {
            Log.e("MainActivity", "创建根目录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 为 WebView 设置 WebViewClient 和 WebChromeClient。
     */
    private void setupWebViewClients() {
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .setHttpAllowed(true)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/icons/", new WebViewAssetLoader.InternalStoragePathHandler(this, new File(getFilesDir(), "icons")))
                .addPathHandler("/app_data/", new WebViewAssetLoader.InternalStoragePathHandler(this, new File(getFilesDir(), "app_data")))
				.addPathHandler("/watermark_icon/", new WebViewAssetLoader.InternalStoragePathHandler(this, new File(getFilesDir(), "watermark/icon")))
                .build();
        webView.setWebViewClient(new WebViewClient() {
            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith(open_in_app_url_head)) {
                    // 在 WebView 内部加载本地文件 URL
                    return false;
                }
                openExternalUrl(url);
                return true;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                webView.evaluateJavascript("if(typeof onAndroidReady === 'function') { onAndroidReady(); }", null);
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (!url.startsWith("data:")) {
                String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
                startDownload(url, filename);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = view;
                fullscreenCallback = callback;
                FrameLayout decor = (FrameLayout) getWindow().getDecorView();
                fullscreenContainer = new FrameLayout(MainActivity.this);
                fullscreenContainer.setBackgroundColor(Color.BLACK);
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                decor.addView(fullscreenContainer, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                // 全屏播放时临时跟随重力旋转，横屏视频可以正常横屏播放
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                // 取消任何之前的（可能未处理的）文件选择请求
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                // 相机权限在应用启动时请求。如果此时权限仍未被授予，
                // 我们将优雅地取消文件选择操作，而不是再次请求权限。
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    showToast("需要相机权限才能拍照");
                    filePathCallback.onReceiveValue(null);
                    return true; // 表明我们已经处理了这次请求
                }

                String[] acceptTypes = fileChooserParams.getAcceptTypes();
                boolean isMultiple = (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);

                if(getchoosemode(acceptTypes, ".for_webview_camera")){
                    openCamera();
                } else if (getchoosemode(acceptTypes, ".for_webview_dir")) {
                    Intent contentSelectionIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    webViewFolderPickerLauncher.launch(contentSelectionIntent);
                } else {
                    openFileChooser(isMultiple, fileChooserParams.getAcceptTypes());
                }
                return true;
            }
        });
    }

    /**
     * 在配置发生变化（如屏幕旋转）后恢复实例状态。
     */
    private void restoreInstanceState(Bundle savedInstanceState) {
        cameraPhotoPath = savedInstanceState.getString("cameraPhotoPath");
        if (filePickerBridge != null) {
            filePickerBridge.onRestoreInstanceState(savedInstanceState);
        }
        if (jsBridge != null) {
            jsBridge.onRestoreInstanceState(savedInstanceState);
        }
    }

    /** 退出 WebView 自定义全屏；返回键和 onHideCustomView 共用。 */
    private void exitFullscreen() {
        if (fullscreenContainer != null) {
            ViewGroup parent = (ViewGroup) fullscreenContainer.getParent();
            if (parent != null) parent.removeView(fullscreenContainer);
            fullscreenContainer = null;
        }
        fullscreenView = null;
        if (fullscreenCallback != null) {
            fullscreenCallback.onCustomViewHidden();
            fullscreenCallback = null;
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        // 退出全屏后恢复 Web 层固定竖屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 全屏播放时，返回键只退出全屏，不再触发 JS 的页面退出逻辑
            if (fullscreenView != null) {
                exitFullscreen();
                return true;
            }
            // 拦截返回按钮事件并将其转发给 JavaScript 处理
            webView.evaluateJavascript("window.phoneback()", null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
        }
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        // DatabaseHelper 是进程级单例，可能仍被相机页/Web 桥使用；
        // 不在单个 Activity 销毁时 close，交给进程生命周期回收。
    }

    /**
     * 使用现代化的 Launcher API 检查并请求必要的运行时权限。
     */
    private void checkAndRequestPermissions() {
        String[] REQUIRED_PERMISSIONS = {
                // [移除] Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };
        requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS);
    }

    /**
     * 辅助方法，用于检查文件选择器的 acceptTypes 数组中是否存在特定字符串。
     */
    private boolean getchoosemode(String[] acceptTypes, String targetType) {
        if (acceptTypes != null && targetType != null) {
            for (String type : acceptTypes) {
                if (targetType.equals(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 打开一个文件选择器，其中包含拍摄新照片的选项。
     */
    private void openFileChooser(boolean isMultiple, String[] acceptTypes) {
        // --- 1. 创建拍照意图 (不变) ---
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        File photoFile = null;
        try {
            photoFile = createImageFile();
        } catch (IOException ex) {
            showToast("无法创建图片文件");
        }
        if (photoFile != null) {
            cameraPhotoPath = photoFile.getAbsolutePath();
            Uri photoURI = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
        } else {
            takePictureIntent = null;
        }

        // --- 2. 创建文件选择意图 (核心修改部分) ---
        Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
        contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
        contentSelectionIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, isMultiple);

        // --- 2.1 智能处理 acceptTypes ---
        HashSet<String> mimeTypesSet = new HashSet<>();
        if (acceptTypes != null && acceptTypes.length > 0 && !(acceptTypes.length == 1 && (acceptTypes[0] == null || acceptTypes[0].trim().isEmpty()))) {
            for (String type : acceptTypes) {
                if (type == null || type.trim().isEmpty()) continue;

                if (type.startsWith(".")) { // 处理后缀名
                    String extension = type.substring(1).toLowerCase();
                    String mimeFromExtension = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                    if (mimeFromExtension != null) {
                        mimeTypesSet.add(mimeFromExtension);
                    }
                    // 对已知特殊后缀做兼容处理
                    if ("geojson".equalsIgnoreCase(extension)) {
                        mimeTypesSet.add("application/geo+json");
                        mimeTypesSet.add("application/octet-stream");
                    } else if (mimeFromExtension == null) { // 对其他未知后缀
                        mimeTypesSet.add("application/octet-stream");
                    }
                } else { // 处理MIME类型
                    mimeTypesSet.add(type);
                }
            }
        }
        
        // ★★★ 关键的兼容性优化 ★★★
        String[] finalMimeTypes = mimeTypesSet.toArray(new String[0]);

        if (finalMimeTypes.length == 1) {
            // 如果只有一种MIME类型，直接使用 setType，这是最直接、最兼容的方式。
            contentSelectionIntent.setType(finalMimeTypes[0]);
        } else if (finalMimeTypes.length > 1) {
            // 如果有多种MIME类型，才使用 setType("*/*") + EXTRA_MIME_TYPES 的组合。
            contentSelectionIntent.setType("*/*");
            contentSelectionIntent.putExtra(Intent.EXTRA_MIME_TYPES, finalMimeTypes);
        } else {
            // 如果没有指定任何类型，或者处理后为空，则允许所有文件。
            contentSelectionIntent.setType("*/*");
        }
        
        // 打印日志用于调试，确认最终传递给Intent的MIME类型是什么
        Log.d("FileChooser", " MIME Types: " + java.util.Arrays.toString(finalMimeTypes));
        if(finalMimeTypes.length > 1){
            Log.d("FileChooser", "Using EXTRA_MIME_TYPES method.");
        } else if (finalMimeTypes.length == 1) {
            Log.d("FileChooser", "Using setType() method with: " + finalMimeTypes[0]);
        } else {
            Log.d("FileChooser", "No MIME types specified, defaulting to */*.");
        }

        // --- 3. 创建并启动最终的选择器 (不变) ---
        Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
        chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
        chooserIntent.putExtra(Intent.EXTRA_TITLE, "选择文件");
        if (takePictureIntent != null) {
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{takePictureIntent});
        }
        webViewFileChooserLauncher.launch(chooserIntent);
    }
    /**
     * 直接打开相机来拍照。
     */
    private void openCamera() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        File photoFile;
        try {
            photoFile = createImageFile();
        } catch (IOException ex) {
            showToast("无法创建图片文件");
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
            }
            return;
        }
        cameraPhotoPath = photoFile.getAbsolutePath();
        Uri photoURI = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
        // 使用与文件选择器相同的 Launcher
        webViewFileChooserLauncher.launch(takePictureIntent);
    }

    /**
     * 在应用的外部图片目录中创建一个临时的图片文件。
     */
    private File createImageFile() throws IOException {
        String uniqueId = IdGenerator.get_id_by_time();
        String imageFileName = uniqueId + ".jpg";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return new File(storageDir, imageFileName);
    }

    /**
     * 打开外部链接：交给系统处理，不做 scheme 白名单。
     * 私人版可能需要自定义 scheme 或从网络地址取数据。
     */
    private void openExternalUrl(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            showToast("未找到可打开该链接的应用");
        } catch (Exception e) {
            Log.w(TAG, "打开外部链接失败", e);
        }
    }

    /**
     * 使用 Android 的 DownloadManager 启动文件下载。
     */
    private void startDownload(String url, String fileName) {
        try {
            Uri uri = Uri.parse(url);
            android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(uri);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) {
                request.addRequestHeader("Cookie", cookies);
            }
            String safeFileName = SecurityUtils.safePathComponent(fileName);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeFileName);
            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            android.app.DownloadManager downloadManager = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            downloadManager.enqueue(request);
            showToast("下载已开始");
        } catch (Exception e) {
            showToast("下载出现错误");
        }
    }

    /**
     * 用于处理 WebView 文件/相机选择器结果的回调方法。
     */
    private void handleWebViewFileResult(ActivityResult result) {
        if (filePathCallback == null) return;

        Uri[] results = null;
        if (result.getResultCode() == Activity.RESULT_OK) {
            Intent data = result.getData();
            // 情况1：来自文件选择器的结果（单个或多个文件）
            if (data != null && (data.getData() != null || data.getClipData() != null)) {
                if (data.getClipData() != null) {
                    int itemCount = data.getClipData().getItemCount();
                    results = new Uri[itemCount];
                    for (int i = 0; i < itemCount; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            // 情况2：来自相机的结果
            else if (cameraPhotoPath != null) {
                writeLocationToExif(cameraPhotoPath);
                results = new Uri[]{getCameraPhotoUri()};
            }
        }

        filePathCallback.onReceiveValue(results);
        // 清理状态变量
        filePathCallback = null;
        cameraPhotoPath = null;
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    /**
     * 用于处理 WebView 文件夹选择器结果的回调方法。
     */
    private void handleWebViewFolderResult(ActivityResult result) {
        if (filePathCallback == null) return;

        Uri[] results = null;
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && result.getData().getData() != null) {
            try {
                Uri folderUri = result.getData().getData();
                results = jsBridge.getUrisFromFolder(folderUri);
                DocumentFile pickedFolder = DocumentFile.fromTreeUri(this, folderUri);
                if (pickedFolder != null) {
                    List<String> relativePaths = new ArrayList<>();
                    String rootDocumentId = DocumentsContract.getTreeDocumentId(pickedFolder.getUri());
                    String dir_name = pickedFolder.getName();

                    for (Uri fileUri : results) {
                        String fileDocumentId = DocumentsContract.getDocumentId(fileUri);
                        String relativePath = "";
                         if (fileDocumentId.startsWith(rootDocumentId + ":")) {
                            relativePath = fileDocumentId.substring((rootDocumentId + ":").length());
                        } else if (fileDocumentId.startsWith(rootDocumentId)) {
                             relativePath = fileDocumentId.substring(rootDocumentId.length());
                             if (relativePath.startsWith("/")) {
                                 relativePath = relativePath.substring(1);
                             }
                         }
                        relativePaths.add(dir_name + "/" + relativePath);
                    }
                    JSONArray jsonArray = new JSONArray(relativePaths);
                    String tojs = "window.getphonepath(" + jsonArray.toString() + ")";
                    webView.evaluateJavascript(tojs, null);
                }
            } catch (Exception e) {
                webView.evaluateJavascript("window.getFolderFilesError('文件路径获取错误：'" + e.getMessage() + ")", null);
            }
        } else {
            // 用户取消了文件夹选择
            webView.evaluateJavascript("window.getphonepath('')", null);
        }

        filePathCallback.onReceiveValue(results);
        // 清理状态变量
        filePathCallback = null;
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (cameraPhotoPath != null) {
            outState.putString("cameraPhotoPath", cameraPhotoPath);
        }
        if (filePickerBridge != null) {
            filePickerBridge.onSaveInstanceState(outState);
        }
        if (jsBridge != null) {
            jsBridge.onSaveInstanceState(outState);
        }
    }

    private Uri getCameraPhotoUri() {
        File file = new File(cameraPhotoPath);
        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
    }

    /**
     * 将最后一次获取到的 GPS 坐标写入指定照片的 EXIF 数据中。
     */
    private void writeLocationToExif(String photoPath) {
        if (locationTime == 0 || (System.currentTimeMillis() - locationTime) > 20000) {
            showToast("GPS数据无效或已过期");
            return;
        }
        try {
            ExifInterface exif = new ExifInterface(photoPath);
            exif.setGpsInfo(new Location("gps"));
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, convertToDMS(lat));
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, lat >= 0 ? "N" : "S");
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, convertToDMS(lon));
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, lon >= 0 ? "E" : "W");
            if (alt != 0) {
                 exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, String.valueOf(Math.abs(alt)));
                 exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, alt < 0 ? "1" : "0");
            }
            exif.saveAttributes();
        } catch (IOException e) {
            showToast("EXIF写入失败: " + e.getMessage());
        }
    }

    /**
     * 将十进制坐标转换为 EXIF 所需的度/分/秒（DMS）格式。
     */
    private String convertToDMS(double coordinate) {
        coordinate = Math.abs(coordinate);
        int degrees = (int) coordinate;
        coordinate = (coordinate - degrees) * 60;
        int minutes = (int) coordinate;
        coordinate = (coordinate - minutes) * 60;
        double seconds = coordinate;
        return degrees + "/1," + minutes + "/1," + (int) (seconds * 1000) + "/1000";
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        CookieManager.getInstance().flush();
        startLocationUpdates();
    }

    private void startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (locationManager == null) return;
        try {
            // 部分设备（如无 GMS 的安卓系统）不存在 network 定位提供者，需先检测再使用，否则会崩溃
            boolean hasGpsProvider = locationManager.getAllProviders().contains(LocationManager.GPS_PROVIDER);
            boolean hasNetworkProvider = locationManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER);

            Location lastKnownGps = hasGpsProvider ? locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) : null;
            Location lastKnownNetwork = hasNetworkProvider ? locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) : null;
            Location bestLastKnown = null;
            if (lastKnownGps != null && lastKnownNetwork != null) {
                bestLastKnown = lastKnownGps.getTime() > lastKnownNetwork.getTime() ? lastKnownGps : lastKnownNetwork;
            } else {
                bestLastKnown = (lastKnownGps != null) ? lastKnownGps : lastKnownNetwork;
            }
            if (bestLastKnown != null) {
                lat = bestLastKnown.getLatitude();
                lon = bestLastKnown.getLongitude();
                alt = bestLastKnown.getAltitude();
                locationTime = bestLastKnown.getTime();
                if (LocationManager.GPS_PROVIDER.equals(bestLastKnown.getProvider())) {
                    gpsLocationTime = bestLastKnown.getTime();
                }
                if (backServer != null) {
                    backServer.updateGpsLocation(lat, lon);
                }
            }
            if (hasGpsProvider) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0.0f, locationListener);
            }
            if (hasNetworkProvider) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0.0f, locationListener);
            }
        } catch (IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "定位服务启动失败（设备可能不支持 GPS 或网络定位）", e);
        }
    }

    // 公共的 getter 方法，以便其他类可以访问这些助手对象
    public DatabaseHelper getDatabaseHelper() { return dbHelper; }
    public BackServer getBackServer() { return backServer; }
    
    // --- [新增] 清理缓存相关方法 ---

    /**
     * [新增] 应用启动时在后台静默清理缓存目录。
     * 此方法不显示 Toast，也不回调 JS，只记录日志。
     */
    private void clearCacheOnStartup() {
        new Thread(() -> {
            try {
                File cacheDir = new File(getExternalFilesDir(null), "file_camera/cache");
                if (!cacheDir.exists() || !cacheDir.isDirectory()) {
                    Log.i("CacheClear", "Cache directory does not exist, no need to clear.");
                    return;
                }
                
                if (deleteDirectoriesRecursive(cacheDir)) {
                    Log.i("CacheClear", "Cache directory successfully cleared on startup.");
                } else {
                    Log.e("CacheClear", "Failed to clear cache directory on startup.");
                }
            } catch (Exception e) {
                Log.e("CacheClear", "Exception while clearing cache on startup.", e);
            }
        }).start();
    }

    /**
     * [新增] 递归删除目录的辅助方法。
     */
    private boolean deleteDirectoriesRecursive(File directory) {
        if (directory == null || !directory.exists()) {
            return true;
        }
        if (directory.isDirectory()) {
            File[] children = directory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectoriesRecursive(child);
                }
            }
        }
        return directory.delete();
    }
    private void cleanupPendingMediaStoreRecords() {
        // 必须在后台线程执行 I/O 操作
        new Thread(() -> {
            ContentResolver resolver = getContentResolver();
            Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String where = MediaStore.Images.Media.IS_PENDING + " = 1 AND " +
                           MediaStore.Images.Media.OWNER_PACKAGE_NAME + " = ?";
            
            String[] selectionArgs = new String[] { getPackageName() };
            // --------------------

            try {
                int deletedRows = resolver.delete(collection, where, selectionArgs);
                
                if (deletedRows > 0) {
                    Log.d(TAG, "MediaStore Cleanup: Successfully deleted " + deletedRows + " of *our own* pending ghost records.");
                } else {
                    Log.d(TAG, "MediaStore Cleanup: No pending records found for this app.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to cleanup pending media records", e);
            }
        }).start();
    }	
}
