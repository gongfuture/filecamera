let menu_set = new Set
let comfire_menu_set = new Set
let hide_menu_set = new Set
let win_set = new Set(); //用于存放打开窗口的set
function alert_fix(content){
	return new Promise((resolve)=>{
		let win_modal = document.getElementById('win_modal');
		let alert_div = document.getElementById('alert_div')
		let alert_text_div = document.getElementById('alert_text_div')
		alert_text_div.textContent = content;
		alert_div.style.display = 'flex';
		win_modal.style.display = 'flex';
		win_set.add('alert');
		function alert_confirm(event){
			if(event.target.closest('#alert_confirm_button')){
				alert_text_div.textContent = "";
				alert_div.style.display = "none"
				win_set.delete('alert');
				if(win_set.size === 0){
					win_modal.style.display = 'none';
				}
				alert_div.removeEventListener('click', alert_confirm);
				resolve()
			}
		}
		alert_div.addEventListener('click',alert_confirm)
	})
}
function confirm_fix(content){
	return new Promise((resolve)=>{
		let win_modal = document.getElementById('win_modal');
		let confirm_text_div = document.getElementById('confirm_text_div')		
		let confirm_div = document.getElementById('confirm_div')
		confirm_text_div.textContent = content;
		confirm_div.style.display = 'flex';
		win_modal.style.display = 'flex';
		win_set.add('confirm')
		function confirm_confirm(event){
			if(event.target.closest('#confirm_confirm_button')){
				confirm_text_div.textContent = "";
				confirm_div.style.display = "none"
				win_set.delete('confirm')
				if(win_set.size === 0){
					win_modal.style.display = 'none';
				}
				confirm_div.removeEventListener('click', confirm_confirm);
				resolve(true)
			}else if(event.target.closest('#confirm_cance_button')){
				confirm_text_div.textContent = "";
				confirm_div.style.display = "none"
				win_set.delete('confirm');
				if(win_set.size === 0){
					win_modal.style.display = 'none';
				}
				confirm_div.removeEventListener('click', confirm_confirm);
				resolve(false)
			}
		}
		confirm_div.addEventListener('click',confirm_confirm)
	})
}
function prompt_fix(content){
	return new Promise((resolve)=>{
		let win_modal = document.getElementById('win_modal');
		let prompt_text_div = document.getElementById('prompt_text_div')	
		let prompt_div = document.getElementById('prompt_div')
		prompt_text_div.textContent = content;
		prompt_div.style.display = 'flex';
		win_modal.style.display = 'flex';
		win_set.add('prompt')
		function prompt_confirm(event){
			if(event.target.closest('#prompt_confirm_button')){
				prompt_text_div.textContent = "";
				prompt_div.style.display = "none"
				win_set.delete('prompt')
				if(win_set.size === 0){
					win_modal.style.display = 'none';
				}
				prompt_div.removeEventListener('click', prompt_confirm);
				let input_value = document.getElementById('prompt_input').value
				resolve(input_value)
				document.getElementById('prompt_input').value = "";
			}else if(event.target.closest('#prompt_cance_button')){
				prompt_text_div.textContent = "";
				prompt_div.style.display = "none"
				win_set.delete('prompt');
				if(win_set.size === 0){
					win_modal.style.display = 'none';
				}
				prompt_div.removeEventListener('click', prompt_confirm);
				resolve(null)
				document.getElementById('prompt_input').value = "";
			}
		}
		prompt_div.addEventListener('click',prompt_confirm)
	})
}

function close_opening_menu(set) {
	 for (let dom of set) {
		dom.classList.remove('expanded'); // 移除展开样式
	}
	set.clear(); // 清空记录
}

function expand_menu(dom,set){
	let old_size = set.size
	set.add(dom)
	let new_size = set.size
	if(old_size<new_size){
		dom.classList.add('expanded');
	}
}
document.getElementById('index_div').addEventListener('click', async (event) => {
	const menu = document.getElementById('menu');
	const menu2 = document.getElementById('menu2');
	const menuspan = document.getElementById('menuspan'); // 获取 menuspan 元素
	const menu_icon = document.getElementById('menu_icon');
	const sortbutton = document.getElementById('sortbutton');
	const sortmenu = document.getElementById('sortmenu');
	const table1 = document.getElementById('indextable');
	if(event.target.id !='menuspan'&&!event.target.closest('#menu2')){
		close_opening_menu(menu_set);
	}
	if(event.target.id == 'menuspan'){
		if(menu.classList.contains('expanded')){
			close_opening_menu(menu_set);
		}else{
			close_opening_menu(menu_set);
			expand_menu(menu,menu_set);
		}
	}else if(event.target.id == 'gospan'){
		close_opening_menu(menu_set);
		gopath()
	}else if(event.target.id == 'backspan'){
		close_opening_menu(menu_set);
		back()
	}else if(event.target.id == 'cameraspan'){
		close_opening_menu(menu_set);
		//menu_camerainput()
		let uri_arr = await nativeOpenFilePicker(true,'customcamera')
		if (!uri_arr ||uri_arr.length === 0) {
			return;
		}
		let filecount = uri_arr.length
		let file = uri_arr[0]
		if(!file){
			return;
		}
		try {
			let file_obj = await uploadfile(file.uri, open_dir.id, file.name);
			let file_sort_mode  = open_dir.filesortmode
			insertobj(file_sort_mode,file_obj)
		} catch (error) {
			await updateTable(open_dir.id);
			await alert_fix("图片上传失败：" + error);
		}
	}else if(event.target.id == 'settingbutton'){
		close_opening_menu(menu_set);
		open_setting_page()
	}else if(event.target.id == 'keymanagebutton'){
		close_opening_menu(menu_set);
		await open_key_manage_page();
	}else if(event.target.id == 'roadbutton'){
		close_opening_menu(menu_set);
		await open_road_page()
	}else if(event.target.closest("#menu")){
		close_opening_menu(menu_set);
		if(event.target.id == "sortbutton"){
			close_opening_menu(menu_set)
			expand_menu(sortmenu,menu_set)
			expand_menu(menu2,menu_set)
			document.getElementById('file_sort_check').checked = true;
			document.getElementById('dir_sort_check').checked = true;		
		}else if(event.target.id == "createdirbutton"){
			await menui_creat_dir()
		}else if(event.target.id == "inputbutton"){
			//menu_upload_file()
			window.Android.startwakelock()			
			let uri_arr = await nativeOpenFilePicker(true,'file')
			if (!uri_arr ||uri_arr.length === 0) {
				window.Android.endwakelock()				
				return;
			}
			let filecount = uri_arr.length
			let fileseq = 0
			
			document.getElementById("loadingoverlay").style.display = "flex"
			document.getElementById("loadingmessage").innerText = "导入中..."+String(fileseq)+'/'+String(filecount); 
			for(let file of uri_arr){
				fileseq++
				if (file) {
				  try {
					await uploadfile(file.uri, open_dir.id, file.name);
				  } catch (error) {
					await alert_fix("图片上传失败：" + error);
				  }
				}
				document.getElementById("loadingmessage").innerText = "导入中..."+String(fileseq)+'/'+String(filecount); 
			}
			document.getElementById("loadingoverlay").style.display = "none";
			
			await updateTable(open_dir.id);
			window.Android.endwakelock()
		}else if(event.target.id == "uploadirbutton"){
			try{
				window.Android.startwakelock()
				let uri_arr = await nativeOpenFilePicker(true,'dir')
				if (!uri_arr ||uri_arr.length === 0) {
					window.Android.endwakelock()					
					return;
				}
				let filecount = uri_arr.filter(item => item.type === "file").length  // 只计算文件数量
				let fileseq = 0
				
				document.getElementById("loadingmessage").innerText = "正在获取文件路径......"
				document.getElementById("loadingoverlay").style.display = "flex"
				document.getElementById("loadingmessage").innerText = "导入中..."+String(fileseq)+'/'+String(filecount);
				let path_id_map = new Map();
				for(let i = 0; i < uri_arr.length; i++){
					let item = uri_arr[i]
					if (item && item.type === "file"){
						fileseq++
						try {
							let path = item.relativePath
							let file_path_arr = path.split('/')
							let name = file_path_arr.pop()
							let dir_id = path_id_map.get(path)
							if(dir_id){
								await uploadfile(item.uri, dir_id, name)
							}else{
								let path_and_id = await upload_file_to_path(item.uri, file_path_arr, name, open_dir.id)
								if(path_and_id){
									path_id_map.set(path,path_and_id[1])
								}
							}
						} catch (error) {
							await alert_fix("文件上传失败：" + error);
						}
						document.getElementById("loadingmessage").innerText = "导入中..."+String(fileseq)+'/'+String(filecount); 
					}
				}
				await updateTable(open_dir.id);	
			}catch(err){
				await alert_fix('导入文件夹出错')
			}
			document.getElementById("loadingoverlay").style.display = "none";			
			window.Android.endwakelock()
		}
	}else if(sortmenu.contains(event.target)){
		close_opening_menu(menu_set)
		const target = event.target;
		let sort_file_path_arr = [];
		for(let i = 0;i<当前文件夹路径_arr.length-1;i++){
			sort_file_path_arr.push(当前文件夹路径_arr[i])
		}
		let dirpath = sort_file_path_arr.join('/')+'/'
		let dir_name = 当前文件夹路径_arr[当前文件夹路径_arr.length-1]
		if (target.tagName === 'LI') {
			let selectsortmode = 1
			switch (target.id) {
				case 'name_sort1':
					selectsortmode = 1
					break;
				case 'name_sort2':
					selectsortmode = 2
					break;
				case 'number_sort1':
					selectsortmode = 3
					break;
				case 'number_sort2':
					selectsortmode = 4
					break;
				case 'time_sort1':
					selectsortmode = 5
					break;
				case 'time_sort2':
					selectsortmode = 6
					break;
				case 'class_sort1':
					selectsortmode = 7
					break;
				case 'class_sort2':
					selectsortmode = 8
					break;
			}
			if(selectsortmode>0){
				if(file_sort_check.checked){
					open_dir.filesortmode = Number(selectsortmode)
				}
				if(dir_sort_check.checked){
					open_dir.dirsortmode = Number(selectsortmode)
				}
				await updatesortmode(open_dir.dirsortmode,open_dir.filesortmode)
				await updateTable(open_dir.id)
			}
		}	
	}else if(event.target.closest(".tabletr")){
		close_opening_menu(menu_set)
		let tabletr = event.target.closest(".tabletr");	
		let cells = tabletr.children;
		if(!cells){
			return;
		}
		if(event.target.closest('.td1')){
			await open_dir_or_file(cells[0])
		}else if(event.target.className == 'row-check') {
			select_click(event.target)
			if(!document.getElementById("comfirediv").classList.contains("expanded")){
				if(selectmap.size == 0){
					close_opening_menu(hide_menu_set)
				}else if(selectmap.size == 1){
					expand_menu(document.getElementById("hidecontrondiv"),hide_menu_set)
					document.getElementById("renamebutton").style.display = 'flex';
				}else{
					expand_menu(document.getElementById("hidecontrondiv"),hide_menu_set)
					document.getElementById("renamebutton").style.display = 'none';		
				}
			}
			
		}
	}else if(event.target.id == "allcheck"){
		close_opening_menu(menu_set)
		select_all_click()
		if(selectmap.size == 0){
			close_opening_menu(hide_menu_set)
		}else if(selectmap.size == 1){
			expand_menu(document.getElementById("hidecontrondiv"),hide_menu_set)
			document.getElementById("renamebutton").style.display = 'flex';
		}else{
			expand_menu(document.getElementById("hidecontrondiv"),hide_menu_set)
			document.getElementById("renamebutton").style.display = 'none';					
		}	
	}else if(document.getElementById("hidecontrondiv").contains(event.target)){
		close_opening_menu(menu_set)
		close_opening_menu(hide_menu_set)
		if(event.target.id == 'detelebutton'){
			deleteByPaths(selectmap)
		}else if(event.target.id == 'renamebutton'){
			rename(当前文件夹路径_arr)
		}else if(event.target.id =='sharebutton'){
			let uri = "content://com.filecamera.apk.fileprovider/private_files/app_data/";
			let uri_arr = [];
			for(let [name,obj] of selectmap){
				if(name&&obj.type === 'file'){
					uri_arr.push({uri:uri+obj.real_file_name,name:name})
				}
			}
			await nativeShareFile(uri_arr,true, 'all')
		}else if(event.target.id == 'movebutton'){
			setcomfiremode("move")
		}else if(event.target.id == 'copybutton'){
			setcomfiremode("copy")
		}else if(event.target.id == 'outbutton'){
			let id_set = new Set()
			for(let [key,value ] of selectmap){
				id_set.add(value.id)
			}
			let id_arr = Array.from(id_set)
			
			document.getElementById("loadingoverlay").style.display = "flex"
			window.updata_load_info("文件导出中......")
			await exportItems(id_arr);
			document.getElementById("loadingoverlay").style.display = "none";
			window.updata_load_info("")
		}
	}else if(document.getElementById("comfirediv").contains(event.target)){
		close_opening_menu(menu_set)
		close_opening_menu(comfire_menu_set)
		if(event.target.id == 'comfirebutton'){
			comfirework()
		}else if(event.target.id == 'cancebutton'){
			cancework()
		}
	}
});
function setcomfiremode(mode){
	comfiremode = mode
	source_arr = 当前文件夹路径_arr.slice();
	workselectmap.clear()
	for (const [key, obj] of selectmap) {
	  workselectmap.set(key, obj);
	}
	close_opening_menu(menu_set)
	expand_menu(comfirediv,comfire_menu_set)
}
function cancework(){
	close_opening_menu(comfire_menu_set)
	comfiremode = ""
	source_arr = []
	workselectmap.clear()
	selectmap.clear()
	document.getElementById('allcheck').checked = false;
	let checkboxes = document.getElementById('indextable').querySelectorAll('.row-check');
	checkboxes.forEach(function(checkbox) {
		checkbox.checked = false;
	});	
}
async function comfirework(){
	let target_arr = 当前文件夹路径_arr.slice()
	if(comfiremode == 'move'){
		window.updata_load_info('移动中...')
		document.getElementById("loadingoverlay").style.display = "flex"
		await moveall(source_arr, workselectmap, target_arr)
	}else if(comfiremode == 'copy'){
		window.updata_load_info('复制中...')
		document.getElementById("loadingoverlay").style.display = "flex"
		await copyall(source_arr, workselectmap, target_arr)
	}
	close_opening_menu(comfire_menu_set)
	let table1tbody = document.getElementById('table1tbody');
	let scrollTop = table1tbody.scrollTop||0;
	await updateTable(open_dir.id);
	table1tbody.scrollTop = scrollTop;
	document.getElementById("loadingoverlay").style.display = "none";
	window.updata_load_info('')
}
document.getElementById("table1tbody").addEventListener('contextmenu', function(e) {
    e.preventDefault();
});

document.getElementById("cover_checkbox").addEventListener('change', async () => {
	let cover_checkbox = document.getElementById("cover_checkbox")
	cover_display = cover_checkbox.checked;
	const configToSave = { display: cover_display };
	localStorage.setItem('cover_display', JSON.stringify(configToSave));
	await updateTable(open_dir.id);
});
