
function nativeOpenFilePicker(multiple = false, type = 'file', accept = '*/*') {
    // 准备发送给原生端的数据包
    const params = {
        multiple: multiple,
        type: type,
        acceptTypes: accept.split(',').map(t => t.trim()).filter(Boolean)
    };
    return callAndroidMethod(window.FilePicker, 'open', params);
}
function nativeShareFile(files, multiple = false, targetApp = 'all') {
    // 准备数据包
    const params = {
        files: files,
        multiple: multiple,
        targetApp: targetApp
    };
    return callAndroidMethod(window.FilePicker, 'shareFile', params);
}

function nativeReadConfig() {
    return callAndroidMethod(window.FilePicker, 'readWatermarkConfig', {});
}

function nativeWriteConfig(config) {
    const params = {
        config: config 
    };
    return callAndroidMethod(window.FilePicker, 'writeWatermarkConfig', params);
}
