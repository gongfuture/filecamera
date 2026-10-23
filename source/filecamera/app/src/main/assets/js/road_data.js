// --- Road Manager Script ---

// --- 获取核心 DOM 元素 ---
const roadPage = document.getElementById('road_page');
const rm_statusEl = document.getElementById('rm_status_log');
const rm_replaceInput = document.getElementById('rm_replace_input');
const rm_uploadInput = document.getElementById('rm_upload_input');
const rm_listContent = document.getElementById('rm_road_list_content');
const rm_loader = document.getElementById('rm_list_loader');

// --- 服务器配置（默认值来自 config/default_config.json，定制覆盖见 config/custom_config.json） ---
let server_config = { url: '', key: '' };

async function load_server_config() {
    try {
        const obj = await callAndroidMethod(window.FilePicker, 'readConfig', {
            key_arr: ['serverUrl', 'serverKey']
        });
        server_config.url = (obj && obj.serverUrl) ? String(obj.serverUrl).trim() : '';
        server_config.key = (obj && obj.serverKey) ? String(obj.serverKey).trim() : '';
    } catch (error) {
        console.warn('读取服务器配置失败:', error);
    }
    update_road_server_ui();
    return server_config;
}

function update_road_server_ui() {
    const configured = !!(server_config.url && server_config.key);
    const switchRow = document.getElementById('road_data_switch_row');
    const updateBtn = document.getElementById('get_road_data_btn');
    if (switchRow) switchRow.style.display = configured ? 'flex' : 'none';
    if (updateBtn) updateBtn.style.display = configured ? 'flex' : 'none';
    if (!configured) {
        const checkbox = document.getElementById('road_data_checkbox');
        if (checkbox) checkbox.checked = false;
        auto_get_road_data = false;
    }
}

// --- 核心业务逻辑函数 (保持不变) ---
async function get_fetch(url, timeout = 20000) {
    console.log(url);
    
    try {
        // 网络请求从 JS 的 fetch 下放到 Java 层，绕开 WebView 的证书限制。
        // Java 返回已解析的 JSON；失败时 promise reject，错误信息来自 Java。
        const timeoutPromise = new Promise((_, reject) => {
            setTimeout(() => reject(new Error('请求超时')), timeout);
        });
        const result = await Promise.race([
            callAndroidMethod(window.roadApi, 'fetchRoadData', { url: url }),
            timeoutPromise
        ]);
        return result;
    } catch (error) {
        console.error('请求错误:', error);
        return { success: false, error: error.message || String(error) };
    }
}
async function update_road_data_from_api(url){
	await getRoadData(url || server_config.url);
	await loadAndRenderRoads();
}
let is_getting_road_data = false;
async function getRoadData(url,mode) {
	if(is_getting_road_data){
		return;
	}
    const requestUrl = (url || server_config.url || '').trim();
    if (!requestUrl || !server_config.key) {
        rm_statusEl.textContent = '⚠️ 未配置服务器地址或密钥';
        return;
    }
    const currentTime = Date.now();
    let road_version = null
	if(mode){
		road_version = localStorage.getItem('road_version');
	}
    const obj = {
        key: server_config.key
    };
    if (road_version) {
        obj.client_version = road_version;
    }
    is_getting_road_data = true;
	window.updata_load_info('更新中...')
	if(!mode){
		document.getElementById("loadingoverlay").style.display = "flex"
	}
    try {
        const baseUrl = new URL(requestUrl);
        const apiUrl = new URL('/api/road_api/check', baseUrl);
        
        // 添加查询参数
        if (obj.key) {
            apiUrl.searchParams.set('key', obj.key);
        }
        if (obj.client_version) {
            apiUrl.searchParams.set('client_version', obj.client_version);
        }
        
        const result = await get_fetch(apiUrl.toString());
        // 记录检查时间
        localStorage.setItem('last_checktime', currentTime);

        // --- 处理返回结果 ---
        if (!result || !result.success) {
            const errorMessage = `获取数据失败: ${result?.error || '未知错误'}`;
            rm_statusEl.textContent = `❌ 错误: ${errorMessage}`;
			window.updata_load_info('')
			document.getElementById("loadingoverlay").style.display = "none"	
			is_getting_road_data = false;			
            return; 
        }

        // 检查版本是否同步
        if (result.is_synced) {
            rm_statusEl.textContent = '✅ 数据已是最新版本，无需更新。';
			window.updata_load_info('')
			document.getElementById("loadingoverlay").style.display = "none"	
			is_getting_road_data = false;			
            return;
        }
        
        if (result.data) {
            let clientVersion = result.server_version;
            localStorage.setItem('road_version', clientVersion);
            
            const geojson = result.data;

            if (!geojson.features || !Array.isArray(geojson.features)) {
                await alert_fix('❌ 错误: 文件格式无效：缺少 "features" 数组。');
                rm_statusEl.textContent = '❌ 错误: 文件格式无效：缺少 "features" 数组。';
				window.updata_load_info('')
				document.getElementById("loadingoverlay").style.display = "none"	
				is_getting_road_data = false;				
                return;
            }
            
            geojson.features.forEach(feature => {
                if (feature.properties && feature.properties.coord_type === 'GCJ02') {
                    transformGeometryCoordinates(feature.geometry);
                    feature.properties.coord_type = 'WGS84';
                }
            });
            
            await uploadAndReplaceAllRoads(geojson);
			rm_statusEl.textContent = '✅ 更新完成，版本号：'+clientVersion;
        } else {
            const message = '服务器响应版本不同步，但未返回新数据，请稍后重试。';
            rm_statusEl.textContent = message;
            console.warn(result.message);
        }
    } catch (error) {
        rm_statusEl.textContent = `❌ 错误: URL格式无效 - ${error.message}`;
        console.error('URL解析错误:', error);
    }
	window.updata_load_info('')
	document.getElementById("loadingoverlay").style.display = "none"	
	is_getting_road_data = false;	
}
function uploadAndReplaceAllRoads(geoJsonObject) {
    const payload = { geoJson: geoJsonObject };
    return callAndroidMethod(window.roadApi, 'replaceAllRoads', payload);
}

function processGeoJsonFile(file) {
    return new Promise((resolve, reject) => {
        if (!file) return reject(new Error("没有选择文件。"));
        rm_statusEl.textContent = `正在读取: ${file.name}...`;
        const reader = new FileReader();
        reader.onload = (e) => {
            try {
                rm_statusEl.textContent = '正在解析和转换坐标...';
                const geojsonObj = JSON.parse(e.target.result);
                if (!geojsonObj.features || !Array.isArray(geojsonObj.features)) {
                    return reject(new Error("文件格式无效：缺少 'features' 数组。"));
                }
                let featuresTransformedCount = 0;
                geojsonObj.features.forEach(feature => {
                    if (feature.properties && feature.properties.coord_type === 'GCJ02') {
                        transformGeometryCoordinates(feature.geometry);
                        feature.properties.coord_type = 'WGS84';
                        featuresTransformedCount++;
                    }
                });
                console.log(`坐标转换完成，共处理了 ${featuresTransformedCount} 个 Feature。`);
                resolve(geojsonObj);
            } catch (error) {
                reject(new Error(`处理失败: ${error.message}`));
            }
        };
        reader.onerror = () => reject(new Error("读取文件时发生错误。"));
        reader.readAsText(file);
    });
}

function transformGeometryCoordinates(geometry) {
    if (!geometry || !geometry.coordinates) return;
    const transformCoord = (coord) => coordtransform.gcj02towgs84(coord[0], coord[1]);
    switch (geometry.type) {
        case 'Point': geometry.coordinates = transformCoord(geometry.coordinates); break;
        case 'LineString': case 'MultiPoint': geometry.coordinates = geometry.coordinates.map(transformCoord); break;
        case 'Polygon': case 'MultiLineString': geometry.coordinates = geometry.coordinates.map(ring => ring.map(transformCoord)); break;
        case 'MultiPolygon': geometry.coordinates = geometry.coordinates.map(p => p.map(ring => ring.map(transformCoord))); break;
    }
}

function uploadSingleRoad(singleFeatureObject) {
    if (!singleFeatureObject || singleFeatureObject.type !== 'Feature') {
        throw new Error("传入的数据不是一个有效的 GeoJSON Feature！");
    }
    const payload = { feature: singleFeatureObject };
    return callAndroidMethod(window.roadApi, 'updateOrInsertRoad', payload);
}

function get_all_road_info() {
    return callAndroidMethod(window.roadApi, 'getAllRoadInfo', {});
}

function deleteRoad(roadPartId) {
    if (!roadPartId) return Promise.reject(new Error("无效的 roadPartId"));
    const payload = { road_part_id: roadPartId };
    return callAndroidMethod(window.roadApi, 'deleteRoadById', payload);
}

function formatPile(pileInKm) {
    if (pileInKm === null || isNaN(pileInKm)) return "暂无";
    let kilometers = Math.floor(pileInKm);
    let meters = Math.round((pileInKm - kilometers) * 1000);
    if (meters === 1000) {
        meters = 0;
        kilometers++;
    }
    return `K${kilometers}+${String(meters).padStart(3, '0')}`;
}

// --- UI 渲染和状态管理函数 ---

function renderRoadList(roads) {
    rm_listContent.innerHTML = '';
    if (!roads || roads.length === 0) {
        rm_listContent.innerHTML = '<p style="text-align:center; color: var(--text-secondary);">本地没有存储任何路线数据。</p>';
        return;
    }
    const fragment = document.createDocumentFragment();
    roads.forEach(road => {
        const card = document.createElement('div');
        card.className = 'road-card';
        const roadName = road.road_name || '未命名路线';
        const roadCountryId = road.road_country_id;
        let displayText = roadName;
        if (roadCountryId && roadCountryId.trim() !== '' && roadCountryId !== '暂无') {
            displayText = `${roadCountryId} - ${roadName}`;
        }
		const infoContainer = document.createElement('div');
		infoContainer.className = 'road-card-info';

		// --- 2. 创建并添加标题 <strong> ---
		const titleElement = document.createElement('strong');
		titleElement.textContent = displayText;
		infoContainer.appendChild(titleElement);

		// --- 3. 创建并添加所有 <small> 标签 (使用一个辅助函数会更简洁) ---

		/**
		 * 一个辅助函数，用于创建并添加一个 <small> 元素
		 * @param {HTMLElement} parent - 父元素
		 * @param {string} text - <small> 标签的文本内容
		 */
		function createAndAppendSmall(parent, text) {
			const smallElement = document.createElement('small');
			smallElement.textContent = text;
			parent.appendChild(smallElement);
		}
		// 依次创建并添加各个信息项
		createAndAppendSmall(infoContainer, `ID: ${road.road_part_id}`);
		createAndAppendSmall(infoContainer, `方向: ${road.direction}`);
		createAndAppendSmall(infoContainer, `起始桩号: ${formatPile(road.start_pile)}`);
		createAndAppendSmall(infoContainer, `终点桩号: ${formatPile(road.end_pile)}`);
		createAndAppendSmall(infoContainer, `描述: ${road.road_desc}`);

		// --- 4. 创建删除按钮 <button> ---
		const deleteButton = document.createElement('button');
		deleteButton.className = 'road-card-delete-btn';
		deleteButton.textContent = '删除'; // 设置按钮上显示的文字
		deleteButton.dataset.roadPartId = road.road_part_id;
		deleteButton.dataset.roadName = roadName;

		card.appendChild(infoContainer);
		card.appendChild(deleteButton);
        fragment.appendChild(card);
    });
    rm_listContent.appendChild(fragment);
}

async function loadAndRenderRoads() {
    rm_listContent.innerHTML = '';
    rm_loader.style.display = 'block';
    try {
        const roads = await get_all_road_info();
        renderRoadList(roads);
    } catch (error) {
        rm_listContent.innerHTML = `<p style="text-align:center; color: var(--danger-color);">加载路线失败: ${error.message}</p>`;
    } 
	rm_loader.style.display = 'none';
}

function resetRoadManagerState() {
    if (rm_statusEl) rm_statusEl.textContent = '请选择一个 GeoJSON 文件进行操作。';
    if (rm_replaceInput) rm_replaceInput.value = '';
    if (rm_uploadInput) rm_uploadInput.value = '';
    if (rm_listContent) rm_listContent.innerHTML = '<p style="text-align:center; color: var(--text-secondary);">点击 "刷新列表" 来查看本地路线。</p>';
}

// --- 事件监听器设置 ---
document.getElementById("road_data_checkbox").addEventListener('change', async () => {
	let road_data_checkbox = document.getElementById("road_data_checkbox")
	auto_get_road_data = road_data_checkbox.checked;
	const configToSave = { auto: auto_get_road_data };
	localStorage.setItem('auto_get_road_data', JSON.stringify(configToSave));
});
roadPage.addEventListener('click', async (event) => {
    const target = event.target;

    // 1. 处理 "增量更新" 按钮点击
    if (target.closest('#rm_replace_and_upload')) {
        rm_uploadInput.click();
        return;
    }

    // 2. 处理 "全量替换" 按钮点击
    if (target.closest('#rm_clear_and_upload')) {
        rm_replaceInput.click();
        return;
    }

    // 3. 处理 "刷新列表" 按钮点击
    if (target.closest('#rm_load_list_btn')) {
        await loadAndRenderRoads();
        return;
    }
    if (target.closest('#get_road_data_btn')) {
        await update_road_data_from_api()
        return;
    }
    // 4. 处理 "删除" 按钮点击
    const deleteBtn = target.closest('.road-card-delete-btn');
    if (deleteBtn) {
        const { roadPartId, roadName } = deleteBtn.dataset;
        const card = deleteBtn.closest('.road-card');
        if (!roadPartId || !card) return;

        if (await confirm_fix(`确定要永久删除路线 "${roadName}" 吗？`)) {
            try {
                deleteBtn.disabled = true;
                deleteBtn.textContent = '删除中...';
                await deleteRoad(roadPartId);

                card.style.transition = 'opacity 0.4s ease, transform 0.4s ease';
                card.style.opacity = '0';
                card.style.transform = 'translateX(20px)';

                setTimeout(() => {
                    card.remove();
                    if (rm_listContent.children.length === 0) {
                        renderRoadList([]);
                    }
                }, 400);
            } catch (error) {
                await alert_fix(`删除失败: ${error.message}`);
                deleteBtn.disabled = false;
                deleteBtn.textContent = '删除';
            }
        }
    }
});

// 文件输入框的 'change' 事件监听器保持不变，因为它们响应的是文件选择，而非点击
rm_uploadInput.addEventListener('change', async (event) => {
    const file = event.target.files[0];
    if(!file) {
		event.target.value = '';
		return;
	}
    try {
        const processedGeoJson = await processGeoJsonFile(file);
        const features = processedGeoJson.features;
        if (features.length === 0) {
            rm_statusEl.textContent = '文件不包含任何道路数据。';
			event.target.value = '';
            return;
        }
        for (let i = 0; i < features.length; i++) {
            const roadName = (features[i].properties && features[i].properties.road_name) || '未知路段';
            rm_statusEl.textContent = `[${i + 1}/${features.length}] 正在上传: ${roadName}`;
            await uploadSingleRoad(features[i]);
        }
        rm_statusEl.textContent = `✅ 成功上传全部 ${features.length} 条数据！`;
        await loadAndRenderRoads();
    } catch (error) {
        rm_statusEl.textContent = `❌ 错误: ${error.message}`;
    } 
	event.target.value = '';
});

rm_replaceInput.addEventListener('change', async (event) => {
    const file = event.target.files[0];
    if (!file) {
		event.target.value = '';
		return;
	}
    try {
        const processedGeoJson = await processGeoJsonFile(file);
        rm_statusEl.textContent = '正在上传数据到APP (全量替换)...';
        await uploadAndReplaceAllRoads(processedGeoJson);
        rm_statusEl.textContent = '✅ 全量替换成功！';
        await loadAndRenderRoads();
    } catch (error) {
        rm_statusEl.textContent = `❌ 错误: ${error.message}`;
    }
	event.target.value = '';	
});

// --- 页面控制函数 (暴露到全局) ---

async function open_road_page() {
    const page = document.getElementById('road_div');
    if (page) {
        page.classList.add('expanded');
        await loadAndRenderRoads();
    }
}

function close_road_page() {
    const page = document.getElementById('road_div');
    if (page) {
        page.classList.remove('expanded');
    }
    resetRoadManagerState();
}
