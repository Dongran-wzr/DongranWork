/* HTTP mode uses the local Spring Boot service. file:// remains the visual demo. */
(() => {
  const enabled = /^https?:$/.test(location.protocol) || !!window.DongranBackendBase;
  if (!enabled) return;
  const api = window.DongranBackend;
  const esc = escapeHtml;
  const activeStatuses = new Set(['queued', 'running', 'awaiting_approval']);
  const labels = {queued:'排队中',running:'执行中',awaiting_approval:'等待确认',completed:'已完成',failed:'失败',cancelled:'已停止',interrupted:'已中断'};
  let ready = false, source = null, selectedId = null, renderVersion = 0, savedSettings = {};
  let settingsQueue = Promise.resolve(), submitting = false, pendingText = '';
  const eventLog = new Map();
  let elapsedTimer=null;
  const request = (path, method='GET', body) => api.request(path, {method, ...(body === undefined ? {} : {body:JSON.stringify(body)})});
  const safely = work => Promise.resolve().then(work).catch(error => toast(error.message || '操作失败'));
  const requireReady = () => { if (!ready) throw new Error('本地服务尚未连接，请点击重新连接。'); };
  const projectId = () => projectContext?.id || null;
  const projectQuery = () => projectId() ? `?projectId=${encodeURIComponent(projectId())}` : '';
  const button = (id, text, primary=false) => `<button type="button" id="${id}" class="${primary?'primary':'secondary'}">${text}</button>`;
  const pre = text => `<pre class="runtime-pre">${esc(text)}</pre>`;
  let followLatest=true,scrollTask=null,scrollFrame=0,animatingScroll=false,animateNext=false;
  const latestButton=document.createElement('button');latestButton.id='chat-latest';latestButton.className='chat-latest';latestButton.type='button';latestButton.title='回到最新';latestButton.setAttribute('aria-label','回到最新消息');latestButton.innerHTML=icon('arrow-down');latestButton.hidden=true;$('#composer-area').prepend(latestButton);
  const chatScroller=$('#messages');
  function syncLatest(){latestButton.hidden=!state.active||chatScroller.scrollHeight-chatScroller.clientHeight-chatScroller.scrollTop<80;}
  function scrollLatest(force=false,animate=false){
    if(force)followLatest=true;
    if(!followLatest){syncLatest();return;}
    if(animate&&!matchMedia('(prefers-reduced-motion: reduce)').matches&&document.documentElement.dataset.reduceMotion!=='true'){
      cancelAnimationFrame(scrollFrame);const start=chatScroller.scrollTop,began=performance.now();animatingScroll=true;
      const tick=now=>{const progress=Math.min(1,(now-began)/340),target=chatScroller.scrollHeight-chatScroller.clientHeight;chatScroller.scrollTop=start+(target-start)*(1-Math.pow(1-progress,3));syncLatest();if(progress<1)scrollFrame=requestAnimationFrame(tick);else{animatingScroll=false;followLatest=true;}};scrollFrame=requestAnimationFrame(tick);
    }else if(!animatingScroll){chatScroller.scrollTop=chatScroller.scrollHeight;syncLatest();}
  }
  function interruptScroll(){cancelAnimationFrame(scrollFrame);animatingScroll=false;followLatest=false;}
  chatScroller.addEventListener('wheel',e=>{if(e.deltaY<0)interruptScroll();},{passive:true});
  chatScroller.addEventListener('touchstart',interruptScroll,{passive:true});
  chatScroller.addEventListener('keydown',e=>{if(['ArrowUp','PageUp','Home'].includes(e.key))interruptScroll();});
  chatScroller.addEventListener('scroll',()=>{if(!animatingScroll)followLatest=chatScroller.scrollHeight-chatScroller.clientHeight-chatScroller.scrollTop<80;syncLatest();},{passive:true});
  latestButton.onclick=()=>scrollLatest(true,true);
  new ResizeObserver(()=>{if(followLatest)scrollLatest();else syncLatest();}).observe(chatScroller);
  function detach() { cancelAnimationFrame(scrollFrame);animatingScroll=false;animateNext=false;followLatest=true;scrollTask=null;latestButton.hidden=true;clearInterval(elapsedTimer);elapsedTimer=null;source?.close(); source=null; selectedId=null; pendingText=''; renderVersion++; }

  async function connect() {
    const boot = await api.connect();
    if (!boot) { ready=false; home(); toast(api.state.error || '无法连接本地服务'); return; }
    ready=true;
    window.DongranSettings.hydrate(boot.settings);
    savedSettings=window.DongranSettings.snapshot();
    window.DongranSchedules?.hydrate(boot.schedules);
    document.querySelector('.terminal-demo').textContent='本地命令';
    actions.about=()=>showDialog('关于 Dongran','<p>本地 Agent 工作台 · Spring Boot 服务已连接</p><p>终端执行独立命令进程，任务和资料保存在本机数据库。</p>');
    actions.demo=()=>toast('当前是真实工作区，请打开本地项目。');
    await reloadTasks();
    await window.DongranProviders.load();
    home();
  }

  async function reloadTasks() {
    const tasks = await request('/api/tasks'+projectQuery());
    state.tasks=tasks.map(item=>({...item,running:activeStatuses.has(item.status),done:item.status==='completed',replies:[]}));
    renderSidebar(); icons();
  }

  function home() {
    detach();state.active=null;
    window.DongranPages?.close();setTaskTitle('新的任务');setPanel(false);
    $('#composer-area').hidden=false;$('.conversation').classList.add('start-view');
    $('#send').innerHTML=icon('arrow-up');
    $('#messages').innerHTML=`<div class="start-content"><div class="project-study activity-map"></div><h1>Dongran</h1><p class="start-question">今天，我们推进哪个项目？</p><div class="start-actions">${button('runtime-open','打开项目目录')}${!ready?button('runtime-reconnect','重新连接'):''}</div><div class="recent-projects"><div class="recent-label"><span>最近项目</span><span>${ready?'本地服务已连接':'正在连接本地服务'}</span></div><div id="runtime-recent"></div></div></div>`;
    $('#runtime-open').onclick=()=>safely(()=>directory());
    $('#runtime-reconnect')?.addEventListener('click',()=>safely(connect));
    window.DongranActivity.render($('.activity-map'),{counts:{},sample:false});
    if(ready){
      safely(async()=>{
        const rows=await request('/api/projects');const target=$('#runtime-recent');if(!target)return;
        target.innerHTML=projectRows(rows)||'<p class="empty-note">选择一个本地目录开始工作。</p>';
        bindProjects(target);icons();
      });
      safely(activity);
    }
    renderSidebar();icons();
  }

  const projectRows = rows => rows.map(p=>`<button class="project-row" data-runtime-project="${esc(p.id)}" data-runtime-path="${esc(p.path)}">${icon('folder')}<span><strong>${esc(p.name)}</strong><small>${esc(p.path)}</small></span>${icon('chevron-right')}</button>`).join('');
  function bindProjects(host){host.querySelectorAll('[data-runtime-project]').forEach(b=>b.onclick=()=>safely(()=>open(b.dataset.runtimePath)));}
  async function projects(){
    requireReady(); const rows=await request('/api/projects');
    showDialog('最近项目',button('runtime-browse','打开项目目录',true)+projectRows(rows));
    $('#runtime-browse').onclick=()=>safely(()=>directory());bindProjects($('#dialog'));icons();
  }
  async function directory(path){
    requireReady();
    const listing=await request('/api/directories'+(path?`?path=${encodeURIComponent(path)}`:''));
    showDialog('选择项目目录',`<form id="runtime-path-form" class="runtime-form"><label>目录路径<input id="runtime-path" class="search-input" value="${esc(listing.path)}" autocomplete="off"></label><div class="runtime-actions">${button('runtime-parent','上一级')}<button class="secondary" type="submit">前往</button>${button('runtime-select','将此目录作为项目',true)}</div></form><div class="runtime-actions">${listing.roots.map(r=>`<button class="secondary" data-root="${esc(r)}">${esc(r)}</button>`).join('')}</div><div class="runtime-directory">${listing.directories.map(d=>`<button class="project-row" data-directory="${esc(d.path)}">${icon('folder')}<span>${esc(d.name)}</span>${icon('chevron-right')}</button>`).join('')}</div>`);
    $('#runtime-path-form').onsubmit=e=>{e.preventDefault();safely(()=>directory($('#runtime-path').value));};
    $('#runtime-parent').disabled=!listing.parent;$('#runtime-parent').onclick=()=>safely(()=>directory(listing.parent));
    $('#runtime-select').onclick=()=>safely(()=>open(listing.path));
    $('#dialog').querySelectorAll('[data-directory],[data-root]').forEach(b=>b.onclick=()=>safely(()=>directory(b.dataset.directory||b.dataset.root)));icons();
  }
  async function open(path){
    const p=await request('/api/projects','POST',{path});detach();
    projectContext={...p,sample:false};state.project=p.name;state.active=null;state.attachments=[];
    $('#project-name').textContent=p.name;$('#project-path').textContent=p.path;$('#context-project').textContent=p.name;$('#breadcrumb').textContent=p.name;
    $('#branch-name').textContent='读取 Git…';
    window.dispatchEvent(new CustomEvent('projectchange',{detail:{...projectContext}}));
    closeDialog();await reloadTasks();newTask();
    if(window.DongranSettings.get('gitAutoFetch'))safely(()=>request('/api/projects/'+p.id+'/git/fetch','POST',{confirmed:true}));
    safely(async()=>{const git=await request(`/api/projects/${p.id}/git`);if(projectId()===p.id)$('#branch-name').textContent=git.branch||'未初始化 Git';});
  }
  async function activity(){
    if(!ready)return;
    const id=projectId(),rows=await request('/api/activity'+projectQuery());
    if(id!==projectId())return;
    const counts=Object.fromEntries(rows.map(row=>[row.date,row.count]));
    document.querySelectorAll('.activity-map').forEach(container=>window.DongranActivity.render(container,{projectId:id,counts,sample:false}));
  }
  async function submit(){
    if(submitting)return;
    try{
      requireReady(); const current=task();
      if(current?.running){await request(`/api/tasks/${current.id}/cancel`,'POST');await renderTask();return;}
      const prompt=$('#prompt').value.trim();if(!prompt)return;
      if(state.attachments.length){toast('请先把附件导入知识库，或放入项目目录后在任务中说明路径。');return;}
      submitting=true;
      await settingsQueue;
      const value=current?await request(`/api/tasks/${current.id}/messages`,'POST',{prompt}):await request('/api/tasks','POST',{prompt,projectId:projectId(),mode:$('#mode').value});
      state.active=value.id;followLatest=true;animateNext=true;$('#prompt').value='';await reloadTasks();await renderTask();
    }catch(error){toast(error.message);}finally{submitting=false;}
  }
  function knowledgeButton(row,query=''){
    return `<button type="button" class="knowledge-citation" data-cite-document="${esc(row.id)}" data-cite-chunk="${esc(row.chunkId)}" data-cite-query="${esc(query)}">${icon('file-search')}<span>${esc(row.name||'知识库资料')} · ${esc(row.location||'查看片段')}</span></button>`;
  }
  function bindCitations(host){host.querySelectorAll('[data-cite-document]').forEach(button=>button.onclick=()=>window.DongranKnowledgePreview.open(button.dataset.citeDocument,{chunkId:button.dataset.citeChunk,query:button.dataset.citeQuery||''}));}
  function answerMarkup(text){return window.DongranMarkdown.render(text,knowledgeButton);}
  let markdownFrame=0;
  function renderStreamingMarkdown(){
    if(markdownFrame)return;
    markdownFrame=requestAnimationFrame(()=>{markdownFrame=0;const target=$('#runtime-stream');if(target){target.innerHTML=answerMarkup(pendingText);bindCitations(target);}scrollLatest();});
  }
  function messageMarkup(message){
    if(message.role==='route'){try{const route=JSON.parse(message.content);const names={search_knowledge:'知识库检索',web_fetch:'读取网页',web_search:'搜索网页',list_files:'查看项目文件'};return `<div class="intent-route">${esc(route.actions.map(a=>names[a]||a).join(' → ')||'理解问题并回答')}</div>`;}catch{return '';}}
    if(message.role==='web_source'){try{const data=JSON.parse(message.content),rows=data.tool==='web_fetch'?[data.result]:(data.result.results||[]);return `<section class="knowledge-sources"><small>${data.tool==='web_fetch'?'已读取网页':'网页搜索结果'}</small><div>${rows.filter(row=>/^https?:\/\//i.test(row.url||'')).map(row=>`<a class="knowledge-citation" href="${esc(row.url)}" target="_blank" rel="noopener noreferrer">${esc(row.title||row.url)}</a>`).join('')||'没有找到相关网页'}</div></section>`;}catch{return '';}}
    if(message.role==='memory_capture'){try{const data=JSON.parse(message.content);return `<div class="intent-route">${data.status==='completed'?(data.candidates?`已提炼 ${data.candidates} 条记忆候选，可在设置 → 记忆中审阅`:'本轮没有待保存的稳定记忆'):esc(data.message)}</div>`;}catch{return '';}}
    if(message.role==='memory_status'){try{const data=JSON.parse(message.content);return `<div class="intent-route">${data.status==='active'?'已保存明确要求的记忆':'记忆待审阅'}：${esc(data.title)} · 可在设置 → 记忆中撤销</div>`;}catch{return '';}}
    if(message.role==='action_status')return `<p class="runtime-error">${esc(message.content)}</p>`;
    if(message.role==='knowledge'){try{const data=JSON.parse(message.content);return `<div class="intent-route">${data.results.length?'已读取相关知识库资料':'知识库没有找到相关资料'}</div>`;}catch{return '';}}
    if(message.role==='references'){try{const data=JSON.parse(message.content);return `<section class="knowledge-sources"><small>参考资料 · ${data.results.length} 处引用</small><div>${data.results.map(row=>knowledgeButton(row,row.query||'')).join('')}</div></section>`;}catch{return '';}}

    const name=message.role==='user'?'你':({lead:'主 Agent',product:'产品 Agent',developer:'开发 Agent',tester:'测试 Agent'}[message.agent]||'Agent');
    return `<article class="runtime-message"><div class="message-meta"><span class="${message.role==='user'?'user-avatar':'agent-avatar lead'}">${message.role==='user'?icon('user-round'):icon('sparkles')}</span><strong>${name}</strong></div><div class="runtime-message-text${message.role==='assistant'?' markdown-body':''}">${message.role==='assistant'?answerMarkup(message.content):esc(message.content)}</div></article>`;
  }
  function renderElapsed(value){
    clearInterval(elapsedTimer);elapsedTimer=null;
    const latestUser=[...value.messages].reverse().find(message=>message.role==='user');
    const start=Date.parse(latestUser?.createdAt||value.createdAt);
    const active=activeStatuses.has(value.status);
    const finish=active?null:Date.parse(value.updatedAt);
    const update=()=>{
      const node=document.querySelector('#task-elapsed');
      if(state.active!==value.id||!node){clearInterval(elapsedTimer);elapsedTimer=null;return;}
      if(!Number.isFinite(start)){node.hidden=true;return;}
      const seconds=Math.max(0,Math.floor(((active?Date.now():finish)-start)/1000));
      node.textContent='本轮用时 '+(seconds<60?seconds+' 秒':Math.floor(seconds/60)+' 分 '+seconds%60+' 秒');
      node.title='从本轮发送起计算，包含排队和等待确认的时间';
    };
    update();if(active)elapsedTimer=setInterval(update,1000);
  }
  async function renderTask(){
    const id=state.active;if(!id){newTask();return;} const version=++renderVersion;
    try{
      const value=await request(`/api/tasks/${id}`);if(state.active!==id||version!==renderVersion)return;
      const row=state.tasks.find(t=>t.id===id);if(row)Object.assign(row,value,{running:activeStatuses.has(value.status),done:value.status==='completed'});
      $('.conversation').classList.remove('start-view');setTaskTitle(value.title);$('#composer-area').hidden=false;
      $('#send').innerHTML=icon(activeStatuses.has(value.status)?'square':'arrow-up');$('#send').title=activeStatuses.has(value.status)?'停止执行':'发送任务';$('#send').setAttribute('aria-label',$('#send').title);
      const pending=value.approvals.filter(a=>a.status==='pending');
      const previousTop=chatScroller.scrollTop;const stick=followLatest||scrollTask!==id;scrollTask=id;
      $('#messages').innerHTML=`<div class="thread"><div class="task-heading"><h1>${esc(value.title)}</h1><span class="badge ${value.status==='completed'?'success':'review'}">${labels[value.status]||value.status}</span><span id="task-elapsed" class="task-elapsed" role="timer" aria-live="off"></span><button class="secondary context-open" data-context-task="${esc(value.id)}">本轮上下文</button></div>${value.messages.map(messageMarkup).join('')}<div id="runtime-stream" class="runtime-message-text markdown-body" aria-live="polite"></div>${pending.map(a=>`<section class="permission"><div class="permission-title">${icon('shield-check')}确认操作：${esc(a.action==='confirm_plan'?'确认执行':a.action)}</div>${pre(JSON.stringify(a.arguments,null,2))}<div class="runtime-actions"><button class="secondary" data-approve="${esc(a.id)}" data-allowed="false">拒绝</button><button class="primary" data-approve="${esc(a.id)}" data-allowed="true">批准本次操作</button></div></section>`).join('')}${value.error?`<p class="runtime-error">${esc(value.error)}</p>`:''}</div>`;
      $('#messages').querySelectorAll('[data-approve]').forEach(b=>b.onclick=()=>safely(async()=>{b.disabled=true;try{await request(`/api/approvals/${b.dataset.approve}`,'POST',{approved:b.dataset.allowed==='true'});await renderTask();}finally{b.disabled=false;}}));
      bindCitations(chatScroller);
      renderElapsed(value);
      $('#runtime-stream').innerHTML=answerMarkup(pendingText);bindCitations($('#runtime-stream'));
      if(stick){scrollLatest(true,animateNext);animateNext=false;}else{chatScroller.scrollTop=previousTop;syncLatest();}
      renderSidebar();icons();if(selectedId!==id||(!source&&activeStatuses.has(value.status)))subscribe(id);
    }catch(error){toast(error.message);}
  }
  function subscribe(id){
    source?.close();selectedId=id;pendingText='';eventLog.set(id,[]);const stream=api.events(id);source=stream;
    let cursor=0;
    const on=(type,callback)=>stream.addEventListener(type,event=>{if(source!==stream||state.active!==id)return;const n=Number(event.lastEventId);if(type!=='end'&&n&&n<=cursor)return;if(n)cursor=n;callback(JSON.parse(event.data));});
    on('delta',data=>{pendingText+=data.text;renderStreamingMarkdown();});
    on('message',()=>{pendingText='';renderTask();});
    on('status',()=>renderTask());
    on('approval',()=>renderTask());
    for(const kind of ['tool','agent','hook'])on(kind,data=>{const items=eventLog.get(id)||[];items.push({kind,...data});eventLog.set(id,items.slice(-200));if(state.panel&&state.tab==='run')inspector();});
    on('end',()=>{stream.close();if(source===stream)source=null;renderTask();safely(reloadTasks);safely(activity);});
    stream.onerror=()=>{if(source===stream)toast('事件连接暂时中断，正在重连。');};
  }
  async function inspector(path=''){
    const host=$('#inspector-content');if(!host)return;
    document.querySelectorAll('.tabs button').forEach(b=>{b.classList.toggle('active',b.dataset.tab===state.tab);b.setAttribute('aria-selected',String(b.dataset.tab===state.tab));});
    $('#artifact-status').textContent='当前项目 · 实际数据';$('#artifact-total').textContent='—';
    if(!projectId()){host.innerHTML='<div class="empty-note">打开项目后查看文件和 Git 变更。</div>';return;}
    const id=projectId(),tab=state.tab;
    try{
      if(tab==='diff'){
        const result=await request(`/api/projects/${id}/git/diff`);if(tab!==state.tab||id!==projectId())return;
        host.innerHTML='<div class="file-bar">Git 工作区差异</div>'+pre(result.output||JSON.stringify(result,null,2));
      }else if(tab==='run'){
        host.innerHTML='<div class="file-bar">真实工具与 Agent 记录</div>'+pre((eventLog.get(state.active)||[]).map(item=>JSON.stringify(item,null,2)).join('\n\n')||'当前尚无工具执行记录。');
      }else{
        const files=await request(`/api/projects/${id}/files?path=${encodeURIComponent(path)}`);if(tab!==state.tab||id!==projectId())return;
        host.innerHTML=`<div class="file-bar">${esc(path||'项目文件')}${button('runtime-git','Git / 工作树')}</div><div class="files-tree">${path?button('runtime-file-up','上一级'):''}${files.map((f,i)=>`<button class="file-entry" data-runtime-file="${i}">${icon(f.directory?'folder':'file-text')}<span>${esc(f.name)}</span></button>`).join('')}</div>`;
        $('#runtime-git').onclick=()=>safely(gitPanel);
        $('#runtime-file-up')?.addEventListener('click',()=>inspector(path.split('/').slice(0,-1).join('/')));
        host.querySelectorAll('[data-runtime-file]').forEach(b=>b.onclick=()=>{const f=files[Number(b.dataset.runtimeFile)];safely(()=>f.directory?inspector(f.path):editFile(f.path));});icons();
      }
    }catch(error){host.innerHTML=`<p class="runtime-error">${esc(error.message)}</p>`;}
  }
  async function editFile(path){
    const id=projectId(),file=await request(`/api/projects/${id}/file?path=${encodeURIComponent(path)}`);
    showDialog(path,`<form id="runtime-file-form" class="runtime-form"><textarea id="runtime-file-content" class="runtime-editor" spellcheck="false" aria-label="文件内容">${esc(file.content)}</textarea><p>保存将修改此文件；如果文件已被外部修改，将提示冲突。</p><p id="runtime-file-error" class="runtime-error"></p><button class="primary">保存文件</button></form>`);
    $('#runtime-file-form').onsubmit=async e=>{e.preventDefault();const b=e.target.querySelector('button');b.disabled=true;try{await request(`/api/projects/${id}/file`,'PUT',{path,content:$('#runtime-file-content').value,expectedSha256:file.sha256,confirmed:true});closeDialog();toast('文件已保存');}catch(error){$('#runtime-file-error').textContent=error.message;}finally{b.disabled=false;}};
  }
  async function gitPanel(){return window.DongranGit.open();}
  async function worktreePanel(){
    const id=projectId();if(!id)throw new Error('请先打开项目。');
    const [status,trees]=await Promise.all([request(`/api/projects/${id}/git`),request(`/api/projects/${id}/worktrees`).catch(e=>({message:e.message}))]);
    showDialog('Git 与工作树',`<div class="runtime-form"><h3>项目状态</h3>${pre(status.output||JSON.stringify(status,null,2))}<h3>工作树</h3>${pre(trees.output||JSON.stringify(trees,null,2))}<div class="runtime-actions">${button('runtime-git-init','初始化仓库')}${button('runtime-git-fetch','获取远端')}${button('runtime-git-new','新建工作树')}</div><label>提交说明<input id="runtime-commit-message" class="search-input" placeholder="说明本次改动"></label><label>提交文件（每行一个相对路径）<textarea id="runtime-commit-files" class="search-input" rows="3"></textarea></label>${button('runtime-git-commit','提交所列文件')}</div>`);
    $('#runtime-git-init').onclick=()=>safely(()=>confirmOperation('初始化当前项目 Git 仓库？',()=>request(`/api/projects/${id}/git/init`,'POST',{confirmed:true})));
    $('#runtime-git-fetch').onclick=()=>safely(()=>confirmOperation('连接远端并获取更新？',()=>request(`/api/projects/${id}/git/fetch`,'POST',{confirmed:true})));
    $('#runtime-git-commit').onclick=()=>{const message=$('#runtime-commit-message').value,files=$('#runtime-commit-files').value.split('\n').map(v=>v.trim()).filter(Boolean);confirmOperation('提交以下文件？\n'+files.join('\n'),()=>request(`/api/projects/${id}/git/commit`,'POST',{confirmed:true,message,paths:files}));};
    $('#runtime-git-new').onclick=()=>showWorktreeForm(id);
    $('#dialog-content').insertAdjacentHTML('beforeend','<div class="runtime-form"><label>移除工作树（仅限本项目创建的干净工作树）<input id="runtime-tree-remove-path" class="search-input" placeholder="粘贴上方工作树的完整路径"></label>'+button('runtime-tree-remove','移除指定工作树')+'</div>');
    $('#runtime-tree-remove').onclick=()=>{const path=$('#runtime-tree-remove-path').value.trim();if(!path){toast('请填写工作树路径');return;}confirmOperation('移除工作树：'+path,()=>request('/api/projects/'+id+'/worktrees','DELETE',{path,confirmed:true}));};
  }
  function showWorktreeForm(id){
    showDialog('新建工作树',`<form id="runtime-tree-form" class="runtime-form"><label>分支名称<input id="runtime-tree-branch" class="search-input" required></label><label>基准引用<input id="runtime-tree-name" class="search-input" value="HEAD" required></label><p id="runtime-tree-error" class="runtime-error"></p><button class="primary">创建工作树</button></form>`);
    $('#runtime-tree-form').onsubmit=async e=>{e.preventDefault();try{await request(`/api/projects/${id}/worktrees`,'POST',{branch:$('#runtime-tree-branch').value,base:$('#runtime-tree-name').value,confirmed:true});closeDialog();toast('工作树已创建');}catch(error){$('#runtime-tree-error').textContent=error.message;}};
  }
  function confirmOperation(description,operation){
    showDialog('确认操作',pre(description)+`<p id="runtime-confirm-error" class="runtime-error"></p><div class="runtime-actions">${button('runtime-confirm-cancel','取消')}${button('runtime-confirm-ok','确认执行',true)}</div>`);
    $('#runtime-confirm-cancel').onclick=closeDialog;
    $('#runtime-confirm-ok').onclick=async e=>{e.target.disabled=true;try{const result=await operation();closeDialog();toast(result.output||'操作已完成');}catch(error){$('#runtime-confirm-error').textContent=error.message;e.target.disabled=false;}};
  }
  async function knowledge(host){
    const id=projectId();let uploadScope=id;
    host.innerHTML=`<div class="knowledge-page"><header class="knowledge-heading"><h1>知识库</h1><span class="workspace-pending">本地索引</span></header><p>${esc(projectContext?.name||'全局工作区')} · 文档导入、窗口预览与片段检索</p><div class="runtime-actions">${button('runtime-knowledge-add','新建资料',true)}<label class="secondary runtime-upload">导入文件<input id="runtime-knowledge-upload" type="file" multiple accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.odt,.ods,.odp,.rtf,.txt,.md,.markdown,.csv,.json,.java,.js,.ts,.tsx,.jsx,.yaml,.yml,.xml,.html,.css,.sql,.py,.rs,.go,.sh,.log" hidden></label>${button('runtime-knowledge-upload-scope',id?'导入到当前项目':'导入到全局')}${button('runtime-knowledge-settings','检索设置')}</div><p class="knowledge-search-info">每份文件最多 10 MB · 一次最多 20 份 · PDF / DOCX 排版预览，其他格式提供内容预览</p><p id="knowledge-upload-state" class="knowledge-upload-state" role="status"></p><input id="runtime-knowledge-query" class="search-input" placeholder="搜索文档内容，支持中文和英文" aria-label="检索资料" maxlength="200"><p id="knowledge-search-state" class="knowledge-search-info"></p><p id="runtime-knowledge-error" class="runtime-error" role="alert"></p><div id="runtime-knowledge-list"></div></div>`;
    const list=host.querySelector('#runtime-knowledge-list');let version=0;
    async function refresh(){
      const current=++version;try{
        const queryInput=host.querySelector('#runtime-knowledge-query');if(!queryInput||!list.isConnected)return;const q=queryInput.value.trim();
        const params=new URLSearchParams();if(id)params.set('projectId',id);if(q)params.set('query',q);
        const result=await request((q?'/api/knowledge/search?':'/api/knowledge?')+params);if(current!==version||!list.isConnected)return;
        const rows=q?result.results:result;
        host.querySelector('#knowledge-search-state').textContent=q?(result.mode||'bm25').toUpperCase()+' · '+rows.length+' 个相关片段 '+(result.warnings||[]).join(' '):rows.length+' 份资料';
        list.innerHTML=rows.map(row=>`<button class="project-row" data-knowledge-id="${esc(row.id)}" data-knowledge-chunk="${esc(row.chunkId||'')}">${icon('file-text')}<span><strong>${esc(row.name)}</strong><small>${row.projectId?'当前项目':'全局'} · ${esc(row.excerpt||((row.characters===0)?'没有可提取文本 · 可预览原件，扫描件需 OCR':'点击打开文档预览'))}</small>${row.location?`<span class="knowledge-result-meta">${esc(row.location)} · ${row.rerankScore!=null?'重排 '+Number(row.rerankScore).toPrecision(3):row.score!=null?'相似度 '+Number(row.score).toPrecision(3):'BM25 '+Number(row.bm25).toPrecision(3)}</span>`:''}</span>${icon('arrow-up-right')}</button>`).join('')||'<p class="empty-note">暂无资料或没有匹配结果。支持 Office、PDF、Markdown、文本和代码。</p>';
        list.querySelectorAll('[data-knowledge-id]').forEach(b=>b.onclick=()=>window.DongranKnowledgePreview.open(b.dataset.knowledgeId,{chunkId:b.dataset.knowledgeChunk,query:q,edit:()=>editKnowledge(b.dataset.knowledgeId,id,refresh),onDeleted:refresh}));
        host.querySelector('#runtime-knowledge-error').textContent='';icons();
      }catch(error){const node=host.querySelector('#runtime-knowledge-error');if(node)node.textContent=error.message;}
    }
    let timer;host.querySelector('#runtime-knowledge-query').oninput=()=>{clearTimeout(timer);timer=setTimeout(refresh,300);};
    host.querySelector('#runtime-knowledge-add').onclick=()=>editKnowledge(null,id,refresh);
    host.querySelector('#runtime-knowledge-settings').onclick=()=>DongranKnowledgeSettings.open();
    host.querySelector('#runtime-knowledge-upload-scope').onclick=e=>DongranUI.showMenu(e.currentTarget,{label:'导入范围',items:[{value:'global',label:'全局资料',checked:!uploadScope},...(id?[{value:'project',label:'当前项目',checked:!!uploadScope}]:[])],onSelect:value=>{uploadScope=value==='project'?id:null;e.currentTarget.textContent=uploadScope?'导入到当前项目':'导入到全局';}});
    host.querySelector('#runtime-knowledge-upload').onchange=async e=>{
      const input=e.target,files=[...input.files];if(!files.length)return;
      const note=host.querySelector('#knowledge-upload-state'),scope=uploadScope,failures=[];let succeeded=0;
      if(files.length>20){note.textContent='一次最多选择 20 份文件。';input.value='';return;}
      input.disabled=true;
      for(let i=0;i<files.length;i++){
        const file=files[i];note.textContent='正在导入 '+(i+1)+' / '+files.length+'：'+file.name;
        try{
          if(file.size>10*1024*1024)throw Error('超过 10 MB');
          const form=new FormData();form.append('file',file);if(scope)form.append('projectId',scope);
          await api.request('/api/knowledge/upload',{method:'POST',body:form});succeeded++;
        }catch(error){failures.push(file.name+'：'+error.message);}
      }
      note.textContent='已导入 '+succeeded+' / '+files.length+' 份。'+(failures.length?'\n'+failures.join('\n'):'');
      input.disabled=false;input.value='';await refresh();
    };
    await refresh();
  }
  async function editKnowledge(id,scope,refresh){
    const value=id?await request('/api/knowledge/'+id):{name:'',content:''};let selectedScope=value.projectId??scope;
    showDialog(id?'编辑资料':'新建资料',`<form id="runtime-knowledge-form" class="runtime-form"><label>名称<input id="runtime-knowledge-name" class="search-input" required maxlength="200" value="${esc(value.name)}"></label>${!id?button('runtime-knowledge-scope',selectedScope?'当前项目':'全局'):''}<label>内容<textarea id="runtime-knowledge-content" class="runtime-editor" required>${esc(value.content)}</textarea></label><p id="runtime-knowledge-form-error" class="runtime-error"></p><div class="runtime-actions">${id?button('runtime-knowledge-preview','窗口预览'):''}${id?button('runtime-knowledge-delete','删除资料'):''}<button class="primary">保存资料</button></div></form>`);
    $('#runtime-knowledge-preview')?.addEventListener('click',()=>window.DongranKnowledgePreview?.open(id,{edit:()=>editKnowledge(id,scope,refresh),onDeleted:refresh}));
    $('#runtime-knowledge-scope')?.addEventListener('click',e=>window.DongranUI.showMenu(e.currentTarget,{label:'资料范围',items:[{value:'global',label:'全局'},...(scope?[{value:'project',label:'当前项目'}]:[])],onSelect:v=>{selectedScope=v==='project'?scope:null;$('#runtime-knowledge-scope').textContent=v==='project'?'当前项目':'全局';}}));
    $('#runtime-knowledge-form').onsubmit=async e=>{e.preventDefault();try{await request('/api/knowledge'+(id?'/'+id:''),id?'PUT':'POST',{name:$('#runtime-knowledge-name').value,content:$('#runtime-knowledge-content').value,projectId:selectedScope});closeDialog();await refresh();}catch(error){$('#runtime-knowledge-form-error').textContent=error.message;}};
    $('#runtime-knowledge-delete')?.addEventListener('click',()=>confirmOperation('删除这份资料？',async()=>{const result=await request('/api/knowledge/'+id,'DELETE');await refresh();return result;}));
  }
  function saveSettings(snapshot){
    const action=settingsQueue.catch(()=>{}).then(async()=>{
      requireReady();const changed=Object.fromEntries(Object.entries(snapshot).filter(([key,value])=>JSON.stringify(value)!==JSON.stringify(savedSettings[key])));
      if(!Object.keys(changed).length)return;
      await request('/api/settings','PATCH',changed);Object.assign(savedSettings,structuredClone(changed));
    });settingsQueue=action;return action;
  }
  function settingsPanel(category,host){
    if(category==='extensions'){
      host.querySelectorAll('[data-extension-id]').forEach(row=>{
        const id=row.dataset.extensionId;
        const configured=window.DongranSettings.get('installedExtensions').find(item=>item.id===id);
        const note=row.querySelector('small');if(note&&configured)note.textContent=configured.enabled?'已启用 · 可交给主 Agent 执行':'已禁用';
        if(!configured?.enabled)return;
        const run=document.createElement('button');run.type='button';run.className='settings-command';run.textContent='执行';
        row.querySelector('.integration-row-actions').prepend(run);
        run.onclick=()=>safely(async()=>{
          await settingsQueue;
          const result=await request('/api/extensions/'+id+'/run','POST',{projectId:projectId(),mode:$('#mode').value});
          if(!await window.DongranSettings.close())return;
          window.DongranPages.close();await reloadTasks();state.active=result.id;await renderTask();
        });
      });
    }
    if(category==='connections'){
      host.querySelectorAll('[data-connection-id]').forEach(row=>{
        const id=row.dataset.connectionId,actions=row.querySelector('.integration-row-actions');
        const test=document.createElement('button');test.className='settings-command';test.type='button';test.textContent='测试';
        const credentials=document.createElement('button');credentials.className='settings-command';credentials.type='button';credentials.textContent='凭据';
        actions.prepend(test,credentials);
        test.onclick=async()=>{
          test.disabled=true;const status=row.querySelector('.integration-state');status.textContent='连接中…';
          try{await settingsQueue;const result=await request('/api/connections/'+id+'/test','POST');status.textContent=result.connected?'已连接':'已响应';}
          catch(error){status.textContent='连接失败';toast(error.message);}finally{test.disabled=false;}
        };
        credentials.onclick=()=>credentialDialog(id);
      });
    }
    if(category==='models'){window.DongranProviders.render(host);return;}
    if(category==='git'){
      host.insertAdjacentHTML('beforeend',button('runtime-settings-git','打开 Git 工作区')+button('runtime-settings-worktrees','管理工作树'));
      $('#runtime-settings-git').onclick=()=>safely(gitPanel);
      $('#runtime-settings-worktrees').onclick=()=>safely(worktreePanel);
      host.querySelector('.settings-project-context span')?.replaceChildren(document.createTextNode('点击下方按钮读取实际 Git 状态'));
      host.insertAdjacentHTML('beforeend','<p class="settings-footnote">当前支持手动创建、查看和移除工作树；自动按任务创建、初始化命令与自动清理策略尚未启用。</p>');
    }
  }
  function credentialDialog(id){
    showDialog('连接凭据',`<form id="runtime-credential-form" class="runtime-form"><label>访问令牌<input id="runtime-credential-value" type="password" autocomplete="new-password" class="search-input"></label><label><input id="runtime-credential-remember" type="checkbox" checked> 使用系统凭据存储</label><p id="runtime-credential-error" class="runtime-error"></p><div class="runtime-actions">${button('runtime-credential-delete','清除凭据')}<button class="primary">保存凭据</button></div></form>`);
    $('#runtime-credential-form').onsubmit=async e=>{e.preventDefault();try{await request('/api/credentials/'+id,'PUT',{value:$('#runtime-credential-value').value,remember:$('#runtime-credential-remember').checked});closeDialog();toast('凭据已保存');}catch(error){$('#runtime-credential-error').textContent=error.message;}};
    $('#runtime-credential-delete').onclick=()=>safely(async()=>{await request('/api/credentials/'+id,'DELETE');closeDialog();toast('凭据已清除');});
  }
  async function scheduleAction(id,kind){
    if(kind==='run'){const result=await request(`/api/schedules/${id}/run`,'POST');await reloadTasks();state.active=result.id;window.DongranPages.close();await renderTask();}
    else {const rows=await request(`/api/schedules/${id}/runs`);showDialog('计划执行记录',pre(rows.length?rows.map(r=>`${r.due_at} · ${r.taskStatus||r.status} ${r.error||''}`).join('\n'):'暂无执行记录'));}
  }
  window.DongranRuntime={enabled,connect,home,projects:()=>safely(projects),directory:(path)=>safely(()=>directory(path)),open,submit,renderTask,inspector,activity,detach,knowledge,saveSettings,settingsPanel,scheduleAction:(id,kind)=>safely(()=>scheduleAction(id,kind)),request};
  home();safely(connect);
})();
