(() => {
  const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const icon=name=>`<i data-lucide="${name}"></i>`;
  const dialog=document.createElement('dialog');
  dialog.id='git-workspace';dialog.className='tools-window git-window';dialog.setAttribute('aria-label','Git 工作区');
  document.body.append(dialog);
  let project=null,snapshot=null,tab='changes',selected=new Set(),file=null,viewVersion=0,busy=false,returnFocus=null;
  let commitDraft='',branchQuery='';
  const api=(suffix,method='GET',body)=>window.DongranRuntime.request('/api/projects/'+project.id+'/git/'+suffix,method,body);
  const icons=()=>window.lucide?.createIcons();
  const uiButton=(action,label,glyph,kind='')=>`<button type="button" class="git-button ${kind}" data-git-action="${action}">${glyph?icon(glyph):''}<span>${label}</span></button>`;
  function notice(message,error=false){const node=dialog.querySelector('.git-notice');if(node){node.textContent=message;node.dataset.error=error;node.hidden=!message;}}
  function close(){viewVersion++;window.DongranUI.closeMenu({immediate:true});dialog.classList.add('is-leaving');setTimeout(()=>{dialog.close();returnFocus?.focus();},document.documentElement.dataset.reduceMotion==='true'?0:140);}
  async function open(){
    const current=window.DongranProjects.current();if(!current){toast('请先打开一个项目目录');return;}
    if(project?.id!==current.id){commitDraft='';selected.clear();file=null;}
    project=current;returnFocus=document.activeElement;
    dialog.classList.remove('is-leaving');if(!dialog.open)dialog.showModal();
    dialog.innerHTML=`<div class="tools-loading">${icon('loader-circle')}正在读取 ${esc(project.name)} 的 Git 状态…</div>`;icons();
    await refresh();
  }
  async function refresh(){
    const version=++viewVersion;
    try{const data=await api('workspace');if(!dialog.open||version!==viewVersion)return;snapshot=data;selected.clear();render();syncBranch();}
    catch(error){if(!dialog.open||version!==viewVersion)return;dialog.innerHTML=`<header class="tools-heading"><strong>Git 工作区</strong><button class="icon-btn" data-git-action="close" aria-label="关闭">${icon('x')}</button></header><div class="tools-empty">${esc(error.message)}<br>${uiButton('refresh','重试','refresh-cw')}</div>`;icons();}
  }
  function syncBranch(){const label=document.getElementById('branch-name');if(label&&window.DongranProjects.current()?.id===project.id)label.textContent=snapshot.branch||(snapshot.detached?snapshot.head:'未初始化 Git');}
  function render(){
    const count=snapshot.files.length;
    dialog.innerHTML=`<header class="tools-heading"><span class="tools-title-icon">${icon('git-branch')}</span><div><h2>Git 工作区</h2><p>${esc(project.name)}<span>${esc(project.path)}</span></p></div><div class="tools-heading-actions">${uiButton('refresh','刷新','refresh-cw')}<button class="icon-btn" data-git-action="close" aria-label="关闭 Git 工作区">${icon('x')}</button></div></header>
      <div class="git-toolbar"><button class="git-current-branch" data-git-action="branch-menu" aria-haspopup="menu">${icon('git-branch')}<strong>${esc(snapshot.branch||(snapshot.detached?'Detached · '+snapshot.head:'尚无分支'))}</strong>${icon('chevron-down')}</button><span class="git-tracking">${snapshot.upstream?esc(snapshot.upstream)+' · ↑ '+snapshot.ahead+' ↓ '+snapshot.behind:'未设置上游分支'}</span><div class="git-toolbar-spacer"></div>${uiButton('fetch','获取','cloud-download')}${uiButton('pull','更新','arrow-down')}${uiButton('push','推送','arrow-up')}</div>
      <div class="git-tabbar" role="tablist">${[['changes','本地更改',count],['branches','分支',snapshot.branches.length],['history','提交记录',snapshot.commits.length]].map(([value,label,n])=>`<button role="tab" aria-selected="${tab===value}" data-git-tab="${value}">${label}<span>${n}</span></button>`).join('')}<span>${snapshot.repository?'本机仓库':'未初始化'}</span></div>
      <p class="git-notice" role="status" hidden></p><div class="git-view"></div><footer class="git-footer"><span>${icon('folder-git-2')}${esc(project.name)}</span><span>${snapshot.detached?'分离 HEAD':snapshot.branch?'当前分支 '+esc(snapshot.branch):'创建首次提交后即可管理分支'} · Esc 关闭</span></footer>`;
    const view=dialog.querySelector('.git-view');
    if(!snapshot.repository){view.innerHTML=`<div class="tools-empty">${icon('folder-git-2')}<h3>为这个项目启用版本管理</h3><p>在当前目录初始化 Git 仓库，文件内容不会被改写。</p>${uiButton('init','初始化 Git 仓库','plus','strong')}</div>`;}
    else if(tab==='changes')renderChanges(view);
    else if(tab==='branches')renderBranches(view);
    else renderHistory(view);
    icons();
  }
  const key=(path,staged)=>JSON.stringify([path,staged]);
  function groupMarkup(staged){
    const files=snapshot.files.filter(f=>staged?f.staged:(f.unstaged||f.untracked));
    return `<section class="git-file-group"><h3>${staged?'已暂存':'工作区'}<span>${files.length}</span></h3>${files.map(f=>{
      const status=f.conflict?'!':f.untracked?'?':staged?f.index:f.worktree;
      return `<div class="git-file-row ${file?.path===f.path&&file?.staged===staged?'selected':''}"><input type="checkbox" aria-label="选择 ${esc(f.path)} ${staged?'已暂存':'工作区'}" data-git-check="${esc(key(f.path,staged))}" ${selected.has(key(f.path,staged))?'checked':''}><button data-git-file="${esc(f.path)}" data-staged="${staged}" title="${esc(f.path)}">${icon('file-code-2')}<span>${esc(f.path)}</span><b class="git-status-letter status-${status==='D'?'deleted':status==='?'||status==='A'?'added':'modified'}">${esc(status)}</b></button></div>`;
    }).join('')||'<p class="git-group-empty">没有文件</p>'}</section>`;
  }
  function renderChanges(view){
    const stagedCount=snapshot.files.filter(f=>f.staged).length;
    view.innerHTML=`<div class="git-change-layout"><aside class="git-change-list"><div class="git-list-tools">${uiButton('stage','暂存所选','plus')}${uiButton('unstage','取消暂存','minus')}</div><div class="git-file-groups">${groupMarkup(false)}${groupMarkup(true)}</div><div class="git-commit-box"><label for="git-commit-message">提交说明</label><textarea id="git-commit-message" placeholder="概括这次更改的目的…" maxlength="4000">${esc(commitDraft)}</textarea><div><span>${stagedCount} 个已暂存文件</span>${uiButton('commit','提交','git-commit-horizontal','strong')}</div></div></aside><section class="git-diff-view"><header class="git-diff-heading"><span id="git-diff-title">选择文件查看差异</span><button class="git-button" data-git-action="discard" disabled>${icon('undo-2')}还原工作区</button></header><div class="git-diff-body"><div class="tools-empty">${icon('file-diff')}<h3>审查每一处改动</h3><p>选择左侧文件，查看改动行与上下文。</p></div></div></section></div>`;
    dialog.querySelector('#git-commit-message').addEventListener('input',e=>{commitDraft=e.target.value;});
    const prior=file&&snapshot.files.find(f=>f.path===file.path);
    const first=prior?{path:prior.path,staged:file.staged?prior.staged:!prior.unstaged&&!prior.untracked}:snapshot.files.length?{path:snapshot.files[0].path,staged:!snapshot.files[0].unstaged&&!snapshot.files[0].untracked}:null;
    if(first)loadDiff(first.path,first.staged);
  }
  async function loadDiff(path,staged){
    const version=++viewVersion;file={path,staged};const title=dialog.querySelector('#git-diff-title');if(!title)return;
    title.textContent=path+' · '+(staged?'已暂存':'工作区');dialog.querySelectorAll('.git-file-row').forEach(row=>{const b=row.querySelector('[data-git-file]');row.classList.toggle('selected',b.dataset.gitFile===path&&(b.dataset.staged==='true')===staged);});
    const host=dialog.querySelector('.git-diff-body'),discard=dialog.querySelector('[data-git-action="discard"]');discard.disabled=true;host.innerHTML='<div class="tools-loading">正在读取差异…</div>';
    try{
      const result=await api('file-diff?'+new URLSearchParams({path,staged}));if(!host.isConnected||version!==viewVersion)return;
      file={path,staged,...result};
      const row=snapshot.files.find(f=>f.path===path);discard.disabled=staged||row?.untracked||row?.conflict||!result.fingerprint;
      if(result.binary||!result.diff){host.innerHTML=`<div class="tools-empty">${icon('file')}<p>${esc(result.message||'二进制内容无法显示行差异。')}</p></div>`;icons();return;}
      let left=0,right=0;let added=0,removed=0;
      const rows=result.diff.split('\n').map(line=>{
        const hunk=/^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line);
        let kind='context',before='',after='';
        if(hunk){left=Number(hunk[1]);right=Number(hunk[2]);kind='hunk';}
        else if(/^(diff |index |--- |\+\+\+ |new file|deleted file|rename |similarity |\\ No newline)/.test(line))kind='meta';
        else if(line.startsWith('+')){kind='added';after=right++;added++;}
        else if(line.startsWith('-')){kind='removed';before=left++;removed++;}
        else if(line.startsWith(' ')){before=left++;after=right++;}
        return `<div class="git-diff-line ${kind}"><span class="line-no">${before}</span><span class="line-no">${after}</span><code>${esc(line)}</code></div>`;
      }).join('');
      host.innerHTML=`<div class="git-diff-stats"><span>统一差异</span><b>+${added}</b><em>−${removed}</em></div><div class="git-diff-code">${rows}</div>`;
    }catch(error){if(host.isConnected&&version===viewVersion)host.innerHTML=`<div class="tools-empty">${esc(error.message)}</div>`;}
  }
  function renderBranches(view){
    view.innerHTML=`<div class="git-branches"><div class="git-section-toolbar"><input id="git-branch-search" placeholder="搜索分支…" aria-label="搜索分支" value="${esc(branchQuery)}">${uiButton('create-branch','新建分支','plus','strong')}</div><div class="git-branch-list"></div><p class="git-help">切换前会检查未提交修改，更新操作使用 fast-forward only。</p></div>`;
    const renderList=()=>{const rows=snapshot.branches.filter(b=>b.name.toLowerCase().includes(branchQuery.toLowerCase()));view.querySelector('.git-branch-list').innerHTML=['本地分支','远程分支'].map((title,index)=>`<section><h3>${title}</h3>${rows.filter(b=>b.remote===Boolean(index)).map(b=>`<div class="git-branch-row">${icon(b.remote?'cloud':'git-branch')}<div><strong>${esc(b.name)}</strong><small>${esc(b.upstream||b.hash)}</small></div>${b.current?'<span class="tools-badge active">当前</span>':b.remote?'<span class="tools-badge">远程</span>':`<button class="git-button" data-git-checkout="${esc(b.name)}">切换</button>`}</div>`).join('')||'<p class="git-group-empty">没有匹配的分支</p>'}</section>`).join('');icons();};
    view.querySelector('input').oninput=e=>{branchQuery=e.target.value;renderList();};renderList();
  }
  function renderHistory(view){
    if(!snapshot.commits.length){view.innerHTML='<div class="tools-empty"><h3>还没有提交记录</h3><p>暂存文件并创建首次提交。</p></div>';return;}
    // Lanes follow the actual parent hashes returned by git log --topo-order.
    let lanes=[];
    const rows=snapshot.commits.map(commit=>{
      let lane=lanes.indexOf(commit.hash);if(lane<0){lane=lanes.length;lanes.push(commit.hash);}
      const before=[...lanes];lanes.splice(lane,1);
      for(let n=0;n<commit.parents.length;n++)if(!lanes.includes(commit.parents[n]))lanes.splice(Math.min(lane+n,lanes.length),0,commit.parents[n]);
      const x=n=>14+n*13;
      let paths=before.filter(hash=>hash!==commit.hash&&lanes.includes(hash)).map(hash=>`<path d="M${x(before.indexOf(hash))},0 L${x(lanes.indexOf(hash))},44"/>`).join('');
      paths+=`<path d="M${x(lane)},0 V22"/>`+commit.parents.map(parent=>`<path d="M${x(lane)},22 L${x(lanes.indexOf(parent))},44"/>`).join('');
      return `<button class="git-log-row" data-commit-hash="${commit.hash}"><svg width="${Math.max(66,(Math.max(before.length,lanes.length)+1)*13)}" height="44" aria-label="提交父子关系">${paths}<circle cx="${x(lane)}" cy="22" r="3.5"/></svg><span class="git-log-subject"><strong>${esc(commit.subject)}</strong>${commit.refs?`<small>${esc(commit.refs)}</small>`:''}</span><span>${esc(commit.author)}</span><time>${esc(new Date(commit.date).toLocaleDateString())}</time><code>${esc(commit.shortHash)}</code></button>`;
    }).join('');
    view.innerHTML=`<div class="git-log-header"><span>最近 60 条提交 · 所有分支</span><span>作者 / 日期 / SHA</span></div><div class="git-log-list">${rows}</div><div class="git-log-detail" id="git-log-detail">选择提交查看完整信息</div>`;
  }
  function confirm(title,description,action,fields=''){
    window.DongranUI.closeMenu({immediate:true});
    dialog.querySelector('.git-confirm-layer')?.remove();
    const layer=document.createElement('div');layer.className='git-confirm-layer';layer.innerHTML=`<section class="git-confirm-card" role="alertdialog" aria-modal="true" aria-label="${esc(title)}"><h3>${esc(title)}</h3><p>${esc(description)}</p>${fields}<p class="git-confirm-error" role="alert"></p><div>${uiButton('confirm-cancel','取消',null)}<button class="git-button strong" id="git-confirm-submit">确认</button></div></section>`;dialog.append(layer);
    layer.addEventListener('keydown',e=>{if(e.key!=='Tab')return;const focusable=[...layer.querySelectorAll('button:not(:disabled),input')];const first=focusable[0],last=focusable.at(-1);if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}});
    layer.querySelector('[data-git-action="confirm-cancel"]').onclick=()=>layer.remove();
    layer.querySelector('#git-confirm-submit').onclick=async e=>{e.target.disabled=true;try{await action(layer);layer.remove();}catch(error){layer.querySelector('.git-confirm-error').textContent=error.message;e.target.disabled=false;}};
    (layer.querySelector('input')||layer.querySelector('#git-confirm-submit')).focus();
  }
  async function operate(operation,extra={}){
    if(busy)throw new Error('上一项 Git 操作仍在执行。');busy=true;dialog.classList.add('is-busy');
    const requestedProject=project.id;
    try{const result=await api('operations','POST',{operation,...extra,confirmed:true});if(project.id!==requestedProject)return;snapshot=result.workspace;selected.clear();if(operation==='commit')commitDraft='';render();syncBranch();notice(result.output.trim()||'操作已完成');}
    finally{busy=false;dialog.classList.remove('is-busy');}
  }
  async function action(name,trigger){
    if(name==='close'){close();return;}
    if(name==='refresh'){await refresh();return;}
    if(name==='branch-menu'){
      window.DongranUI.showMenu(trigger,{label:'Git 分支',showHeading:true,items:[{value:'branches',label:'查看所有分支',icon:'git-branch'},{value:'new',label:'新建分支',icon:'plus'},{type:'separator'},...snapshot.branches.filter(b=>!b.remote).slice(0,12).map(b=>({value:b.name,label:b.name,checked:b.current,icon:'git-branch'}))],onSelect:value=>{if(value==='branches'){tab='branches';render();}else if(value==='new')action('create-branch');else if(value!==snapshot.branch)checkout(value);}});return;
    }
    if(name==='stage'||name==='unstage'){
      const paths=[...selected].map(JSON.parse).filter(([,staged])=>name==='stage'?!staged:staged).map(([path])=>path);
      if(!paths.length){notice(name==='stage'?'请勾选工作区文件。':'请勾选已暂存文件。',true);return;}
      await operate(name,{paths});return;
    }
    if(name==='commit'){confirm('提交已暂存的更改',`向 ${snapshot.branch||'当前分支'} 创建提交，仅包含暂存区内容。`,()=>operate('commit',{message:commitDraft}));return;}
    if(name==='discard'&&file){const target={...file};confirm('还原工作区修改',`将 ${target.path} 的未暂存修改还原到暂存区版本。这些修改将丢失。`,()=>operate('discard',{paths:[target.path],fingerprint:target.fingerprint}));return;}
    if(name==='create-branch'){confirm('新建并切换分支','从指定引用创建新分支。当前工作区必须干净。',layer=>operate('create-branch',{branch:layer.querySelector('#git-new-branch').value,base:layer.querySelector('#git-base-branch').value}),'<label>分支名称<input id="git-new-branch" placeholder="feature/my-task"></label><label>基准引用<input id="git-base-branch" value="HEAD"></label>');return;}
    if(name==='init'){confirm('初始化 Git 仓库',project.path,async()=>{await window.DongranRuntime.request('/api/projects/'+project.id+'/git/init','POST',{confirmed:true});await refresh();});return;}
    if(['fetch','pull','push'].includes(name)){confirm({fetch:'获取远端更新',pull:'更新当前分支',push:'推送当前分支'}[name],name==='push'?`将 ${snapshot.branch} 推送到已配置的上游；不会强制推送。`:name==='pull'?'从上游执行快进更新；需要合并时会停止。':'连接已配置的远端并刷新远程分支信息。',()=>operate(name));}
  }
  function checkout(name){confirm('切换分支',`从 ${snapshot.branch} 切换到 ${name}。未提交修改不会被覆盖。`,()=>operate('checkout',{branch:name}));}
  dialog.addEventListener('change',e=>{if(e.target.dataset.gitCheck){const value=e.target.dataset.gitCheck;e.target.checked?selected.add(value):selected.delete(value);}});
  dialog.addEventListener('click',e=>{
    const b=e.target.closest('button');if(!b||b.disabled)return;
    if(b.dataset.gitAction&&!b.dataset.gitAction.startsWith('confirm'))action(b.dataset.gitAction,b).catch(error=>notice(error.message,true));
    else if(b.dataset.gitTab){viewVersion++;tab=b.dataset.gitTab;render();}
    else if(b.dataset.gitFile)loadDiff(b.dataset.gitFile,b.dataset.staged==='true');
    else if(b.dataset.gitCheckout)checkout(b.dataset.gitCheckout);
    else if(b.dataset.commitHash){const c=snapshot.commits.find(c=>c.hash===b.dataset.commitHash);dialog.querySelector('#git-log-detail').textContent=c.subject+'\n'+c.hash+'\n'+c.author+' · '+new Date(c.date).toLocaleString()+'\n父提交：'+(c.parents.join(', ')||'根提交');}
  });
  dialog.addEventListener('cancel',e=>{e.preventDefault();const layer=dialog.querySelector('.git-confirm-layer');if(layer)layer.remove();else close();});
  window.addEventListener('projectchange',()=>{if(dialog.open)close();});
  window.DongranGit={open,refresh};
})();
