// --- 数据模型 (默认值) ---
let DEFAULT_CONFIG = {
    camera:{
		proportion:"16:9"
	},
    watermark: {
        enable: true,
        scale: 0.75,
        padding: [16, 16, 16, 16],
        radius: [8, 8, 8, 8],
        position: { left: 10, bottom: 10 },
        header: {
			display:true, // <-- 新增
            icon: { display:true, value: "file:///android_asset/icon.png", width: 48, height: 48, position: "left" }, // <-- 新增
            title: { value: "巡查记录", color: "#FFFFFF", size: 20 },
            background: [27, 68, 147, 255]
        },
        body: {
			display:true, // <-- 新增
            background: [248, 250, 252, 170],
            content: {
                road_name: { name: "路线名称", type: "road", name_display: true, index: 1, color: "#2C3E50", size: 16 },
                pile: { name: "桩号", type: "camera_pile", name_display: true, index: 2, color: "#2C3E50", size: 16 },
                coord: { name: "坐标", type: "camera_coord", name_display: true, index: 3, color: "#2C3E50", size: 16 },
                time: { name: "时间", type: "time", name_display: true, index: 4, color: "#2C3E50", size: 16 },
				//problem:{ name: "问题", type: "input", name_display: true, index: 5, color: "#2C3E50", size: 16 },
				//weather:{ name: "天气", type: "weather", name_display: true, value: "",index: 6, color: "#2C3E50", size: 16 },
				//address:{ name: "地址", type: "address", name_display: true, index: 7, color: "#2C3E50", size: 16 },
				
            }
        },
        foot: {
			display:true, // <-- 新增
            background: [27, 68, 147, 255],
            content: {
                unit: { name: "责任单位", type: "string", name_display: true, value: "", index: 1, color: "#FFFFFF", size: 12 }
            }
        }
    },
    qrcode: {
        enable: true,
        scale: 0.75,
        padding: [4, 4, 4, 4],
        radius: [8, 8, 8, 8],
        position: { right: 10, top: 10 }
    }
};

let config = null;
let isConfigDirty = false;
let pendingIconChange = null; 
const TYPE_OPTIONS = {
    string: '字符串',
    input: '实时输入',
    road: '路线名称 (动态)',
    camera_coord: '坐标 (动态)',
    camera_pile: '桩号 (动态)',
    time: '时间 (动态)',
	weather: '天气 (动态，需秘钥)',
	address: '地址 (动态，需秘钥)'
};
async function open_setting_page(){
	let obj = await nativeReadConfig()
	await loadConfig(obj)
	document.getElementById('setting_div').classList.add('expanded');
}
async function close_setting_page(){
    if(isConfigDirty){
        if(await confirm_fix('是否保存设置')){
            await saveConfig();
        } else {
            // 用户选择不保存，清理临时文件
            if (pendingIconChange) {
                await callAndroidMethod(
                    window.backServer, 
                    'cleanupTempIcon', 
                    { tempUri: pendingIconChange.tempUri }
                );
                pendingIconChange = null;
            }
        }
    }
    isConfigDirty = false;
    config = null;
    document.getElementById('settings-form').innerHTML ="";
    document.getElementById('setting_div').classList.remove('expanded');
}


// --- 工具函数 ---
function createElement(tag, options = {}) {
    const el = document.createElement(tag);
    if (options.className) el.className = options.className;
    if (options.textContent) el.textContent = options.textContent;
    if (options.properties) Object.assign(el, options.properties);
    if (options.dataset) Object.assign(el.dataset, options.dataset);
    if (options.children) options.children.forEach(child => child && el.appendChild(child));
    if (options.events) Object.entries(options.events).forEach(([e, l]) => el.addEventListener(e, l));
    return el;
}

function setValueByPath(obj, path, value) {
    const keys = path.split('.');
    let current = obj;
    for (let i = 0; i < keys.length - 1; i++) {
        if (!current[keys[i]]) current[keys[i]] = {};
        current = current[keys[i]];
    }
    current[keys[keys.length - 1]] = value;
}

function getValueByPath(obj, path) {
    return path.split('.').reduce((acc, key) => acc && acc[key], obj);
}

function hexToRgbaArray(hex) {
    let c;
    if (/^#([A-Fa-f0-9]{3}){1,2}$/.test(hex)) {
        c = hex.substring(1).split('');
        if (c.length == 3) {
            c = [c[0], c[0], c[1], c[1], c[2], c[2]];
        }
        c = '0x' + c.join('');
        return [(c >> 16) & 255, (c >> 8) & 255, c & 255, 255];
    }
    return [0, 0, 0, 255];
}

function rgbaArrayToHex(rgba) {
    if (!Array.isArray(rgba) || rgba.length < 3) return '#000000';
    const r = Math.max(0, Math.min(255, rgba[0] || 0));
    const g = Math.max(0, Math.min(255, rgba[1] || 0));
    const b = Math.max(0, Math.min(255, rgba[2] || 0));
    return "#" + ((1 << 24) + (r << 16) + (g << 8) + b).toString(16).slice(1).toUpperCase();
}

// =================================================================
// --- App 与 WebView 交互接口 ---
// =================================================================
async function loadConfig(obj) {
    try {
        // 兼容旧的配置文件，如果旧的里面没有camera属性，就给它加上
        if (obj && !obj.camera) {
            obj.camera = JSON.parse(JSON.stringify(DEFAULT_CONFIG.camera));
        }
        config = obj;
        isConfigDirty = false;
        render(); // 初始加载，必须完全重绘
    } catch (e) {
        console.error("Failed to parse JSON from App:", e);
        await alert_fix("加载配置失败，请检查数据格式！");
    }
}


// --- UI 组件构建函数 ---
function createFormGroup(label, input) {
    return createElement('div', {
        className: 'form-group',
        children: [
            createElement('label', { textContent: label }),
            input
        ]
    });
}

function createInput(options) {
    const { path, type = 'text', placeholder = '', arrayFill = 0 } = options;
    const value = getValueByPath(config, path);
    return createElement('input', {
        properties: {
            type,
            placeholder,
            value: (value !== null && value !== undefined) ? value : ''
        },
        events: {
            input: (e) => {
                let val = e.target.type === 'number' ? (parseFloat(e.target.value) || 0) : e.target.value;
                if (arrayFill > 0) {
                    const basePath = path.substring(0, path.lastIndexOf('.'));
                    updateConfig(basePath, Array(arrayFill).fill(val));
                } else {
                    updateConfig(path, val);
                }
            }
        }
    });
}

/**
 * 创建一个移动端友好的颜色输入组件 (用于无透明度颜色)
 */
function createColorInput(path) {
    const initialValue = getValueByPath(config, path) || '#000000';

    const colorPreview = createElement('div', {
        className: 'color-preview',
        properties: { style: `background-color: ${initialValue}` }
    });

    const textInput = createElement('input', {
        properties: {
            type: 'text',
            value: initialValue.toUpperCase(),
            className: 'color-text-input'
        }
    });

    // 封装颜色更新逻辑
    const handleColorChange = (newValue) => {
        newValue = newValue.trim().toUpperCase();
        if (/^#([A-F0-9]{6}|[A-F0-9]{3})$/.test(newValue)) {
            colorPreview.style.backgroundColor = newValue;
            updateConfig(path, newValue);
        }
        // 无论输入是否有效，最终都将输入框的值同步为数据模型中的最新值
        textInput.value = getValueByPath(config, path).toUpperCase();
    };

    // `input` 事件用于实时预览
    textInput.addEventListener('input', (e) => {
        const tempValue = e.target.value.trim().toUpperCase();
        if (/^#([A-F0-9]{6}|[A-F0-9]{3})$/.test(tempValue)) {
            colorPreview.style.backgroundColor = tempValue;
        }
    });

    // `change` 事件（失焦时触发）用于最终确认并更新数据模型
    textInput.addEventListener('change', (e) => {
        handleColorChange(e.target.value);
    });

    return createElement('div', {
        className: 'color-input-wrapper',
        children: [colorPreview, textInput]
    });
}

/**
 * 创建一个功能更全的 RGBA 颜色编辑器 (用于带透明度颜色)
 */
function createRgbaColorInputWithAlpha(path, label) {
    const initialRgba = getValueByPath(config, path) || [0, 0, 0, 255];
    let [r, g, b, a] = initialRgba;

    const colorPreview = createElement('div', { className: 'color-preview' });
    const hexInput = createElement('input', { properties: { type: 'text', className: 'color-text-input' } });
    const rInput = createElement('input', { properties: { type: 'number', min: 0, max: 255 } });
    const gInput = createElement('input', { properties: { type: 'number', min: 0, max: 255 } });
    const bInput = createElement('input', { properties: { type: 'number', min: 0, max: 255 } });
    const alphaSlider = createElement('input', { properties: { type: 'range', min: 0, max: 255 } });
    const alphaInput = createElement('input', { properties: { type: 'number', min: 0, max: 255, className: 'alpha-number-input' } });

    // 核心函数：根据 RGBA 值更新所有UI元素的状态
    function updateUI(r, g, b, a) {
        const hex = rgbaArrayToHex([r, g, b]);
        // 预览块使用rgba以显示透明效果
        colorPreview.style.backgroundColor = `rgba(${r}, ${g}, ${b}, ${a / 255})`;
        hexInput.value = hex.toUpperCase();
        rInput.value = r;
        gInput.value = g;
        bInput.value = b;
        alphaSlider.value = a;
        alphaInput.value = a;
    }

    // 核心函数：根据新的 RGBA 值更新数据模型和所有UI
    function updateModelAndUI(newR, newG, newB, newA) {
        // 确保所有值都在有效范围内
        newR = Math.max(0, Math.min(255, parseInt(newR) || 0));
        newG = Math.max(0, Math.min(255, parseInt(newG) || 0));
        newB = Math.max(0, Math.min(255, parseInt(newB) || 0));
        newA = Math.max(0, Math.min(255, parseInt(newA) || 0));
        
        updateConfig(path, [newR, newG, newB, newA]);
        updateUI(newR, newG, newB, newA);
    }

    // --- 事件绑定 ---
    const onRgbChange = () => {
        updateModelAndUI(rInput.value, gInput.value, bInput.value, alphaInput.value);
    };

    [rInput, gInput, bInput].forEach(el => el.addEventListener('change', onRgbChange));
    
    alphaSlider.addEventListener('input', (e) => {
        const newAlpha = parseInt(e.target.value);
        updateModelAndUI(rInput.value, gInput.value, bInput.value, newAlpha);
    });

    alphaInput.addEventListener('change', (e) => {
        updateModelAndUI(rInput.value, gInput.value, bInput.value, e.target.value);
    });

    hexInput.addEventListener('change', e => {
        const hex = e.target.value.trim();
        if (/^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$/.test(hex)) {
            const [newR, newG, newB] = hexToRgbaArray(hex);
            updateModelAndUI(newR, newG, newB, alphaInput.value);
        } else {
            // 输入无效，恢复UI
            const currentRgba = getValueByPath(config, path);
            updateUI(...currentRgba);
        }
    });

    // 初始化UI状态
    updateUI(r, g, b, a);

    // --- 组件组装 ---
    const hexWrapper = createElement('div', { className: 'color-input-wrapper', children: [colorPreview, hexInput] });
    const rgbWrapper = createElement('div', {
        className: 'rgb-inputs-wrapper',
        children: [
            createFormGroup('R', rInput), createFormGroup('G', gInput), createFormGroup('B', bInput)
        ]
    });
    const alphaWrapper = createElement('div', {
        className: 'alpha-control-wrapper',
        children: [
            createElement('label', { textContent: '透明度' }),
            alphaSlider,
            alphaInput
        ]
    });

    const container = createElement('div', {
        className: 'rgba-editor',
        children: [hexWrapper, rgbWrapper, alphaWrapper]
    });

    return createFormGroup(label, container);
}


function createPositionControl(path, label) {
    const container = createElement('div', { className: 'position-control' });
    const positionObject = getValueByPath(config, path) || {};

    const createAxisControl = (axisKeys, defaultKey) => {
        let currentKey = axisKeys.find(k => k in positionObject) || defaultKey;

        const select = createElement('select', {
            children: axisKeys.map(k => createElement('option', { properties: { value: k, selected: k === currentKey }, textContent: k })),
            events: {
                change: (e) => {
                    const newKey = e.target.value;
                    const oldKey = currentKey;
                    if (newKey === oldKey) return;

                    const value = getValueByPath(config, `${path}.${oldKey}`);
                    delete getValueByPath(config, path)[oldKey];
                    setValueByPath(config, `${path}.${newKey}`, value);
                    isConfigDirty = true;
                    currentKey = newKey; // 更新闭包中的 key
                }
            }
        });

        const input = createElement('input', {
			properties: { 
				type: 'number', 
				value: positionObject[currentKey] || 0 
			},
            events: {
                input: (e) => updateConfig(`${path}.${select.value}`, parseFloat(e.target.value) || 0)
            }
        });

        return createElement('div', { className: 'control-row', children: [select, input] });
    };

    container.append(createAxisControl(['left', 'right'], 'left'), createAxisControl(['top', 'bottom'], 'top'));
    return createFormGroup(label, container);
}

function createCard(title, children, enablePath = null) {
    const cardContent = createElement('div', { className: 'card-content' });
    // 卡片主开关使用标准 truthy/falsy 判断 (null/undefined 视为 false)
    if (enablePath && !getValueByPath(config, enablePath)) {
        cardContent.classList.add('disabled');
    }
    cardContent.append(...children);

    let headerChildren = [createElement('span', { textContent: title })];
    if (enablePath) {
        const switchInput = createElement('input', { properties: { type: 'checkbox', checked: !!getValueByPath(config, enablePath) } });
        const switchLabel = createElement('label', {
            className: 'switch',
            dataset: { action: 'toggle-enable', path: enablePath },
            children: [switchInput, createElement('span', { className: 'slider' })]
        });
        headerChildren.push(switchLabel);
    }

    return createElement('div', {
        className: 'card',
        children: [
            createElement('header', { className: 'card-header', children: headerChildren }),
            cardContent
        ]
    });
}

/**
 * [修改] 创建一个独立的开关 (Switch) UI
 * @param {string} path - 绑定到 config 对象的路径
 * @param {string} [targetSelector] - (可选) 当开关关闭时，需要被切换 'disabled' 类的目标元素的 CSS 选择器 (相对于开关的 .details-content 或 details 父级)
 * @returns {HTMLElement}
 */
function createSwitch(path, targetSelector = null) {
    const value = getValueByPath(config, path);
    // [兼容性修改] 只有 'false' 才是 'off'. null/undefined/true 都按 'on' (checked) 处理.
    const isChecked = !(value === false); 
    
    const switchInput = createElement('input', { 
        properties: { type: 'checkbox', checked: isChecked } 
    });
    
    const switchLabel = createElement('label', {
        className: 'switch',
        dataset: { 
            action: 'toggle-content-enable', // 新增的 action
            path: path 
        },
        children: [switchInput, createElement('span', { className: 'slider' })]
    });

    if (targetSelector) {
        switchLabel.dataset.target = targetSelector;
    }
    return switchLabel;
}

/**
 * 检查单一实例类型（如天气、地址）是否唯一的辅助函数
 * @param {string} typeToCheck - 要检查的类型 ('weather', 'address')
 * @param {string} pathToIgnore - 检查时要忽略的当前项的路径
 * @returns {boolean} - 如果是唯一的则返回 true，否则返回 false
 */
function isSingletonTypeUnique(typeToCheck, pathToIgnore) {
    const sections = ['watermark.body.content', 'watermark.foot.content'];
    for (const sectionPath of sections) {
        const content = getValueByPath(config, sectionPath) || {};
        for (const key in content) {
            const currentItemPath = `${sectionPath}.${key}`;
            // 如果找到另一个相同类型的项（并且不是我们正在编辑的那个），则返回 false
            if (currentItemPath !== pathToIgnore && content[key].type === typeToCheck) {
                return false; // 发现重复
            }
        }
    }
    return true; // 没有找到其他相同类型的项
}


function createContentItem(itemKey, itemData, contentPath) {
    const nameInput = createElement('input', { className: 'name-input', properties: { type: 'text', value: itemData.name }, events: { input: (e) => updateConfig(`${contentPath}.${itemKey}.name`, e.target.value) } });
    const controls = createElement('div', {
        className: 'item-controls',
        children: [
            createElement('button', { className: 'btn-move', textContent: '▲', dataset: { action: 'move-item', path: contentPath, key: itemKey, direction: -1 } }),
            createElement('button', { className: 'btn-move', textContent: '▼', dataset: { action: 'move-item', path: contentPath, key: itemKey, direction: 1 } }),
            createElement('button', { className: 'btn-remove', textContent: '✖', dataset: { action: 'remove-item', path: contentPath, key: itemKey } })
        ]
    });
    const keyInput = createInput({ path: `${contentPath}.${itemKey}._key_`, type: 'text' });
    keyInput.value = itemKey;
    keyInput.addEventListener('change', async (e) => await renameContentKey(contentPath, itemKey, e.target.value));

    const valueFormGroup = createFormGroup('值', createInput({ path: `${contentPath}.${itemKey}.value` }));
    const valueInput = valueFormGroup.querySelector('input');
    const valueLabel = valueFormGroup.querySelector('label');

    // --- 初始化逻辑 ---
    // 根据初始类型设置值输入框的状态
    if (itemData.type === 'weather') {
        valueInput.disabled = false;
        valueInput.placeholder = "请输入城市adcode";
        valueLabel.textContent = '城市 Adcode';
    } else if (itemData.type === 'string') {
        valueInput.disabled = false; // 明确 string 类型是可输入的
        valueLabel.textContent = '值 (类型为字符串时)';
    } else if (itemData.type === 'input') {
        // 明确 'input' 类型也是可输入的 (用于设置默认值)
        valueInput.disabled = false; 
        valueInput.placeholder = "请输入默认值";
        valueLabel.textContent = '默认值';
    } else {
        // 其他所有动态类型 (road, time, address 等) 才被禁用
        valueInput.disabled = true; 
        valueInput.placeholder = "此类型为动态值";
        valueLabel.textContent = '值';
    }
    
    // 为类型选择器添加对 'weather' 和 'address' 的唯一性检查逻辑
    const typeSelect = createElement('select', {
        events: {
            input: async e => {
                const newType = e.target.value;
                const currentItemPath = `${contentPath}.${itemKey}`;
                const oldType = getValueByPath(config, `${currentItemPath}.type`);

                if (newType === oldType) return;

                // 如果新类型是单一实例类型（'weather' 或 'address'），则进行唯一性检查
                if (newType === 'weather' || newType === 'address') {
                    if (!isSingletonTypeUnique(newType, currentItemPath)) {
						const typeName = TYPE_OPTIONS[newType] || newType;
                        await alert_fix(`${typeName} 类型最多只能配置一个。`);
                        e.target.value = oldType; // 在UI上恢复原来的选项
                        return; // 阻止更改
                    }
                }
                
                // 检查通过，更新数据模型
                updateConfig(`${currentItemPath}.type`, newType);
                
                // --- 切换类型逻辑 ---
                // 根据选择的类型动态调整值输入框
                if (newType === 'string') {
                    valueInput.disabled = false;
                    valueInput.placeholder = "";
                    valueLabel.textContent = '值 (类型为字符串时)';
                } else if (newType === 'weather') {
                    valueInput.disabled = false;
                    valueInput.placeholder = "请输入城市adcode";
                    valueLabel.textContent = '城市 Adcode';
                } else if (newType === 'input') {
                    valueInput.disabled = false; // 允许输入默认值
                    valueInput.placeholder = "请输入默认值";
                    valueLabel.textContent = '默认值';
                } else { // 其他动态类型（包括 'address'）
                    valueInput.disabled = true;
                    valueInput.placeholder = "此类型为动态值";
                    valueLabel.textContent = '值';
                    valueInput.value = ""; // 清空值
                    updateConfig(`${currentItemPath}.value`, ""); // 同步数据模型
                }
            }
        },
        children: Object.entries(TYPE_OPTIONS).map(([value, text]) => createElement('option', { properties: { value, selected: itemData.type === value }, textContent: text }))
    });

    const nameDisplayCheckbox = createElement('input', { properties: { type: 'checkbox', checked: itemData.name_display, id: `check-${itemKey}` }, events: { input: e => updateConfig(`${contentPath}.${itemKey}.name_display`, e.target.checked) } });
    const checkboxGroup = createElement('div', { className: 'checkbox-group', children: [nameDisplayCheckbox, createElement('label', { properties: { htmlFor: `check-${itemKey}` }, textContent: '显示名称' })] });

    return createElement('div', {
        className: 'content-item',
        children: [
            createElement('div', { className: 'content-item-header', children: [nameInput, controls] }),
            createFormGroup('属性名 (Key)', keyInput),
            createElement('div', { className: 'grid-2', children: [createFormGroup('类型', typeSelect), valueFormGroup] }),
            createElement('div', { className: 'grid-2', children: [createFormGroup('颜色', createColorInput(`${contentPath}.${itemKey}.color`)), createFormGroup('字号', createInput({ path: `${contentPath}.${itemKey}.size`, type: 'number' }))] }),
            checkboxGroup
        ]
    });
}

// --- 内容生成函数 ---

/**
 * [修改] 构建水印设置卡片的内容
 */
function createWatermarkContent() {
    const fragment = document.createDocumentFragment();
    fragment.append(
        createElement('div', { className: 'grid-2', children: [createFormGroup('全局缩放', createInput({ path: 'watermark.scale', type: 'number' })), createFormGroup('内边距(px)', createInput({ path: 'watermark.padding.0', type: 'number', arrayFill: 4, placeholder: '输入后应用到四边' }))] }),
        createFormGroup('圆角(px)', createInput({ path: 'watermark.radius.0', type: 'number', arrayFill: 4 })),
        createPositionControl('watermark.position', '位置')
    );

    // --- 1. 页眉 (Header) ---
    const headerDisplayPath = 'watermark.header.display';
    const iconDisplayPath = 'watermark.header.icon.display';

    const headerContent = createElement('div', {
        className: 'details-content', // 使用 'details-content' 以便开关控制
        children: [
            createRgbaColorInputWithAlpha('watermark.header.background', '背景色'),
            createFormGroup('标题文字', createInput({ path: 'watermark.header.title.value' })),
            createElement('div', { className: 'grid-2', children: [createFormGroup('标题颜色', createColorInput('watermark.header.title.color')), createFormGroup('标题字号', createInput({ path: 'watermark.header.title.size', type: 'number' }))] }),
            
            // 图标显示开关
            createFormGroup(
                '显示图标', 
                createSwitch(iconDisplayPath, '.icon-controls-group') // 目标选择器
            ),
            
            // 将所有图标控件包裹在一个组中，以便切换
            createElement('div', {
                // [兼容性修改] 只有 'false' 才是 'off'.
                className: `icon-controls-group ${getValueByPath(config, iconDisplayPath) === false ? 'disabled' : ''}`, // 初始状态
                children: [
                    createFormGroup('图标预览', createIconPickerControl('watermark.header.icon.value')),
                    createElement('div', {
                        className: 'grid-3',
                        children: [
                            createFormGroup('图标宽度', createInput({ path: 'watermark.header.icon.width', type: 'number' })),
                            createFormGroup('图标高度', createInput({ path: 'watermark.header.icon.height', type: 'number' })),
                            createFormGroup('图标位置', createElement('select', {
                                events: { input: e => updateConfig('watermark.header.icon.position', e.target.value) },
                                children: ['left', 'right'].map(pos => createElement('option', { properties: { value: pos, selected: config.watermark.header.icon.position === pos }, textContent: pos }))
                            }))
                        ]
                    })
                ]
            }),
        ]
    });
    
    // 创建页眉的显示开关
    const headerSwitch = createSwitch(headerDisplayPath, '.details-content');

    const headerDetails = createElement('details', {
        properties: { open: true },
        children: [
            createElement('summary', { 
                className: 'card-header', 
                children: [
                    createElement('span', { textContent: '页眉 (Header)' }),
                    headerSwitch // 将开关添加到 summary
                ]
            }),
            headerContent
        ]
    });
    
    // [兼容性修改] 检查页眉初始状态
    if (getValueByPath(config, headerDisplayPath) === false) {
        headerContent.classList.add('disabled');
    }
    fragment.appendChild(headerDetails);

    // --- 2. 主体 (Body) 和 页脚 (Foot) ---
    ['body', 'foot'].forEach(section => {
        const contentPath = `watermark.${section}.content`;
        const displayPath = `watermark.${section}.display`; 
        
        const contentData = getValueByPath(config, contentPath) || {};
        const sortedItems = Object.entries(contentData).sort(([, a], [, b]) => a.index - b.index);
        const contentList = createElement('div', { children: sortedItems.map(([key, data]) => createContentItem(key, data, contentPath)) });
        
        const sectionContent = createElement('div', {
            className: 'details-content', 
            children: [
                createRgbaColorInputWithAlpha(`watermark.${section}.background`, '背景色'),
                contentList,
                createElement('button', { className: 'btn btn-primary', textContent: '添加内容项', dataset: { action: 'add-item', path: contentPath } })
            ]
        });

        // 创建 section 的显示开关
        const sectionSwitch = createSwitch(displayPath, '.details-content');

        const sectionDetails = createElement('details', {
            properties: { open: true },
            children: [
                createElement('summary', { 
                    className: 'card-header', 
                    children: [
                        createElement('span', { textContent: section === 'body' ? '主体 (Body)' : '页脚 (Foot)' }),
                        sectionSwitch // 将开关添加到 summary
                    ]
                }),
                sectionContent
            ]
        });

        // [兼容性修改] 检查初始状态
        if (getValueByPath(config, displayPath) === false) {
            sectionContent.classList.add('disabled');
        }

        fragment.appendChild(sectionDetails);
    });
    return [fragment];
}

function createQrCodeContent() {
    return [
        createElement('div', { className: 'grid-2', children: [createFormGroup('缩放', createInput({ path: 'qrcode.scale', type: 'number' })), createFormGroup('内边距(px)', createInput({ path: 'qrcode.padding.0', type: 'number', arrayFill: 4 }))] }),
        createFormGroup('圆角(px)', createInput({ path: 'qrcode.radius.0', type: 'number', arrayFill: 4 })),
        createPositionControl('qrcode.position', '位置')
    ];
}

function createCameraSettingsContent() {
    const currentProportion = getValueByPath(config, 'camera.proportion') || '16:9';

    const proportionSelect = createElement('select', {
        events: {
            input: e => updateConfig('camera.proportion', e.target.value)
        },
        children: ['16:9', '4:3'].map(ratio =>
            createElement('option', {
                properties: {
                    value: ratio,
                    selected: currentProportion === ratio
                },
                textContent: ratio
            })
        )
    });

    return [
        createFormGroup('相机比例', proportionSelect)
    ];
}

// --- 渲染主函数 ---
function render() {
    if (!config) return;
    const form = document.getElementById('settings-form');
    form.textContent = ''; // 清空
    
    form.appendChild(createCard('相机设置', createCameraSettingsContent()));
    form.appendChild(createCard('水印设置', createWatermarkContent(), 'watermark.enable'));
    form.appendChild(createCard('二维码设置', createQrCodeContent(), 'qrcode.enable'));

    const buttonContainer = createElement('div', {
        className: 'setting_button_div',
        children: [
            createElement('button', { className: 'btn btn-secondary', textContent: '恢复默认', dataset: { action: 'reset-config' } }),
            createElement('button', { className: 'btn btn-primary', textContent: '保存设置', dataset: { action: 'save-config' } })
        ]
    });
    buttonContainer.style.marginTop = '20px';
    form.appendChild(buttonContainer);
}

// --- 数据操作 & 事件处理 ---
async function saveConfig() {
    try {
        // 1. 如果有待定的图标变更，先提交它
        if (pendingIconChange) {
            const commitResult = await callAndroidMethod(window.backServer, 'commitIconChange', pendingIconChange);
            pendingIconChange = null; // 清除待定状态
        }
        
        // 2. 保存配置
        let result = await nativeWriteConfig(config);
        if (result) {
            isConfigDirty = false;
            await alert_fix('配置保存成功');
        }
    } catch (err) {
        await alert_fix('保存失败: ' + err.message);
    }
}


function updateConfig(path, value) {
    if (!config) return;
    setValueByPath(config, path, value);
    isConfigDirty = true;
}

async function resetToDefault() {
    if (await confirm_fix('确定要恢复默认设置吗？')) {
        config = JSON.parse(JSON.stringify(DEFAULT_CONFIG));
        isConfigDirty = true; // 标记为已更改
        render(); // 数据完全重置，必须重绘
        await saveConfig();
    }
}

function addContentItem(path) {
    const content = getValueByPath(config, path);
    const newKey = get_id_by_time();
    const maxIndex = Object.values(content).reduce((max, item) => Math.max(max, item.index || 0), 0);
    
    // 根据路径判断所属部分，设置默认颜色
    let defaultColor = '#2C3E50'; // 默认为 body 的颜色
    if (path.includes('foot.content')) { // 'foot' 文本颜色为白
        defaultColor = '#FFFFFF';
    } else if (path.includes('body.content')) { // 'body' 文本颜色为深
        defaultColor = '#2C3E50';
    }
    
    content[newKey] = { 
        name: '新项目', 
        type: 'string', 
        value: '', 
        name_display: true, 
        index: maxIndex + 1, 
        color: defaultColor,  // 使用根据部分确定的默认颜色
        size: 16 
    };
    
    isConfigDirty = true;
    render(); // DOM 结构变化
}

async function removeContentItem(path, key) {
    if (await confirm_fix('确定要删除？')) {
        delete getValueByPath(config, path)[key];
        isConfigDirty = true;
        render(); // DOM 结构变化
    }
}

function moveContentItem(path, key, direction) {
    const content = getValueByPath(config, path);
    const sorted = Object.entries(content).sort(([, a], [, b]) => a.index - b.index);
    const currentIndex = sorted.findIndex(([k]) => k === key);
    const targetIndex = currentIndex + direction;

    if (targetIndex >= 0 && targetIndex < sorted.length) {
        const otherKey = sorted[targetIndex][0];
        [content[key].index, content[otherKey].index] = [content[otherKey].index, content[key].index];
        isConfigDirty = true;
        render(); // DOM 结构变化
    }
}

async function renameContentKey(path, oldKey, newKey) {
    newKey = newKey.trim().replace(/\s/g, '_');
    const content = getValueByPath(config, path);

    if (!newKey || newKey === oldKey) {
        if (!newKey) render(); // 如果清空了输入框，恢复显示旧 key
        return;
    }

    if (content[newKey]) {
        await alert_fix(`属性名 "${newKey}" 已存在，请使用其他名称。`);
        render(); // 恢复显示旧 key
        return;
    }

    Object.defineProperty(content, newKey, Object.getOwnPropertyDescriptor(content, oldKey));
    delete content[oldKey];
    isConfigDirty = true;
    render(); // DOM 结构变化
}

function createIconPickerControl(path) {
    // Java 使用的默认路径 (存储在配置中)
    const defaultIconPathForJava = DEFAULT_CONFIG.watermark.header.icon.value; // "file:///android_asset/icon.png"
    // JavaScript 使用的默认路径 (用于显示)
    const defaultIconPathForJS = 'icon.png';
    
    const currentUri = getValueByPath(config, path) || defaultIconPathForJava;

    // --- 1. 创建核心 UI 元素 ---

    // 图片预览
    const iconPreview = createElement('img', {
        className: 'icon-preview',
        properties: {
            src: '', // 初始 src 将在下面设置
            onerror: function() {
                // 如果图片加载失败，显示一个占位符或默认图标
                this.src = defaultIconPathForJS;
            }
        }
    });
    
    // --- 2. 核心逻辑：更新预览图和文本 ---
	let currentObjectUrl = null;
	async function updatePreview(uri) {
		if (currentObjectUrl) {
			URL.revokeObjectURL(currentObjectUrl);
			currentObjectUrl = null;
		}

		if (uri === defaultIconPathForJava) {
			iconPreview.src = defaultIconPathForJS;
		} else {
			try {
				let fetchUrl = "https://appassets.androidplatform.net/watermark_icon/cache_icon.png";
				if (pendingIconChange && pendingIconChange.targetUri === uri) {
					// 使用临时文件的访问路径
					fetchUrl = "https://appassets.androidplatform.net/watermark_icon/temp_icon.png";
				}
				
				const response = await fetch(fetchUrl);
				if (!response.ok) {
					throw new Error(`HTTP error! status: ${response.status}`);
				}
				const blob = await response.blob();
				currentObjectUrl = URL.createObjectURL(blob);
				iconPreview.src = currentObjectUrl;
			} catch (error) {
				console.error("加载图标失败:", error);
				iconPreview.src = defaultIconPathForJS;
			}
		}
	}
    // --- 3. 创建按钮及其事件 ---

    // "选择图标"按钮
    const pickerButton = createElement('button', {
        className: 'btn',
        textContent: '选择图标',
        events: {
            click: async (e) => {
                e.preventDefault();
                if (typeof nativeOpenFilePicker !== 'function') {
                    await alert_fix("错误：原生文件选择接口 'nativeOpenFilePicker' 不可用。");
                    return;
                }
                try {
                    const uri_arr = await nativeOpenFilePicker(true, 'file');
                    if (!uri_arr || uri_arr.length === 0 || !uri_arr[0].uri) {
                        return;
                    }
                    const newUri = uri_arr[0].uri;
                    if (!newUri) {
                        return;
                    }
                    // 上传图标并获取 Java 使用的路径
                    let icon_uri = await upload_watermark_icon(newUri);
                    if (icon_uri) {
                        // 保存 Java 路径到配置
                        updateConfig(path, icon_uri);
                        // 使用 JS 路径更新预览
                        updatePreview(icon_uri);
                    }
                } catch (error) {
                    console.error("调用 nativeOpenFilePicker 失败:", error);
                    await alert_fix(`选择文件失败: ${error.message}`);
                }
            }
        }
    });

    // "恢复默认"按钮
    const restoreButton = createElement('button', {
        className: 'btn btn-secondary',
        textContent: '恢复默认',
        events: {
            click: (e) => {
                e.preventDefault();
                // 恢复 Java 路径到配置
                updateConfig(path, defaultIconPathForJava);
                // 使用 JS 路径更新预览
                updatePreview(defaultIconPathForJava);
            }
        }
    });

    // --- 4. 初始化和组装 ---
    
    // 初始化时设置一次预览
    updatePreview(currentUri);

    // 将按钮放入一个组中，以便更好地布局
    const buttonGroup = createElement('div', {
        className: 'icon-button-group',
        children: [pickerButton, restoreButton]
    });

    // 返回组装好的完整控件
    return createElement('div', {
        className: 'icon-picker-control',
        children: [iconPreview, buttonGroup]
    });
}

// --- 初始化和事件委托 ---
const settings_form = document.getElementById('settings-form');
settings_form.addEventListener('click', async (e) => {
    const target = e.target;
    const actionTarget = target.closest('[data-action]');
    if (!actionTarget) return;

    const { action, path, key, direction } = actionTarget.dataset;

    switch (action) {
        case 'toggle-enable': // 这是卡片的主开关
            const checkbox = actionTarget.querySelector('input[type="checkbox"]');
            if (checkbox) {
                const isEnabled = checkbox.checked;
                updateConfig(path, isEnabled);
                const cardContent = actionTarget.closest('.card').querySelector('.card-content');
                if (cardContent) {
                    cardContent.classList.toggle('disabled', !isEnabled);
                }
            }
            break;

        // 处理 createSwitch 创建的开关 (用于 <details> 内部)
        case 'toggle-content-enable':
            const checkboxToggle = actionTarget.querySelector('input[type="checkbox"]');
            if (checkboxToggle) {
                const isEnabled = checkboxToggle.checked;
                // [兼容性修改] 当开关打开时，我们存 'true'，关闭时存 'false'。
                // 读取时 (在 createSwitch 中) 会把 null/undefined 视为 'true'。
                updateConfig(path, isEnabled); 
                
                const targetSelector = actionTarget.dataset.target;
                if (targetSelector) {
                    // 查找 <details> (用于 summary 中的开关) 或 .details-content (用于内容中的开关)
                    let container = actionTarget.closest('details'); // 开关在 summary 中
                    if (!container) {
                        container = actionTarget.closest('.details-content'); // 开关在 content 中
                    }
                    
                    if (container) {
                        const contentToToggle = container.querySelector(targetSelector);
                        if (contentToToggle) {
                            contentToToggle.classList.toggle('disabled', !isEnabled);
                        }
                    }
                }
            }
            break;

        case 'add-item':
            addContentItem(path);
            break;
        case 'remove-item':
            await removeContentItem(path, key);
            break;
        case 'move-item':
            moveContentItem(path, key, parseInt(direction, 10));
            break;
        case 'save-config':
            await saveConfig();
            break;
        case 'reset-config':
            await resetToDefault();
            break;
    }
});
async function upload_watermark_icon(sourceUri) {
    if (!sourceUri) {
        throw new Error("sourceUri 不能为空");
    }

    console.log(`[JS] 正在请求原生保存图标: ${sourceUri}`);
    
    const data = {
        sourceUri: sourceUri,
        temporary: true // 标记为临时保存
    };
    
    try {
        const result = await callAndroidMethod(window.backServer, 'saveIconToCache', data);
        if (result && result.uri && result.tempUri) {
            // 记录这次待定的变更
            pendingIconChange = {
                tempUri: result.tempUri,
                targetUri: result.uri
            };
            
            return result.uri; // 返回目标路径（但实际文件还在临时位置）
        } else {
            throw new Error("原生接口未按预期返回 uri 对象");
        }
        
    } catch (error) {
        throw new Error(error.message || "保存图标失败");
    }
}