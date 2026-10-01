(() => {
  const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
  const MAX_AVATAR_LENGTH = 400000;
  const AVATAR_SIZE = 256;
  const LIMITS = {accountNickname:32,accountTitle:80,accountBio:300};
  const escape = value => String(value).replace(/[&<>"']/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
  const icon = name => `<i data-lucide="${name}"></i>`;
  const preferences = () => window.DongranSettings;
  let uploadGeneration = 0;
  let pendingUrl = null;
  let activeCrop = null;

  function validAvatar(value) {
    if (value === '') return true;
    if (typeof value !== 'string' || value.length > MAX_AVATAR_LENGTH || !/^data:image\/png;base64,[A-Za-z0-9+/]+={0,2}$/.test(value)) return false;
    try {
      const bytes = atob(value.slice('data:image/png;base64,'.length));
      if (bytes.length < 80 || bytes.slice(0,8) !== '\x89PNG\r\n\x1a\n' || bytes.slice(8,16) !== '\x00\x00\x00\x0dIHDR') return false;
      const uint32 = offset => (((bytes.charCodeAt(offset) * 256 + bytes.charCodeAt(offset+1)) * 256 + bytes.charCodeAt(offset+2)) * 256 + bytes.charCodeAt(offset+3));
      return uint32(16) === AVATAR_SIZE && uint32(20) === AVATAR_SIZE && bytes.slice(-12) === '\x00\x00\x00\x00IEND\xaeB\x60\x82';
    } catch {
      return false;
    }
  }

  function validateText(key,value) {
    return typeof value === 'string' && value.length <= LIMITS[key] && (key !== 'accountNickname' || value.trim().length > 0) && (key === 'accountBio' || !/[\r\n\x00-\x1f]/.test(value));
  }

  function sync() {
    const api = preferences();
    if (!api) return;
    const nickname = api.get('accountNickname') || 'Dongran-wzr';
    const avatar = api.get('accountAvatar') || '';
    const initial = Array.from(nickname.trim())[0]?.toLocaleUpperCase() || 'D';
    document.querySelectorAll('[data-account-name]').forEach(element => {
      element.textContent = nickname;
      element.title = nickname;
    });
    document.querySelectorAll('[data-account-avatar]').forEach(element => {
      element.classList.add('account-avatar-display');
      element.setAttribute('role','img');
      element.setAttribute('aria-label',`${nickname}的头像`);
      if (element.dataset.avatarSource === avatar && element.dataset.avatarInitial === initial) return;
      element.dataset.avatarSource = avatar;
      element.dataset.avatarInitial = initial;
      if (avatar && validAvatar(avatar)) element.innerHTML=`<img src="${escape(avatar)}" alt="" draggable="false">`;
      else element.textContent=initial;
    });
    const remove = document.getElementById('account-avatar-remove');
    if (remove) remove.disabled=!avatar;
    const title = document.getElementById('account-preview-title');
    if (title) title.textContent=api.get('accountTitle') || '个人资料';
  }

  function avatarError(message = '') {
    const error = document.getElementById('account-avatar-error');
    if (error) {error.textContent=message;error.hidden=!message;}
  }

  function uploadBusy(busy) {
    const button = document.getElementById('account-avatar-upload');
    if (!button) return;
    button.setAttribute('aria-busy',String(busy));
    const label = button.querySelector('span');
    if (label) label.textContent=busy ? '正在处理...' : '更换头像';
  }

  function releaseUrl(url) {
    if (url) URL.revokeObjectURL(url);
    if (pendingUrl === url) pendingUrl=null;
  }

  function cancelUpload() {
    uploadGeneration++;
    releaseUrl(pendingUrl);
    if (activeCrop) {
      releaseUrl(activeCrop.url);
      activeCrop=null;
    }
    uploadBusy(false);
  }

  function drawCrop() {
    const crop = activeCrop;
    const canvas = document.getElementById('account-crop-canvas');
    if (!crop || !canvas) return;
    const zoom = Number(document.getElementById('account-crop-zoom').value);
    const size = Math.min(crop.image.naturalWidth,crop.image.naturalHeight)/zoom;
    const left = (crop.image.naturalWidth-size)/2;
    const top = (crop.image.naturalHeight-size)/2;
    const context = canvas.getContext('2d');
    context.clearRect(0,0,AVATAR_SIZE,AVATAR_SIZE);
    context.drawImage(crop.image,left,top,size,size,0,0,AVATAR_SIZE,AVATAR_SIZE);
    document.getElementById('account-crop-zoom-value').textContent=`${Math.round(zoom*100)}%`;
  }

  function showCrop(image,url,generation) {
    activeCrop={image,url,generation};
    window.DongranUI.showDialog('调整头像', `<div class="account-crop-preview"><canvas id="account-crop-canvas" width="${AVATAR_SIZE}" height="${AVATAR_SIZE}" aria-label="头像裁切预览"></canvas></div><div class="account-crop-slider"><label for="account-crop-zoom">缩放</label><input id="account-crop-zoom" type="range" min="1" max="3" step="0.01" value="1"><output id="account-crop-zoom-value" for="account-crop-zoom">100%</output></div><p id="account-crop-error" class="account-field-error" role="alert" hidden></p><div class="settings-confirm-actions"><button id="account-crop-cancel" type="button" class="secondary">取消</button><button id="account-crop-save" type="button" class="primary">保存头像</button></div>`);
    document.getElementById('account-crop-zoom').addEventListener('input',drawCrop);
    document.getElementById('account-crop-cancel').addEventListener('click',() => {cancelUpload();window.DongranUI.closeDialog();});
    document.getElementById('account-crop-save').addEventListener('click',() => {
      if (!activeCrop || activeCrop.generation !== generation || generation !== uploadGeneration) return;
      try {
        const avatar = document.getElementById('account-crop-canvas').toDataURL('image/png');
        if (!validAvatar(avatar) || !preferences().set('accountAvatar',avatar)) throw new Error('无法保存头像，请换一张图片后重试。');
        cancelUpload();
        avatarError();
        sync();
        window.DongranUI.closeDialog();
      } catch (error) {
        const message = document.getElementById('account-crop-error');
        message.textContent=error.message || '头像处理失败，请重试。';
        message.hidden=false;
      }
    });
    const dialog = document.getElementById('dialog');
    dialog.addEventListener('close',() => {
      if (activeCrop?.generation === generation) cancelUpload();
      else releaseUrl(url);
    },{once:true});
    drawCrop();
    document.getElementById('account-crop-zoom').focus();
  }

  async function uploadAvatar(file) {
    if (activeCrop) window.DongranUI.closeDialog();
    cancelUpload();
    const generation = uploadGeneration;
    if (!file) return false;
    avatarError();
    if (!['image/png','image/jpeg','image/webp'].includes(file.type)) {avatarError('请选择 PNG、JPEG 或 WebP 图片。');return false;}
    if (!file.size || file.size > MAX_UPLOAD_BYTES) {avatarError('图片大小需在 5 MB 以内。');return false;}
    uploadBusy(true);
    let url = null;
    try {
      const header = new Uint8Array(await file.slice(0,16).arrayBuffer());
      if (generation !== uploadGeneration) return false;
      const isPng = header.length >= 8 && [137,80,78,71,13,10,26,10].every((value,index) => header[index] === value);
      const isJpeg = header.length >= 3 && header[0] === 255 && header[1] === 216 && header[2] === 255;
      const isWebp = header.length >= 12 && String.fromCharCode(...header.slice(0,4)) === 'RIFF' && String.fromCharCode(...header.slice(8,12)) === 'WEBP';
      if (!(file.type === 'image/png' && isPng || file.type === 'image/jpeg' && isJpeg || file.type === 'image/webp' && isWebp)) throw new Error('invalid-image');
      url = URL.createObjectURL(file);
      pendingUrl=url;
      const image = new Image();
      image.src=url;
      await image.decode();
      if (generation !== uploadGeneration) {releaseUrl(url);return false;}
      if (!image.naturalWidth || !image.naturalHeight || image.naturalWidth > 16000 || image.naturalHeight > 16000) throw new Error('图片尺寸不受支持，请选择边长不超过 16000 像素的图片。');
      const settingsScreen = document.getElementById('settings-screen');
      if (settingsScreen?.hidden || settingsScreen?.classList.contains('is-leaving') || !document.getElementById('account-avatar-upload')) {cancelUpload();return false;}
      uploadBusy(false);
      pendingUrl=null;
      showCrop(image,url,generation);
      return true;
    } catch (error) {
      releaseUrl(url);
      if (generation !== uploadGeneration) return false;
      uploadBusy(false);
      avatarError(error.message?.includes('16000') ? error.message : '图片无法解码，请选择有效的 PNG、JPEG 或 WebP 图片。');
      return false;
    }
  }

  function fieldChanged(input) {
    const key = input.dataset.accountField;
    const value = key === 'accountBio' ? input.value : input.value.trim();
    const error = document.getElementById(`${input.id}-error`);
    let message = '';
    if (!validateText(key,value)) message=key === 'accountNickname' && !value.trim() ? '昵称不能为空。' : `最多输入 ${LIMITS[key]} 个字符。`;
    if (!message && !preferences().set(key,value)) message='暂时无法保存，请重试。';
    input.setAttribute('aria-invalid',String(!!message));
    if (error) {error.textContent=message;error.hidden=!message;}
    if (key === 'accountBio') document.getElementById('account-bio-length').textContent=`${input.value.length} / 300`;
    if (!message) sync();
  }

  function render(category,api) {
    if (category !== 'account') return '';
    const name = api.get('accountNickname') || 'Dongran-wzr';
    const title = api.get('accountTitle') || '';
    const bio = api.get('accountBio') || '';
    const avatar = api.get('accountAvatar') || '';
    return `<section class="settings-section account-profile-section"><h2>个人资料</h2><div class="account-profile-preview"><span id="account-avatar-preview" class="account-profile-avatar account-avatar-display" data-account-avatar role="img" aria-label="${escape(name)}的头像">${avatar ? `<img src="${escape(avatar)}" alt="" draggable="false">` : escape(Array.from(name.trim())[0]?.toLocaleUpperCase() || 'D')}</span><div class="account-profile-identity"><strong data-account-name>${escape(name)}</strong><span id="account-preview-title">${escape(title || '个人资料')}</span></div><div class="account-avatar-controls"><button id="account-avatar-upload" type="button" class="settings-command">${icon('upload')}<span>更换头像</span></button><button id="account-avatar-remove" type="button" class="icon-btn" title="移除头像" aria-label="移除头像" ${avatar ? '' : 'disabled'}>${icon('trash-2')}</button></div></div><div class="account-avatar-note">PNG、JPEG 或 WebP，最大 5 MB。</div><p id="account-avatar-error" class="account-field-error" role="alert" hidden></p><input id="account-avatar-file" type="file" accept="image/png,image/jpeg,image/webp" hidden><div class="account-field-row"><label for="account-nickname">昵称<span>显示在工作台和任务对话中。</span></label><div class="account-field-control"><input id="account-nickname" data-account-field="accountNickname" type="text" maxlength="32" value="${escape(name)}" autocomplete="nickname" aria-describedby="account-nickname-error" required><p id="account-nickname-error" class="account-field-error" role="alert" hidden></p></div></div><div class="account-field-row"><label for="account-title">职位 / 团队<span>可选</span></label><div class="account-field-control"><input id="account-title" data-account-field="accountTitle" type="text" maxlength="80" value="${escape(title)}" placeholder="例如：产品经理 · 平台研发" autocomplete="organization-title" aria-describedby="account-title-error"><p id="account-title-error" class="account-field-error" role="alert" hidden></p></div></div><div class="account-field-row account-bio-row"><label for="account-bio">个人简介<span>可选</span></label><div class="account-field-control"><textarea id="account-bio" data-account-field="accountBio" rows="4" maxlength="300" placeholder="写下一句介绍。" aria-describedby="account-bio-error">${escape(bio)}</textarea><div id="account-bio-length" class="account-field-length">${bio.length} / 300</div><p id="account-bio-error" class="account-field-error" role="alert" hidden></p></div></div></section><div class="account-local-note">${icon('hard-drive')}<span>个人资料仅保存在当前设备。</span></div>`;
  }

  document.addEventListener('click',event => {
    const button = event.target.closest('button');
    if (!button || button.disabled) return;
    if (button.id === 'account-avatar-upload') document.getElementById('account-avatar-file').click();
    else if (button.id === 'account-avatar-remove') {
      cancelUpload();
      preferences().set('accountAvatar','');
      avatarError();
      sync();
    }
  });
  document.addEventListener('input',event => {if (event.target.dataset.accountField) fieldChanged(event.target);});
  document.addEventListener('change',event => {
    if (event.target.id === 'account-avatar-file') {
      const file=event.target.files[0];
      event.target.value='';
      if (file) uploadAvatar(file);
    } else if (event.target.dataset.accountField) fieldChanged(event.target);
  });
  window.addEventListener('settingschange',event => {
    if (event.detail.key.startsWith('account') || ['init','import','reset','reset-category'].includes(event.detail.key)) sync();
    if (['import','reset','reset-category'].includes(event.detail.key)) cancelUpload();
  });

  (window.DongranSettingsModules ||= []).push({
    categories:[{id:'account',label:'账户',icon:'user-round',group:'个人偏好',subtitle:'管理当前设备上的头像与个人资料。',resettable:false,searchKeywords:'账户 头像 昵称 个人资料 职位 团队 简介 上传'}],
    definitions:[
      {key:'accountNickname',category:'account',section:'个人资料',title:'昵称',type:'text',default:'Dongran-wzr',maxLength:32,validate:value => validateText('accountNickname',value),hidden:true,preserveOnReset:true},
      {key:'accountTitle',category:'account',section:'个人资料',title:'职位 / 团队',type:'text',default:'',maxLength:80,validate:value => validateText('accountTitle',value),hidden:true,preserveOnReset:true},
      {key:'accountBio',category:'account',section:'个人资料',title:'个人简介',type:'textarea',default:'',maxLength:300,validate:value => validateText('accountBio',value),hidden:true,preserveOnReset:true},
      {key:'accountAvatar',category:'account',section:'个人资料',title:'头像',type:'text',default:'',maxLength:MAX_AVATAR_LENGTH,validate:validAvatar,hidden:true,preserveOnReset:true}
    ],
    render
  });
  window.DongranAccount={sync,uploadAvatar,cancelUpload,validAvatar,validateText};
})();
