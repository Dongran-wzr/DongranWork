const $ = (selector) => document.querySelector(selector);
const icon = (name) => `<i data-lucide="${name}"></i>`;
const escapeHtml = (value) => String(value).replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const state = { project: null, tasks: [], active: null, tab: 'doc', attachments: [], files: [], panel: false, timer: null, toastTimer: null, model: 'Auto', pendingPrompt: '' };
let projectContext = null;
const directoryIdentities = [];
window.DongranProjects = {current:() => projectContext ? {...projectContext} : null};

async function identifyDirectory(handle) {
  for (const entry of directoryIdentities) {
    if (await entry.handle.isSameEntry(handle)) return entry.id;
  }
  let database;
  try {
    database = await new Promise((resolve,reject) => {
      const request = indexedDB.open('dongran-projects',1);
      request.onupgradeneeded = () => request.result.createObjectStore('directories',{keyPath:'id'});
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const entries = await new Promise((resolve,reject) => {
      const request = database.transaction('directories').objectStore('directories').getAll();
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    for (const entry of entries) {
      if (await entry.handle.isSameEntry(handle)) {
        directoryIdentities.push({id:entry.id,handle});
        return entry.id;
      }
    }
    const id = crypto.randomUUID();
    await new Promise((resolve,reject) => {
      const transaction = database.transaction('directories','readwrite');
      transaction.objectStore('directories').put({id,handle});
      transaction.oncomplete = resolve;
      transaction.onerror = () => reject(transaction.error);
    });
    directoryIdentities.push({id,handle});
    return id;
  } catch {
    const id = crypto.randomUUID();
    directoryIdentities.push({id,handle});
    return id;
  } finally { database?.close(); }
}
const docTemplate = `<div class="doc-eyebrow">PRODUCT REQUIREMENTS DOCUMENT</div><h2>项目成员与权限管理</h2><div class="doc-meta"><span class="badge review">待确认</span><span>v1.0</span><span>产品 Agent</span><span>今天 14:32</span></div><h3><span>01</span>需求背景</h3><p>随着团队规模增长，项目需要更清晰的成员边界和权限分工。支持管理员邀请成员，并通过角色控制项目内的访问与操作权限。</p><h3><span>02</span>目标与范围</h3><p>建立项目级成员管理能力，覆盖邀请、角色分配和成员移除。第一版采用预设角色，不包含自定义权限。</p><h3><span>03</span>角色与权限</h3><table><thead><tr><th>角色</th><th>查看项目</th><th>编辑内容</th><th>管理成员</th></tr></thead><tbody><tr><td>管理员</td><td>允许</td><td>允许</td><td>允许</td></tr><tr><td>编辑者</td><td>允许</td><td>允许</td><td>不允许</td></tr><tr><td>访客</td><td>允许</td><td>不允许</td><td>不允许</td></tr></tbody></table><h3><span>04</span>验收标准</h3><ul class="checklist"><li>${icon('square')}管理员可通过邮箱邀请成员</li><li>${icon('square')}成员仅可执行所属角色授权的操作</li><li>${icon('square')}不可移除项目的最后一位管理员</li><li>${icon('square')}权限变更后即时生效</li></ul><div class="doc-note">待确认：邀请链接有效期暂定为 7 天。</div>`;
function icons(){
  if(window.lucide) window.lucide.createIcons();
  document.querySelectorAll('.message-meta .user-avatar').forEach(avatar => {
    avatar.setAttribute('data-account-avatar','');
    if(avatar.nextElementSibling?.tagName==='STRONG')avatar.nextElementSibling.setAttribute('data-account-name','');
  });
  window.DongranAccount?.sync();
}
function task(){ return state.tasks.find(t => t.id === state.active); }
function setTaskTitle(title){ if(window.DongranPages)window.DongranPages.updateTaskTitle(title);else $('#header-title').textContent=title; }
function toast(message){ clearTimeout(state.toastTimer); $('#toast').textContent=message; $('#toast').hidden=false; state.toastTimer=setTimeout(()=>$('#toast').hidden=true,3400); }
let panelMotion;

function updateTeamCount(count=1){const node=document.querySelector('#agent-team-count');if(node)node.textContent=String(Math.max(1,count));}

function setPanel(open){
  state.panel=open;const panel=$('#inspector'),trigger=$('.title-actions [data-action="panel"]');trigger.setAttribute('aria-expanded',String(open));
  if(panel.contains(document.activeElement)&&!open)trigger.focus();
  const wasHidden=panel.hidden,currentTransform=getComputedStyle(panel).transform,currentOpacity=getComputedStyle(panel).opacity;
  panelMotion?.cancel();panel.inert=!open;panel.setAttribute('aria-hidden',String(!open));
  const reduced=matchMedia('(prefers-reduced-motion: reduce)').matches||document.documentElement.dataset.reduceMotion==='true';
  if(reduced||(!open&&wasHidden)){panel.hidden=!open;return;}
  panel.hidden=false;const start={transform:wasHidden?'translateX(100%)':currentTransform,opacity:wasHidden?0:currentOpacity};
  panelMotion=panel.animate([start,{transform:open?'translateX(0)':'translateX(100%)',opacity:open?1:0}],{duration:240,easing:'cubic-bezier(.22,.8,.3,1)',fill:'both'});
  const animation=panelMotion;animation.onfinish=()=>{if(panelMotion!==animation)return;panel.hidden=!state.panel;animation.cancel();panelMotion=null;};
}
function closeDialog(){ window.DongranUI.closeDialog(); }
function showDialog(title,body){ window.DongranUI.showDialog(title,body); }
async function resumeWorkspace(action){ if(!window.DongranSettings||await window.DongranSettings.close())action(); }
const menuChoices = {
  permissions: [
    {value:'修改前询问', label:'修改前询问', icon:'shield-check', description:'写入文件或执行命令前确认'},
    {value:'仅规划', label:'仅规划', icon:'notebook-pen', description:'分析需求，生成任务计划'},
    {value:'允许项目内修改', label:'允许项目内修改', icon:'folder-check', description:'自动修改当前项目内的文件'}
  ],
  model: [
    {value:'Auto', label:'Auto', icon:'sparkles', description:'使用工作区默认模型'},
    {value:'自定义模型', label:'自定义模型', icon:'sliders-horizontal', description:'使用已配置的模型连接'}
  ]
};
const appMenuChoices = {
  file: {label:'文件',items:[
    {value:'new',label:'新的任务'},
    {value:'search',label:'搜索任务'},
    {type:'separator'},
    {value:'open-directory',label:'打开文件夹'},
    {value:'projects',label:'最近项目'},
    {type:'separator'},
    {value:'settings',label:'工作区设置'}
  ]},
  edit: {label:'编辑',items:[
    {value:'focus-input',label:'编辑任务内容'},
    {value:'attach',label:'添加附件'},
    {type:'separator'},
    {value:'clear-input',label:'清空任务输入'}
  ]},
  view: {label:'视图',items:[
    {value:'show-terminal',label:'终端'},
    {value:'show-artifacts',label:'任务产物'},
    {value:'toggle-sidebar',label:'侧栏'},
    {type:'separator'},
    {value:'fullscreen',label:'切换全屏'}
  ]},
  help: {label:'帮助',items:[
    {value:'team',label:'Agent 团队'},
    {type:'separator'},
    {value:'about',label:'关于 Dongran'}
  ]}
};
function openChoiceMenu(trigger) {
  const kind = trigger.dataset.menu;
  if(kind==='model'&&window.DongranRuntime?.enabled){window.DongranProviders.menu(trigger);return;}
  if (appMenuChoices[kind]) {
    window.DongranUI.showMenu(trigger, {...appMenuChoices[kind], onSelect:value => actions[value]?.()});
    return;
  }
  const field = trigger.dataset.field ? document.getElementById(trigger.dataset.field) : null;
  const current = field ? field.value : state.model;
  window.DongranUI.showMenu(trigger, {
    label: kind === 'permissions' ? '执行权限' : '选择模型',
    items: menuChoices[kind].map(option => ({...option, checked:option.value === current})),
    onSelect(value) {
      if (field) { field.value = value; document.getElementById(`${field.id}-label`).textContent = value; window.DongranSettings?.set('permissionMode',value); }
      else { state.model = value; $('#model-label').textContent = value; window.DongranSettings?.set('defaultModel',value); }
    }
  });
}
function renderSidebar(){
  $('#task-count').textContent=state.tasks.length;
  if(window.DongranRuntime?.enabled){
    const groups=[...(state.workspaceProjects||[]).map(p=>({...p,tasks:state.tasks.filter(t=>t.projectId===p.id)})),{id:null,name:'未关联项目',tasks:state.tasks.filter(t=>!t.projectId)}];
    $('#task-list').innerHTML=groups.map(p=>`<details class="workspace-project-group" ${p.id===projectContext?.id||p.tasks.some(t=>t.id===state.active)||p.id===null?'open':''}><summary>${icon('folder')}<span>${escapeHtml(p.name)}</span><small>${p.tasks.length}</small></summary>${p.path?`<button class="workspace-project-open" data-workspace-project="${escapeHtml(p.path)}" title="${escapeHtml(p.path)}">进入项目 ${icon('arrow-up-right')}</button>`:''}${p.tasks.map(t=>`<button class="task-item ${t.id===state.active?'active':''}" data-workspace-task="${escapeHtml(t.id)}">${icon(t.done?'circle-check':'message-square')}<span>${escapeHtml(t.title)}</span>${t.running?'<span class="dot loading-dot"></span>':''}</button>`).join('')||'<div class="empty-tasks">暂无任务</div>'}</details>`).join('');return;
  }
  $('#task-list').innerHTML=state.tasks.length ? '<div class="task-group">今天</div>'+state.tasks.map(t=>`<button class="task-item ${t.id===state.active?'active':''}" data-task="${t.id}">${icon(t.done?'circle-check':'message-square')}<span>${escapeHtml(t.title)}</span>${t.id===state.active?'<span class="dot"></span>':''}</button>`).join(''):'<div class="empty-tasks">还没有任务</div>';
}
function welcome(){
  if(window.DongranRuntime?.enabled)return window.DongranRuntime.home();
  window.DongranPages?.close();
  setTaskTitle('新的任务'); $('#composer-area').hidden=false; $('.conversation').classList.add('start-view'); setPanel(false);
  $('#messages').innerHTML=`<div class="start-content"><div class="project-study activity-map"></div><h1>Dongran</h1><p class="start-question">今天，我们推进哪个项目？</p><div class="start-actions"><button class="secondary" data-action="open-directory">${icon('folder-open')}打开项目目录</button></div><div class="recent-projects"><div class="recent-label"><span>最近项目</span><span>本地</span></div><button class="project-row" data-action="demo">${icon('folder')}<span><strong>dongran-console</strong><small>D:\\Projects\\dongran-console</small></span><time>演示项目</time>${icon('chevron-right')}</button></div></div>`;
  window.DongranActivity.render($('.activity-map'),{sample:true});
  renderSidebar(); icons();
}
function openProject(name, sample=false, projectId){
  window.DongranPages?.close();
  clearTimeout(state.timer); state.timer=null; state.project=name; state.tasks=[]; state.active=null; state.attachments=[]; $('#attachment-list').innerHTML=''; $('#prompt').value='';
  $('#project-name').textContent=name; $('#project-path').textContent=sample?'D:\\Projects\\dongran-console':name+' / 本地目录'; $('#context-project').textContent=name; $('#breadcrumb').textContent=name; $('#branch-name').textContent=sample?'feat/member-roles':'本地目录';
  projectContext={id:projectId||(sample?'demo:dongran-console':crypto.randomUUID()),name,path:sample?'D:\\Projects\\dongran-console':name,sample};
  window.dispatchEvent(new CustomEvent('projectchange', {detail:{...projectContext}}));
  closeDialog(); $('.sidebar').classList.remove('open');
  if(sample){ state.files=[]; state.tasks=[{id:1,title:'为项目增加成员权限管理',prompt:'为项目增加成员权限管理，支持管理员、编辑者和访客三种角色。先梳理需求，再完成开发和测试。',sample:true,done:false,running:false,doc:docTemplate,replies:[]}]; state.active=1; renderTask(); setPanel(window.DongranSettings?.get('artifactPanel')==='auto'); renderInspector(); }
  else newTask();
  if(state.pendingPrompt){const draft=state.pendingPrompt;state.pendingPrompt='';newTask();$('#prompt').value=draft;submitTask({preventDefault(){}});}
}
function newTask(){
  if(window.DongranRuntime?.enabled)window.DongranRuntime.detach();
  if(window.DongranSettings&&!$('#settings-screen').hidden){window.DongranSettings.close().then(closed=>{if(closed)newTask();});return;}
  window.DongranPages?.close();
  if(!state.project){welcome();$('#prompt').focus();return;}
  $('.conversation').classList.add('start-view');
  state.active=null; setTaskTitle('新建任务'); $('#composer-area').hidden=false; $('#send').innerHTML=icon('arrow-up'); $('#send').title='发送任务'; $('#prompt').value=''; state.attachments=[]; renderAttachments(); setPanel(false);
  $('#messages').innerHTML=`<div class="start-content"><div class="project-study activity-map"></div><h1>开始新的任务</h1><p class="start-question">${escapeHtml(state.project)}</p><div class="start-suggestions"><button data-suggestion="梳理当前项目的业务流程，生成一份需求文档">${icon('file-text')}梳理产品需求</button><button data-suggestion="理解项目结构，规划并实现一个新功能">${icon('code-2')}实现新功能</button><button data-suggestion="检查当前项目的测试情况，整理验证计划">${icon('flask-conical')}验证项目</button></div></div>`;
  window.DongranActivity.render($('.activity-map'),{projectId:projectContext?.id,sample:projectContext?.sample===true});
  if(window.DongranRuntime?.enabled)window.DongranRuntime.activity();
  renderSidebar(); icons(); $('#prompt').focus();
}
function renderTask(){
  if(window.DongranRuntime?.enabled)return window.DongranRuntime.renderTask();
  const t=task(); if(!t){newTask();return;}
  $('.conversation').classList.remove('start-view');
  setTaskTitle(t.title); $('#composer-area').hidden=false;
  $('#send').innerHTML=icon(t.running?'square':'arrow-up'); $('#send').title=t.running?'停止执行':'发送任务'; $('#send').setAttribute('aria-label',$('#send').title);
  const transcript=t.sample?`<div class="agent-message"><div class="message-meta"><span class="agent-avatar lead">${icon('sparkles')}</span><strong>主 Agent</strong><span class="auto-label">自动协作</span><time>14:31</time></div><p>我会先明确角色与权限边界，再完成实现并验证关键场景。<br>已将任务拆分给产品、开发和测试 Agent。</p><div class="timeline"><div class="step"><div class="step-heading"><span class="agent-avatar product">P</span><strong>产品 Agent</strong><span class="muted">需求梳理</span><span class="duration">${icon('check')} 42s</span></div><div class="step-desc">已梳理 3 类角色、4 项验收标准，生成需求文档。</div><button class="artifact" data-tab="doc">${icon('file-text')}<span><strong>项目成员与权限管理</strong><small>docs / member-permissions.md</small></span>${icon('arrow-up-right')}</button><details><summary>${icon('chevron-right')}查看 3 条执行记录</summary><p>读取项目说明 README.md<br>分析现有成员模型<br>生成需求文档与验收标准</p></details></div><div class="step"><div class="step-heading"><span class="agent-avatar developer">D</span><strong>开发 Agent</strong><span class="muted">功能实现</span><span class="duration">${icon('check')} 2m 18s</span></div><div class="step-desc">已完成角色校验与成员管理接口，补充权限边界测试。</div><button class="artifact" data-tab="diff">${icon('git-pull-request')}<span><strong>4 个文件变更</strong><small>成员接口、权限校验、测试用例</small></span><span class="file-numbers"><span class="addition">+128</span> <span class="deletion">−16</span></span>${icon('chevron-right')}</button><details><summary>${icon('chevron-right')}查看 6 条执行记录</summary><p>读取成员控制器<br>检查现有路由守卫<br>新增角色类型<br>实现角色校验<br>更新成员接口<br>补充权限测试</p></details></div><div class="step ${t.done?'':'waiting'}"><div class="step-heading"><span class="agent-avatar tester">T</span><strong>测试 Agent</strong><span class="muted">运行验证</span><span class="duration">${t.done?'已完成':t.running?'运行中':'等待授权'}</span></div><div class="step-desc">${t.done?'12 项权限测试通过，0 项失败。':t.running?'正在运行权限测试…':'测试计划已就绪，需要运行项目测试命令。'}</div>${t.done?`<button class="artifact" data-tab="run">${icon('circle-check')}<span><strong>权限验证报告</strong><small>12 项通过 · 耗时 3.2s</small></span>${icon('arrow-up-right')}</button>`:`<div class="permission"><div class="permission-title">${icon('shield-check')}申请执行命令</div><code>npm run test -- permissions</code><div class="permission-actions"><span>执行范围：当前项目</span><button class="primary" data-action="run" ${t.running?'disabled':''}>${icon(t.running?'loader-circle':'play')}${t.running?'正在运行':'允许运行'}</button></div></div>`}</div></div><div class="next-line">${t.done?'需求、代码与验证结果已整理完成，可在右侧审查本次产物。':'需求文档和代码变更已就绪，你可以先审查右侧产物。'}</div></div>`:`<div class="agent-message"><div class="message-meta"><span class="agent-avatar lead">${icon('sparkles')}</span><strong>主 Agent</strong><span class="auto-label">自动协作</span></div><p>${t.running?'正在整理任务上下文…':t.stopped?'本次执行已停止。':t.planOnly?'已生成任务计划，等待你确认范围。':'已形成任务计划，等待补充业务细节。'}</p>${!t.running&&!t.stopped?`<div class="timeline"><div class="step"><div class="step-heading"><span class="agent-avatar product">P</span><strong>产品 Agent</strong><span class="muted">明确需求与验收标准</span></div></div><div class="step"><div class="step-heading"><span class="agent-avatar developer">D</span><strong>开发 Agent</strong><span class="muted">分析相关文件与实现范围</span></div></div><div class="step waiting"><div class="step-heading"><span class="agent-avatar tester">T</span><strong>测试 Agent</strong><span class="muted">制定验证方案</span></div></div></div><p>请补充目标用户、业务规则和验收标准。</p>`:''}</div>`;
  $('#messages').innerHTML=`<div class="thread"><div class="task-heading"><h1>${escapeHtml(t.title)}</h1><span class="badge ${t.done?'success':'review'}">${t.done?'已完成':t.running?'执行中':t.sample?'待审查':'规划中'}</span></div><div class="message-meta"><span class="user-avatar">林</span><strong>你</strong><time>14:30</time></div><p class="user-request">${escapeHtml(t.prompt)}</p><span class="context-chip">${icon('folder')}${escapeHtml(state.project)}</span>${transcript}${t.replies.map(r=>`<div class="reply"><div class="message-meta"><span class="user-avatar">林</span><strong>你</strong></div><p>${escapeHtml(r)}</p><div class="message-meta"><span class="agent-avatar lead">${icon('sparkles')}</span><strong>主 Agent</strong></div><p>已记录这条补充，纳入下一步任务规划。</p></div>`).join('')}</div>`;
  applyTaskPreferences(t);
  renderSidebar(); icons();
}
function renderInspector(){
  if(window.DongranRuntime?.enabled)return window.DongranRuntime.inspector();
  const t=task(); $('#artifact-total').textContent=t?.sample?'3':'0';
  document.querySelectorAll('.tabs button').forEach(b=>{b.classList.toggle('active',b.dataset.tab===state.tab);b.setAttribute('aria-selected',String(b.dataset.tab===state.tab));});
  $('#artifact-status').textContent=t?.done?'项目内产物 · 验证通过':'项目内产物 · 等待审查';
  if(state.tab==='files'){
    const paths=state.files.length?state.files.map(f=>f.webkitRelativePath||f.name):t?.sample?['docs/member-permissions.md','src/types/roles.ts','src/guards/permissions.ts','src/api/members.ts','tests/permissions.test.ts','package.json','README.md']:[];
    $('#inspector-content').innerHTML=`<div class="file-bar">${icon('folder-open')}${escapeHtml(state.project||'项目文件')}</div><div class="files-tree">${paths.slice(0,150).map((p,i)=>`<button class="file-entry" data-file="${i}">${icon('file-text')}<span>${escapeHtml(p)}</span></button>`).join('')||'<div class="empty-note">当前目录没有可展示的文件</div>'}</div>`;
  }else if(!t?.sample){$('#inspector-content').innerHTML='<div class="empty-note">当前任务还没有生成产物</div>';}
  else if(state.tab==='doc'){
    $('#inspector-content').innerHTML=`<div class="file-bar">${icon('file-text')}member-permissions.md<button class="icon-btn" data-action="edit-doc" title="编辑文档" aria-label="编辑文档">${icon('pencil')}</button><button class="icon-btn" data-action="download" title="导出文档" aria-label="导出文档">${icon('download')}</button></div><article class="document" id="doc-content">${t.doc}</article>`;
  }else if(state.tab==='diff'){
    const diffs=[['src/types/roles.ts',['+export type Role =','+  "admin" | "editor" | "viewer";']],['src/guards/permissions.ts',['-return user.isMember;','+const role = membership.role;','+if (action === "manage_members") {','+  return role === "admin";','+}','+return role !== "viewer";']],['src/api/members.ts',['+await requireRole(projectId, "admin");','+await assertNotLastAdmin(member);',' return updateMemberRole(member, role);']],['tests/permissions.test.ts',['+it("viewer cannot edit", () => {','+  expect(canEdit("viewer")).toBe(false);','+});','+it("keeps the last admin", async () => {','+  await expect(removeAdmin()).rejects','+    .toThrow("LAST_ADMIN");','+});']]];
    $('#inspector-content').innerHTML=`<div class="diff-summary"><span>4 个文件</span><span><span class="addition">+128</span> <span class="deletion">−16</span></span></div>${diffs.map(([name,lines])=>`<details class="diff-file" open><summary>${name}</summary><pre><code>${lines.map((l,i)=>`<span class="code-line ${l[0]==='+'?'add':l[0]==='-'?'del':''}"><span class="ln">${i+1}</span>${escapeHtml(l)}</span>`).join('')}</code></pre></details>`).join('')}`;
  }else{
    $('#inspector-content').innerHTML=`<div class="file-bar">${icon('terminal')}权限验证</div><div class="run-area"><div class="run-label"><span class="dot ${t.running?'loading-dot':''}"></span>${t.done?'测试完成':t.running?'正在执行':'等待执行授权'}</div><pre class="terminal">$ npm run test -- permissions\n\n${t.done?'PASS  tests/permissions.test.ts\n\n  ✓ 管理员可邀请成员\n  ✓ 编辑者可修改项目内容\n  ✓ 访客不可修改项目\n  ✓ 拒绝跨项目权限访问\n  ✓ 保留最后一位管理员\n  ...\n\nTest Files  1 passed (1)\nTests       12 passed (12)\nDuration    3.2s':t.running?'RUN  tests/permissions.test.ts\n正在验证角色权限与边界场景…':'命令尚未执行。'}</pre>${!t.done?`<button class="primary" data-action="run" ${t.running?'disabled':''}>${icon('play')}允许运行</button>`:`<button class="secondary" data-action="run">${icon('rotate-cw')}重新运行</button>`}</div>`;
  }
  icons();
}
function showProjects(){
  if(window.DongranRuntime?.enabled)return window.DongranRuntime.projects();showDialog('打开项目',`<button class="primary" data-action="open-directory">${icon('folder-open')}选择本地目录</button><div class="recent-label"><span>最近项目</span></div><button class="project-row" data-action="demo">${icon('folder')}<span><strong>dongran-console</strong><small>D:\\Projects\\dongran-console</small></span><time>演示项目</time>${icon('chevron-right')}</button>`);}
async function pickDirectory(){
  if(window.DongranRuntime?.enabled)return window.DongranRuntime.directory();
  if(window.showDirectoryPicker){
    try{const handle=await window.showDirectoryPicker({mode:'read'}); const entries=[]; for await(const [name,entry] of handle.entries()){entries.push({name,kind:entry.kind});} const projectId=await identifyDirectory(handle);state.files=entries;openProject(handle.name,false,projectId);toast('已打开目录：'+handle.name);}
    catch(error){if(error.name!=='AbortError')$('#directory-input').click();}
  }else $('#directory-input').click();
}
function runTests(){
  const t=task(); if(!t?.sample||t.running)return;
  t.running=true;t.done=false;renderTask();state.tab='run';setPanel(true);renderInspector();
  state.timer=setTimeout(()=>{t.running=false;t.done=true;if(state.active===t.id){renderTask();renderInspector();}renderSidebar();if(window.DongranSettings?.get('completionNotice')!==false)toast('演示验证完成：12 项测试通过');},2600);
}
function renderAttachments(){ $('#attachment-list').innerHTML=state.attachments.map((a,i)=>`<span class="attachment">${icon('file')} ${escapeHtml(a.name)}<button type="button" data-remove="${i}" title="移除附件" aria-label="移除附件">${icon('x')}</button></span>`).join('');icons(); }
function submitTask(event){
  if(window.DongranRuntime?.enabled){event.preventDefault();return window.DongranRuntime.submit();}
  event.preventDefault();const current=task();
  if(current?.running){clearTimeout(state.timer);current.running=false;current.stopped=true;renderTask();renderInspector();toast('已停止执行');return;}
  const value=$('#prompt').value.trim();if(!value)return;
  if(!state.project){state.pendingPrompt=value;showProjects();return;}
  if(current){current.replies.push(value);renderTask();}
  else{
    const t={id:Date.now(),title:value.length>24?value.slice(0,24)+'…':value,prompt:value,sample:false,done:false,running:true,planOnly:$('#mode').value==='仅规划',delegation:window.DongranSettings?.get('autoDelegate')!==false,agents:['enableProduct','enableDeveloper','enableTester'].filter(key=>window.DongranSettings?.get(key)!==false),memory:window.DongranMemory?.getEffectiveEntries(projectContext?.id)||[],replies:[]};
    state.tasks.unshift(t);state.active=t.id;renderTask();
    window.DongranActivity.mark(projectContext.id);
    state.timer=setTimeout(()=>{t.running=false;if(state.active===t.id)renderTask();renderSidebar();},1600);
  }
  $('#prompt').value='';state.attachments=[];renderAttachments();$('#messages').scrollTop=$('#messages').scrollHeight;
}
function searchDialog(){showDialog('搜索任务','<input class="search-input" id="search-input" placeholder="输入任务名称…" aria-label="搜索任务"><div id="search-results"></div>');const search=()=>{$('#search-results').innerHTML=state.tasks.filter(t=>t.title.toLowerCase().includes($('#search-input').value.toLowerCase())).map(t=>`<button class="project-row" data-task="${t.id}">${icon('message-square')}<span>${escapeHtml(t.title)}</span>${icon('chevron-right')}</button>`).join('')||'<div class="empty-note">没有匹配的任务</div>';icons();};$('#search-input').addEventListener('input',search);search();$('#search-input').focus();}
const actions={
  'git-workspace':()=>window.DongranRuntime?.enabled?window.DongranGit.open():toast('连接本地服务并打开项目后使用 Git 工作区。'),
  projects:showProjects,demo:()=>openProject('dongran-console',true),'open-directory':pickDirectory,new:newTask,search:searchDialog,
  knowledge:()=>window.DongranPages.open('knowledge'),schedules:()=>window.DongranPages.open('schedules'),
  files:()=>{if(!state.project){showProjects();return;}window.DongranPages?.close();state.tab='files';setPanel(true);renderInspector();},
  panel:()=>{window.DongranPages?.close();setPanel(!state.panel);renderInspector();},sidebar:()=>{window.DongranUI.closeMenu({immediate:true,restoreFocus:false});const collapsed=document.body.classList.toggle('sidebar-collapsed');const sidebar=$('.sidebar');sidebar.inert=collapsed;sidebar.setAttribute('aria-hidden',String(collapsed));const trigger=document.querySelector('[data-action="sidebar"]');trigger.setAttribute('aria-expanded',String(!collapsed));if(collapsed&&sidebar.contains(document.activeElement))trigger.focus();},
  'focus-input':()=>resumeWorkspace(()=>{window.DongranPages?.close();$('#prompt').focus();}),
  'clear-input':()=>resumeWorkspace(()=>{window.DongranPages?.close();$('#prompt').value='';$('#prompt').focus();}),
  'view-menu':(trigger)=>window.DongranUI.showMenu(trigger,{label:'视图',items:[{value:'show-terminal',label:'终端',icon:'square-terminal'},{value:'show-artifacts',label:'任务产物',icon:'panel-right'},{value:'toggle-sidebar',label:'侧栏',icon:'panel-left'}],onSelect:value=>actions[value]()}),
  'show-terminal':()=>resumeWorkspace(()=>{closeDialog();if($('#terminal-dock').hidden)$('#terminal-toggle').click();else window.DongranTerminal?.focus();}),
  'show-artifacts':()=>resumeWorkspace(()=>{closeDialog();window.DongranPages?.close();setPanel(true);renderInspector();}),
  'toggle-sidebar':()=>{closeDialog();actions.sidebar();},
  fullscreen:async()=>{try{if(document.fullscreenElement)await document.exitFullscreen();else await document.documentElement.requestFullscreen();}catch{toast('当前浏览器不支持全屏');}},
  about:()=>showDialog('关于', '<h3 class="about-title">Dongran</h3><p class="about-meta">项目工作台 · Prototype 0.2<br>本地交互原型，Agent 与终端使用演示数据。</p>'),
  team:async()=>{if(window.DongranRuntime?.enabled){try{const id=window.DongranProjects.current()?.id;if(id){const tasks=await window.DongranRuntime.request('/api/tasks?projectId='+encodeURIComponent(id));const active=tasks.find(t=>['queued','running','awaiting_approval'].includes(t.status));if(active){const plan=await window.DongranRuntime.request('/api/tasks/'+active.id+'/team');showDialog('当前 Agent 团队',`<p class="team-summary">${escapeHtml(plan.summary)}</p><div class="team-directory"><div class="team"><span class="agent-avatar lead">${icon('sparkles')}</span><div><strong>主 Agent</strong><small>拆解任务、调度协作者、复核结果</small></div><span class="online"></span></div>${plan.specialists.map(s=>`<div class="team"><span class="agent-avatar specialist">${escapeHtml(s.label.slice(0,1))}</span><div><strong>${escapeHtml(s.label)}</strong><small>${escapeHtml(s.purpose)} · ${escapeHtml(s.reason)}</small></div><span class="online"></span></div>`).join('')}</div>`);return;}}}catch(error){toast(error.message);}showDialog('Agent 团队',`<div class="team-directory"><div class="team"><span class="agent-avatar lead">${icon('sparkles')}</span><div><strong>主 Agent</strong><small>根据任务意图动态选择专业协作者</small></div><span class="online"></span></div><p class="empty-note">开始一个任务后，这里会显示本次任务实际启用的专业角色。</p></div>`)}} ,
  'close-dialog':closeDialog,attach:()=>$('#file-input').click(),run:runTests,
  settings:()=>window.DongranSettings.open(),
  account:(trigger)=>window.DongranUI.showMenu(trigger,{label:window.DongranSettings.get('accountNickname')||'我的账户',showHeading:true,items:[{type:'separator'},{value:'account',label:'账户',icon:'user-round'},{value:'settings',label:'设置',icon:'settings-2',shortcut:'Ctrl+,'}],onSelect:value=>window.DongranSettings.open(value==='account'?'account':'general')}),
  'edit-doc':()=>{const doc=$('#doc-content');if(!doc)return;const editing=doc.contentEditable==='true';if(editing){task().doc=doc.innerHTML;renderInspector();toast('文档已保存至当前会话');}else{doc.contentEditable='true';doc.focus();const b=$('[data-action="edit-doc"]');b.innerHTML=icon('check');b.title='保存文档';b.setAttribute('aria-label','保存文档');icons();}},
  download:()=>{const doc=$('#doc-content');if(!doc)return;const url=URL.createObjectURL(new Blob([doc.innerText],{type:'text/plain;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download='member-permissions.txt';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
};
document.addEventListener('click',(event)=>{
  const button=event.target.closest('button');if(!button||button.disabled)return;
  if(button.dataset.menu){openChoiceMenu(button);return;}
  if(button.dataset.action){actions[button.dataset.action]?.(button);return;}
  if(button.dataset.task){window.DongranPages?.close();state.active=window.DongranRuntime?.enabled?button.dataset.task:Number(button.dataset.task);closeDialog();$('.sidebar').classList.remove('open');renderTask();renderInspector();return;}
  if(button.dataset.tab){window.DongranPages?.close();state.tab=button.dataset.tab;setPanel(true);renderInspector();return;}
  if(button.dataset.suggestion){$('#prompt').value=button.dataset.suggestion;$('#prompt').focus();return;}
  if(button.dataset.remove!==undefined){state.attachments.splice(Number(button.dataset.remove),1);renderAttachments();return;}
  if(button.dataset.file!==undefined){if(task()?.sample){state.tab=Number(button.dataset.file)===0?'doc':'diff';renderInspector();}else toast(state.files[Number(button.dataset.file)]?.name||'文件');}
});
$('#composer').addEventListener('submit',submitTask);
$('#prompt').addEventListener('keydown',e=>{
  if(e.isComposing||e.keyCode===229||e.key!=='Enter'||e.shiftKey)return;
  e.preventDefault();
  if(task()?.running){toast('当前回答仍在生成，输入内容已保留。');return;}
  submitTask(e);
});
$('#file-input').addEventListener('change',e=>{state.attachments.push(...Array.from(e.target.files));renderAttachments();e.target.value='';});
$('#directory-input').addEventListener('change',e=>{const files=Array.from(e.target.files);if(!files.length)return;state.files=files;openProject(files[0].webkitRelativePath.split('/')[0]);toast('已打开目录，共 '+files.length+' 个文件');e.target.value='';});
$('#dialog').addEventListener('click',event=>{if(event.target===$('#dialog')){const r=$('#dialog').getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)closeDialog();}});
welcome();

function applyTaskPreferences(currentTask=task()) {
  const prefs=window.DongranSettings;
  if(!prefs)return;
  document.querySelectorAll('.step details').forEach(details=>{
    details.open=prefs.get('expandTools');
    const summary=details.querySelector('summary');
    if(!summary.dataset.fullLabel)summary.dataset.fullLabel=summary.innerHTML;
    summary.innerHTML=prefs.get('showToolCount')?summary.dataset.fullLabel:icon('chevron-right')+'执行记录';
  });
  if(currentTask&&!currentTask.sample){
    const keys=['enableProduct','enableDeveloper','enableTester'];
    document.querySelectorAll('.agent-message .timeline .step').forEach((step,index)=>{step.hidden=!currentTask.delegation||!currentTask.agents?.includes(keys[index]);});
    if(!currentTask.delegation&&$('.agent-message .auto-label'))$('.agent-message .auto-label').textContent='独立执行';
  }
  icons();
}

function applyWorkspacePreferences(event) {
  const prefs=event.detail.settings;
  const root=document.documentElement;
  root.dataset.theme=prefs.theme==='system'?(matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light'):prefs.theme;
  root.dataset.density=prefs.density;
  root.dataset.reduceMotion=String(prefs.reduceMotion);
  root.dataset.showActivity=String(prefs.showActivity);
  root.dataset.showSuggestions=String(prefs.showSuggestions);
  root.style.setProperty('--ui-font-size',`${prefs.fontSize}px`);
  document.documentElement.style.setProperty('--sidebar-width',`${prefs.sidebarWidth}px`);
  if(['init','import','reset','reset-category','defaultModel'].includes(event.detail.key)){state.model=prefs.defaultModel;$('#model-label').textContent=state.model;window.DongranRuntime?.enabled&&window.DongranProviders?.sync();}
  if(['init','import','reset','reset-category','permissionMode'].includes(event.detail.key)){$('#mode').value=prefs.permissionMode;$('#mode-label').textContent=prefs.permissionMode;}
  if(event.detail.key==='artifactPanel'&&task()?.sample){setPanel(prefs.artifactPanel==='auto');renderInspector();}
  applyTaskPreferences();
}
window.addEventListener('settingschange',applyWorkspacePreferences);
matchMedia('(prefers-color-scheme: dark)').addEventListener('change',()=>{
  if(window.DongranSettings?.get('theme')==='system')document.documentElement.dataset.theme=matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light';
});
function shortcutMatches(event,shortcut){
  const parts=shortcut.toLowerCase().split('+');
  const key=event.code.startsWith('Key')?event.code.slice(3).toLowerCase():event.code==='Comma'?',':event.code==='Backquote'?'`':event.key.toLowerCase();
  return key===parts.at(-1)&&Boolean(event.ctrlKey||event.metaKey)===parts.includes('ctrl')&&event.shiftKey===parts.includes('shift')&&event.altKey===parts.includes('alt');
}
document.addEventListener('keydown',async event=>{
  if(event.isComposing||$('#dialog').open)return;
  const prefs=window.DongranSettings;if(!prefs)return;
  if(shortcutMatches(event,prefs.get('settingsKey'))){event.preventDefault();const screen=$('#settings-screen');screen.hidden||screen.classList.contains('is-leaving')?prefs.open():prefs.close();}
  else if(shortcutMatches(event,prefs.get('terminalKey'))){event.preventDefault();if(await prefs.close())$('#terminal-toggle').click();}
  else if(shortcutMatches(event,prefs.get('searchKey'))){event.preventDefault();if(!$('#settings-screen').hidden)$('#settings-search').focus();else searchDialog();}
  else if(shortcutMatches(event,'Ctrl+Alt+N')){event.preventDefault();if(await prefs.close())newTask();}
});
