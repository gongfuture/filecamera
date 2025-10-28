
function get_key(key_arr){
    const params = {
        key_arr:key_arr
    };
    return callAndroidMethod(window.FilePicker, 'readConfig', params);	
	
}
async function open_key_manage_page(){
	document.getElementById('key_manage_div').classList.add('expanded');
	 let obj = await get_key(["gaodeWebKey"])
	 if(obj&&obj["gaodeWebKey"]){
		document.getElementById('gaodeWebKeyinput').value = obj["gaodeWebKey"]
	 }else{
		 document.getElementById('gaodeWebKeyinput').value = "";
	 }
}
function close_key_manage_page(){
	document.getElementById('key_manage_div').classList.remove('expanded');
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
		let gaodeWebKeyValue = document.getElementById('gaodeWebKeyinput').value.trim();
		await write_key_config([{key:"gaodeWebKey",value:gaodeWebKeyValue}])
		await alert_fix('秘钥已保存')
	}	
})