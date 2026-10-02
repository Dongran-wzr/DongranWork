(() => {
  const catalog = [
    {id:'product-docs', name:'需求文档', icon:'notebook-pen', version:'1.0.0', description:'整理需求、用户流程与验收标准，生成项目需求文档。', permissions:['读取项目文档','在项目内写入 Markdown 文档']},
    {id:'code-review', name:'代码审查', icon:'git-pull-request', version:'1.0.0', description:'检查代码变更、潜在回归与缺失的测试。', permissions:['读取项目代码','读取 Git 差异']},
    {id:'browser-check', name:'浏览器验证', icon:'monitor-check', version:'1.0.0', description:'打开本地页面，检查关键流程并整理验证结果。', permissions:['访问本地预览地址','保存截图与验证记录']}
  ];
  const events = [{value:'before-task', label:'任务开始前'}, {value:'before-tool', label:'工具调用前'}, {value:'after-task', label:'任务结束后'}];
  const policies = [{value:'stop', label:'停止任务'}, {value:'continue', label:'记录并继续'}];
  const connectionTypes = [{value:'mcp-http', label:'MCP · HTTP', icon:'network'}, {value:'mcp-sse', label:'MCP · SSE', icon:'radio'}, {value:'github', label:'GitHub', icon:'github'}];
  const keys = {extensions:'installedExtensions', hooks:'automationHooks', connections:'serviceConnections'};
  const escape = value => String(value ?? '').replace(/[&<>"']/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
  const icon = name => `<i data-lucide="${name}"></i>`;
  const validText = (value, max) => typeof value === 'string' && value.trim().length > 0 && value.length <= max;
  const validId = value => typeof value === 'string' && /^[a-z0-9-]{1,100}$/.test(value);
  const exactKeys = (value, allowed) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === allowed.length && allowed.every(key => Object.hasOwn(value, key));
  const validList = (value, validate) => Array.isArray(value) && value.length <= 100 && value.every(validate) && new Set(value.map(item => item.id)).size === value.length;
  const validUrl = value => {
    if (typeof value !== 'string' || value.length > 2048 || value !== value.trim()) return false;
    try {
      const url = new URL(value);
      return ['http:', 'https:'].includes(url.protocol) && !!url.hostname && !url.username && !url.password && !url.search && !url.hash;
    } catch { return false; }
  };
  const validExtensions = value => validList(value, item => exactKeys(item, ['id','enabled']) && catalog.some(entry => entry.id === item.id) && typeof item.enabled === 'boolean');
  const validHooks = value => validList(value, item => exactKeys(item, ['id','name','event','command','timeout','failurePolicy','enabled']) && validId(item.id) && validText(item.name, 100) && events.some(event => event.value === item.event) && validText(item.command, 4000) && Number.isInteger(item.timeout) && item.timeout >= 1 && item.timeout <= 300 && policies.some(policy => policy.value === item.failurePolicy) && typeof item.enabled === 'boolean');
  const validConnections = value => validList(value, item => exactKeys(item, ['id','name','type','url','enabled']) && validId(item.id) && validText(item.name, 100) && connectionTypes.some(type => type.value === item.type) && validUrl(item.url) && typeof item.enabled === 'boolean');
  const api = () => window.DongranSettings;
  const list = category => api()?.get(keys[category]) || [];
  let extensionTab = 'added';
  let renderApi;
  let draft = null;
  let removal = null;

  function refresh(message) {
    api()?.refresh();
    if (message) renderApi?.showStatus(message);
    window.lucide?.createIcons();
  }

  function save(category, values, message) {
    if (!api()?.set(keys[category], values)) {
      renderApi?.showStatus('配置未保存，请检查输入内容。', true);
      return false;
    }
    refresh(message);
    return true;
  }

  function toggleMarkup(category, item, label) {
    return `<button type="button" class="settings-switch" role="switch" aria-checked="${item.enabled}" aria-label="${escape(label)}" title="${item.enabled ? '禁用' : '启用'}" data-integration-action="toggle" data-integration-category="${category}" data-integration-id="${escape(item.id)}"><span></span></button>`;
  }

  function moreMarkup(category, id, label) {
    return `<button type="button" class="icon-btn integration-more" title="${escape(label)}" aria-label="${escape(label)}" data-integration-action="more" data-integration-category="${category}" data-integration-id="${escape(id)}" aria-haspopup="menu" aria-expanded="false">${icon('ellipsis')}</button>`;
  }

  function emptyMarkup(glyph, title, copy) {
    return `<div class="integration-empty">${icon(glyph)}<strong>${title}</strong><p>${copy}</p></div>`;
  }

  function renderExtensions() {
    const installed = list('extensions');
    const visible = catalog.filter(extension => extensionTab === 'added' ? installed.some(item => item.id === extension.id) : !installed.some(item => item.id === extension.id));
    return `<section class="integration-section" aria-label="扩展管理"><div class="integration-toolbar"><div class="integration-tabs" role="tablist" aria-label="扩展列表"><button type="button" role="tab" aria-selected="${extensionTab === 'added'}" tabindex="${extensionTab === 'added' ? 0 : -1}" data-integration-tab="added">已添加<span>${installed.length}</span></button><button type="button" role="tab" aria-selected="${extensionTab === 'available'}" tabindex="${extensionTab === 'available' ? 0 : -1}" data-integration-tab="available">可添加<span>${catalog.length - installed.length}</span></button></div><span class="integration-caption">本地配置</span></div><div class="integration-list" role="tabpanel" aria-label="${extensionTab === 'added' ? '已添加扩展' : '可添加扩展'}">${visible.map(extension => {
      const item = installed.find(value => value.id === extension.id);
      return `<div class="integration-row" data-extension-id="${extension.id}"><span class="integration-symbol">${icon(extension.icon)}</span><button type="button" class="integration-row-copy integration-detail-link" data-integration-action="extension-detail" data-integration-id="${extension.id}"><strong>${extension.name}<span class="integration-version">${extension.version}</span></strong><p>${extension.description}</p><small>${item ? (item.enabled ? '已启用配置 · 运行时未接入' : '已禁用') : '内置配置示例'}</small></button><div class="integration-row-actions">${item ? `${toggleMarkup('extensions', item, `启用${extension.name}`)}${moreMarkup('extensions', item.id, `${extension.name}选项`)}` : `<button type="button" class="settings-command" data-integration-action="extension-add" data-integration-id="${extension.id}">${icon('plus')}添加</button>`}</div></div>`;
    }).join('') || emptyMarkup('blocks', extensionTab === 'added' ? '还没有添加扩展' : '所有示例已添加', extensionTab === 'added' ? '从“可添加”中选择项目需要的扩展。' : '可在“已添加”中管理扩展配置。')}</div><p class="integration-footnote">${icon('info')}${window.DongranRuntime?.enabled?'内置扩展以任务模板运行，由主 Agent 调用实际项目工具。':'扩展配置保存在本机，尚未连接扩展运行时。'}</p></section>`;
  }

  function renderHooks() {
    const hooks = list('hooks');
    return `<section class="integration-section" aria-label="钩子管理"><div class="integration-toolbar"><span class="integration-list-title">任务钩子 <small>${hooks.length}</small></span><button type="button" class="settings-command" data-integration-action="hook-add">${icon('plus')}添加钩子</button></div><div class="integration-list">${hooks.map(item => `<div class="integration-row" data-hook-id="${escape(item.id)}"><span class="integration-symbol">${icon('workflow')}</span><div class="integration-row-copy"><strong>${escape(item.name)}</strong><p class="integration-command" title="${escape(item.command)}">${escape(item.command)}</p><small>${events.find(event => event.value === item.event).label} · ${item.timeout} 秒 · ${policies.find(policy => policy.value === item.failurePolicy).label}</small></div><div class="integration-row-actions">${toggleMarkup('hooks', item, `启用${item.name}`)}${moreMarkup('hooks', item.id, `${item.name}选项`)}</div></div>`).join('') || emptyMarkup('workflow', '还没有配置钩子', '在任务或工具执行前后安排项目命令。')}</div><p class="integration-footnote">${icon('info')}这里只保存钩子配置，命令不会执行。</p></section>`;
  }

  function renderConnections() {
    const connections = list('connections');
    return `<section class="integration-section" aria-label="连接管理"><div class="integration-toolbar"><span class="integration-list-title">服务连接 <small>${connections.length}</small></span><button type="button" class="settings-command" data-integration-action="connection-add" aria-haspopup="menu" aria-expanded="false">${icon('plus')}添加连接${icon('chevron-down')}</button></div><div class="integration-list">${connections.map(item => {
      const type = connectionTypes.find(entry => entry.value === item.type);
      return `<div class="integration-row" data-connection-id="${escape(item.id)}"><span class="integration-symbol">${icon(type.icon)}</span><div class="integration-row-copy"><strong>${escape(item.name)}<span class="integration-state">未连接</span></strong><p class="integration-command" title="${escape(item.url)}">${escape(item.url)}</p><small>${type.label}${item.enabled ? '' : ' · 已禁用'}</small></div><div class="integration-row-actions">${toggleMarkup('connections', item, `启用${item.name}`)}${moreMarkup('connections', item.id, `${item.name}选项`)}</div></div>`;
    }).join('') || emptyMarkup('unplug', '还没有添加连接', '添加 MCP 服务地址或 GitHub 配置。')}</div><p class="integration-footnote">${icon('lock-keyhole')}连接配置保存在本机；当前不发起连接，也不保存访问密钥。</p></section>`;
  }

  function closeDialog() {
    draft = null;
    removal = null;
    window.DongranUI.closeDialog();
  }

  function extensionDetail(id) {
    const extension = catalog.find(item => item.id === id);
    if (!extension) return;
    const item = list('extensions').find(value => value.id === id);
    window.DongranUI.showDialog(extension.name, `<div class="integration-detail"><div class="integration-detail-meta"><span class="integration-symbol">${icon(extension.icon)}</span><span>内置配置示例 · ${extension.version}</span><span class="integration-state">待接入</span></div><p>${extension.description}</p><h3>需要的项目权限</h3><ul>${extension.permissions.map(permission => `<li>${icon('check')}<span>${permission}</span></li>`).join('')}</ul><p class="integration-dialog-note">${item ? '配置已添加到本机。' : '添加后只会保存本地配置。'}${window.DongranRuntime?.enabled?'可在扩展列表中交给主 Agent 执行。':'当前未连接扩展运行时。'}</p><div class="integration-dialog-actions"><button type="button" class="secondary" data-integration-action="cancel">关闭</button>${item ? '' : `<button type="button" class="primary" data-integration-action="extension-add" data-integration-id="${id}">${icon('plus')}添加到本地配置</button>`}</div></div>`);
  }

  function field(label, name, value, options = {}) {
    const id = `integration-${name}`;
    return `<label class="integration-field" for="${id}"><span>${label}</span><input id="${id}" name="${name}" type="${options.type || 'text'}" value="${escape(value)}" ${options.required === false ? '' : 'required'} ${options.maxLength ? `maxlength="${options.maxLength}"` : ''} ${options.type === 'number' ? 'min="1" max="300" step="1"' : ''} autocomplete="off" spellcheck="false" placeholder="${escape(options.placeholder || '')}"></label>`;
  }

  function picker(label, name, value, options) {
    const selected = options.find(item => item.value === value);
    return `<div class="integration-field"><span id="integration-${name}-label">${label}</span><button type="button" id="integration-${name}" class="integration-select" data-integration-action="pick" data-integration-field="${name}" aria-labelledby="integration-${name}-label integration-${name}-value" aria-haspopup="menu" aria-expanded="false"><span id="integration-${name}-value">${escape(selected.label)}</span>${icon('chevron-down')}</button></div>`;
  }

  function newId(prefix) {
    return `${prefix}-${crypto.randomUUID()}`;
  }

  function hookDialog(id) {
    const existing = id && list('hooks').find(item => item.id === id);
    if (id && !existing) return;
    draft = {category:'hooks', editing:!!existing, value:existing ? {...existing} : {id:newId('hook'), name:'', event:'before-task', command:'', timeout:30, failurePolicy:'stop', enabled:true}};
    const item = draft.value;
    window.DongranUI.showDialog(existing ? '编辑钩子' : '添加钩子', `<form id="integration-editor" class="integration-form" novalidate>${field('名称', 'name', item.name, {maxLength:100, placeholder:'例如：检查工作区'})}${picker('触发事件', 'event', item.event, events)}<label class="integration-field" for="integration-command"><span>执行命令</span><textarea id="integration-command" name="command" required maxlength="4000" rows="3" spellcheck="false" placeholder="例如：npm run lint">${escape(item.command)}</textarea></label><div class="integration-field-grid">${field('超时（秒）', 'timeout', item.timeout, {type:'number'})}${picker('命令失败时', 'failurePolicy', item.failurePolicy, policies)}</div><p class="integration-dialog-note">${window.DongranRuntime?.enabled?'钩子会在指定任务事件执行，并遵守命令审批与失败策略。':'仅保存配置，当前不会执行命令。'}</p><p id="integration-form-error" class="integration-form-error" role="alert" hidden></p><div class="integration-dialog-actions"><button type="button" class="secondary" data-integration-action="cancel">取消</button><button type="submit" class="primary">保存钩子</button></div></form>`);
    document.getElementById('integration-name').focus();
  }

  function connectionDialog(type, id) {
    const existing = id && list('connections').find(item => item.id === id);
    if (id && !existing) return;
    draft = {category:'connections', editing:!!existing, value:existing ? {...existing} : {id:newId('connection'), name:type === 'github' ? 'GitHub' : '', type, url:type === 'github' ? 'https://api.github.com' : '', enabled:true}};
    const item = draft.value;
    window.DongranUI.showDialog(existing ? '编辑连接' : '添加连接', `<form id="integration-editor" class="integration-form" novalidate>${field('名称', 'name', item.name, {maxLength:100, placeholder:'例如：团队知识库'})}${picker('连接类型', 'type', item.type, connectionTypes)}${field('服务地址', 'url', item.url, {type:'url', maxLength:2048, placeholder:'https://example.com/mcp'})}<p class="integration-dialog-note">使用 HTTP 或 HTTPS 地址，请勿填写密钥、账号密码或带查询参数的地址。${window.DongranRuntime?.enabled?'保存后可测试连接；令牌通过“凭据”按钮单独管理。':'当前仅保存配置，连接状态保持“未连接”。'}</p><p id="integration-form-error" class="integration-form-error" role="alert" hidden></p><div class="integration-dialog-actions"><button type="button" class="secondary" data-integration-action="cancel">取消</button><button type="submit" class="primary">保存连接</button></div></form>`);
    document.getElementById('integration-name').focus();
  }

  function removeDialog(category, id) {
    const item = list(category).find(entry => entry.id === id);
    if (!item) return;
    const name = category === 'extensions' ? catalog.find(entry => entry.id === id).name : item.name;
    removal = {category, id};
    draft = null;
    window.DongranUI.showDialog('移除配置', `<div class="integration-detail"><p>移除“${escape(name)}”的本地配置？</p><p class="integration-dialog-note">此操作不会删除项目文件。</p><div class="integration-dialog-actions"><button type="button" class="secondary" data-integration-action="cancel">取消</button><button type="button" class="primary integration-remove-confirm" data-integration-action="remove-confirm">移除</button></div></div>`);
  }

  function showMore(trigger) {
    const {integrationCategory:category, integrationId:id} = trigger.dataset;
    window.DongranUI.showMenu(trigger, {label:'配置选项', items:[{value:'edit', label:category === 'extensions' ? '查看详情' : '编辑', icon:category === 'extensions' ? 'info' : 'pencil'}, {type:'separator'}, {value:'remove', label:'移除', icon:'trash-2'}], onSelect:value => {
      if (value === 'remove') removeDialog(category, id);
      else if (category === 'extensions') extensionDetail(id);
      else if (category === 'hooks') hookDialog(id);
      else connectionDialog(null, id);
    }});
  }

  document.addEventListener('click', event => {
    if (!(event.target instanceof Element)) return;
    const tab = event.target.closest('[data-integration-tab]');
    if (tab) { extensionTab = tab.dataset.integrationTab; refresh(); document.querySelector(`[data-integration-tab="${extensionTab}"]`)?.focus(); return; }
    const trigger = event.target.closest('[data-integration-action]');
    if (!trigger) return;
    const {integrationAction:action, integrationId:id, integrationCategory:category} = trigger.dataset;
    if (action === 'cancel') return closeDialog();
    if (action === 'more') return showMore(trigger);
    if (action === 'extension-detail') return extensionDetail(id);
    if (action === 'extension-add') {
      if (!catalog.some(item => item.id === id) || list('extensions').some(item => item.id === id)) return;
      if (save('extensions', [...list('extensions'), {id, enabled:true}])) {
        if (trigger.closest('dialog')) closeDialog();
      }
      return;
    }
    if (action === 'toggle') {
      const updated = list(category).map(item => item.id === id ? {...item, enabled:!item.enabled} : item);
      save(category, updated);
      document.querySelector(`[data-integration-action="toggle"][data-integration-category="${category}"][data-integration-id="${id}"]`)?.focus();
      return;
    }
    if (action === 'hook-add') return hookDialog();
    if (action === 'connection-add') return window.DongranUI.showMenu(trigger, {label:'连接类型', items:connectionTypes, onSelect:type => connectionDialog(type)});
    if (action === 'remove-confirm' && removal) {
      if (save(removal.category, list(removal.category).filter(item => item.id !== removal.id))) closeDialog();
      return;
    }
    if (action === 'pick' && draft) {
      const name = trigger.dataset.integrationField;
      const options = name === 'event' ? events : name === 'failurePolicy' ? policies : connectionTypes;
      window.DongranUI.showMenu(trigger, {label:document.getElementById(`integration-${name}-label`).textContent, items:options.map(option => ({...option, checked:option.value === draft.value[name]})), onSelect:value => {
        if (!draft) return;
        draft.value = {...draft.value, [name]:value};
        document.getElementById(`integration-${name}-value`).textContent = options.find(option => option.value === value).label;
      }});
    }
  });

  document.addEventListener('keydown', event => {
    if (!(event.target instanceof Element)) return;
    const tab = event.target.closest('[data-integration-tab]');
    if (tab && ['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) {
      event.preventDefault();
      extensionTab = event.key === 'Home' ? 'added' : event.key === 'End' ? 'available' : extensionTab === 'added' ? 'available' : 'added';
      refresh(); document.querySelector(`[data-integration-tab="${extensionTab}"]`)?.focus();
    }
    const trigger = event.target.closest('[data-integration-action="pick"], [data-integration-action="more"], [data-integration-action="connection-add"]');
    if (trigger && ['ArrowDown','ArrowUp'].includes(event.key)) { event.preventDefault(); trigger.click(); }
  });

  document.addEventListener('submit', event => {
    if (event.target.id !== 'integration-editor' || !draft) return;
    event.preventDefault();
    const form = event.target;
    const values = new FormData(form);
    const candidate = {...draft.value, name:String(values.get('name')).trim()};
    let error = '';
    let invalidName = '';
    if (!validText(candidate.name, 100)) { error = '请填写名称，最多 100 个字符。'; invalidName = 'name'; }
    if (draft.category === 'hooks') {
      candidate.command = String(values.get('command')).trim();
      candidate.timeout = Number(values.get('timeout'));
      if (!error && !validText(candidate.command, 4000)) { error = '请填写执行命令，最多 4000 个字符。'; invalidName = 'command'; }
      if (!error && (!Number.isInteger(candidate.timeout) || candidate.timeout < 1 || candidate.timeout > 300)) { error = '超时必须是 1 至 300 之间的整数。'; invalidName = 'timeout'; }
    } else {
      candidate.url = String(values.get('url')).trim();
      if (!error && !validUrl(candidate.url)) { error = '请填写有效的 HTTP 或 HTTPS 地址，不包含账号密码、查询参数或片段。'; invalidName = 'url'; }
    }
    const current = list(draft.category);
    if (!error && draft.editing && !current.some(item => item.id === candidate.id)) error = '这项配置已被移除，请关闭窗口后重新添加。';
    if (!error && !draft.editing && current.length >= 100) error = '最多可保存 100 项配置，请先移除不再使用的配置。';
    form.querySelectorAll('[aria-invalid]').forEach(input => input.removeAttribute('aria-invalid'));
    const errorElement = document.getElementById('integration-form-error');
    errorElement.hidden = !error;
    errorElement.textContent = error;
    if (error) {
      const input = invalidName && form.elements.namedItem(invalidName);
      if (input) { input.setAttribute('aria-invalid', 'true'); input.focus(); }
      return;
    }
    const updated = draft.editing ? current.map(item => item.id === candidate.id ? candidate : item) : [...current, candidate];
    if (save(draft.category, updated)) closeDialog();
  });

  document.getElementById('dialog')?.addEventListener('close', () => { draft = null; removal = null; });
  (window.DongranSettingsModules ||= []).push({
    categories:[
      {id:'extensions', label:'扩展管理', icon:'blocks', group:'集成', subtitle:'管理项目可使用的扩展能力。', searchKeywords:'扩展 插件 需求文档 代码审查 浏览器验证 extensions plugins', status:'待接入'},
      {id:'hooks', label:'钩子', icon:'workflow', group:'集成', subtitle:'配置任务与工具执行前后的命令。', searchKeywords:'钩子 命令 事件 超时 失败策略 before-task before-tool after-task hooks', status:'待接入'},
      {id:'connections', label:'连接', icon:'plug', group:'集成', subtitle:'管理 MCP 服务与代码平台连接。', searchKeywords:'MCP HTTP SSE GitHub 连接 服务地址 connections', status:'待接入'}
    ],
    definitions:[
      {key:keys.extensions, category:'extensions', section:'扩展配置', title:'已添加扩展', description:'保存在本机的扩展配置。', icon:'blocks', type:'collection', hidden:true, default:[], validate:validExtensions},
      {key:keys.hooks, category:'hooks', section:'钩子配置', title:'任务钩子', description:'保存在本机的命令钩子。', icon:'workflow', type:'collection', hidden:true, default:[], validate:validHooks},
      {key:keys.connections, category:'connections', section:'连接配置', title:'服务连接', description:'保存在本机的服务地址。', icon:'plug', type:'collection', hidden:true, default:[], validate:validConnections}
    ],
    render(category, context) {
      renderApi = context;
      if (category === 'extensions') return renderExtensions();
      if (category === 'hooks') return renderHooks();
      if (category === 'connections') return renderConnections();
      return '';
    }
  });
})();
