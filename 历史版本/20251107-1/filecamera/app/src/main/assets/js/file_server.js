// 通用的POST请求函数
async function post_fetch(url, data) {
    try {
        const response = await fetch(url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(data)
        });
        
        const result = await response.json();
        return result;
    } catch (error) {
        console.error('请求错误:', error);
        return { success: false, error: error.message };
    }
}
// Blob转Base64函数
async function blobToBase64(blob) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onloadend = () => {
            resolve(reader.result);
        };
        reader.onerror = reject;
        reader.readAsDataURL(blob);
    });
}

// Base64转Blob函数
function base64ToBlob(dataUrl) {
    // 分割数据URL，获取MIME类型和Base64数据
    const parts = dataUrl.split(',');
    const metaPart = parts[0];
    const base64Data = parts[1];

    // 从元数据部分提取MIME类型
    // 例如: "data:image/png;base64" -> "image/png"
    const mimeType = metaPart.split(':')[1].split(';')[0];

    // 解码Base64字符串
    // atob() 函数用于解码一个已经被base-64编码过的字符串
    const byteCharacters = atob(base64Data);
    const byteNumbers = new Array(byteCharacters.length);
    for (let i = 0; i < byteCharacters.length; i++) {
        byteNumbers[i] = byteCharacters.charCodeAt(i);
    }

    // 将字节码转换为类型化数组
    const byteArray = new Uint8Array(byteNumbers);

    // 创建并返回Blob对象
    return new Blob([byteArray], { type: mimeType });
}
function createSvgBlobUrl(svgContent) {
    const blob = new Blob([svgContent], { type: 'image/svg+xml' });
    const blobUrl = URL.createObjectURL(blob);
    return blobUrl;
}

function isImageFile(filename) {
    return /\.(jpg|jpeg|png|gif|bmp|webp|svg)$/i.test(filename);
}
let dir_icon_text = '<?xml version="1.0" standalone="no"?><!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd"><svg class="icon" style="width: 5em; height: 5em;vertical-align: middle;fill: currentColor;overflow: hidden;" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg"><path d="M977.6 238.4c-9.6-9.6-21.6-14.4-33.6-14.4H472L366.4 118.4c-4-4-9.6-8-15.2-10.4-6.4-2.4-12-4-18.4-4H80c-12 0-24.8 4.8-33.6 14.4S32 140 32 152v280h960V272c0-12-4.8-24.8-14.4-33.6z" fill="#434a54" /><path d="M944 912H80c-26.4 0-48-21.6-48-48V352h960v512c0 26.4-21.6 48-48 48z" fill="#656d78" /> </svg>'
let file_icon_text = '<?xml version="1.0" standalone="no"?><!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd"><svg class="icon" style="width: 5em; height: 5em;vertical-align: middle;fill: currentColor;overflow: hidden;" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg"><path d="M160 32c-12 0-24.8 4.8-33.6 14.4S112 68 112 80v864c0 12 4.8 24.8 14.4 33.6 9.6 9.6 21.6 14.4 33.6 14.4h704c12 0 24.8-4.8 33.6-14.4 9.6-9.6 14.4-21.6 14.4-33.6V304L640 32H160z" fill="#E5E5E5" /><path d="M912 304H688c-12 0-24.8-4.8-33.6-14.4-9.6-8.8-14.4-21.6-14.4-33.6V32l272 272z" fill="#CCCCCC" /></svg>'

let book_icon_text = '<svg width="24" height="24" xmlns="http://www.w3.org/2000/svg" fill-rule="evenodd" clip-rule="evenodd"><path d="M22 24h-17c-1.657 0-3-1.343-3-3v-18c0-1.657 1.343-3 3-3h17v24zm-2-4h-14.505c-1.375 0-1.375 2 0 2h14.505v-2zm-3-15h-10v3h10v-3z" fill="#656d78"/><rect x="7" y="5" width="10" height="3" fill="#434a54"/><path d="M5.495 20h14.505v2h-14.505c-1.375 0-1.375-2 0-2z" fill="#FFFFFF"/></svg>'
let dir_icon_url = createSvgBlobUrl(dir_icon_text)
let file_icon_url = createSvgBlobUrl(file_icon_text)
let book_icon_url = createSvgBlobUrl(book_icon_text)
// 图标制作函数
async function icon_make(file) {
    if (!(file instanceof File) || !file.type.startsWith('image/')) {
        return null;
    }
    
    const dataUrl = await new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result);
        reader.onerror = () => reject(reader.error);
        reader.readAsDataURL(file);
    });

    const canvas = document.createElement('canvas');
    const context = canvas.getContext('2d');
    canvas.width = 256;
    canvas.height = 256;

    const image = new Image();
    image.src = dataUrl;

    await new Promise((resolve, reject) => {
        image.onload = resolve;
        image.onerror = reject;
    });
    
    const originalWidth = image.width;
    const originalHeight = image.height;
    const scale = Math.max(originalWidth, originalHeight) / 256;
    const newWidth = Math.round(originalWidth / scale);
    const newHeight = Math.round(originalHeight / scale);
    const offsetY = (canvas.height - newHeight) / 2;

    context.drawImage(image, 0, offsetY, newWidth, newHeight);
    
    return new Promise((resolve) => {
        canvas.toBlob((blob) => resolve(blob), 'image/png');
    });
}
async function get_data_by_arr(arr, table) {
    try {
        const result = await callAndroidMethod(window.backServer,'get_data_by_arr', {
            pathArray: arr,
            table: table
        });
        return result;
    } catch (error) {
        console.error('获取数据失败:', error);
        if (error === '文件夹不存在') {
            await alert_fix('文件夹不存在');
        }
        return null;
    }
}
function getFileNameFromUri(uri) {
    try {
        // 去掉query参数和fragment
        let path = decodeURIComponent(uri);
        // 获取最后一段路径
        let segments = path.split('/');
        let fileName = segments[segments.length - 1];
        
        // 如果是空的，尝试倒数第二段
        if (!fileName && segments.length > 1) {
            fileName = segments[segments.length - 2];
        }
        // 如果还是空的，生成默认名称
        if (!fileName) {
            fileName = 'unknown_file_' + Date.now();
        }
        
        return fileName;
    } catch (e) {
        return 'unknown_file_' + Date.now();
    }
}
// 上传文件
async function uploadfile(file, dir_id, name) {
	// Android环境：file参数是content URI，后端处理所有逻辑包括图标制作
	try {
		let obj = {
			contentUri: file,  // Android中file就是content URI
			dirId: dir_id,
			fileName: name
		}
		const result = await callAndroidMethod(window.backServer,'upload_file',obj);
		
		return result;
	} catch (error) {
		console.error('上传文件失败:', error);
		throw new Error(error);
	}
}
//----------------------------------------
async function upload_file_to_path(uri, parentarr, name, now_id) {
    try {
        // 1. 先创建/查找目录路径，获取目录ID
        const dir_id = await create_dir_to_path(parentarr, now_id);
        
        if (!dir_id) {
            return null;
        }
        
        // 2. 然后上传文件到该目录
        const result = await uploadfile(uri, dir_id, name);
        
        // 3. 返回路径和目录ID（和原来的逻辑保持一致）
        return [parentarr, dir_id];
        
    } catch (error) {
        console.error('upload_file_to_path 错误:', error);
        throw error;
    }
}
async function create_dir_to_path(parentarr, now_id) {
    try {
        const result = await callAndroidMethod(window.backServer,'create_dir_to_path', {
            parentarr: parentarr,
            now_id: now_id
        });

        return result.dir_id;
    } catch (error) {
        await alert_fix(error)
        return null;
    }
}
//----------------------------------------
function menu_upload_file(){
	document.getElementById("pictureinput").click();
}
function menu_camerainput(){
	document.getElementById("camerainput").click();
}
// 获取文件数据
async function getFileData(parentpath_arr, name, table) {
    try {
        const result = await callAndroidMethod(window.backServer,'getFileData', {
            parentpath_arr: parentpath_arr,
            fileName: name,
            table: table
        });
        return result;
    } catch (error) {
        console.error('获取文件数据失败:', error);
        return null;
    }
}
// 根据ID获取目录索引
async function get_index_by_id(id) {
    try {
		let obj = {id:id}
		if(cover_display){
			obj.cover = true;
		}else{
			obj.cover = false;
		}
        const result = await callAndroidMethod(window.backServer,'get_index_by_id', obj);
        const { dir_info } = result;
        
        // 清空全局数组并填充新数据
        file_index_arr.length = 0;
        dir_index_arr.length = 0;
        
        for (let item of result.file_index_arr) {
            file_index_arr.push(item);
        }
        for (let item of result.dir_index_arr) {
            dir_index_arr.push(item);
        }
        
		open_dir.id = dir_info.id;
		open_dir.parent_dir_id = dir_info.parent_dir_id;
		open_dir.name = dir_info.name;
		open_dir.dirsortmode = (dir_info && dir_info.dirsortmode) || 2;
		open_dir.filesortmode = (dir_info && dir_info.filesortmode) || 2;
        return true;
    } catch (error) {
        console.error('获取目录索引失败:', error);
        return false;
    }
}

// 创建文件夹
async function createFolder(name, parentId) {
    try {
        const result = await callAndroidMethod(window.backServer,'createFolder', {
            name: name,
            parentId: parentId
        });

        return result;
    } catch (error) {
        if (error === '文件夹已经存在') {
            await alert_fix("文件夹已经存在");
        } else {
            console.error('创建文件夹失败:', error);
        }
        return null;
    }
}

//新建文件夹
async function menui_creat_dir(){
	const name = await prompt_fix("请输入名称");
	if (name=="") {
	  await alert_fix("文件名不能为空！");
	  return;
	}
	if (!name) {
	  return;
	}
	// 定义非法字符的正则表达式
	const invalidChars = /[\/\\\?\%\*\:\|\\"<>\"]/;
	// 检查名称是否包含非法字符
	if (invalidChars.test(name)) {
		await alert_fix("文件名不能有非法字符！");
		return ; // 包含非法字符，返回false
	}
	try {
	  const newFolder = await createFolder(name, open_dir.id);
		if(newFolder){
			 let dir_sort_mode  = open_dir.dirsortmode
			insertobj(dir_sort_mode,newFolder)
		}
	} catch (error) {
	  console.error("创建文件夹时出错：", error);
	}
}
// 删除文件/文件夹
async function deleteByPaths(map) {
    if (!await confirm_fix("确定要删除所选的文件吗？")) {
        return false;
    }
    window.updata_load_info('删除中...');
    document.getElementById("loadingoverlay").style.display = "flex";
    let success = false;
    const items = Array.from(map).map(([name, { type }]) => ({ name, type }));
    
    try {
        await callAndroidMethod(window.backServer, 'delete_items', {
            items: items,
            parentId: open_dir.id
        });
		let table1tbody = document.getElementById('table1tbody');
		let scrollTop = table1tbody.scrollTop||0;
        await updateTable(open_dir.id);
		table1tbody.scrollTop = scrollTop;
        await alert_fix("删除成功！");
        success = true;
        
    } catch (error) {
        console.error('删除失败:', error);
        await alert_fix("删除失败：" + error);
    }
    document.getElementById("loadingoverlay").style.display = "none";
    window.updata_load_info('');
    return success;
}
async function deletefileByid(file_id) {
    window.updata_load_info('删除中...');
    document.getElementById("loadingoverlay").style.display = "flex";
    
    let success = false;
    try {
        await callAndroidMethod(window.backServer, 'delete_file_by_id', { file_id: file_id });
		let table1tbody = document.getElementById('table1tbody');
		let scrollTop = table1tbody.scrollTop||0;
        await updateTable(open_dir.id);
		table1tbody.scrollTop = scrollTop;		
        await alert_fix("删除成功！");
        success = true;
    } catch (error) {
        console.error('删除失败:', error);
        await alert_fix("删除失败：" + error);
        success = false;
    }

    document.getElementById("loadingoverlay").style.display = "none";
    window.updata_load_info('');

    return success;
}
// 重命名
async function rename(arr) {
    if (selectmap.size === 1) {
        const oldfilename = selectmap.keys().next().value;
        const select_obj = selectmap.get(oldfilename);
		const type = select_obj.type;
		document.getElementById('prompt_input').value = oldfilename;
        const newName = await prompt_fix("请输入文件夹名称", oldfilename);
        
        if (newName === "") {
            await alert_fix("文件名不能为空！");
            return;
        }
        
        if (!newName) {
            return;
        }
        
        // 定义非法字符的正则表达式
        const invalidChars = /[\/\\\?\%\*\:\|\\"<>\"]/;
        
        // 检查名称是否包含非法字符
        if (invalidChars.test(newName)) {
            await alert_fix("文件名不能有非法字符！");
            return;
        }
        
        try {
            // 调用后端重命名API
            await callAndroidMethod(window.backServer,'rename_item', {
                oldName: oldfilename,
                newName: newName,
                type: type,
                parentId: open_dir.id
            });
            
            // 重命名成功，刷新表格
			let table1tbody = document.getElementById('table1tbody');
			let scrollTop = table1tbody.scrollTop||0			
            await updateTable(open_dir.id);
			table1tbody.scrollTop = scrollTop;
            // 清空选择
            selectmap.clear();
            document.getElementById("allcheck").checked = false;
            
        } catch (error) {
            // 显示错误信息
            await alert_fix(error || "重命名失败");
        }
    } else {
        await alert_fix("请选择一个文件或文件夹进行重命名");
    }
}
//移动函数
async function moveall(source_arr, map, target_arr) {
	let nowsource_arr = source_arr.slice();
	let nowtarget_arr = target_arr.slice();
	let sourcePath = nowsource_arr.join('/') + '/';
	let targetPath = nowtarget_arr.join('/') + '/';
	
	if (sourcePath === targetPath) {
		return;
	}
	
	let fileseq = 0;
	let filecount = map.size;
	
	document.getElementById("loadingoverlay").style.display = "flex"
	window.Android.startwakelock();
	try {
		// 转换map为数组格式
		const items = Array.from(map).map(([name, {type}]) => ({ name, type }));
		
		// 先检查路径冲突
		for (const item of items) {
			if (item.type === 'dir') {
				let path1 = sourcePath + item.name + '/';
				let path2 = targetPath + item.name;
				if (path2.indexOf(path1) === 0) {
					await alert_fix('目标路径不能在源路径中');
					document.getElementById("loadingoverlay").style.display = "none";
					window.Android.endwakelock();						
					return;
				}
			}
		}
		
		// 逐个移动项目
		for (const item of items) {
			fileseq++;
			document.getElementById("loadingmessage").innerText = 
				"移动中..." + String(fileseq) + '/' + String(filecount);
			
			try {
				await callAndroidMethod(window.backServer,'move_item', {
					item: item,
					sourcePath: nowsource_arr,
					targetPath: nowtarget_arr
				});
			} catch (error) {
				// 检查是否需要用户确认
				if (error && typeof error === 'object' && error.needConfirm) {
					// 需要用户确认覆盖
					if (await confirm_fix(error.message)) {
						// 用户确认覆盖，重新请求
						try {
							await callAndroidMethod(window.backServer,'move_item', {
								item: item,
								sourcePath: nowsource_arr,
								targetPath: nowtarget_arr,
								forceOverwrite: true
							});
							await alert_fix('移动完成！');
						} catch (confirmError) {
							console.error('移动失败:', confirmError);
							await alert_fix('移动失败: ' + confirmError);
						}
					}
					// 用户拒绝覆盖，跳过这个项目
				} else {
					console.error('移动失败:', error);
					await alert_fix('移动失败: ' + error);
				}
			}
		}
		await alert_fix('移动完成！');		
	} catch (error) {
		console.error('移动过程中发生错误:', error);
		await alert_fix('移动失败: ' + error);
	}
	document.getElementById("loadingoverlay").style.display = "none";
	window.Android.endwakelock();	
}
//复制函数
async function copyall(source_arr, map, target_arr) {
    let nowsource_arr = source_arr.slice();
    let nowtarget_arr = target_arr.slice();
    let sourcePath = nowsource_arr.join('/') + '/';
    let targetPath = nowtarget_arr.join('/') + '/';
    
    if (sourcePath === targetPath) {
        return;
    }
    let fileseq = 0;
    let filecount = map.size;
	
    document.getElementById("loadingoverlay").style.display = "flex"
    window.Android.startwakelock();
    document.getElementById("loadingmessage").innerText = "复制中..." + String(fileseq) + '/' + String(filecount);
    
    for (const [name, value] of map) {
        fileseq++;
        let type = value.type;
        let path1 = sourcePath + name + '/';
        let path2 = targetPath + name;
        
        if (path2.indexOf(path1) == 0) {
            await alert_fix('目标路径不能在源路径中');
            continue;
        }
        
        try {
            // 调用后端复制API
            await callAndroidMethod(window.backServer,'copy_item', {
                name: name,
                type: type,
                sourceArray: nowsource_arr,
                targetArray: nowtarget_arr
            });
            
        } catch (error) {
            // 检查是否需要用户确认
            if (error && typeof error === 'object' && error.needConfirm) {
                // 需要用户确认是否覆盖
                if (await confirm_fix(error.message)) {
                    // 用户确认覆盖，重新调用API
                    try {
                        await callAndroidMethod(window.backServer,'copy_item', {
                            name: name,
                            type: type,
                            sourceArray: nowsource_arr,
                            targetArray: nowtarget_arr,
                            forceOverwrite: true
                        });
                    } catch (retryError) {
                        await alert_fix(`复制 ${name} 失败: ${retryError}`);
                    }
                }
            } else {
                await alert_fix(`复制 ${name} 失败: ${error}`);
            }
        }
        
        document.getElementById("loadingmessage").innerText = "复制中..." + String(fileseq) + '/' + String(filecount);
    }
    
    document.getElementById("loadingoverlay").style.display = "none";
    window.Android.endwakelock();
}
function exportItems(ids) {
  if (!Array.isArray(ids) || ids.length === 0) {
    return Promise.reject("请提供一个有效的ID数组。");
  }

  const data = {
    ids: ids
  };
  return callAndroidMethod(window.backServer,'export_items', data);
}
// 根据ID获取数据
async function get_data_by_id(id, table) {
	if(!id){
		return null;
	}
    try {
        const result = await callAndroidMethod(window.backServer,'get_data_by_id', {
            id: id,
            table: table
        });
        return result;
    } catch (error) {
        console.error('get_data_by_id 错误:', error);
        return null;
    }
}

// 其他保持不变的函数
function wrapBlobAsFile(blob, filename, type = 'image/jpeg') {
    return new File([blob], filename, { type: type, lastModified: Date.now() });
}

//排序函数
function downsort(arr, property) {
    return arr.sort((a, b) => {
        if (a[property] < b[property]) return 1;
        if (a[property] > b[property]) return -1;
        return 0;
    });
}

function upsort(arr, property) {
    return arr.sort((a, b) => {
        if (a[property] < b[property]) return -1;
        if (a[property] > b[property]) return 1;
        return 0;
    });
}
function insertobj(mode,obj){
	let property = "name";
	let ascending = true; 
	switch(mode) {
	  case 1:
		property = "name"
		ascending = true;
		break;
	  case 2:
		property = "name"
		ascending = false;
		break;	
	  case 3:
		property = "seqnumber"
		ascending = true;
		break;	
	  case 4:
		property = "seqnumber"	
		ascending = false;
		break;
	  case 5:
		property = "createtime"	
		ascending = true;
		break;	  
	  case 6:
		property = "createtime"	
		ascending = false;
		break;
	  case 7:
		property = "extension"
		ascending = true;
		if(obj.type === "dir"){
			property = "name"
		}
		break;		
	  case 8:
		property = "extension"	
		ascending = false;
		if(obj.type === "dir"){
			property = "name"
		}
		break;		 
	}
	// 比较函数，根据升降序选择不同的比较方式
	const comparison = ascending ? (a, b) => a[property] < b[property]: (a, b) => a[property] > b[property];
	// 使用二分查找找到插入位置
	let left = 0;
	console.log('cs')
	if(obj.type == 'dir'){
		let right = dir_index_arr.length - 1;
		let position = dir_index_arr.length;  // 默认插入到数组末尾
		while (left <= right) {
			const mid = Math.floor((left + right) / 2);
			if (comparison(dir_index_arr[mid], obj)) {
				left = mid + 1;  // 如果 obj 比 mid 小，继续在右边查找
			} else {
				right = mid - 1;  // 如果 obj 比 mid 大，继续在左边查找
			}
		}
		// 使用 splice 在找到的插入位置插入新对象
		dir_index_arr.splice(left, 0, obj);
		insert_tr(obj,left)
	}else if(obj.type == 'file'){
		let right = file_index_arr.length - 1;
		let position = file_index_arr.length;  // 默认插入到数组末尾
		while (left <= right) {
			const mid = Math.floor((left + right) / 2);
			if (comparison(file_index_arr[mid], obj)) {
				left = mid + 1;  // 如果 obj 比 mid 小，继续在右边查找
			} else {
				right = mid - 1;  // 如果 obj 比 mid 大，继续在左边查找
			}
		}
		// 使用 splice 在找到的插入位置插入新对象
		file_index_arr.splice(left, 0, obj);
		insert_tr(obj,left)
	}
}
async function insert_tr(obj,seq){
	const row = document.createElement("div");
	if(obj.type === 'file'){
		row.className = 'tabletr'
		const nameCell = document.createElement("div");
		nameCell.className = 'td1'
		let iconimg = document.createElement('img');
		if(isImageFile(obj.name)){
			let real_icon_name = obj.real_icon_name;
			if(real_icon_name){
				iconimg.onerror = function(){
					iconimg.src = file_icon_url;
				}
				iconimg.src = "https://appassets.androidplatform.net/icons/"+real_icon_name;
			}else{
				iconimg.src = file_icon_url;
			}
		}else{
			iconimg.src = file_icon_url;
		}
		iconimg.className = 'icon_img'
		iconimg.dataset.name = obj.name
		iconimg.dataset.id = obj.id
		iconimg.dataset.real_icon_name = obj.real_icon_name;
		let icon_div = document.createElement("div");			
		icon_div.className = 'icon_div'
		icon_div.style.width = "150px";
		icon_div.style.height = "150px";
		icon_div.appendChild(iconimg);
		nameCell.appendChild(icon_div);
        const textdiv = document.createElement("div");
		textdiv.className = 'name_div'
		textdiv.style.maxHeight = "150px";
		textdiv.textContent = obj.name;
        nameCell.appendChild(textdiv);
		row.appendChild(nameCell);
		nameCell.dataset.type = 'file'
		nameCell.dataset.name = obj.name
		nameCell.dataset.id = obj.id
		nameCell.dataset.real_file_name = obj.real_file_name
		nameCell.dataset.extension = obj.extension||""
		const checkboxCell = document.createElement("div");
		checkboxCell.className = 'td2'
		const checkbox = document.createElement("input");
		checkbox.type = "checkbox";
		checkbox.className = "row-check";  // 为每个checkbox添加class以便全选功能使用
		checkboxCell.appendChild(checkbox);
		row.appendChild(checkboxCell);
		let tbody = document.getElementById('table1tbody');	
		let dir_count = dir_index_arr.length
		if(seq+dir_count>=tbody.children.length||seq<0){
			tbody.appendChild(row);
		}else{
			let targetRow = tbody.children[seq+dir_count];
			tbody.insertBefore(row,targetRow);
		}
		observer.observe(row)
	}else if(obj.type === "dir"){
		row.className = 'tabletr'
		const nameCell = document.createElement("div");
		nameCell.className = 'td1'
		let icon_div = document.createElement("div");
		const textdiv = document.createElement("div");
		textdiv.className = 'name_div'		
		if(obj.cover){
			let book_cover = document.createElement('img');
			book_cover.className = 'book_cover';
			book_cover.src = book_icon_url;
			icon_div.style.width = "150px";
			icon_div.style.height = "150px";
			iconimg.className = 'icon_img';
			iconimg.src = file_icon_url;
			iconimg.dataset.name = obj.name;
			iconimg.dataset.id = obj.id;
			iconimg.dataset.cover = obj.cover
			icon_div.className = 'icon_div';
			icon_div.appendChild(iconimg);
			icon_div.appendChild(book_cover);
			nameCell.appendChild(icon_div);	
			textdiv.style.maxHeight = "150px";
			observer.observe(row)
		}else{
			let iconimg = document.createElement('img');
			iconimg.className = 'icon_img';
			iconimg.src = dir_icon_url;
			iconimg.dataset.name = obj.name;
			iconimg.dataset.id = obj.id;
			icon_div.className = 'icon_div';
			icon_div.style.width = "48px";
			icon_div.style.height = "48px";
			icon_div.appendChild(iconimg);	
			nameCell.appendChild(icon_div);	
			textdiv.style.maxHeight = "48px";	
		}
		textdiv.textContent = obj.name;
        nameCell.appendChild(textdiv);
		row.appendChild(nameCell);
		nameCell.dataset.type = 'dir'
		nameCell.dataset.name = obj.name
		nameCell.dataset.id = obj.id
		nameCell.dataset.filesortmode = obj.filesortmode || 2
		nameCell.dataset.dirsortmode = obj.dirsortmode || 2
		nameCell.dataset.createtime = obj.createtime
		const checkboxCell = document.createElement("div");
		checkboxCell.className = 'td2'
		const checkbox = document.createElement("input");
		checkbox.type = "checkbox";
		checkbox.className = "row-check";  // 为每个checkbox添加class以便全选功能使用
		checkboxCell.appendChild(checkbox);
		row.appendChild(checkboxCell);
		let tbody = document.getElementById('table1tbody');
		if(seq<0){
			seq = dir_index_arr_.length-1
		}		
		if(seq>=tbody.children.length){
			tbody.appendChild(row);
		}else{
			let targetRow = tbody.children[seq];
			if(targetRow){
				tbody.insertBefore(row,targetRow);
			}else{
				tbody.appendChild(row);
			}			
		}
	}
}
function sortindex(arr, type, mode) {
    let property = "name";
    switch (mode) {
        case 1:
            property = "name"
            return upsort(arr, property);
        case 2:
            property = "name"
            return downsort(arr, property);
        case 3:
            property = "seqnumber"
            return upsort(arr, property);
        case 4:
            property = "seqnumber"
            return downsort(arr, property);
        case 5:
            property = "createtime"
            return upsort(arr, property);
        case 6:
            property = "createtime"
            return downsort(arr, property);
        case 7:
            property = "extension"
            if (type === "dir") {
                property = "name"
            }
            return upsort(arr, property);
        case 8:
            property = "extension"
            if (type === "dir") {
                property = "name"
            }
            return downsort(arr, property);
    }
}
function setupIntersectionObserver(tr_arr) {
    if (observer) {
        observer.disconnect();
    }
    
    let table1tbody = document.getElementById('table1tbody');
    
    observer = new IntersectionObserver((entries) => {
        entries.forEach(async entry => {
            const tr = entry.target;
            let nameCell = tr.querySelector('.td1');
            let img = nameCell.querySelector('.icon_div').querySelector('.icon_img');
            if (nameCell.dataset.type === 'file') {
                if (entry.isIntersecting) {  // 进入视口
                    if (!img.dataset.load && isImageFile(img.dataset.name)) {
                        img.dataset.load = true;
                        
                        let real_icon_name = img.dataset.real_icon_name;
                        if (real_icon_name) {
                            img.onerror = function() {
                                img.src = file_icon_url;
                            };
                            img.src = "https://appassets.androidplatform.net/icons/" + real_icon_name;
                        } else {
                            img.src = file_icon_url;
                        }
                    }
                } else {  // 离开视口
                    if (img.dataset.load && isImageFile(img.dataset.name)) {
                        img.src = file_icon_url;
                        delete img.dataset.load;
                    }
                }
            }else if(img.dataset.cover){
                if (entry.isIntersecting) {  // 进入视口
					img.dataset.load = true;
					let cover = img.dataset.cover;
					if (cover) {
						img.onerror = function() {
							img.src = file_icon_url;
						};
						img.src = "https://appassets.androidplatform.net/icons/" + cover;
					} else {
						img.src = file_icon_url;
					}
                } else {  // 离开视口
                    if (img.dataset.load && img.dataset.cover) {
                        img.src = file_icon_url;
                        delete img.dataset.load;
                    }
                }
			}
        });
    }, {
        root: table1tbody,   // 改为监听 table1tbody 容器
        rootMargin: '300px', // 减少预加载距离
        threshold: 0
    });
    
    tr_arr.forEach(tr => observer.observe(tr));
}
async function updateTable(id,have_data) {
    const table = document.getElementById("indextable");
    const tbody = document.getElementById("table1tbody");
    tbody.innerHTML = "";
    document.getElementById("allcheck").checked = false;
	if(!have_data){
		let is_ok = await get_index_by_id(id)
		if(!is_ok){
			return;
		}
	}
    // 如果有菜单关闭函数，调用它们
    if (typeof close_opening_menu !== 'undefined') {
        close_opening_menu(menu_set);
        close_opening_menu(hide_menu_set);
    }
    let tempdiv = document.createDocumentFragment();
    // 添加目录行
	let tr_arr = [];
    for (let item of dir_index_arr) {
        const row = document.createElement("div");
        row.className = 'tabletr';
        const nameCell = document.createElement("div");
        nameCell.className = 'td1';
		let icon_div = document.createElement("div");
        const textdiv = document.createElement("div");		
		textdiv.className = 'name_div'		
		if(item.cover){
			let book_cover = document.createElement('img');
			book_cover.className = 'book_cover';
			book_cover.src = book_icon_url;
			icon_div.style.width = "150px";
			icon_div.style.height = "150px";
			
			let iconimg = document.createElement('img');
			iconimg.className = 'icon_img';
			iconimg.src = file_icon_url;
			iconimg.dataset.name = item.name;
			iconimg.dataset.id = item.id;
			iconimg.dataset.cover = item.cover;
			icon_div.className = 'icon_div';
			icon_div.appendChild(iconimg);
			icon_div.appendChild(book_cover);
			nameCell.appendChild(icon_div);
			textdiv.style.maxHeight = "150px";			
			tr_arr.push(row);
		}else{
			let iconimg = document.createElement('img');
			iconimg.className = 'icon_img';
			iconimg.src = dir_icon_url;
			iconimg.dataset.name = item.name;
			iconimg.dataset.id = item.id;
			icon_div.className = 'icon_div';
			icon_div.style.width = "48px";
			icon_div.style.height = "48px";
			icon_div.appendChild(iconimg);	
			nameCell.appendChild(icon_div);
			textdiv.style.maxHeight = "48px";			
		}
		textdiv.textContent = item.name;
        nameCell.appendChild(textdiv);
        row.appendChild(nameCell);
        nameCell.dataset.type = 'dir';
        nameCell.dataset.id = item.id;
        nameCell.dataset.name = item.name;
        nameCell.dataset.createtime = item.createtime;
        const checkboxCell = document.createElement("div");
        checkboxCell.className = 'td2';
        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.className = "row-check";
        checkboxCell.appendChild(checkbox);
        row.appendChild(checkboxCell);
        tempdiv.appendChild(row);
    }
    // 添加文件行
    for (let item of file_index_arr) {
        const row = document.createElement("div");
        tr_arr.push(row);
        row.className = 'tabletr';
        const nameCell = document.createElement("div");
        nameCell.className = 'td1';
        let iconimg = document.createElement('img');
        iconimg.src = file_icon_url;
        iconimg.className = 'icon_img';
        iconimg.dataset.name = item.name;
        iconimg.dataset.id = item.id;
		iconimg.dataset.real_icon_name = item.real_icon_name||"";
		let icon_div = document.createElement("div");
		icon_div.className = 'icon_div';
		icon_div.style.width = "150px";
		icon_div.style.height = "150px";		
		icon_div.appendChild(iconimg);
        nameCell.appendChild(icon_div);
        
        const textdiv = document.createElement("div");
		textdiv.className = 'name_div'
		textdiv.style.maxHeight = "150px";
		textdiv.textContent = item.name;
        nameCell.appendChild(textdiv);
        row.appendChild(nameCell);
        nameCell.dataset.type = 'file';
        nameCell.dataset.name = item.name;
        nameCell.dataset.id = item.id;
		nameCell.dataset.real_file_name = item.real_file_name
        nameCell.dataset.extension = item.extension || "";	
        const checkboxCell = document.createElement("div");
        checkboxCell.className = 'td2';
        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.className = "row-check";
        checkboxCell.appendChild(checkbox);
        row.appendChild(checkboxCell);
        tempdiv.appendChild(row);
    }
    
    tbody.appendChild(tempdiv);
    
    if (typeof selectmap !== 'undefined') {
        selectmap.clear();
    }
    
    if (typeof setupIntersectionObserver !== 'undefined') {
        setupIntersectionObserver(tr_arr);
    }
}
//任务委托
async function open_dir_or_file(td){
	if(ui_lock){
		return;
	}
	ui_lock = true;
	if(td.dataset.type =='dir'){
		let dirname = td.textContent
		let table1tbody = document.getElementById('table1tbody');
		let scrollTop = table1tbody.scrollTop||0
		top_map.set(当前文件夹路径_arr.join('/')+'/',scrollTop)
		当前文件夹路径_arr.push(dirname)
		document.getElementById("fileinput").value = 当前文件夹路径_arr.join('/')+'/'
		await updateTable(td.dataset.id);
		document.getElementById('fileinput').value = 当前文件夹路径_arr.join('/')+'/'
		table1tbody.scrollTop = 0
	}else{
		if(isImageFile(td.dataset.name)){
			await load_img(td.dataset.name)
		}
	}
	ui_lock = false;
}
function select_click(checkbox){
	const isChecked = checkbox.checked; 
	const row = checkbox.closest(".tabletr");
	const nameCell = row.querySelector(".td1");
	let name = nameCell.dataset.name
	if(isChecked){
		selectmap.set(name,{type:nameCell.dataset.type,id:nameCell.dataset.id,real_file_name:nameCell.dataset.real_file_name})
	}else{
		selectmap.delete(name)
	}
}
function select_all_click(){
	let checkboxes = document.getElementById('table1tbody').querySelectorAll('.row-check');
	checkboxes.forEach(function(checkbox) {
		checkbox.checked = document.getElementById('allcheck').checked;
		if(checkbox.checked){
			const row = checkbox.closest(".tabletr");
			const nameCell = row.querySelector(".td1");
			let name = nameCell.dataset.name
			selectmap.set(name,{type:nameCell.dataset.type,id:nameCell.dataset.id,real_file_name:nameCell.dataset.real_file_name})
		}
	});
	if(!document.getElementById('allcheck').checked){
		selectmap.clear();
	}
}

//app前端操作
window.updata_load_info = function(str){
	document.getElementById("loadingmessage").innerText = str;
}

async function back(){
	if(ui_lock){
		return;
	}
	if(当前文件夹路径_arr.length>1){
		ui_lock = true;	
		top_map.delete(当前文件夹路径_arr.join('/')+'/')
		当前文件夹路径_arr.pop()
		let fileinput = document.getElementById('fileinput');
		let path = 当前文件夹路径_arr.join('/')+'/'
		fileinput.value = path
		let fileName = 当前文件夹路径_arr[当前文件夹路径_arr.length-1]
		await updateTable(open_dir.parent_dir_id)
		let table1tbody = document.getElementById('table1tbody');
		table1tbody.scrollTop = top_map.get(path)||0
		ui_lock = false;
	}	
}
async function gopath(){
	if(ui_lock){
		return;
	}
	ui_lock = true;	
	let path_arr = document.getElementById('fileinput').value.split('/')
	let table1tbody = document.getElementById('table1tbody');
	let scrollTop = table1tbody.scrollTop
	top_map.set(当前文件夹路径_arr.join('/')+'/',scrollTop)
	if(!path_arr[path_arr.length-1]&&path_arr.length>1){
		path_arr.pop()
	}
	let data = await get_data_by_arr(path_arr,'dir')
	if(!data){
		return;
	}
	await updateTable(data.id)
	当前文件夹路径_arr = path_arr.slice();
	table1tbody.scrollTop = 0
	ui_lock = false;	
}
async function updatesortmode(dirsortmode, filesortmode) {
	let obj = {
            dir_sort_mode: dirsortmode,
            file_sort_mode: filesortmode,
            dir_id: open_dir.id
        }
		let file_sort_check = document.getElementById('file_sort_check');
		let dir_sort_check = document.getElementById('dir_sort_check');
		if(!file_sort_check.checked&&!dir_sort_check.checked){
			return;
		}		
    try {
		if(await confirm_fix("是否应用文件夹内所有文件夹？")){
			obj.apply_to_all = true;
		}else{
			obj.apply_to_all = false;
		}
		if(file_sort_check.checked){
			obj.update_file_sort = true
		}else{
			obj.update_file_sort = false
		}
		if(dir_sort_check.checked){
			obj.update_dir_sort = true
		}else{
			obj.update_dir_sort = false;
		}
        const result = await callAndroidMethod(window.backServer,'update_sort_mode', obj);
        return result;
    } catch (error) {
        console.error('更新排序模式失败:', error);
        throw error;
    }
}
let upload_path_arr_Promise = null;
