(() => {
  const STORAGE_KEY = 'dongran.schedules.v1';
  const KEYS = ['id','name','prompt','scope','projectId','projectName','frequency','time','weekdays','date','enabled','createdAt','updatedAt'];
  const FREQUENCIES = {daily:'每天',weekly:'每周',once:'一次'};
  const WEEKDAYS = ['一','二','三','四','五','六','日'];
  const escape = value => String(value).replace(/[&<>"']/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
  const icon = name => `<i data-lucide="${name}"></i>`;
  const currentProject = () => window.DongranProjects?.current?.() || null;
  const currentZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || '系统本地时间';
  const textWithin = (value,limit) => typeof value === 'string' && !!value.trim() && value.length <= limit;
  let tasks = [];
  let revision = null;
  let saving = false;
  let host = null;
  let filter = 'all';
  let query = '';
  let status = '';
  let idSequence = 0;

  function validDate(date) {
    if (typeof date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(date)) return false;
    const parsed = new Date(`${date}T12:00:00`);
    return Number.isFinite(parsed.getTime()) && parsed.getFullYear() === Number(date.slice(0,4)) && parsed.getMonth()+1 === Number(date.slice(5,7)) && parsed.getDate() === Number(date.slice(8,10));
  }

  function validTimestamp(value) {
    return typeof value === 'string' && value.length === 24 && Number.isFinite(Date.parse(value)) && new Date(value).toISOString() === value;
  }

  function validateTask(task) {
    if (!task || typeof task !== 'object' || Array.isArray(task) || Object.keys(task).length !== KEYS.length || Object.keys(task).some(key => !KEYS.includes(key))) return false;
    return typeof task.id === 'string' && /^[a-zA-Z0-9_-]{1,100}$/.test(task.id) && textWithin(task.name,80) && textWithin(task.prompt,4000)
      && ['global','project'].includes(task.scope) && (task.scope === 'global' ? task.projectId === null && task.projectName === '' : textWithin(task.projectId,2048) && textWithin(task.projectName,255))
      && Object.hasOwn(FREQUENCIES,task.frequency) && typeof task.time === 'string' && /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(task.time)
      && Array.isArray(task.weekdays) && task.weekdays.every(day => Number.isInteger(day) && day >= 1 && day <= 7) && new Set(task.weekdays).size === task.weekdays.length
      && (task.frequency === 'weekly' ? task.weekdays.length > 0 && task.date === '' : task.weekdays.length === 0 && (task.frequency === 'once' ? validDate(task.date) : task.date === ''))
      && typeof task.enabled === 'boolean' && validTimestamp(task.createdAt) && validTimestamp(task.updatedAt) && task.updatedAt >= task.createdAt;
  }

  function validateDocument(value) {
    if (!value || value.version !== 1 || !Array.isArray(value.tasks) || value.tasks.length > 100) return false;
    const ids = new Set();
    return value.tasks.every(task => {
      if (!validateTask(task) || ids.has(task.id)) return false;
      ids.add(task.id);
      return true;
    });
  }

  try {
    const raw = /^https?:$/.test(location.protocol) ? null : localStorage.getItem(STORAGE_KEY);
    if (raw) {
      if (raw.length > 2000000) throw new Error('oversized');
      const saved = JSON.parse(raw);
      if (!validateDocument(saved)) throw new Error('invalid');
      tasks=saved.tasks;
    }
  } catch {
    status='无法读取本机定时任务，当前显示空列表。';
  }

  async function persist(next) {
    if(saving){status='正在保存，请稍后再试。';return false;}
    if(window.DongranRuntime?.enabled){
      if (!validateDocument({version:1,tasks:next})) {status='配置格式无效，原配置已保留。';return false;}
      saving=true;
      try{const result=await window.DongranRuntime.request('/api/schedules','PUT',{tasks:next,revision});tasks=result.tasks;revision=result.revision;status='';return true;}
      catch(error){status=error.message;if(error.status===409){try{const latest=await window.DongranRuntime.request('/api/schedules');tasks=latest.tasks;revision=latest.revision;}catch{}}return false;}
      finally{saving=false;}
    }

    if (!validateDocument({version:1,tasks:next})) {status='配置格式无效，原配置已保留。';return false;}
    try {
      localStorage.setItem(STORAGE_KEY,JSON.stringify({version:1,tasks:next}));
      tasks=next;
      status='';
      return true;
    } catch {
      status='保存失败：无法写入本机存储，原配置已保留。';
      return false;
    }
  }

  function isExpired(task) {
    return task.frequency === 'once' && new Date(`${task.date}T${task.time}:00`).getTime() <= Date.now();
  }

  function ruleSummary(task) {
    if (task.frequency === 'once') return `${task.date} ${task.time}`;
    if (task.frequency === 'weekly') return `每周${task.weekdays.map(day => WEEKDAYS[day-1]).join('、')} ${task.time}`;
    return `每天 ${task.time}`;
  }

  function filteredTasks() {
    const term = query.trim().toLocaleLowerCase();
    return tasks.filter(task => (filter === 'all' || (filter === 'enabled' ? task.enabled : !task.enabled)) && (!term || `${task.name} ${task.prompt} ${task.projectName}`.toLocaleLowerCase().includes(term))).sort((a,b) => b.updatedAt.localeCompare(a.updatedAt));
  }

  function rowsMarkup() {
    const entries=filteredTasks();
    if (!entries.length) return `<div class="schedules-empty">${icon(query ? 'search' : 'calendar-clock')}<h2>${query ? '没有找到匹配的任务' : tasks.length ? filter === 'enabled' ? '没有已启用的任务' : '没有已暂停的任务' : '还没有定时任务'}</h2><p>${query ? '试试其他名称、项目或任务关键词。' : tasks.length ? '可以切换范围查看其他任务。' : '为重复的工作保存一个执行计划。'}</p>${!tasks.length && !query ? `<button type="button" class="settings-command" data-schedule-command="add">${icon('plus')}新建定时任务</button>` : ''}</div>`;
    return entries.map(task => `<article class="schedule-row" data-schedule-id="${task.id}"><span class="schedule-row-icon">${icon('calendar-clock')}</span><div class="schedule-row-main"><h2>${escape(task.name)}</h2><p class="schedule-task-prompt">${escape(task.prompt)}</p><div class="schedule-row-meta"><span>${icon(task.scope === 'global' ? 'globe' : 'folder')}${task.scope === 'global' ? '全局' : escape(task.projectName)}</span><span>${icon('clock-3')}${escape(ruleSummary(task))}</span>${isExpired(task) ? '<span class="schedule-expired">日期已过</span>' : ''}</div></div><div class="schedule-row-status"><span>${task.enabled ? '已启用' : '已暂停'}</span><button id="schedule-toggle-${task.id}" type="button" class="settings-switch" role="switch" aria-label="${task.enabled ? '暂停' : '启用'} ${escape(task.name)}" aria-checked="${task.enabled}" data-schedule-toggle="${task.id}"><span></span></button></div><button id="schedule-more-${task.id}" class="icon-btn schedule-more" type="button" data-schedule-more="${task.id}" title="更多操作" aria-label="${escape(task.name)}的更多操作" aria-haspopup="menu" aria-expanded="false">${icon('ellipsis')}</button></article>`).join('');
  }

  function render(container) {
    host=container;
    if (!host) return;
    host.innerHTML=`<div class="schedules-page" data-schedules-page><div class="schedules-page-inner"><header class="schedules-header"><div><h1 id="workspace-page-title" tabindex="-1">定时任务</h1><p>安排主 Agent 的重复工作与单次计划。</p></div><button id="schedule-add" type="button" class="schedules-create" data-schedule-command="add">${icon('plus')}新建任务</button></header><div class="schedules-service-state">${icon('clock-3')}<span>${window.DongranRuntime?.enabled?'本地调度已连接':'调度服务待接入'}</span><span class="schedules-state-separator"></span><span>计划仅保存在本机</span><span class="schedules-timezone">时区：${escape(currentZone())}</span></div><p id="schedule-storage-error" class="schedules-error" role="alert" ${status ? '' : 'hidden'}>${escape(status)}</p><div class="schedules-toolbar"><div class="schedules-tabs" role="tablist" aria-label="任务状态">${[['all','全部'],['enabled','已启用'],['paused','已暂停']].map(([value,label]) => `<button id="schedule-filter-${value}" type="button" role="tab" aria-selected="${filter === value}" tabindex="${filter === value ? 0 : -1}" data-schedule-filter="${value}">${label}<span>${value === 'all' ? tasks.length : tasks.filter(task => value === 'enabled' ? task.enabled : !task.enabled).length}</span></button>`).join('')}</div><div class="schedules-search">${icon('search')}<input id="schedule-search" type="search" placeholder="搜索定时任务..." aria-label="搜索定时任务" value="${escape(query)}"><button id="schedule-search-clear" type="button" class="icon-btn" title="清空搜索" aria-label="清空搜索" data-schedule-command="clear-search" ${query ? '' : 'hidden'}>${icon('x')}</button></div></div><div id="schedule-list" class="schedule-list">${rowsMarkup()}</div><footer class="schedules-footer"><span id="schedule-visible-count">${filteredTasks().length} 个任务</span><span>${window.DongranRuntime?.enabled?'仅在应用运行期间执行':'当前不会自动执行'}</span></footer></div></div>`;
    window.lucide?.createIcons();
  }

  function refresh() {
    if (!host?.isConnected || !host.querySelector('[data-schedules-page]')) return;
    const focused=document.activeElement;
    const focusId=host.contains(focused) ? focused.id : '';
    const selection=focused instanceof HTMLInputElement ? [focused.selectionStart,focused.selectionEnd] : null;
    render(host);
    const replacement=focusId ? document.getElementById(focusId) : null;
    replacement?.focus({preventScroll:true});
    if (selection && replacement instanceof HTMLInputElement && selection[0] !== null) replacement.setSelectionRange(...selection);
  }

  function showPageError(message) {
    status=message;
    const error=document.getElementById('schedule-storage-error');
    if (error) {error.textContent=message;error.hidden=!message;}
  }

  async function toggle(id) {
    const entry=tasks.find(task => task.id === id);
    if (!entry) return false;
    if (!entry.enabled && isExpired(entry)) {showPageError('这项单次计划的日期已过，请先编辑为未来时间。');return false;}
    const next=tasks.map(task => task.id === id ? {...task,enabled:!task.enabled,updatedAt:new Date().toISOString()} : {...task,weekdays:[...task.weekdays]});
    const saved=await persist(next);
    refresh();
    return saved;
  }

  function today() {
    const value=new Date();
    return `${value.getFullYear()}-${String(value.getMonth()+1).padStart(2,'0')}-${String(value.getDate()).padStart(2,'0')}`;
  }

  function openEditor(id) {
    const original=id ? tasks.find(task => task.id === id) : null;
    if (id && !original) return false;
    const project=original?.scope === 'project' ? {id:original.projectId,name:original.projectName} : currentProject();
    const draft={scope:original?.scope || (project ? 'project' : 'global'),frequency:original?.frequency || 'daily',weekdays:original?.frequency === 'weekly' ? [...original.weekdays] : [1,2,3,4,5],enabled:original?.enabled ?? true};
    const scopeLabel=() => draft.scope === 'global' ? '全局' : project?.name || '当前项目';
    window.DongranUI.showDialog(original ? '编辑定时任务' : '新建定时任务', `<form id="schedule-form" class="schedule-form" novalidate><label class="schedule-form-field" for="schedule-name">任务名称<input id="schedule-name" type="text" maxlength="80" value="${escape(original?.name || '')}" placeholder="例如：每日需求与代码检查" required autocomplete="off"></label><label class="schedule-form-field" for="schedule-prompt">交给主 Agent 的任务<textarea id="schedule-prompt" rows="4" maxlength="4000" placeholder="描述需要完成的工作与期望结果。" required>${escape(original?.prompt || '')}</textarea></label><div class="schedule-form-grid"><div class="schedule-form-field"><span id="schedule-scope-label">工作范围</span><button id="schedule-scope" type="button" class="settings-select" aria-labelledby="schedule-scope-label schedule-scope-value" aria-haspopup="menu" aria-expanded="false"><span id="schedule-scope-value">${escape(scopeLabel())}</span>${icon('chevron-down')}</button></div><div class="schedule-form-field"><span id="schedule-frequency-label">重复频率</span><button id="schedule-frequency" type="button" class="settings-select" aria-labelledby="schedule-frequency-label schedule-frequency-value" aria-haspopup="menu" aria-expanded="false"><span id="schedule-frequency-value">${FREQUENCIES[draft.frequency]}</span>${icon('chevron-down')}</button></div></div><div id="schedule-weekday-field" class="schedule-form-field" ${draft.frequency === 'weekly' ? '' : 'hidden'}><span>星期</span><div class="schedule-weekdays" role="group" aria-label="每周执行日">${WEEKDAYS.map((day,index) => `<button type="button" data-schedule-weekday="${index+1}" aria-label="星期${day}" aria-pressed="${draft.weekdays.includes(index+1)}">${day}</button>`).join('')}</div></div><div class="schedule-form-grid"><label id="schedule-date-field" class="schedule-form-field" for="schedule-date" ${draft.frequency === 'once' ? '' : 'hidden'}>日期<input id="schedule-date" type="date" min="${today()}" value="${original?.date || today()}"></label><label class="schedule-form-field" for="schedule-time">时间<input id="schedule-time" type="text" value="${original?.time || '09:00'}" maxlength="5" inputmode="numeric" placeholder="09:00" pattern="(?:[01][0-9]|2[0-3]):[0-5][0-9]" aria-describedby="schedule-timezone-note"></label></div><div id="schedule-timezone-note" class="schedule-timezone-note">${icon('globe')}系统时区：${escape(currentZone())}</div><div class="schedule-form-enabled"><span>启用计划</span><button id="schedule-enabled" type="button" class="settings-switch" role="switch" aria-label="启用计划" aria-checked="${draft.enabled}"><span></span></button></div><div class="schedule-form-notice">${icon('clock-3')}${window.DongranRuntime?.enabled?'应用运行时执行；完全退出后停止，不补跑离线期间的计划。':'调度服务待接入，保存后不会自动执行。'}</div><p id="schedule-form-error" class="schedules-error" role="alert" hidden></p><div class="settings-confirm-actions"><button id="schedule-cancel" type="button" class="secondary">取消</button><button id="schedule-save" type="submit" class="primary">保存任务</button></div></form>`);
    const form=document.getElementById('schedule-form');
    const error=message => {const target=document.getElementById('schedule-form-error');target.textContent=message;target.hidden=false;};
    const clearError=() => {document.getElementById('schedule-form-error').hidden=true;};
    form.addEventListener('input',clearError);
    const chooseScope=() => window.DongranUI.showMenu(document.getElementById('schedule-scope'),{label:'工作范围',items:[{value:'global',label:'全局',icon:'globe',checked:draft.scope === 'global'},{value:'project',label:project?.name || '当前项目（未选择）',icon:'folder',checked:draft.scope === 'project',disabled:!project}],onSelect:value => {draft.scope=value;document.getElementById('schedule-scope-value').textContent=scopeLabel();clearError();}});
    const chooseFrequency=() => window.DongranUI.showMenu(document.getElementById('schedule-frequency'),{label:'重复频率',items:Object.entries(FREQUENCIES).map(([value,label]) => ({value,label,checked:value === draft.frequency})),onSelect:value => {draft.frequency=value;document.getElementById('schedule-frequency-value').textContent=FREQUENCIES[value];document.getElementById('schedule-weekday-field').hidden=value !== 'weekly';document.getElementById('schedule-date-field').hidden=value !== 'once';clearError();}});
    document.getElementById('schedule-scope').addEventListener('click',chooseScope);
    document.getElementById('schedule-frequency').addEventListener('click',chooseFrequency);
    form.addEventListener('keydown',event => {
      if (!['ArrowDown','ArrowUp'].includes(event.key)) return;
      if (event.target.id === 'schedule-scope') {event.preventDefault();chooseScope();}
      if (event.target.id === 'schedule-frequency') {event.preventDefault();chooseFrequency();}
    });
    form.addEventListener('click',event => {
      const button=event.target.closest('[data-schedule-weekday]');
      if (!button) return;
      const day=Number(button.dataset.scheduleWeekday);
      draft.weekdays=draft.weekdays.includes(day) ? draft.weekdays.filter(value => value !== day) : [...draft.weekdays,day].sort((a,b) => a-b);
      button.setAttribute('aria-pressed',String(draft.weekdays.includes(day)));
      clearError();
    });
    document.getElementById('schedule-enabled').addEventListener('click',event => {draft.enabled=!draft.enabled;event.currentTarget.setAttribute('aria-checked',String(draft.enabled));});
    document.getElementById('schedule-cancel').addEventListener('click',() => window.DongranUI.closeDialog());
    form.addEventListener('submit',async event => {
      event.preventDefault();
      const name=document.getElementById('schedule-name').value.trim();
      const prompt=document.getElementById('schedule-prompt').value.trim();
      const time=document.getElementById('schedule-time').value.trim();
      const date=draft.frequency === 'once' ? document.getElementById('schedule-date').value : '';
      if (!textWithin(name,80)) {error('请填写任务名称，最多 80 个字符。');return;}
      if (!textWithin(prompt,4000)) {error('请填写任务内容，最多 4000 个字符。');return;}
      if (!/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(time)) {error('请输入有效的 24 小时时间，例如 09:00。');return;}
      if (draft.scope === 'project' && !project) {error('请先选择一个项目。');return;}
      if (draft.frequency === 'weekly' && !draft.weekdays.length) {error('每周计划至少选择一天。');return;}
      if (draft.frequency === 'once' && (!validDate(date) || new Date(`${date}T${time}:00`).getTime() <= Date.now())) {error('单次计划需要设置未来的日期和时间。');return;}
      if (original && !tasks.some(task => task.id === original.id)) {error('这项任务已被删除，请关闭后重新创建。');return;}
      if (!original && tasks.length >= 100) {error('最多保存 100 项定时任务，请先整理已有任务。');return;}
      const now=new Date().toISOString();
      const entry={id:original?.id || (globalThis.crypto?.randomUUID?.() || `schedule-${Date.now()}-${++idSequence}`),name,prompt,scope:draft.scope,projectId:draft.scope === 'project' ? project.id : null,projectName:draft.scope === 'project' ? project.name : '',frequency:draft.frequency,time,weekdays:draft.frequency === 'weekly' ? [...draft.weekdays] : [],date,enabled:draft.enabled,createdAt:original?.createdAt || now,updatedAt:now};
      const next=original ? tasks.map(task => task.id === original.id ? entry : {...task,weekdays:[...task.weekdays]}) : [...tasks.map(task => ({...task,weekdays:[...task.weekdays]})),entry];
      if (!await persist(next)) {error(status || '配置无法保存，请检查输入。');return;}
      refresh();
      window.DongranUI.closeDialog();
    });
    document.getElementById('schedule-name').focus();
    return true;
  }

  function confirmDelete(id) {
    const entry=tasks.find(task => task.id === id);
    if (!entry) return;
    window.DongranUI.showDialog('删除定时任务', `<p class="settings-confirm-copy">删除“${escape(entry.name)}”？这项计划将从本机移除。</p><p id="schedule-delete-error" class="schedules-error" role="alert" hidden></p><div class="settings-confirm-actions"><button id="schedule-cancel-delete" type="button" class="secondary">取消</button><button id="schedule-confirm-delete" type="button" class="primary">删除任务</button></div>`);
    document.getElementById('schedule-cancel-delete').addEventListener('click',() => window.DongranUI.closeDialog());
    document.getElementById('schedule-confirm-delete').addEventListener('click',async () => {
      if (!await persist(tasks.filter(task => task.id !== id).map(task => ({...task,weekdays:[...task.weekdays]})))) {
        const error=document.getElementById('schedule-delete-error');error.textContent=status;error.hidden=false;return;
      }
      refresh();
      window.DongranUI.closeDialog();
    });
    document.getElementById('schedule-cancel-delete').focus();
  }

  document.addEventListener('click',event => {
    const button=event.target.closest('button');
    if (!button || button.disabled) return;
    if (button.dataset.scheduleCommand === 'add') openEditor();
    else if (button.dataset.scheduleFilter) {filter=button.dataset.scheduleFilter;refresh();document.getElementById(`schedule-filter-${filter}`)?.focus({preventScroll:true});}
    else if (button.dataset.scheduleToggle) toggle(button.dataset.scheduleToggle);
    else if (button.dataset.scheduleMore) window.DongranUI.showMenu(button,{label:'任务操作',items:[{value:'edit',label:'编辑任务',icon:'pencil'},...(window.DongranRuntime?.enabled?[{value:'run',label:'立即执行',icon:'play'},{value:'history',label:'执行记录',icon:'history'}]:[]),{type:'separator'},{value:'delete',label:'删除任务',icon:'trash-2'}],onSelect:value => value === 'edit' ? openEditor(button.dataset.scheduleMore) : value === 'delete' ? confirmDelete(button.dataset.scheduleMore) : window.DongranRuntime.scheduleAction(button.dataset.scheduleMore,value)});
    else if (button.dataset.scheduleCommand === 'clear-search') {query='';refresh();document.getElementById('schedule-search')?.focus();}
  });
  document.addEventListener('input',event => {
    if (event.target.id !== 'schedule-search') return;
    query=event.target.value;
    document.getElementById('schedule-list').innerHTML=rowsMarkup();
    document.getElementById('schedule-visible-count').textContent=`${filteredTasks().length} 个任务`;
    document.getElementById('schedule-search-clear').hidden=!query;
    window.lucide?.createIcons();
  });
  document.addEventListener('keydown',event => {
    const tab=event.target.closest('[data-schedule-filter]');
    if (!tab || !['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) return;
    event.preventDefault();
    const values=['all','enabled','paused'];
    const index=values.indexOf(filter);
    const next=event.key === 'Home' ? 0 : event.key === 'End' ? 2 : (index+(event.key === 'ArrowRight' ? 1 : -1)+3)%3;
    document.getElementById(`schedule-filter-${values[next]}`)?.click();
  });
  window.addEventListener('projectchange',refresh);
  window.DongranSchedules={hydrate(snapshot){tasks=snapshot.tasks;revision=snapshot.revision;status='';refresh();},render,refresh,openEditor,toggle,getAll:() => tasks.map(task => ({...task,weekdays:[...task.weekdays]})),validateTask,validateDocument,ruleSummary};
})();
