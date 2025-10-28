const fileImg = document.getElementById('fileimg');
const displayFileDiv = document.getElementById('displayfilediv');
let scale = 1;  // 当前缩放比例
let startX = 0, startY = 0;  // 初始触摸位置
let offsetX = 0, offsetY = 0;  // 图片的偏移量（中心点的偏移）
let startDist = 0; // 初始两指距离
let page = 0
let picture_arr = []
let pull_distance_arr = [0,0];
let lastTouchTime = 0;
let lastTouchdistance = 0
let is_double_click = false
// 监听触摸事件
displayFileDiv.addEventListener('touchstart', (event) => {
    // 检测双击
    const currentTime = new Date().getTime();
    const timeDiff = currentTime - lastTouchTime;
    if (timeDiff < 200 && event.touches.length === 1&&is_double_click === false){
		is_double_click = true
		setTimeout(() => {
			is_double_click = false;
		}, 200);
		handlePinchZoom(event) 
    } else {
		lastTouchTime = currentTime; // 更新触摸时间
		if (event.touches.length === 1) {
			// 记录单指拖动时的初始位置
			startX = event.touches[0].pageX;
			startY = event.touches[0].pageY;
		} else if (event.touches.length === 2) {
			// 记录两指缩放时的初始距离
			startDist = Math.sqrt(
				Math.pow(event.touches[0].pageX - event.touches[1].pageX, 2) +
				Math.pow(event.touches[0].pageY - event.touches[1].pageY, 2)
			);
		}
	}
});
displayFileDiv.addEventListener('touchmove', (event) => {
	if (event.touches.length === 1) {
		pull_distance_arr = handleDrag(event); // 单指拖动
	} else if (event.touches.length === 2) {
		handlePinchZoom(event); // 两指缩放
	}
});
displayFileDiv.addEventListener('touchend', () => {
	endDrag(); // 结束时检查拖动是否超出边界并弹回
	startDist = 0;  // 重置两指间距
});
// 获取图片的尺寸（缩放后的尺寸）	
function getImageSize() {
	const fileImg = document.getElementById('fileimg');
	let width = fileImg.offsetWidth * scale;
	let height = fileImg.offsetHeight * scale;
	return {
		width: width,
		height: height
	};
}

// 判断是否超出容器边界
function isOutOfBounds() {
	const imgSize = getImageSize();
	const displayFileDiv = document.getElementById('displayfilediv');
	const containerSize = {
		width: displayFileDiv.offsetWidth,
		height: displayFileDiv.offsetHeight
	};
	// 图片的最大偏移量
	let maxX = (Math.abs(imgSize.width-containerSize.width)/(2*scale));	
	let maxY = (Math.abs(imgSize.height-containerSize.height)/(2*scale));
	return {
		outOfBoundsX: Math.abs(offsetX)>= maxX,
		outOfBoundsY: Math.abs(offsetY) >= maxY
	};
}

// 缩放操作：根据两指的距离来缩放
function handlePinchZoom(event) {
	const fileImg = document.getElementById('fileimg');
	fileImg.style.transition = 'transform 0.2s ease-out'
	if(event.touches.length == 1){
		if(scale<1.5){
			scale = 3
			fileImg.style.transform = `scale(${scale}) translate(${offsetX}px, ${offsetY}px)`;
		}else{
			scale = 1
			fileImg.style.transform = 'scale('+scale+') translate(0, 0)';
			pull_distance_arr = [0,0];
		}
	}else if (event.touches.length === 2) {
		const dist = Math.sqrt(
			Math.pow(event.touches[0].pageX - event.touches[1].pageX, 2) +
			Math.pow(event.touches[0].pageY - event.touches[1].pageY, 2)
		);

		if (startDist !== 0) {
			const scaleChange = dist / startDist;
			scale *= scaleChange;
			// 限制缩放比例
			scale = Math.min(Math.max(scale, 1), 3); // 限制最大缩放比例为3，最小为0.5
			fileImg.style.transform = `scale(${scale}) translate(${offsetX}px, ${offsetY}px)`;
		}
		startDist = dist;
	}
}
// 拖动操作：根据触摸移动更新偏移量
function handleDrag(event) {
	const fileImg = document.getElementById('fileimg');
	event.preventDefault();
	const dx = event.touches[0].pageX - startX;
	const dy = event.touches[0].pageY - startY;
	offsetX += dx/scale;
	offsetY += dy/scale;
	// 限制拖动范围：图片中心不能超出容器的边界
	const imgSize = getImageSize();
	const displayFileDiv = document.getElementById('displayfilediv');
	const containerSize = {
		width: displayFileDiv.offsetWidth,
		height: displayFileDiv.offsetHeight
	};
	let maxX = (Math.abs(containerSize.width-imgSize.width)/(2*scale))+(50/scale);
	let maxY = (Math.abs(containerSize.height-imgSize.height) /(2*scale))+(50/scale);//移动最大距离（会被弹回来)
	if(imgSize.height<=containerSize.height){
		maxY =0
	}
	if (offsetX > maxX) offsetX = maxX;
	if (offsetX < -maxX) offsetX = -maxX;
	if (offsetY > maxY) offsetY = maxY;
	if (offsetY < -maxY) offsetY = -maxY;
	fileImg.style.transition = 'transform 0.1s ease-out'
	fileImg.style.transform = `scale(${scale}) translate(${offsetX}px, ${offsetY}px)`;
	// 更新触摸起点
	startX = event.touches[0].pageX;
	startY = event.touches[0].pageY;
	return [offsetX,offsetY];
}
// 结束拖动时检查是否超出边界并弹回
function endDrag() {
	if(is_double_click == true){
		return;
	}
	const imgSize = getImageSize();
	const displayFileDiv = document.getElementById('displayfilediv');
	const containerSize = {
		width: displayFileDiv.offsetWidth,
		height: displayFileDiv.offsetHeight
	};
	let maxX = (Math.abs(containerSize.width-imgSize.width)/(2*scale));//判断什么时候要被弹回来
	let maxY = (Math.abs(containerSize.height-imgSize.height) /(2*scale));
	
	const fileImg = document.getElementById('fileimg');
	const { outOfBoundsX, outOfBoundsY } = isOutOfBounds();
	if(scale==1&&is_double_click == false){
		if(pull_distance_arr[0]<=-40){
			// 重置偏移量
			offsetX = 0;
			offsetY = 0;
			scale = 1
			pull_distance_arr = [0,0];
			return picture_forward()
		}else if(pull_distance_arr[0]>=40){
			// 重置偏移量
			offsetX = 0;
			offsetY = 0;
			scale = 1
			pull_distance_arr = [0,0];
			return picture_back()
		}
		fileImg.style.transition = 'transform 0.3s ease-out';
		fileImg.style.transform = `scale(${scale}) translate(0, 0)`;
		// 重置偏移量
		offsetX = 0;
		offsetY = 0;
		scale = 1
		pull_distance_arr = [0,0];
	}
	if (outOfBoundsX || outOfBoundsY) {
		// 弹回效果：当图片超出边界时，平滑回到边界
		fileImg.style.transition = 'transform 0.3s ease-out';
		// 处理 X 方向（水平）
		if (outOfBoundsX) {
			if (offsetX > maxX) {
				offsetX = maxX;  // 弹回最大 X 边界
			} else if (offsetX < -maxX) {
				offsetX = -maxX; // 弹回最小 X 边界
			}
		}

		// 处理 Y 方向（垂直）
		if (outOfBoundsY) {
			if (offsetY > maxY) {
				offsetY = maxY;  // 弹回最大 Y 边界
			} else if (offsetY < -maxY) {
				offsetY = -maxY; // 弹回最小 Y 边界
			}
		}
		// 更新图片的 transform 样式，根据新的 offsetX 和 offsetY 调整
		fileImg.style.transform = `scale(${scale}) translate(${offsetX}px, ${offsetY}px)`;
		pull_distance_arr = [offsetX, offsetY];
	}

}
function reset_image(isSmooth = true) {
    const fileImg = document.getElementById('fileimg');
    if (!fileImg) return; // 如果图片元素不存在，则直接返回

    // --- 1. 重置视觉样式 ---
    if (isSmooth) {
        // 使用平滑的过渡效果回到初始位置
        fileImg.style.transition = 'transform 0.3s ease-out';
    } else {
        // 立即重置，没有动画
        fileImg.style.transition = 'none';
    }
    fileImg.style.transform = 'scale(1) translate(0px, 0px)';

    // --- 2. 重置所有相关的状态变量 ---
    scale = 1;
    startX = 0;
    startY = 0;
    offsetX = 0;
    offsetY = 0;
    startDist = 0;
    pull_distance_arr = [0, 0];
    lastTouchTime = 0;
    is_double_click = false;
}
async function open_address(){
	if(addressurl){
		window.location.href = addressurl
	}else{
		await alert_fix("此图片无位置信息")
	}
};
function convertGPSInfo(coords, ref) {
	const degrees = coords[0];
	const minutes = coords[1];
	const seconds = coords[2];
	const direction = ref === "S" || ref === "W" ? -1 : 1;
	return direction * (degrees + minutes / 60 + seconds / 3600);
}
function getaddress(fileblob) {
    return new Promise((resolve, reject) => {
        EXIF.getData(fileblob, function() {
			let address = ""
            const lat = EXIF.getTag(fileblob, "GPSLatitude");
            const lon = EXIF.getTag(fileblob, "GPSLongitude");
            const latRef = EXIF.getTag(fileblob, "GPSLatitudeRef") || "N";
            const lonRef = EXIF.getTag(fileblob, "GPSLongitudeRef") || "E";
			if (lat && lon) {
				const latitude = convertGPSInfo(lat, latRef);
				const longitude = convertGPSInfo(lon, lonRef);
				if (!isNaN(latitude) && !isNaN(longitude)) {
					address = 'W-' + latitude + '-' + longitude;
				}
			} else {
				address = "";  // 如果没有找到经纬度，返回空地址
			}			
            resolve(address);  // 传递 EXIF 数据
        });
    });
}
function gotoaddress(address,name) {
	if(!address){
		return "";
	}
	let userAgent = navigator.userAgent;
	address_arr = address.split('-')
	if(address&&address_arr.length == 3&&!isNaN(address_arr[1])&&!isNaN(address_arr[2])){
		if(address_arr[0] =='W'){
			let dlat = address_arr[1];
			let dlon = address_arr[2];
			let dname = encodeURIComponent(name);
			//dev是否偏移(0: lat 和 lon 是已经加密后的,不需要国测加密; 1:需要国测加密)
			if (/iPhone|iPad|iPod/i.test(userAgent)) {
				// iOS设备使用的链接格式
				url = "iosamap://path?sourceApplication=webapp&backScheme=myapp://&t=0&lat=" + dlat + "&lon=" + dlon + "&dev=1&dname=" + dname;
				return url;
			} else if (/Android/i.test(userAgent)) {
				// 安卓设备使用的链接格式
				url = "androidamap://route?sourceApplication=webapp&dev=1&t=0&dlat=" + dlat + "&dlon=" + dlon + "&dname=" + dname;
				return url;
			} else {
				// PC或非移动设备使用的链接格式
				url = 'https://uri.amap.com/marker?position='+dlon+','+dlat+'&name='+name+'&coordinate=wgs84&callnative=1';
				return url;
			}
		}else if(address_arr[0] =='C'){
			let dlat = address_arr[1];
			let dlon = address_arr[2];
			let dname = encodeURIComponent(name);
			//dev是否偏移(0: lat 和 lon 是已经加密后的,不需要国测加密; 1:需要国测加密)
			if (/iPhone|iPad|iPod/i.test(userAgent)) {
				// iOS设备使用的链接格式
				let url = "iosamap://path?sourceApplication=webapp&backScheme=myapp://&t=0&lat=" + dlat + "&lon=" + dlon + "&dev=0&dname=" + dname;
				return url;
			} else if (/Android/i.test(userAgent)) {
				// 安卓设备使用的链接格式
				let url = "androidamap://route?sourceApplication=webapp&dev=0&t=0&dlat=" + dlat + "&dlon=" + dlon + "&dname=" + dname;
				return url;
			} else {
				// PC或非移动设备使用的链接格式
				let url = 'https://uri.amap.com/marker?position='+dlon+','+dlat+'&name='+name+'&coordinate=gaode&callnative=1';
				return url;
			}
		}
	}else{
		let url = ''
		return url;
	}
}
async function exit_page(){
	const fileImg = document.getElementById('fileimg');
	const picturediv = document.getElementById('picturediv');
	picturediv.classList.remove('expanded');
	reset_image()
	if(fileImg.src){
		URL.revokeObjectURL(fileImg.src)
	}
	fileImg.src ="dir/img/icon/loading_icon.svg"
}
async function fetchblob(url) {
  try {
    const response = await fetch(url);
    if (!response.ok) {
      throw new Error(`HTTP 请求失败: ${response.status} ${response.statusText}`);
    }
    const blob = await response.blob();
    return blob;
  } catch (error) {
    return null;
  }
}
async function load_img(picture_name){
	const picturediv = document.getElementById('picturediv');
	const fileImg = document.getElementById('fileimg');
	if(fileImg.src){
		URL.revokeObjectURL(fileImg.src)
	}
	picturediv.classList.add('expanded')
	picture_arr.length = 0
	let file_id = null;
	let real_file_name = "";
	let real_icon_name = ""
	for(let file of file_index_arr){
		if(isImageFile(file.name)){
			picture_arr.push([file.name,file.id,file.real_file_name])
		}
		if(file.name === picture_name ){
			file_id = file.id
			real_file_name = file.real_file_name||""
			real_icon_name= file.real_icon_name||""
		}
	}
	document.getElementById('picturenamespan').textContent = picture_name
	document.getElementById('picturenamespan').dataset.id = file_id
	document.getElementById('picturenamespan').dataset.real_file_name = real_file_name
	let url = ""
	let address = ""
	if(real_file_name){
		let blob = await fetchblob("https://appassets.androidplatform.net/app_data/"+real_file_name)
		url = URL.createObjectURL(blob);
		address = await getaddress(blob)
	}
	addressurl = gotoaddress(address,"照片目的地")
	page = 0
	for(let i= 0;i<picture_arr.length;i++){
		if(picture_arr[i][0] == picture_name){
			page = i+1
			break
		}
		
	}
	document.getElementById('pageseqinput').textContent = String(page)+'/'+picture_arr.length
	picturediv.addEventListener('transitionend', onTransitionEnd);
	function onTransitionEnd() {
		fileImg.src = url;
		picturediv.removeEventListener('transitionend', onTransitionEnd);
	}
}
async function picture_back(){
	let img = document.getElementById('fileimg')
	if(img.src){
		URL.revokeObjectURL(img.src)
	}	
	page =page-1
	let page_seq = page-1
	if(page_seq<0){
		page = picture_arr.length
		page_seq = page-1
	}
	let picture_name = ""
	let picture_id = ""
	let real_file_name = ""
	if(!picture_arr[page_seq]){
		if(picture_arr[picture_arr.length-1]){
			picture_name = picture_arr[picture_arr.length-1][0]
			picture_id = picture_arr[picture_arr.length-1][1]
			real_file_name = picture_arr[picture_arr.length-1][2]
			page = picture_arr.length
		}
	}else{
		picture_name = picture_arr[page_seq][0]
		picture_id = picture_arr[page_seq][1]
		real_file_name = picture_arr[page_seq][2]
	}
	if(picture_name){
		let move_width = displayfilediv.offsetWidth
		img.style.transform = 'translate('+String(move_width)+'px, 0)';
		await delay(200)
		img.style.visibility = 'hidden'
		img.style.transform = 'translate(-'+String(move_width)+'px, 0)';
		const start_time = new Date()
		let url = ""
		let address = "";
		if(real_file_name){
			let blob = await fetchblob("https://appassets.androidplatform.net/app_data/"+real_file_name)
			url = URL.createObjectURL(blob);
			address = await getaddress(blob)
		}
		await new Promise((resolve, reject) => {
			img.src = url
			img.onload = () => resolve(); // 图片加载成功，返回 img 对象
			img.onerror = () => reject(new Error("图片加载失败"));
		});
		addressurl = gotoaddress(address,"照片目的地")		
		const end_time = new Date()
		let wait_time = 100-(end_time - start_time);
		if(wait_time>0){
			await delay(wait_time)
		}
		img.style.visibility = 'visible';		
		img.style.transition = 'transform 0.2s ease-out'
		img.style.transform = 'translate(0, 0)';
		pull_distance_arr = [0,0];
		document.getElementById('picturenamespan').textContent = picture_name
		document.getElementById('picturenamespan').dataset.id = picture_id
		document.getElementById('picturenamespan').dataset.real_file_name = real_file_name
		await delay(200)
		img.style.transition = 'transform 0.1s ease-out'
	}
	document.getElementById('pageseqinput').textContent = String(page)+'/'+picture_arr.length
}
//
function delay(ms) {
    return new Promise((resolve) => {
        setTimeout(() => {
            resolve(); // 延时结束后，Promise 进入 resolved 状态
        }, ms);
    });
}

async function picture_forward(){
	if(picture_arr.length===0){
		return;
	}
	let img = document.getElementById('fileimg')
	let img_src =img.src 
	if(img_src){
		URL.revokeObjectURL(img_src)
	}
	page++
	let page_seq = page-1

	if(page>picture_arr.length){
		page = 1
		page_seq = page-1
	}
	let picture_name = ""
	let picture_id = ""
	let real_file_name = ""
	if(!picture_arr[page_seq]){
		if(picture_arr[0]){
			picture_name = picture_arr[0][0]
			picture_id = picture_arr[0][1]
			real_file_name = picture_arr[0][2]
			page = 1
		}
	}else{
		picture_name = picture_arr[page_seq][0]
		picture_id = picture_arr[page_seq][1]
		real_file_name = picture_arr[page_seq][2]
	}
	if(picture_name){
		let move_width = displayfilediv.offsetWidth
		img.style.transition = 'transform 0.2s ease-out'
		img.style.transform = 'translate(-'+String(move_width)+'px, 0)';
		await delay(200)
		img.style.transition = 'transform 0.1s ease-out'
		img.style.visibility = 'hidden'
		img.style.transform = 'translate('+String(move_width)+'px, 0)';
		const start_time = new Date()
		let url = ""
		let address = ""
		if(real_file_name){
			let blob = await fetchblob("https://appassets.androidplatform.net/app_data/"+real_file_name)
			url = URL.createObjectURL(blob);
			address = await getaddress(blob)
		}
		await new Promise((resolve, reject) => {
			img.src = url
			img.onload = () => resolve(); // 图片加载成功，返回 img 对象
			img.onerror = () => reject(new Error("图片加载失败"));
		});	
		addressurl = gotoaddress(address,"照片目的地")	
		const end_time = new Date()
		let wait_time = 100-(end_time - start_time);
		if(wait_time>0){
			await delay(wait_time)
		}
		img.style.visibility = 'visible';
		img.style.transition = 'transform 0.2s ease-out'
		img.style.transform = 'translate(0, 0)';
		pull_distance_arr = [0,0];
		document.getElementById('picturenamespan').textContent = picture_name
		document.getElementById('picturenamespan').dataset.id = picture_id
		document.getElementById('picturenamespan').dataset.real_file_name = real_file_name
		await delay(200)
		img.style.transition = 'transform 0.1s ease-out'
	}
	document.getElementById('pageseqinput').textContent = String(page)+'/'+picture_arr.length
}
async function picture_goto_page(){
	let folderPath = await prompt_fix("请输入页码");
	if (folderPath=="") {
	  await alert_fix("页码不能为空");
	  return;
	}
	if (!folderPath) {
	  return;
	}
	let page_number = Number(folderPath)
	if(isNaN(page_number) || folderPath <1){
		return;
	}
	let oldpage = page
	if(oldpage == page_number){
		return;
	}
	let nowpath = 当前文件夹路径_arr.join('/')+'/'
	let img = document.getElementById('fileimg')
	if(img.src){
		URL.revokeObjectURL(img.src)
	}
	if(page_number>0&&page_number<= picture_arr.length){
		page = page_number
	}else{
		await alert_fix('超出范围');
		return;
	}
	let picture_name = ""
	let picture_id = ""
	let real_file_name = ""
	page_seq = page -1 
	if(!picture_arr[page_seq]){
		return;
	}else{
		picture_name = picture_arr[page_seq][0]
		picture_id = picture_arr[page_seq][1]
		real_file_name = picture_arr[page_seq][2]
	}
	if(picture_name){
		let move_width = displayfilediv.offsetWidth
		let anime1 = 'translate('+String(move_width)+'px, 0)'
		let anime2 = 'translate(-'+String(move_width)+'px, 0)'
		if(oldpage>page){
			anime1 = 'translate(-'+String(move_width)+'px, 0)'
			anime2 = 'translate('+String(move_width)+'px, 0)'
		}
		img.style.transition = 'transform 0.2s ease-out'
		img.style.transform = anime1;
		await delay(200)
		const start_time = new Date()
		img.style.transition = 'transform 0.1s ease-out'
		img.style.visibility = 'hidden'
		img.style.transform = anime2;

		let url = ""
		let address = ""
		if(real_file_name){
			let blob = await fetchblob("https://appassets.androidplatform.net/app_data/"+real_file_name)
			url = URL.createObjectURL(blob);
			address = await getaddress(blob)
		}
		await new Promise((resolve, reject) => {
			img.src = url
			img.onload = () => resolve(); // 图片加载成功，返回 img 对象
			img.onerror = () => reject(new Error("图片加载失败"));
		});
		addressurl = gotoaddress(address,"照片目的地")			
		const end_time = new Date()
		let wait_time = 100-(end_time - start_time);
		if(wait_time>0){
			await delay(wait_time)
		}
		img.style.visibility = 'visible';
		img.style.transition = 'transform 0.2s ease-out'
		img.style.transform = 'translate(0, 0)';
		pull_distance_arr = [0,0];
		document.getElementById('picturenamespan').textContent = picture_name
		document.getElementById('picturenamespan').dataset.id = picture_id
		document.getElementById('picturenamespan').dataset.real_file_name = real_file_name
		await delay(200)
		img.style.transition = 'transform 0.1s ease-out'
	}
	document.getElementById('pageseqinput').textContent = String(page)+'/'+picture_arr.length
}
async function pic_download() {
	let fileimg = document.getElementById('fileimg');
	let picture_name = "";
	let picture_name_element = picture_arr[page-1]
	if(picture_name_element){
		picture_name = picture_name_element[0]
	}
	if(!picture_name){
		return;
	}
	let downloadmap = new Map()
	downloadmap.set(picture_name,'file')
	let id = document.getElementById('picturenamespan').dataset.id
	if(id){
		await exportItems([id]);
	}
}
async function pic_deleteByPaths() {
    if (page < 1 || picture_arr.length < 1) {
        resetPictureView(); 
        return;
    }
    if (!await confirm_fix("是否删除该图片?")) {
        return;
    }
    const img = document.getElementById('fileimg');
    const pageNumInput = document.getElementById('pageseqinput');
    const pictureNameSpan = document.getElementById('picturenamespan');
    if (img.src && img.src.startsWith('blob:')) {
        URL.revokeObjectURL(img.src);
    }
    img.src = "";
    const currentIndex = page - 1;
    const picture_id = picture_arr[currentIndex][1];
    const isDeleted = await deletefileByid(picture_id);
    if (!isDeleted) {
        await alert_fix("删除失败！"); 
        return;
    }
    picture_arr.splice(currentIndex, 1);
    if (page > picture_arr.length) {
        page = picture_arr.length;
    }
    await updatePictureView();
}
async function updatePictureView() {
    const img = document.getElementById('fileimg');
    const pageNumInput = document.getElementById('pageseqinput');
    const pictureNameSpan = document.getElementById('picturenamespan');

    // 如果数组中还有图片
    if (picture_arr.length > 0 && page >= 1) {
        const currentIndex = page - 1;
        const [picture_name, picture_id, real_file_name] = picture_arr[currentIndex];

        let url = "";
        let address = "";
        
        if (real_file_name) {
            const blob = await fetchblob("https://appassets.androidplatform.net/app_data/" + real_file_name);
            if (blob) {
                url = URL.createObjectURL(blob);
                address = await getaddress(blob);
            }
        }
        
        img.src = url;
        addressurl = gotoaddress(address, "照片目的地");
        pictureNameSpan.textContent = picture_name;
        pictureNameSpan.dataset.id = picture_id;
        pictureNameSpan.dataset.real_file_name = real_file_name;
    } else {
        resetPictureView();
    }
    
    // 统一在最后更新页码显示
    pageNumInput.textContent = `${page}/${picture_arr.length}`;
}
function resetPictureView() {
    page = 0;
    addressurl = "";
    document.getElementById('fileimg').src = "";
    const pictureNameSpan = document.getElementById('picturenamespan');
    pictureNameSpan.textContent = "";
    pictureNameSpan.dataset.id = "";
    pictureNameSpan.dataset.real_file_name = "";
    document.getElementById('pageseqinput').textContent = `0/0`;
}

function gotoaddress2(address,name) {
	if(!address){
		return "";
	}
	let userAgent = navigator.userAgent;
	address_arr = address.split('-')
	if(address&&address_arr.length == 3&&!isNaN(address_arr[1])&&!isNaN(address_arr[2])){
		if(address_arr[0] =='W'){
			let dlat = address_arr[1];
			let dlon = address_arr[2];
			let dname = encodeURIComponent(name);
			//dev是否偏移(0: lat 和 lon 是已经加密后的,不需要国测加密; 1:需要国测加密)
			// PC或非移动设备使用的链接格式
			url = 'https://uri.amap.com/marker?position='+dlon+','+dlat+'&name='+name+'&coordinate=wgs84&callnative=1';
			return url;
		}else if(address_arr[0] =='C'){
			let dlat = address_arr[1];
			let dlon = address_arr[2];
			let dname = encodeURIComponent(name);
			//dev是否偏移(0: lat 和 lon 是已经加密后的,不需要国测加密; 1:需要国测加密)
			// PC或非移动设备使用的链接格式
			let url = 'https://uri.amap.com/marker?position='+dlon+','+dlat+'&name='+name+'&coordinate=gaode&callnative=1';
			return url;
		}
	}else{
		let url = ''
		return url;
	}
}
async function share_picture(){
	let real_file_name = document.getElementById('picturenamespan').dataset.real_file_name
	if(!real_file_name){
		return;
	}	
	let uri = "content://com.filecamera.apk.fileprovider/private_files/app_data/";
	let uri_arr = [];
	let name = document.getElementById('picturenamespan').textContent;
	uri_arr.push({uri:uri+real_file_name,name:name})
	await nativeShareFile(uri_arr,true, 'all')
}