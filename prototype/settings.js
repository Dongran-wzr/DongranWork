(() => {
  const screen = document.getElementById('settings-screen');
  if (!screen) return;

  const STORAGE_KEY = 'dongran.settings.v1';
  const glyph = name => `<i data-lucide="${name}"></i>`;
  const escape = value => String(value).replace(/[&<>"']/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
  const refreshIcons = () => window.lucide?.createIcons();
  const choices = values => values.map(value => typeof value === 'string' ? {value, label:value} : value);
  const modules = window.DongranSettingsModules || [];
  const definitions = [
    {key:'artifactPanel', category:'general', section:'任务与工具', title:'产物面板打开方式', description:'生成文档、代码变更和验证结果后的默认打开方式。', icon:'panel-right', type:'select', default:'manual', options:choices([{value:'manual',label:'手动打开'},{value:'auto',label:'自动展开'}])},
    {key:'showToolCount', category:'general', section:'任务与工具', title:'显示工具调用次数', description:'在折叠的执行记录中显示工具调用数量。', icon:'chart-no-axes-column-increasing', type:'toggle', default:true},
    {key:'expandTools', category:'general', section:'任务与工具', title:'默认展开执行记录', description:'查看 Agent 的文件读取、代码修改与运行记录。', icon:'list-tree', type:'toggle', default:false},
    {key:'showSuggestions', category:'general', section:'对话与通知', title:'任务建议', description:'在新建任务时显示产品、开发和验证建议。', icon:'message-square-plus', type:'toggle', default:true},
    {key:'completionNotice', category:'general', section:'对话与通知', title:'任务完成提醒', description:'任务结束时在应用内显示完成提示。', icon:'bell', type:'toggle', default:true},

    {key:'theme', category:'appearance', section:'主题', title:'显示模式', description:'选择浅色、深色或跟随系统。', icon:'sun-moon', type:'segment', default:'light', options:choices([{value:'light',label:'浅色',icon:'sun'},{value:'dark',label:'深色',icon:'moon'},{value:'system',label:'跟随系统',icon:'monitor'}])},
    {key:'density', category:'appearance', section:'界面', title:'界面密度', description:'调整列表、任务记录和设置项之间的间距。', icon:'rows-3', type:'segment', default:'comfortable', options:choices([{value:'comfortable',label:'舒适'},{value:'compact',label:'紧凑'}])},
    {key:'fontSize', category:'appearance', section:'界面', title:'文字大小', description:'设置工作台的基础文字大小。', icon:'type', type:'range', default:14, min:12, max:18, step:1, unit:'px'},
    {key:'sidebarWidth', category:'appearance', section:'界面', title:'侧栏宽度', description:'为项目名称和任务列表保留合适的空间。', icon:'panel-left', type:'range', default:274, min:240, max:340, step:1, unit:'px'},
    {key:'showActivity', category:'appearance', section:'显示与动效', title:'首页活动点阵', description:'在首页和新任务页面显示活动点阵。', icon:'grid-3x3', type:'toggle', default:true},
    {key:'reduceMotion', category:'appearance', section:'显示与动效', title:'减少动态效果', description:'减少页面切换、弹窗和菜单的过渡动画。', icon:'accessibility', type:'toggle', default:false},

    {key:'defaultModel', category:'models', section:'模型连接', title:'默认模型', description:'新任务使用的模型偏好。', icon:'sparkles', type:'select', default:'Auto', options:choices(['Auto','自定义模型'])},
    {key:'provider', category:'models', section:'模型连接', title:'模型提供方', description:'选择兼容服务或本地模型。', icon:'server', type:'select', default:'OpenAI compatible', options:choices(['OpenAI compatible','本地模型'])},
    {key:'baseUrl', category:'models', section:'模型连接', title:'服务地址', description:'模型服务的 HTTP 或 HTTPS 地址。', icon:'link', type:'text', default:'http://localhost:11434/v1', maxLength:2048, validate:value => { try { return ['http:','https:'].includes(new URL(value).protocol); } catch { return false; } }},
    {key:'modelName', category:'models', section:'模型连接', title:'模型名称', description:'与服务端提供的模型标识保持一致。', icon:'box', type:'text', default:'', placeholder:'例如 qwen3-coder', maxLength:200},
    {key:'temperature', category:'models', section:'生成参数', title:'随机性', description:'较低值使输出更稳定，较高值增加变化。', icon:'sliders-horizontal', type:'range', default:0.7, min:0, max:2, step:0.1},
    {key:'contextWindow', category:'models', section:'生成参数', title:'上下文上限', description:'每次任务可使用的最大上下文 token 数。', icon:'scan-text', type:'number', default:32768, min:4096, max:131072, step:1024, unit:'tokens'},

    {key:'autoDelegate', category:'agents', section:'任务协作', title:'自动分配任务', description:'主 Agent 根据任务需要安排专业 Agent。', icon:'workflow', type:'toggle', default:true},
    {key:'parallelAgents', category:'agents', section:'任务协作', title:'同时运行的任务数量', description:'限制并行任务数；任务内部由主 Agent 按需调度专业 Agent。', icon:'network', type:'range', default:3, min:1, max:4, step:1},
    {key:'confirmPlan', category:'agents', section:'任务协作', title:'执行前确认计划', description:'首次写文件或运行命令前确认具体操作；普通问答不触发。', icon:'list-checks', type:'toggle', default:true},
    {key:'enableProduct', category:'agents', section:'专业 Agent', title:'产品 Agent', description:'梳理需求、业务流程与验收标准。', icon:'notebook-pen', type:'toggle', default:true},
    {key:'enableDeveloper', category:'agents', section:'专业 Agent', title:'开发 Agent', description:'理解项目结构、修改代码与整理变更。', icon:'code-2', type:'toggle', default:true},
    {key:'enableTester', category:'agents', section:'专业 Agent', title:'测试 Agent', description:'制定测试计划、运行验证与汇总结果。', icon:'flask-conical', type:'toggle', default:true},
    {key:'projectInstructions', category:'agents', section:'补充约束', title:'默认项目指令', description:'新任务默认附带的工作习惯和项目约定。', icon:'file-pen-line', type:'textarea', default:'', placeholder:'例如：先确认需求，再修改代码；完成后运行相关测试。', maxLength:12000},

    {key:'permissionMode', category:'permissions', section:'执行权限', title:'默认执行模式', description:'设置新任务的文件与命令权限。', icon:'shield-check', type:'select', default:'修改前询问', options:choices(['修改前询问','仅规划','允许项目内修改'])},
    {key:'commandApproval', category:'permissions', section:'执行权限', title:'运行命令前确认', description:'执行项目命令前，请求确认。', icon:'square-terminal', type:'toggle', default:true},
    {key:'allowNetwork', category:'permissions', section:'执行权限', title:'允许工具网络访问', description:'控制支持此偏好的工具；当前版本的沙箱命令始终禁止联网，模型连接由本地服务管理。', icon:'globe', type:'toggle', default:false},
    {key:'excludedPaths', category:'permissions', section:'文件访问', title:'排除的路径', description:'每行一个路径，不作为项目上下文读取。', icon:'folder-lock', type:'textarea', default:'.env\nnode_modules\n.git', maxLength:12000},

    {key:'shell', category:'terminal', section:'终端偏好', title:'默认 Shell', description:'新终端会话使用的命令环境。', icon:'terminal', type:'select', default:'PowerShell', options:choices(['PowerShell','Command Prompt','Git Bash'])},
    {key:'terminalFontSize', category:'terminal', section:'终端偏好', title:'终端字号', description:'单独调整命令和输出文字大小。', icon:'type', type:'range', default:12, min:11, max:18, step:1, unit:'px'},
    {key:'terminalHeight', category:'terminal', section:'终端偏好', title:'面板高度', description:'展开终端时的默认高度。', icon:'panel-bottom', type:'range', default:230, min:180, max:360, step:10, unit:'px'},
    {key:'rememberHistory', category:'terminal', section:'终端偏好', title:'保留命令历史', description:'在当前应用会话中保留已输入的命令。', icon:'history', type:'toggle', default:true},

    {key:'settingsKey', category:'shortcuts', section:'工作台', title:'打开设置', description:'', icon:'settings-2', type:'select', default:'Ctrl+,', options:choices(['Ctrl+,','Ctrl+Shift+,'])},
    {key:'terminalKey', category:'shortcuts', section:'工作台', title:'打开或收起终端', description:'', icon:'square-terminal', type:'select', default:'Ctrl+`', options:choices(['Ctrl+`','Ctrl+Shift+J'])},
    {key:'searchKey', category:'shortcuts', section:'工作台', title:'搜索任务', description:'', icon:'search', type:'select', default:'Ctrl+K', options:choices(['Ctrl+K','Ctrl+Shift+F'])}
    ,{key:'gitDefaultBranch', category:'git', section:'Git', title:'默认基准分支', description:'新任务与工作树使用的基准分支。', icon:'git-branch', type:'text', default:'main', maxLength:120, validate:value => !!value && !/[\s~^:?*\[\\]/.test(value)}
    ,{key:'gitBranchPrefix', category:'git', section:'Git', title:'任务分支前缀', description:'创建任务分支时使用的名称前缀。', icon:'git-pull-request', type:'text', default:'agent/', maxLength:80, validate:value => !/[\s~^:?*\[\\]/.test(value)}
    ,{key:'gitAutoFetch', category:'git', section:'Git', title:'自动获取远端更新', description:'打开项目时获取远端分支信息。', icon:'refresh-cw', type:'toggle', default:false}
    ,{key:'gitConfirmCommit', category:'git', section:'Git', title:'提交前确认', description:'审查变更和提交信息后再提交。', icon:'git-commit-horizontal', type:'toggle', default:true}
    ,{key:'gitCommitStyle', category:'git', section:'Git', title:'提交信息格式', description:'Agent 生成提交信息时使用的格式。', icon:'text', type:'select', default:'conventional', options:choices([{value:'conventional',label:'Conventional Commits'},{value:'simple',label:'简洁描述'}])}
    ,{key:'worktreeMode', category:'git', section:'工作树', title:'任务隔离方式', description:'为独立任务分配工作目录与分支。', icon:'git-fork', type:'select', default:'per-task', options:choices([{value:'per-task',label:'每个任务独立工作树'},{value:'current',label:'使用当前目录'},{value:'manual',label:'每次询问'}])}
    ,{key:'worktreeDirectory', category:'git', section:'工作树', title:'工作树目录', description:'相对于项目根目录的路径。', icon:'folder-tree', type:'text', default:'.dongran/worktrees', maxLength:500, validate:value => !!value && !/^[\\/]|^[a-z]:|[\r\n]/i.test(value) && !value.split(/[\\/]/).includes('..')}
    ,{key:'worktreeSetup', category:'git', section:'工作树', title:'初始化命令', description:'工作树创建后的依赖安装或环境准备命令。', icon:'terminal', type:'textarea', default:'', placeholder:'例如 npm ci', maxLength:4000}
    ,{key:'worktreeCleanup', category:'git', section:'工作树', title:'任务完成后', description:'有未提交修改的工作树始终保留。', icon:'archive', type:'select', default:'keep', options:choices([{value:'keep',label:'保留工作树'},{value:'ask',label:'询问是否清理'},{value:'merged',label:'合并后询问清理'}])}
  ].concat(modules.flatMap(module => module.definitions || []));
  const categories = [
    {id:'general', label:'常规', icon:'settings', group:'个人偏好', subtitle:'设置任务、对话与通知的默认行为。'},
    {id:'appearance', label:'外观', icon:'palette', group:'个人偏好', subtitle:'让工作台符合你的阅读与工作习惯。'},
    {id:'shortcuts', label:'快捷键', icon:'keyboard', group:'个人偏好', subtitle:'设置常用操作的快捷键。'},
    {id:'models', label:'模型', icon:'cpu', group:'Agent 与项目', subtitle:'配置模型连接与生成偏好。', status:'待接入'},
    {id:'agents', label:'Agent 团队', icon:'bot', group:'Agent 与项目', subtitle:'设置主 Agent 的分工方式与专业能力。', status:'待接入'},
    {id:'permissions', label:'权限与安全', icon:'shield-check', group:'Agent 与项目', subtitle:'查看命令沙箱状态，管理任务执行与项目文件访问。', searchKeywords:'sandbox 沙箱 隔离 执行助手'},
    {id:'terminal', label:'终端', icon:'square-terminal', group:'Agent 与项目', subtitle:'设置终端的命令环境与显示。'},
    {id:'git', label:'Git 与工作树', icon:'git-branch', group:'Agent 与项目', subtitle:'配置版本管理和任务工作目录。', status:'待接入'},
    {id:'data', label:'数据与配置', icon:'database', group:'管理', subtitle:'备份、迁移或重置你的个人偏好。'}
  ].concat(modules.flatMap(module => module.categories || []));
  const categoryOrder = ['account','general','appearance','shortcuts','pet','models','agents','skills','context','memory','permissions','terminal','git','extensions','hooks','connections','websearch','data'];
  categories.sort((left,right) => categoryOrder.indexOf(left.id) - categoryOrder.indexOf(right.id));
  const byKey = new Map(definitions.map(definition => [definition.key, definition]));
  const defaults = Object.freeze(Object.fromEntries(definitions.map(definition => [definition.key, definition.default])));
  let settings = structuredClone(defaults);
  let activeCategory = 'general';
  let query = '';
  let priorFocus = null;
  let closeTimer = null;
  let closePromise = null;
  let settleClose = null;
  let saveError = '';
  let statusMessage = '已保存到本机';
  let statusIsError = false;

  function validValue(definition, value) {
    if (definition.type === 'collection') return !!definition.validate?.(value);
    if (['select','segment'].includes(definition.type)) return definition.options.some(option => option.value === value);
    if (definition.type === 'toggle') return typeof value === 'boolean';
    if (['number','range'].includes(definition.type)) {
      const steps = (value - definition.min) / definition.step;
      return typeof value === 'number' && Number.isFinite(value) && value >= definition.min && value <= definition.max && Math.abs(steps - Math.round(steps)) < 0.000001;
    }
    return typeof value === 'string' && value.length <= definition.maxLength && (!definition.validate || definition.validate(value));
  }

  function validateDocument(document) {
    if (!document || Array.isArray(document) || document.version !== 1 || !document.settings || typeof document.settings !== 'object' || Array.isArray(document.settings)) throw new Error('不支持的配置文件，请选择版本 1 的设置文件。');
    const entries = Object.entries(document.settings);
    if (!entries.length) throw new Error('配置文件中没有设置项。');
    const result = {};
    for (const [key, value] of entries) {
      const definition = byKey.get(key);
      if (!definition) throw new Error('配置文件包含无法识别的设置项。');
      if (!validValue(definition, value)) throw new Error(`“${definition.title}”的设置值无效。`);
      result[key] = value;
    }
    return result;
  }

  try {
    const stored = /^https?:$/.test(location.protocol) ? null : localStorage.getItem(STORAGE_KEY);
    if (stored) settings = {...structuredClone(defaults), ...validateDocument(JSON.parse(stored))};
  } catch {
    saveError = '无法读取本机设置，已使用默认值。';
  }

  function showStatus(message = statusMessage, error = false) {
    statusMessage = message;
    statusIsError = error;
    const status = document.getElementById('settings-status');
    if (!status) return;
    status.classList.toggle('is-error', error);
    status.innerHTML = `${glyph(error ? 'circle-alert' : 'check')}<span>${escape(message)}</span>`;
    refreshIcons();
  }

  function persist() {
    if(window.DongranRuntime?.enabled){showStatus('正在保存…');window.DongranRuntime.saveSettings(structuredClone(settings)).then(()=>{saveError='';showStatus('已保存到本机数据库');}).catch(error=>{saveError=error.message;showStatus('保存失败：'+saveError,true);});return;}
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({version:1, settings}));
      saveError = '';
      showStatus('已保存到本机');
    } catch {
      saveError = '保存失败：当前窗口无法写入本机存储。';
      showStatus(saveError, true);
    }
  }

  function notify(key, value) {
    window.dispatchEvent(new CustomEvent('settingschange', {detail:{key, value:structuredClone(value), settings:structuredClone(settings)}}));
  }

  function set(key, value) {
    const definition = byKey.get(key);
    if (!definition || !validValue(definition, value)) return false;
    if (settings[key] === value) {
      syncControl(key);
      if (saveError) persist();
      else if (statusIsError) showStatus('已保存到本机');
      return true;
    }
    settings[key] = structuredClone(value);
    persist();
    syncControl(key);
    notify(key, value);
    return true;
  }

  function syncControl(key) {
    const definition = byKey.get(key);
    const control = document.getElementById(`setting-${key}`);
    if (!control) return;
    const value = settings[key];
    if (definition.type === 'toggle') control.setAttribute('aria-checked', String(value));
    else if (definition.type === 'segment') {
      control.querySelectorAll('[data-setting-value]').forEach(button => {
        button.setAttribute('aria-checked', String(button.dataset.settingValue === value));
        button.tabIndex = button.dataset.settingValue === value ? 0 : -1;
      });
    } else if (definition.type === 'select') {
      control.querySelector('.settings-select-value').textContent = definition.options.find(option => option.value === value).label;
    } else {
      control.value = value;
      const output = document.getElementById(`setting-${key}-output`);
      if (output) output.textContent = `${value}${definition.unit ? ` ${definition.unit}` : ''}`;
      if (definition.type === 'range') control.style.setProperty('--range-progress', `${(value-definition.min)/(definition.max-definition.min)*100}%`);
      control.removeAttribute('aria-invalid');
    }
  }

  function controlMarkup(definition) {
    const {key,type,title} = definition;
    const value = settings[key];
    const common = `id="setting-${key}" aria-labelledby="setting-${key}-label"`;
    if (type === 'toggle') return `<button type="button" ${common} class="settings-switch" role="switch" aria-checked="${value}" data-setting-toggle="${key}"><span></span></button>`;
    if (type === 'select') return `<button type="button" ${common} class="settings-select" data-setting-select="${key}" aria-haspopup="menu" aria-expanded="false"><span class="settings-select-value">${escape(definition.options.find(option => option.value === value).label)}</span>${glyph('chevron-down')}</button>`;
    if (type === 'segment') return `<div ${common} class="settings-segment" role="radiogroup">${definition.options.map(option => `<button type="button" role="radio" aria-checked="${value === option.value}" tabindex="${value === option.value ? 0 : -1}" data-setting-segment="${key}" data-setting-value="${option.value}">${option.icon ? glyph(option.icon) : ''}<span>${option.label}</span></button>`).join('')}</div>`;
    if (type === 'range') return `<div class="settings-range-control"><input ${common} type="range" data-setting-input="${key}" min="${definition.min}" max="${definition.max}" step="${definition.step}" value="${value}" style="--range-progress:${(value-definition.min)/(definition.max-definition.min)*100}%"><output for="setting-${key}" id="setting-${key}-output">${value}${definition.unit ? ` ${definition.unit}` : ''}</output></div>`;
    if (type === 'number') return `<div class="settings-number-control"><input ${common} data-setting-input="${key}" type="number" min="${definition.min}" max="${definition.max}" step="${definition.step}" value="${value}"><span>${definition.unit || ''}</span></div>`;
    if (type === 'textarea') return `<textarea ${common} data-setting-input="${key}" rows="4" maxlength="${definition.maxLength}" placeholder="${escape(definition.placeholder || '')}" spellcheck="false">${escape(value)}</textarea>`;
    return `<input ${common} data-setting-input="${key}" class="settings-text-input" type="text" maxlength="${definition.maxLength}" value="${escape(value)}" placeholder="${escape(definition.placeholder || '')}" spellcheck="false" autocomplete="off">`;
  }

  function rowMarkup(definition, searching = false) {
    const category = categories.find(item => item.id === definition.category);
    return `<div class="settings-row ${['text','textarea'].includes(definition.type) ? 'settings-row-field' : ''}" data-settings-row="${definition.key}"><span class="settings-row-icon">${glyph(definition.icon)}</span><div class="settings-row-copy">${searching ? `<span class="settings-result-category">${category.label} / ${definition.section}</span>` : ''}<label id="setting-${definition.key}-label" for="setting-${definition.key}">${definition.title}</label>${definition.description ? `<p>${definition.description}</p>` : ''}</div><div class="settings-row-control">${controlMarkup(definition)}</div></div>`;
  }

  function renderNavigation() {
    let group = '';
    document.getElementById('settings-navigation').innerHTML = categories.map(category => {
      const heading = category.group !== group ? `<div class="settings-nav-group">${category.group}</div>` : '';
      group = category.group;
      return `${heading}<button type="button" data-settings-category="${category.id}" class="settings-nav-item ${!query && category.id === activeCategory ? 'active' : ''}" ${!query && category.id === activeCategory ? 'aria-current="page"' : ''}>${glyph(category.icon)}<span>${category.label}</span></button>`;
    }).join('');
  }

  function renderData() {
    return `<section class="settings-section"><h2>个人配置</h2><div class="settings-row"><span class="settings-row-icon">${glyph('download')}</span><div class="settings-row-copy"><label>导出设置</label><p>将个人偏好保存为 JSON 文件。</p></div><button id="settings-export" class="settings-command" type="button">${glyph('download')}导出</button></div><div class="settings-row"><span class="settings-row-icon">${glyph('upload')}</span><div class="settings-row-copy"><label>导入设置</label><p>使用已导出的配置文件替换当前偏好。</p></div><button id="settings-import" class="settings-command" type="button">${glyph('upload')}导入</button></div><div class="settings-row"><span class="settings-row-icon">${glyph('hard-drive')}</span><div class="settings-row-copy"><label>设置保存位置</label><p>当前设备的浏览器本地存储。</p></div><span class="settings-inline-value">本机</span></div></section><section class="settings-section"><h2>重置</h2><div class="settings-row"><span class="settings-row-icon">${glyph('rotate-ccw')}</span><div class="settings-row-copy"><label>恢复默认设置</label><p>重置全部个人偏好，保留当前项目和任务。</p></div><button id="settings-reset" class="settings-command settings-danger" type="button">${glyph('rotate-ccw')}恢复默认</button></div></section>`;
  }

  function renderContent() {
    const category = categories.find(item => item.id === activeCategory);
    const content = document.getElementById('settings-content');
    const searchTerm = query.trim().toLocaleLowerCase();
    const matches = definitions.filter(definition => !definition.hidden && !(window.DongranRuntime?.enabled && definition.category==='models') && (searchTerm ? [definition.title,definition.description,definition.section,categories.find(item => item.id === definition.category).label,definition.key].join(' ').toLocaleLowerCase().includes(searchTerm) : definition.category === activeCategory));
    const extraMatches = [];
    if (searchTerm) {
      for (const item of categories) {
        if (!modules.some(module => module.categories?.some(entry => entry.id === item.id)) && item.id !== 'appearance' && !(item.id==='permissions' && window.DongranSandbox) && !(window.DongranRuntime?.enabled && item.id==='models')) continue;
        const keywords = [item.label,item.subtitle,item.searchKeywords,...definitions.filter(definition => definition.category === item.id && definition.hidden).map(definition => definition.title)].join(' ').toLocaleLowerCase();
        if (keywords.includes(searchTerm)) extraMatches.push(`<button type="button" class="settings-search-category" data-settings-category="${item.id}">${glyph(item.icon)}<span><strong>${item.label}</strong><small>${item.subtitle}</small></span>${glyph('chevron-right')}</button>`);
      }
      const dataRows = document.createElement('div');
      dataRows.innerHTML = renderData();
      for (const row of dataRows.querySelectorAll('.settings-row')) {
        if (!`${row.textContent} 数据与配置`.toLocaleLowerCase().includes(searchTerm)) continue;
        row.querySelector('.settings-row-copy').insertAdjacentHTML('afterbegin','<span class="settings-result-category">数据与配置</span>');
        extraMatches.push(row.outerHTML);
      }
    }
    document.getElementById('settings-heading').textContent = searchTerm ? '搜索结果' : category.label;
    document.getElementById('settings-subtitle').textContent = searchTerm ? `${matches.length + extraMatches.length} 个设置项与“${query.trim()}”相关` : category.subtitle;
    const badge = document.getElementById('settings-category-status');
    badge.hidden = !!searchTerm || !category.status;
    badge.textContent = window.DongranRuntime?.enabled && ['models','agents','skills','context','memory','permissions','terminal','git','extensions','hooks','connections'].includes(activeCategory) ? '本地服务' : category.status || '';
    badge.title = '配置已保存，相关服务尚未接入';
    document.getElementById('settings-reset-category').hidden = !!searchTerm || activeCategory === 'data' || category.resettable === false || (window.DongranRuntime?.enabled && activeCategory==='models');
    if (!searchTerm && activeCategory === 'data') content.innerHTML = renderData();
    else if (searchTerm && !matches.length && !extraMatches.length) content.innerHTML = `<div class="settings-empty">${glyph('search')}<h2>没有找到相关设置</h2><p>试试“模型”“字号”或“权限”。</p></div>`;
    else if (searchTerm) content.innerHTML = `<section class="settings-section settings-search-results">${matches.map(definition => rowMarkup(definition,true)).join('')}${extraMatches.join('')}</section>`;
    else {
      const api = {get:key=>structuredClone(settings[key]),set,escape,icon:glyph,rowMarkup,showStatus,refresh};
      const customContent = modules.map(module => module.render?.(activeCategory,api) || '').join('');
      const groups = [...new Set(matches.map(definition => definition.section))];
      content.innerHTML = groups.map(group => `<section class="settings-section"><h2>${group}</h2>${matches.filter(definition => definition.section === group).map(definition => rowMarkup(definition)).join('')}</section>${activeCategory === 'appearance' && group === '主题' ? customContent : ''}`).join('');
      if (activeCategory === 'shortcuts') content.insertAdjacentHTML('beforeend', `<section class="settings-section"><h2>其他操作</h2><div class="settings-row"><span class="settings-row-icon">${glyph('square-pen')}</span><div class="settings-row-copy"><label>新建任务</label></div><kbd class="settings-shortcut">Ctrl + Alt + N</kbd></div><div class="settings-row"><span class="settings-row-icon">${glyph('arrow-left')}</span><div class="settings-row-copy"><label>返回应用 / 关闭弹窗</label></div><kbd class="settings-shortcut">Esc</kbd></div></section>`);
      if (activeCategory === 'models') content.insertAdjacentHTML('beforeend', `<div class="settings-footnote">${glyph('key-round')}连接凭据将在接入模型服务后单独管理。</div>`);
      if (activeCategory !== 'appearance') content.insertAdjacentHTML('beforeend',customContent);
      if (activeCategory === 'git') content.insertAdjacentHTML('afterbegin', `<div class="settings-project-context">${glyph('folder-git-2')}<div><strong>${escape(window.DongranProjects?.current()?.name || '尚未打开项目')}</strong><span>Git 状态待读取 · 配置适用于新任务</span></div><span class="settings-status-badge">未执行 Git 操作</span></div>`);
    }
    if(!searchTerm)window.DongranRuntime?.settingsPanel(activeCategory,content);
    if(!searchTerm && activeCategory==='permissions')window.DongranSandbox?.mount(content);
    renderNavigation();
    refreshIcons();
  }

  function refresh() {
    if (document.getElementById('settings-content')) renderContent();
  }

  function initializeScreen() {
    screen.innerHTML = `<aside class="settings-sidebar"><button id="settings-back" type="button" class="settings-back">${glyph('arrow-left')}返回应用</button><div class="settings-search-box">${glyph('search')}<input id="settings-search" type="search" placeholder="搜索设置..." aria-label="搜索设置" autocomplete="off"><button id="settings-search-clear" type="button" class="icon-btn" aria-label="清空搜索" title="清空搜索" hidden>${glyph('x')}</button></div><nav id="settings-navigation" class="settings-navigation" aria-label="设置分类"></nav><div class="settings-sidebar-footer"><span class="settings-app-mark">D</span><span><strong>Dongran</strong><small>桌面工作台</small></span><span class="settings-version">0.1</span></div></aside><main class="settings-main"><div class="settings-main-inner"><header class="settings-page-header"><div class="settings-heading-line"><h1 id="settings-heading" tabindex="-1"></h1><span id="settings-category-status" class="settings-status-badge" hidden></span><div id="settings-status" class="settings-save-status" role="status" aria-live="polite"></div></div><p id="settings-subtitle"></p></header><div id="settings-content"></div><footer class="settings-content-footer"><button id="settings-reset-category" type="button">${glyph('rotate-ccw')}恢复本页默认值</button></footer></div></main><input id="settings-import-file" type="file" accept=".json,application/json" hidden>`;
    renderContent();
    showStatus(saveError || statusMessage, !!saveError || statusIsError);
  }

  function reducedMotion() {
    return settings.reduceMotion || matchMedia('(prefers-reduced-motion: reduce)').matches;
  }

  function open(category = 'general') {
    clearTimeout(closeTimer);
    closeTimer = null;
    if (settleClose) {
      settleClose(false);
      settleClose = null;
      closePromise = null;
    }
    window.DongranUI?.closeMenu({immediate:true,restoreFocus:false});
    if (screen.hidden) priorFocus = document.activeElement;
    else if (screen.contains(document.activeElement)) document.activeElement.blur();
    activeCategory = categories.some(item => item.id === category) ? category : 'general';
    query = '';
    initializeScreen();
    document.querySelector('.app').hidden = true;
    screen.hidden = false;
    screen.inert = false;
    screen.classList.remove('is-leaving');
    screen.classList.toggle('is-entering', !reducedMotion());
    document.body.classList.add('settings-open');
    document.getElementById('settings-back').focus({preventScroll:true});
  }

  function close() {
    if (screen.hidden) return Promise.resolve(true);
    if (closePromise) return closePromise;
    const completion = new Promise(resolve => { settleClose = resolve; });
    closePromise = completion;
    document.activeElement?.blur();
    window.DongranUI?.closeMenu({immediate:true,restoreFocus:false});
    const finish = () => {
      clearTimeout(closeTimer);
      closeTimer = null;
      screen.hidden = true;
      screen.inert = false;
      screen.classList.remove('is-entering','is-leaving');
      document.querySelector('.app').hidden = false;
      document.body.classList.remove('settings-open');
      if (priorFocus?.isConnected && priorFocus !== document.body) priorFocus.focus({preventScroll:true});
      const resolve = settleClose;
      settleClose = null;
      closePromise = null;
      resolve?.(true);
    };
    if (reducedMotion()) finish();
    else {
      screen.inert = true;
      screen.classList.remove('is-entering');
      screen.classList.add('is-leaving');
      closeTimer = setTimeout(finish,160);
    }
    return completion;
  }

  function openSelect(trigger) {
    const key = trigger.dataset.settingSelect;
    const definition = byKey.get(key);
    window.DongranUI.showMenu(trigger, {
      label:definition.title,
      items:definition.options.map(option => ({...option,checked:option.value === settings[key]})),
      onSelect:value => set(key,value)
    });
  }

  function reset(category) {
    if (category && !categories.some(item => item.id === category)) return;
    if (category) definitions.filter(definition => definition.category === category && !definition.preserveOnReset).forEach(definition => {settings[definition.key] = structuredClone(definition.default);});
    else {
      const retained = Object.fromEntries(definitions.filter(definition => definition.preserveOnReset).map(definition => [definition.key,settings[definition.key]]));
      settings = {...structuredClone(defaults),...retained};
    }
    persist();
    if (!screen.hidden) renderContent();
    notify(category ? 'reset-category' : 'reset', category || null);
  }

  function confirmReset(category) {
    const label = category ? categories.find(item => item.id === category).label : '全部';
    window.DongranUI.showDialog(`恢复${label}默认设置`, `<p class="settings-confirm-copy">${category ? '本页设置' : '全部个人偏好'}将恢复为默认值。当前项目、任务、文件与记忆条目将保留。</p><div class="settings-confirm-actions"><button id="settings-cancel-reset" class="secondary" type="button">取消</button><button id="settings-confirm-reset" class="primary" type="button">恢复默认</button></div>`);
    document.getElementById('settings-cancel-reset').addEventListener('click', () => window.DongranUI.closeDialog());
    document.getElementById('settings-confirm-reset').addEventListener('click', () => {reset(category);window.DongranUI.closeDialog();});
    document.getElementById('settings-cancel-reset').focus();
  }

  function exportSettings() {
    const blob = new Blob([JSON.stringify({version:1,settings},null,2)], {type:'application/json'});
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'dongran-settings.json';
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(url),1000);
    showStatus(saveError || '配置文件已导出', !!saveError);
  }

  async function importSettings(file) {
    if (!file) return;
    try {
      if (file.size > 8388608) throw new Error('配置文件过大，请选择小于 8 MB 的 JSON 文件。');
      let parsed;
      try { parsed = JSON.parse(await file.text()); } catch { throw new Error('无法读取配置，请选择有效的 JSON 文件。'); }
      const imported = validateDocument(parsed);
      const retained = Object.fromEntries(definitions.filter(definition => definition.preserveOnReset && !Object.hasOwn(imported,definition.key)).map(definition => [definition.key,settings[definition.key]]));
      settings = {...structuredClone(defaults),...retained,...imported};
      persist();
      renderContent();
      notify('import',null);
      if (!saveError && !window.DongranRuntime?.enabled) showStatus('设置已导入并保存到本机');
    } catch (error) {
      showStatus(error.message || '导入失败，请检查配置文件。',true);
    }
  }

  screen.addEventListener('click', event => {
    const target = event.target.closest('button');
    if (!target) return;
    if (target.id === 'settings-back') close();
    else if (target.dataset.settingsCategory) {
      window.DongranUI.closeMenu({immediate:true,restoreFocus:false});
      activeCategory = target.dataset.settingsCategory;
      query = '';
      document.getElementById('settings-search').value = '';
      document.getElementById('settings-search-clear').hidden = true;
      renderContent();
      document.querySelector('.settings-main').scrollTop = 0;
      document.querySelector(`[data-settings-category="${activeCategory}"]`).focus({preventScroll:true});
    } else if (target.dataset.settingToggle) set(target.dataset.settingToggle,!settings[target.dataset.settingToggle]);
    else if (target.dataset.settingSelect) openSelect(target);
    else if (target.dataset.settingSegment) set(target.dataset.settingSegment,target.dataset.settingValue);
    else if (target.id === 'settings-search-clear') {
      query = '';
      document.getElementById('settings-search').value = '';
      target.hidden = true;
      renderContent();
      document.getElementById('settings-search').focus();
    } else if (target.id === 'settings-export') exportSettings();
    else if (target.id === 'settings-import') document.getElementById('settings-import-file').click();
    else if (target.id === 'settings-reset') confirmReset();
    else if (target.id === 'settings-reset-category') confirmReset(activeCategory);
  });

  screen.addEventListener('input', event => {
    const target = event.target;
    if (target.id === 'settings-search') {
      query = target.value;
      document.getElementById('settings-search-clear').hidden = !query;
      renderContent();
      document.querySelector('.settings-main').scrollTop = 0;
    } else if (target.matches('input[type="range"][data-setting-input]')) set(target.dataset.settingInput,Number(target.value));
  });

  screen.addEventListener('change', event => {
    const target = event.target;
    if (target.id === 'settings-import-file') {importSettings(target.files[0]);target.value='';return;}
    if (!target.dataset.settingInput) return;
    const definition = byKey.get(target.dataset.settingInput);
    const value = ['number','range'].includes(definition.type) ? Number(target.value) : target.value.trim();
    if (!set(definition.key,value)) {
      target.setAttribute('aria-invalid','true');
      showStatus(`“${definition.title}”的值无效，已保留原设置。`,true);
      target.value = settings[definition.key];
    }
  });

  screen.addEventListener('keydown', event => {
    const trigger = event.target.closest('[data-setting-select]');
    if (trigger && ['ArrowDown','ArrowUp'].includes(event.key)) {event.preventDefault();openSelect(trigger);return;}
    const segment = event.target.closest('[data-setting-segment]');
    if (segment && ['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) {
      event.preventDefault();
      const siblings = [...segment.parentElement.querySelectorAll('[data-setting-segment]')];
      const index = siblings.indexOf(segment);
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? siblings.length-1 : (index+(event.key === 'ArrowRight' ? 1 : -1)+siblings.length)%siblings.length;
      siblings[next].click();
      siblings[next].focus();
    }
  });

  document.addEventListener('keydown', event => {
    if (screen.hidden || event.key !== 'Escape' || event.defaultPrevented || document.querySelector('dialog[open]') || document.querySelector('#app-menu:popover-open')) return;
    event.preventDefault();
    close();
  });

  function hydrate(values){
    const accepted={};
    for(const [key,value] of Object.entries(values||{})){const definition=byKey.get(key);if(definition&&validValue(definition,value))accepted[key]=value;}
    settings={...structuredClone(defaults),...accepted};
    if(!screen.hidden)renderContent();
    notify('import',null);
  }
  window.DongranSettings = {hydrate,snapshot:()=>structuredClone(settings),open,close,get:key => structuredClone(settings[key]),set,reset,defaults,refresh};
  notify('init',null);
})();
