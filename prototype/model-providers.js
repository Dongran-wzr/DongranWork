(() => {
  const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const icon=name=>`<i data-lucide="${name}"></i>`;
  const kinds={cloud:'云端接口',local:'本地模型',custom:'自定义'};
  const kindIcons={cloud:'cloud',local:'monitor',custom:'blocks'};
  let profiles=[],host=null,filter='all',query='',version=0;
  const request=(path='',method='GET',body)=>window.DongranRuntime.request('/api/model-providers'+path,method,body);
  const icons=()=>window.lucide?.createIcons();
  function status(message,error=false){const node=host?.querySelector('.provider-notice');if(node){node.textContent=message;node.hidden=!message;node.dataset.error=error;}}
  function sync(){
    const active=profiles.find(p=>p.active),label=document.getElementById('model-label');
    if(label){label.textContent=active?.name||'配置模型';label.parentElement.title=active?active.name+' · '+active.modelName:'管理模型供应商';}
  }
  async function load(){profiles=await request();sync();return profiles;}
  async function render(container){
    host=container;const current=++version;
    container.innerHTML=`<div class="providers-page"><header class="providers-heading"><div><span class="providers-eyebrow">MODEL PROVIDERS</span><h2>你的模型，随时切换。</h2><p>为不同环境保存连接配置，让 Agent 使用合适的模型。</p></div><button class="provider-add" id="provider-add">${icon('plus')}添加供应商</button></header><div class="provider-current"></div><div class="providers-toolbar"><div class="provider-filters" role="tablist">${[['all','全部'],...Object.entries(kinds)].map(([value,label])=>`<button role="tab" data-provider-filter="${value}" aria-selected="${filter===value}">${label}</button>`).join('')}</div><label class="provider-search">${icon('search')}<input id="provider-search" value="${esc(query)}" placeholder="搜索名称、模型或地址" aria-label="搜索供应商"></label></div><p class="provider-notice" role="status" hidden></p><div class="provider-list"><div class="tools-loading">正在读取配置…</div></div><footer class="providers-footer">${icon('shield-check')}密钥独立保存，不会出现在配置列表中。<span>协议：Chat Completions</span></footer></div>`;
    container.querySelector('#provider-add').onclick=()=>editor();
    container.querySelector('#provider-search').oninput=e=>{query=e.target.value;cards();};
    container.querySelectorAll('[data-provider-filter]').forEach(b=>b.onclick=()=>{filter=b.dataset.providerFilter;container.querySelectorAll('[data-provider-filter]').forEach(item=>item.setAttribute('aria-selected',item===b));cards();});
    icons();try{await load();if(current===version&&container.isConnected)cards();}
    catch(error){if(current===version&&container.isConnected){container.querySelector('.provider-list').innerHTML='<div class="tools-empty">配置读取失败</div>';status(error.message,true);}}
  }
  function cards(){
    const target=host?.querySelector('.provider-list');if(!target)return;
    const active=profiles.find(p=>p.active);
    host.querySelector('.provider-current').innerHTML=active?`<span class="provider-live-dot"></span><span>当前使用</span><strong>${esc(active.name)}</strong><code>${esc(active.modelName)}</code><span class="provider-current-note">后续模型请求使用此配置</span>`:`${icon('circle-dashed')}<span>尚未配置模型</span><strong>添加第一套配置即可开始</strong>`;
    const rows=profiles.filter(p=>(filter==='all'||p.kind===filter)&&[p.name,p.modelName,p.baseUrl].join(' ').toLowerCase().includes(query.toLowerCase()));
    target.innerHTML=rows.map(p=>`<article class="provider-card ${p.active?'is-active':''}" data-provider-id="${p.id}"><div class="provider-card-main"><span class="provider-symbol kind-${p.kind}">${icon(kindIcons[p.kind])}</span><div class="provider-card-copy"><div><h3>${esc(p.name)}</h3><span class="provider-kind">${kinds[p.kind]}</span>${p.active?'<span class="tools-badge active">使用中</span>':''}</div><p>${esc(p.baseUrl)}</p><div class="provider-card-tags"><span>${icon('cpu')}${esc(p.modelName)}</span><span>${icon('key-round')}${p.credentialConfigured?'密钥已配置':'未设置密钥'}</span><span>${icon('scan-text')}${Math.round(p.contextWindow/1024)}K</span></div></div><button class="provider-enable ${p.active?'is-active':''}" data-provider-action="activate" ${p.active?'disabled':''}>${icon(p.active?'check':'play')}${p.active?'已启用':'启用'}</button></div><p class="provider-card-notes">${p.capabilities?.state==='tested'?[['chat','对话'],['structured','结构化'],['tools','工具调用']].map(([key,label])=>label+'：'+(p.capabilities[key]==='passed'?'已验证':'未验证')).join(' · '):'模型能力尚未检测'}</p>${p.notes?`<p class="provider-card-notes">${esc(p.notes)}</p>`:''}<div class="provider-card-bottom"><span class="provider-test-state ${p.testedAt?(p.testError?'failed':'passed'):''}">${icon(p.testedAt?(p.testError?'circle-alert':'circle-check'):'circle-dashed')}${p.testedAt?(p.testError?'上次测试失败':p.latencyMs+' ms · 连接成功'):'尚未测试'}${p.testedAt?`<time title="${esc(p.testError||new Date(p.testedAt).toLocaleString())}">${esc(new Date(p.testedAt).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'}))}</time>`:''}</span><div><button data-provider-action="capabilities">${icon('scan-line')}能力检测</button><button data-provider-action="test">${icon('zap')}测试连接</button><button data-provider-action="edit">${icon('pencil')}编辑</button><button class="icon-btn" data-provider-action="more" aria-label="${esc(p.name)}更多操作" aria-haspopup="menu">${icon('ellipsis')}</button></div></div></article>`).join('')||`<div class="provider-empty">${icon(query?'search':'server-cog')}<h3>${query?'没有匹配的配置':'连接你的第一个模型'}</h3><p>${query?'试试其他名称或模型关键词。':'添加兼容接口或本地模型，再测试连接并启用。'}</p></div>`;
    target.querySelectorAll('[data-provider-action]').forEach(b=>b.onclick=()=>action(b).catch(error=>status(error.message,true)));icons();
  }
  async function action(button){
    const id=button.closest('[data-provider-id]').dataset.providerId,p=profiles.find(p=>p.id===id),kind=button.dataset.providerAction;
    if(kind==='edit'){editor(p);return;}
    if(kind==='more'){
      window.DongranUI.showMenu(button,{label:'供应商操作',items:[{value:'duplicate',label:'复制配置（不含密钥）',icon:'copy'},{type:'separator'},{value:'delete',label:p.active?'当前配置不能删除':'删除供应商',icon:'trash-2',disabled:p.active}],onSelect:value=>{if(value==='delete')remove(p);else duplicate(p);}});return;
    }
    button.disabled=true;
    try{
      if(kind==='activate'){await request('/'+id+'/activate','POST');await load();cards();status('已切换到 '+p.name);}
      else if(kind==='capabilities'){
        button.textContent='检测中…';status('使用固定样例检测普通对话、JSON 输出和工具调用；不会执行模型返回的工具。');
        const result=await request('/'+id+'/capabilities','POST');await load();cards();
        status('能力检测：'+[['chat','对话'],['structured','结构化输出'],['tools','工具调用']].map(([key,label])=>label+' '+(result[key]==='passed'?'通过':'未通过检测')).join(' · ')+(result.tools==='passed'?'':'。基础知识库/网页读取可由系统执行；复杂自主操作尚未验证。'));
      }
      else if(kind==='test'){
        button.textContent='测试中…';
        try{const result=await request('/'+id+'/test','POST');status('连接成功 · '+result.elapsedMs+' ms · '+result.reply);}
        catch(error){status(error.message,true);}
        await load();cards();
      }
    }finally{button.disabled=false;}
  }
  async function duplicate(p){
    try{const copy=await request('/'+p.id+'/duplicate','POST');await load();cards();status('已复制配置；密钥不会复制，请在副本中单独填写。');editor(copy);}
    catch(error){status(error.message,true);}
  }
  function remove(p){
    window.DongranUI.showDialog('删除供应商',`<div class="runtime-form"><p>删除“${esc(p.name)}”及其保存的凭据？其他供应商不受影响。</p><p id="provider-delete-error" class="runtime-error"></p><div class="runtime-actions"><button class="secondary" data-action="close-dialog">取消</button><button class="primary" id="provider-delete-confirm">删除配置</button></div></div>`);
    document.getElementById('provider-delete-confirm').onclick=async e=>{e.target.disabled=true;try{await request('/'+p.id,'DELETE');window.DongranUI.closeDialog();await load();cards();status('供应商已删除');}catch(error){document.getElementById('provider-delete-error').textContent=error.message;e.target.disabled=false;}};
  }
  const editDialog=document.createElement('dialog');editDialog.id='provider-editor';editDialog.className='tools-window provider-editor';document.body.append(editDialog);
  let editorReturnFocus=null,editorBusy=false;
  function closeEditor(){if(editorBusy)return;editDialog.classList.add('is-leaving');window.DongranUI.closeMenu({immediate:true});setTimeout(()=>{editDialog.close();editorReturnFocus?.focus();},document.documentElement.dataset.reduceMotion==='true'?0:140);}
  function editor(profile){
    const draft={kind:'custom',name:'',baseUrl:'',modelName:'',notes:'',temperature:0.7,contextWindow:32768,...profile};
    editorReturnFocus=document.activeElement;editDialog.classList.remove('is-leaving');
    editDialog.innerHTML=`<header class="tools-heading"><span class="tools-title-icon">${icon('server-cog')}</span><div><h2>${profile?'编辑供应商':'添加供应商'}</h2><p>为 Dongran Agent 配置一个模型连接</p></div><button class="icon-btn" id="provider-editor-close" aria-label="关闭配置窗口">${icon('x')}</button></header><form id="provider-form"><div class="provider-form-scroll"><label class="provider-field-label">配置类型</label><div class="provider-templates">${Object.entries(kinds).map(([value,label])=>`<button type="button" data-provider-kind="${value}" aria-pressed="${draft.kind===value}">${icon(kindIcons[value])}<span>${label}</span>${icon('check')}</button>`).join('')}</div><label class="provider-field">配置名称 <span>*</span><input id="provider-name" maxlength="80" required value="${esc(draft.name)}" placeholder="例如：团队开发环境"></label><label class="provider-field">API 地址 <span>*</span><input id="provider-url" type="url" maxlength="2048" required value="${esc(draft.baseUrl)}" placeholder="https://your-provider.example/v1"><small>填写 API 根地址；请求会自动追加 /chat/completions。</small></label><label class="provider-field">API Key<div class="provider-secret-field"><input id="provider-key" type="password" autocomplete="new-password" placeholder="${draft.credentialConfigured?'已配置 · 留空保留现有密钥':'输入密钥，本地模型可留空'}"><button type="button" id="provider-show-key" class="icon-btn" aria-label="显示密钥">${icon('eye')}</button></div></label><div class="provider-key-options"><label><input id="provider-remember" type="checkbox" checked>使用系统凭据存储</label>${draft.credentialConfigured?'<label><input id="provider-clear-key" type="checkbox">清除原有密钥</label>':''}</div><label class="provider-field">模型 ID <span>*</span><input id="provider-model" maxlength="200" required value="${esc(draft.modelName)}" placeholder="填写供应商提供的模型标识"></label><details class="provider-advanced"><summary>${icon('sliders-horizontal')}高级配置${icon('chevron-down')}</summary><div class="provider-advanced-grid"><label class="provider-field">Temperature<input id="provider-temperature" type="number" min="0" max="2" step="0.1" value="${draft.temperature}" required></label><label class="provider-field">上下文上限<input id="provider-context" type="number" min="4096" max="131072" step="1024" value="${draft.contextWindow}" required></label></div><label class="provider-field">备注<textarea id="provider-notes" rows="2" maxlength="500" placeholder="记录用途或环境，勿填写密钥">${esc(draft.notes)}</textarea></label></details><div class="provider-protocol-note">${icon('info')}当前支持 OpenAI 兼容的 Chat Completions 协议。Anthropic Messages、Responses 原生协议尚未接入。</div><p id="provider-form-error" class="runtime-error" role="alert"></p></div><footer class="provider-editor-footer"><span>${icon('lock-keyhole')}密钥不会回显</span><button type="button" class="secondary" id="provider-editor-cancel">取消</button><button type="submit" class="primary" id="provider-save">${profile?'保存修改':'添加供应商'}</button></footer></form>`;
    if(!editDialog.open)editDialog.showModal();icons();
    editDialog.querySelector('#provider-editor-close').onclick=closeEditor;editDialog.querySelector('#provider-editor-cancel').onclick=closeEditor;
    editDialog.querySelectorAll('[data-provider-kind]').forEach(b=>b.onclick=()=>{draft.kind=b.dataset.providerKind;editDialog.querySelectorAll('[data-provider-kind]').forEach(item=>item.setAttribute('aria-pressed',item===b));if(!profile&&draft.kind==='local'&&!editDialog.querySelector('#provider-url').value)editDialog.querySelector('#provider-url').value='http://localhost:11434/v1';});
    editDialog.querySelector('#provider-show-key').onclick=e=>{const input=editDialog.querySelector('#provider-key'),show=input.type==='password';input.type=show?'text':'password';e.currentTarget.setAttribute('aria-label',show?'隐藏密钥':'显示密钥');};
    editDialog.querySelector('#provider-form').onsubmit=async e=>{
      e.preventDefault();if(editorBusy)return;editorBusy=true;const save=editDialog.querySelector('#provider-save');save.disabled=true;const value=id=>editDialog.querySelector(id).value;
      try{
        const result=await request(profile?'/'+profile.id:'',profile?'PUT':'POST',{name:value('#provider-name'),kind:draft.kind,baseUrl:value('#provider-url'),modelName:value('#provider-model'),apiKey:value('#provider-key'),clearKey:editDialog.querySelector('#provider-clear-key')?.checked||false,rememberKey:editDialog.querySelector('#provider-remember').checked,temperature:Number(value('#provider-temperature')),contextWindow:Number(value('#provider-context')),notes:value('#provider-notes'),revision:profile?.revision});
        editorBusy=false;closeEditor();await load();cards();status(result.credentialStorage==='session'?'配置已保存；密钥仅在本次运行中有效，系统凭据存储不可用或未选用。':'配置已保存'+(result.active?'，当前已启用。':'。'));
      }catch(error){editDialog.querySelector('#provider-form-error').textContent=error.message;}
      finally{editorBusy=false;save.disabled=false;}
    };
    editDialog.querySelector('#provider-name').focus();
  }
  editDialog.addEventListener('cancel',e=>{e.preventDefault();closeEditor();});
  async function menu(trigger){
    try{await load();window.DongranUI.showMenu(trigger,{label:'模型供应商',showHeading:true,items:[...profiles.map(p=>({value:p.id,label:p.name,description:p.modelName,checked:p.active,icon:kindIcons[p.kind]})),...(profiles.length?[{type:'separator'}]:[]),{value:'manage',label:'管理供应商',icon:'sliders-horizontal'}],showDescriptions:true,onSelect:async id=>{if(id==='manage'){window.DongranSettings.open('models');return;}try{await request('/'+id+'/activate','POST');await load();cards();toast('已切换模型供应商');}catch(error){toast(error.message);}}});}
    catch(error){toast(error.message);}
  }
  window.DongranProviders={render,load,menu,editor,sync};
})();
