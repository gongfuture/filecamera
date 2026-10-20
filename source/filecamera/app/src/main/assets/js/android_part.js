let time_arr = [0,0]
let last_haptic_at = 0;

/**
 * 统一的轻震动反馈。原生桥不可用时退化为 navigator.vibrate。
 * 40ms 内合并重复触发，避免“按钮自身 + 全局点击监听”震两次。
 */
function haptic(duration_ms = 12) {
    const now = Date.now();
    if (now - last_haptic_at < 40) return;
    last_haptic_at = now;
    try {
        if (window.Android && typeof window.Android.vibrate === 'function') {
            window.Android.vibrate(duration_ms);
        } else if (navigator.vibrate) {
            navigator.vibrate(duration_ms);
        }
    } catch (e) {
        // 没有马达/系统禁用震动时静默忽略
    }
}
window.haptic = haptic;

// 所有按钮、带 onclick 的控件、文件行、菜单项、复选框等统一给轻震动。
// 具体业务函数里还有显式 haptic() 的调用，靠 40ms 合并去重。
document.addEventListener('click', function (event) {
    const target = event.target.closest(
        'button, [onclick], .back_button, .switch, label, input, ' +
        '.icon_div, .td1, .tabletr, .menu_item, .menu_div, .child_page_title_span, #menuspan, #menu, #menu2'
    );
    if (target && !target.disabled) haptic();
}, true);

function get_id_by_time() {
    let timepart = Date.now();
    let seq = 0;
    if (timepart !== time_arr[0]) {
        time_arr[0] = timepart;
        time_arr[1] = 0;  // 重置序号
        seq = 0;
    } else {
        seq = time_arr[1] + 1;
        time_arr[1] = seq; // 更新序号
    }
    let id = String(timepart) + '-' + String(seq);
    return id;
}

// Promise管理器
const promiseManager = new Map();
// 统一的Android调用函数
function callAndroidMethod(apiInterface, methodName, data) {
    return new Promise((resolve, reject) => {
        const taskId = get_id_by_time();
        promiseManager.set(taskId, { resolve, reject });
        
        // 直接使用传入的接口对象，不关心它是不是在 window 上
        apiInterface[methodName](taskId, JSON.stringify(data));
    });
}

window.handlePromiseCallback = function(params) {
    const { id, success, data, errorMessage } = params;
    const promiseInfo = promiseManager.get(id);
    if (promiseInfo) {
        promiseManager.delete(id);
        if (success) {
            promiseInfo.resolve(data);
        } else if(data){
			promiseInfo.reject(data);

        }else{
			promiseInfo.reject(errorMessage || "操作失败");
		}
    }
};

async function fix_picture(blobUrl) {
    // 创建一个图像对象并设置源为 Blob URL
    const image = new Image();
    image.src = blobUrl;
    image.crossOrigin = "anonymous"; // 处理跨域问题，如果有的话
    // 等待图像加载完成
    await new Promise((resolve, reject) => {
        image.onload = resolve;
        image.onerror = reject;
    });
    // 创建 Canvas 并设置尺寸
    const canvas = document.createElement('canvas');
    const context = canvas.getContext('2d');
    canvas.width = image.width;
    canvas.height = image.height;
    // 在 Canvas 上绘制图像
    context.drawImage(image, 0, 0, image.width, image.height);
    // 将 Canvas 内容导出为 Blob，然后生成新的 Blob URL
    return new Promise((resolve) => {
        canvas.toBlob((blob) => {
            const newBlobUrl = URL.createObjectURL(blob);
            resolve(newBlobUrl);
        }, 'image/png');
    });
}

async function Android_download(url, fileName, parentpath, datatype,toast = "") {

    return new Promise((resolve, reject) => {
        const id = get_id_by_time();
        promiseManager.set(id, { resolve, reject });
        
        try {
            window.Android.downloadFile(url, fileName, parentpath, datatype, toast,id);
        } catch (error) {
            promiseManager.delete(id);
            reject("调用下载接口失败: " + error.message);
        }
    });
}

function clearCacheDirectory() {
    return new Promise((resolve, reject) => {
        const id = get_id_by_time();
        promiseManager.set(id, { resolve, reject });
        
        try {
            Android.clearCacheDirectory(id);
        } catch (error) {
            promiseManager.delete(id);
            reject("调用清空缓存接口失败: " + error.message);
        }
    });
}

function get_dir_path_arr() {
    return new Promise((resolve, reject) => {
        window.getphonepath = function (pathArray) {
            if(pathArray && pathArray.length > 0){
                resolve(pathArray); // 直接返回数组
            }else{
                resolve([]); // 返回空数组
            }
        };
    });
}

function zipwork() {
    return new Promise((resolve, reject) => {
        const id = get_id_by_time();
        promiseManager.set(id, { resolve, reject });
        
        try {
            Android.startOutWithZip(id);
        } catch (error) {
            promiseManager.delete(id);
            reject("调用压缩接口失败: " + error.message);
        }
    });
}
