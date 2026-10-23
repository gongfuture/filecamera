
function get_key(key_arr){
    const params = {
        key_arr:key_arr
    };
    return callAndroidMethod(window.FilePicker, 'readConfig', params);	
	
}
async function open_key_manage_page(){
	document.getElementById('key_manage_div').classList.add('expanded');
	let obj = await get_key(["serverUrl", "serverKey", "gaodeWebKey"]);
	document.getElementById('serverUrlInput').value = (obj && obj.serverUrl != null) ? obj.serverUrl : "";
	document.getElementById('serverKeyInput').value = (obj && obj.serverKey != null) ? obj.serverKey : "";
	document.getElementById('gaodeWebKeyinput').value = (obj && obj.gaodeWebKey != null) ? obj.gaodeWebKey : "";
}
function close_key_manage_page(){
	document.getElementById('key_manage_div').classList.remove('expanded');
	document.getElementById('serverUrlInput').value = "";
	document.getElementById('serverKeyInput').value = "";
	document.getElementById('gaodeWebKeyinput').value = "";
}
function write_key_config(obj_arr){
    const params = {
        obj_arr: obj_arr
    };
    return callAndroidMethod(window.FilePicker, 'writeConfig', params);	
}
document.getElementById('key_main_view').addEventListener('click',async(event)=>{
	if(event.target.id === 'key_write_button'){
		const serverUrl = document.getElementById('serverUrlInput').value.trim();
		const serverKey = document.getElementById('serverKeyInput').value.trim();
		const gaodeWebKey = document.getElementById('gaodeWebKeyinput').value.trim();
		await write_key_config([
			{key:"serverUrl", value:serverUrl},
			{key:"serverKey", value:serverKey},
			{key:"gaodeWebKey", value:gaodeWebKey}
		]);
		// 保存后立即刷新内存中的服务器配置，路线管理页的“自动更新/立即更新”随之更新。
		await load_server_config();
		await alert_fix('配置已保存')
	}	
})
