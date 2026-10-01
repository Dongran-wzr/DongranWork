(() => {
  const ENTRY_KEYS = ['id','title','content','scope','projectId','projectName','createdAt','updatedAt'];
  let selectedScope = 'global';
  let search = '';
  let currentProject = window.DongranProjects?.current?.() || null;
  let idSequence = 0;
  const escape = value => String(value).replace(/[&<>"']/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
  const icon = name => `<i data-lucide="${name}"></i>`;
  const prefs = () => window.DongranSettings;
  const allEntries = () => prefs()?.get('memoryEntries') || [];
  const allProjectPreferences = () => prefs()?.get('memoryProjectPreferences') || [];
  const plainObject = value => !!value && typeof value === 'object' && !Array.isArray(value);
  const nonempty = (value, limit) => typeof value === 'string' && !!value.trim() && value.length <= limit;
  const validDate = value => typeof value === 'string' && value.length === 24 && Number.isFinite(Date.parse(value)) && new Date(value).toISOString() === value;

  function validateEntries(value) {
    if (!Array.isArray(value) || value.length > 100) return false;
    const ids = new Set();
    const valid = value.every(entry => {
      if (!plainObject(entry) || Object.keys(entry).length !== ENTRY_KEYS.length || Object.keys(entry).some(key => !ENTRY_KEYS.includes(key))) return false;
      if (!nonempty(entry.id,100) || !/^[a-zA-Z0-9_-]+$/.test(entry.id) || ids.has(entry.id)) return false;
      ids.add(entry.id);
      return nonempty(entry.title,80) && nonempty(entry.content,2000) && ['global','project'].includes(entry.scope)
        && (entry.scope === 'global' ? entry.projectId === null && entry.projectName === '' : nonempty(entry.projectId,2048) && nonempty(entry.projectName,200))
        && validDate(entry.createdAt) && validDate(entry.updatedAt) && entry.updatedAt >= entry.createdAt;
    });
    return valid && new TextEncoder().encode(JSON.stringify(value)).byteLength <= 131072;
  }

  function validateProjectPreferences(value) {
    if (!Array.isArray(value) || value.length > 200) return false;
    const ids = new Set();
    return value.every(item => {
      if (!plainObject(item) || Object.keys(item).length !== 3 || !Object.keys(item).every(key => ['projectId','enabled','inheritGlobal'].includes(key))) return false;
      if (!nonempty(item.projectId,2048) || ids.has(item.projectId) || typeof item.enabled !== 'boolean' || typeof item.inheritGlobal !== 'boolean') return false;
      ids.add(item.projectId);
      return true;
    });
  }

  function projectPreference(projectId = currentProject?.id) {
    const existing = allProjectPreferences().find(item => item.projectId === projectId);
    return existing ? {...existing} : {projectId:projectId || null,enabled:true,inheritGlobal:true};
  }

  function getEntries(scope = selectedScope, projectId = currentProject?.id) {
    return allEntries().filter(entry => entry.scope === scope && (scope === 'global' || entry.projectId === projectId)).map(entry => ({...entry}));
  }

  function getEffectiveEntries(projectId = currentProject?.id) {
    if (!prefs()?.get('memoryEnabled')) return [];
    if (!projectId) return getEntries('global');
    const options = projectPreference(projectId);
    return [...(options.inheritGlobal ? getEntries('global') : []),...(options.enabled ? getEntries('project',projectId) : [])];
  }

  function setProjectPreference(key,value) {
    if (!currentProject || !['enabled','inheritGlobal'].includes(key) || typeof value !== 'boolean') return false;
    const updated = {...projectPreference(),[key]:value};
    const next = [...allProjectPreferences().filter(item => item.projectId !== currentProject.id),updated];
    return prefs().set('memoryProjectPreferences',next);
  }

  function currentScopeEntries() {
    const term = search.trim().toLocaleLowerCase();
    return getEntries().filter(entry => !term || `${entry.title} ${entry.content}`.toLocaleLowerCase().includes(term)).sort((a,b) => b.updatedAt.localeCompare(a.updatedAt));
  }

  function listMarkup() {
    if (selectedScope === 'project' && !currentProject) return `<div class="memory-empty">${icon('folder-open')}<strong>尚未选择项目</strong><p>打开项目目录后，可管理这个项目的独立记忆。</p></div>`;
    const entries = currentScopeEntries();
    if (!entries.length) return `<div class="memory-empty">${icon(search ? 'search' : 'brain')}<strong>${search ? '没有匹配的记忆' : selectedScope === 'global' ? '还没有全局记忆' : '这个项目还没有记忆'}</strong><p>${search ? '试试其他关键词。' : selectedScope === 'global' ? '保存跨项目通用的偏好、习惯和约定。' : '保存当前项目的术语、规范和业务约定。'}</p></div>`;
    return entries.map(entry => `<article class="memory-entry" data-memory-entry="${escape(entry.id)}"><span class="memory-entry-icon">${icon(entry.scope === 'global' ? 'globe' : 'folder')}</span><div class="memory-entry-copy"><h3>${escape(entry.title)}</h3><p>${escape(entry.content)}</p><div class="memory-entry-meta"><span>${entry.scope === 'global' ? '全局' : escape(entry.projectName)}</span><span>更新于 ${new Date(entry.updatedAt).toLocaleDateString('zh-CN',{month:'2-digit',day:'2-digit'})}</span></div></div><div class="memory-entry-actions"><button class="icon-btn" type="button" data-memory-edit="${escape(entry.id)}" title="编辑记忆" aria-label="编辑 ${escape(entry.title)}">${icon('pencil')}</button><button class="icon-btn" type="button" data-memory-delete="${escape(entry.id)}" title="删除记忆" aria-label="删除 ${escape(entry.title)}">${icon('trash-2')}</button></div></article>`).join('');
  }

  function projectPreferenceMarkup() {
    if (selectedScope !== 'project') return '';
    const options = projectPreference();
    return `<div class="memory-project-preferences"><div class="memory-project-preference"><div><strong>启用项目记忆</strong><p>在当前项目中使用单独保存的记忆。</p></div><button id="memory-project-enabled" type="button" class="settings-switch" role="switch" aria-label="启用项目记忆" aria-checked="${options.enabled}" data-memory-preference="enabled" ${currentProject ? '' : 'disabled'}><span></span></button></div><div class="memory-project-preference"><div><strong>继承全局记忆</strong><p>同时使用跨项目的通用偏好。</p></div><button id="memory-inherit-global" type="button" class="settings-switch" role="switch" aria-label="继承全局记忆" aria-checked="${options.inheritGlobal}" data-memory-preference="inheritGlobal" ${currentProject ? '' : 'disabled'}><span></span></button></div></div>`;
  }

  function panelMarkup() {
    const unavailable = selectedScope === 'project' && !currentProject;
    return `<div class="memory-toolbar"><div id="memory-scope" class="settings-segment" role="tablist" aria-label="记忆范围"><button id="memory-global-tab" type="button" role="tab" aria-selected="${selectedScope === 'global'}" aria-checked="${selectedScope === 'global'}" aria-controls="memory-scope-panel" tabindex="${selectedScope === 'global' ? 0 : -1}" data-memory-scope="global">${icon('globe')}<span>全局记忆</span><span class="memory-count">${getEntries('global').length}</span></button><button id="memory-project-tab" type="button" role="tab" aria-selected="${selectedScope === 'project'}" aria-checked="${selectedScope === 'project'}" aria-controls="memory-scope-panel" tabindex="${selectedScope === 'project' ? 0 : -1}" data-memory-scope="project">${icon('folder')}<span>项目记忆</span><span class="memory-count">${getEntries('project').length}</span></button></div><button id="memory-add" type="button" class="settings-command" data-memory-command="add" ${unavailable ? 'disabled' : ''}>${icon('plus')}添加记忆</button></div><div id="memory-scope-panel" role="tabpanel" aria-labelledby="memory-${selectedScope}-tab">${selectedScope === 'project' ? `<div class="memory-current-project">${icon('folder-open')}<span>${escape(currentProject?.name || '未选择项目')}</span>${currentProject ? `<span class="memory-project-path" title="${escape(currentProject.path || currentProject.name)}">${escape(currentProject.path || '')}</span>` : ''}</div>` : ''}${projectPreferenceMarkup()}<div class="memory-list-toolbar"><div class="memory-search-box">${icon('search')}<input id="memory-search" type="search" placeholder="搜索记忆..." aria-label="搜索记忆" value="${escape(search)}" ${unavailable ? 'disabled' : ''}><button type="button" class="icon-btn" id="memory-search-clear" data-memory-command="clear-search" title="清空搜索" aria-label="清空搜索" ${search ? '' : 'hidden'}>${icon('x')}</button></div><button id="memory-clear" type="button" class="memory-clear-button" data-memory-command="clear" ${!getEntries().length || unavailable ? 'disabled' : ''}>${icon('trash-2')}清空${selectedScope === 'global' ? '全局' : '项目'}记忆</button></div><div id="memory-list" class="memory-list">${listMarkup()}</div><div class="memory-list-footer"><span id="memory-list-count">${currentScopeEntries().length} 条记忆</span><span>仅保存在本机</span></div></div>`;
  }

  function refreshPanel() {
    const panel = document.getElementById('memory-manager');
    if (!panel) return;
    const focused = document.activeElement;
    const focusId = panel.contains(focused) ? focused.id : '';
    const selection = focused instanceof HTMLInputElement ? [focused.selectionStart,focused.selectionEnd] : null;
    panel.innerHTML = panelMarkup();
    window.lucide?.createIcons();
    if (focusId) {
      const next = document.getElementById(focusId);
      next?.focus({preventScroll:true});
      if (selection && next instanceof HTMLInputElement && selection[0] !== null) next.setSelectionRange(...selection);
    }
  }

  function showEditorError(message) {
    const error = document.getElementById('memory-editor-error');
    if (error) {error.textContent=message;error.hidden=false;}
  }

  function closeDialog() { window.DongranUI.closeDialog(); }

  function openEditor(id) {
    const entry = id ? allEntries().find(item => item.id === id) : null;
    if (id && !entry) return false;
    const scope = entry?.scope || selectedScope;
    const projectAtOpen = scope === 'project' ? entry ? {id:entry.projectId,name:entry.projectName} : currentProject ? {...currentProject} : null : null;
    if (scope === 'project' && !projectAtOpen) return false;
    window.DongranUI.showDialog(entry ? '编辑记忆' : '添加记忆', `<form id="memory-editor" novalidate><div class="memory-editor-scope">${icon(scope === 'global' ? 'globe' : 'folder')}<span>${scope === 'global' ? '全局记忆' : escape(projectAtOpen.name)}</span></div><label class="memory-editor-field" for="memory-title">标题<input id="memory-title" name="title" type="text" maxlength="80" value="${escape(entry?.title || '')}" placeholder="例如：代码与文档约定" required autocomplete="off"></label><label class="memory-editor-field" for="memory-content">内容<textarea id="memory-content" name="content" rows="6" maxlength="2000" placeholder="保存需要长期遵循的偏好或项目约定。" required>${escape(entry?.content || '')}</textarea></label><div class="memory-editor-length"><span id="memory-content-length">${entry?.content.length || 0}</span> / 2000</div><p id="memory-editor-error" class="memory-editor-error" role="alert" hidden></p><div class="settings-confirm-actions"><button id="memory-cancel" type="button" class="secondary">取消</button><button id="memory-save" type="submit" class="primary">保存记忆</button></div></form>`);
    document.getElementById('memory-cancel').addEventListener('click',closeDialog);
    document.getElementById('memory-content').addEventListener('input',event => {document.getElementById('memory-content-length').textContent=event.target.value.length;});
    document.getElementById('memory-editor').addEventListener('submit',event => {
      event.preventDefault();
      const title = document.getElementById('memory-title').value.trim();
      const content = document.getElementById('memory-content').value.trim();
      if (!title || !content) {showEditorError('请填写标题和内容。');return;}
      if (entry && !allEntries().some(item => item.id === entry.id)) {showEditorError('这条记忆已被删除，请关闭后重新添加。');return;}
      const now = new Date().toISOString();
      const updated = {id:entry?.id || (globalThis.crypto?.randomUUID?.() || `memory-${Date.now()}-${++idSequence}`),title,content,scope,projectId:projectAtOpen?.id || null,projectName:projectAtOpen?.name || '',createdAt:entry?.createdAt || now,updatedAt:now};
      const next = entry ? allEntries().map(item => item.id === entry.id ? updated : {...item}) : [...allEntries().map(item => ({...item})),updated];
      if (!validateEntries(next)) {showEditorError('记忆容量已满，最多保存 100 条、共 128 KB。请整理后重试。');return;}
      if (!prefs().set('memoryEntries',next)) {showEditorError('无法保存这条记忆，请检查内容后重试。');return;}
      closeDialog();
    });
    document.getElementById('memory-title').focus();
    return true;
  }

  function remove(id) {
    if (!allEntries().some(entry => entry.id === id)) return false;
    return prefs().set('memoryEntries',allEntries().filter(entry => entry.id !== id).map(entry => ({...entry})));
  }

  function clear(scope = selectedScope, projectId = currentProject?.id) {
    if (!['global','project'].includes(scope) || scope === 'project' && !projectId) return false;
    return prefs().set('memoryEntries',allEntries().filter(entry => !(entry.scope === scope && (scope === 'global' || entry.projectId === projectId))).map(entry => ({...entry})));
  }

  function confirmDelete(id) {
    const entry = allEntries().find(item => item.id === id);
    if (!entry) return;
    window.DongranUI.showDialog('删除记忆', `<p class="settings-confirm-copy">删除“${escape(entry.title)}”？这条记忆将从本机移除。</p><div class="settings-confirm-actions"><button id="memory-cancel-delete" class="secondary" type="button">取消</button><button id="memory-confirm-delete" class="primary" type="button">删除</button></div>`);
    document.getElementById('memory-cancel-delete').addEventListener('click',closeDialog);
    document.getElementById('memory-confirm-delete').addEventListener('click',() => {remove(id);closeDialog();});
    document.getElementById('memory-cancel-delete').focus();
  }

  function confirmClear() {
    const scope = selectedScope;
    const projectId = currentProject?.id;
    const entries = getEntries(scope,projectId);
    if (!entries.length) return;
    window.DongranUI.showDialog(`清空${scope === 'global' ? '全局' : '项目'}记忆`, `<p class="settings-confirm-copy">将删除${scope === 'global' ? '全部全局记忆' : `“${escape(currentProject.name)}”的项目记忆`}，共 ${entries.length} 条。其他范围的记忆会保留。</p><div class="settings-confirm-actions"><button id="memory-cancel-clear" class="secondary" type="button">取消</button><button id="memory-confirm-clear" class="primary" type="button">清空记忆</button></div>`);
    document.getElementById('memory-cancel-clear').addEventListener('click',closeDialog);
    document.getElementById('memory-confirm-clear').addEventListener('click',() => {clear(scope,projectId);closeDialog();});
    document.getElementById('memory-cancel-clear').focus();
  }

  function setProject(project) {
    const id = project?.id || project?.path;
    currentProject = id && project?.name ? {id:String(id),name:String(project.name),path:String(project.path || '')} : null;
    search = '';
    refreshPanel();
  }

  document.addEventListener('click',event => {
    const button = event.target.closest('button');
    if (!button || button.disabled) return;
    if (button.dataset.memoryScope) {
      selectedScope=button.dataset.memoryScope;
      search='';
      refreshPanel();
      document.getElementById(`memory-${selectedScope}-tab`)?.focus({preventScroll:true});
    } else if (button.dataset.memoryPreference) setProjectPreference(button.dataset.memoryPreference,!projectPreference()[button.dataset.memoryPreference]);
    else if (button.dataset.memoryEdit) openEditor(button.dataset.memoryEdit);
    else if (button.dataset.memoryDelete) confirmDelete(button.dataset.memoryDelete);
    else if (button.dataset.memoryCommand === 'add') openEditor();
    else if (button.dataset.memoryCommand === 'clear') confirmClear();
    else if (button.dataset.memoryCommand === 'clear-search') {
      search='';
      refreshPanel();
      document.getElementById('memory-search')?.focus();
    }
  });

  document.addEventListener('input',event => {
    if (event.target.id !== 'memory-search') return;
    search=event.target.value;
    document.getElementById('memory-list').innerHTML=listMarkup();
    document.getElementById('memory-list-count').textContent=`${currentScopeEntries().length} 条记忆`;
    document.getElementById('memory-search-clear').hidden=!search;
    window.lucide?.createIcons();
  });

  document.addEventListener('keydown',event => {
    const tab = event.target.closest('[data-memory-scope]');
    if (!tab || !['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) return;
    event.preventDefault();
    const next = event.key === 'Home' ? 'global' : event.key === 'End' ? 'project' : selectedScope === 'global' ? 'project' : 'global';
    document.getElementById(`memory-${next}-tab`)?.click();
  });

  window.addEventListener('projectchange',event => setProject(event.detail));
  window.addEventListener('settingschange',event => {
    if (['memoryEntries','memoryProjectPreferences','memoryEnabled','reset','reset-category','import'].includes(event.detail.key)) refreshPanel();
  });

  (window.DongranSettingsModules ||= []).push({
    categories:[{id:'memory',label:'记忆',icon:'brain',group:'Agent 与项目',subtitle:'管理通用偏好与项目约定，并为每个项目设置独立范围。',searchKeywords:'记忆 全局 项目 继承 添加 删除 编辑 清空 自动提炼'}],
    definitions:[
      {key:'memoryEnabled',category:'memory',section:'记忆偏好',title:'启用记忆',description:'记忆总开关。关闭后保留条目，任务不再使用已保存的记忆。',icon:'brain',type:'toggle',default:true},
      {key:'memoryAutoCapture',category:'memory',section:'记忆偏好',title:'自动提炼记忆',description:'自动提炼待接入，当前可手动管理记忆。',icon:'sparkles',type:'toggle',default:false},
      {key:'memoryEntries',category:'memory',section:'记忆内容',title:'已保存的记忆',type:'collection',default:[],hidden:true,preserveOnReset:true,validate:validateEntries},
      {key:'memoryProjectPreferences',category:'memory',section:'记忆偏好',title:'项目记忆偏好',type:'collection',default:[],hidden:true,validate:validateProjectPreferences}
    ],
    render(category,api) {
      if (category !== 'memory') return '';
      return `<section class="settings-section memory-section"><h2>已保存的记忆</h2><div id="memory-manager">${panelMarkup()}</div></section>`;
    }
  });
  window.DongranMemory = {getEntries,getEffectiveEntries,getProject:() => currentProject ? {...currentProject} : null,getProjectPreference:projectPreference,setProject,setProjectPreference,openEditor,remove,clear,validateEntries,validateProjectPreferences};
})();
