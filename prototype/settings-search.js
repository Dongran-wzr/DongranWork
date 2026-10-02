(() => {
  let generation=0;
  const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const request=(path='',method='GET',body)=>window.DongranRuntime.request('/api/web-search'+path,method,body);
  const names={bing:'Bing RSS · 免费，结果可能不稳定',tavily:'Tavily Search API',brave:'Brave Search API'};
  async function mount(token){
    const host=document.querySelector('#web-search-settings');if(!host||token!==generation)return;
    try{
      const settings=await request();if(!host.isConnected||token!==generation)return;
      let provider=settings.provider;
      host.innerHTML=`<form id="search-provider-form" class="provider-form"><label>搜索服务<button type="button" id="search-provider-picker" class="settings-command" aria-haspopup="menu">${esc(names[provider])}</button></label><p>Agent 自动使用这里的搜索服务。搜索词会发送到所选服务，知识库文档不会随网页搜索上传。</p><label id="search-key-label">API Key<input id="search-api-key" type="password" autocomplete="off" placeholder="留空保留已有密钥"></label><label><input id="search-remember-key" type="checkbox"> 使用系统凭据库记住新密钥</label><label><input id="search-clear-key" type="checkbox"> 清除所选服务的密钥</label><div class="runtime-actions"><button type="submit" class="primary">保存配置</button><button type="button" class="secondary" id="search-test">检验连通性</button></div><p id="search-notice" role="status"></p><div id="search-test-results"></div></form>`;
      const $=s=>host.querySelector(s),notice=s=>$('#search-notice').textContent=s;
      function sync(){ $('#search-key-label').hidden=provider==='bing';$('#search-api-key').value='';$('#search-clear-key').checked=false;notice(provider==='bing'?'无需密钥；无相关结果时会明确提示，不使用无关链接凑数。':settings[provider+'Configured']?'密钥已配置。':'请填写该搜索服务的 API Key。'); }
      sync();
      $('#search-provider-picker').onclick=e=>window.DongranUI.showMenu(e.currentTarget,{label:'搜索服务',items:Object.entries(names).map(([value,label])=>({value,label,checked:value===provider})),onSelect:value=>{provider=value;$('#search-provider-picker').textContent=names[value];sync();}});
      async function save(){const value=await request('','PUT',{provider,apiKey:$('#search-api-key').value,rememberKey:$('#search-remember-key').checked,clearKey:$('#search-clear-key').checked});Object.assign(settings,value);$('#search-api-key').value='';$('#search-clear-key').checked=false;return value;}
      let busy=false;
      async function run(test){if(busy)return;busy=true;host.querySelectorAll('button').forEach(b=>b.disabled=true);try{const saved=await save();notice(saved.credentialStorage==='session'?'已保存；密钥仅本次运行有效。':'已保存配置。');if(test){notice('正在以固定公开查询检验搜索结果…');const result=await request('/test','POST');notice(result.results?.length?'连接成功，返回 '+result.results.length+' 个结果。':result.notice||'连接成功，但没有相关结果。');$('#search-test-results').innerHTML=(result.results||[]).map(row=>`<p><a href="${esc(row.url)}" target="_blank" rel="noopener noreferrer">${esc(row.title)}</a></p>`).join('');}}catch(error){notice(error.message);}finally{busy=false;host.querySelectorAll('button').forEach(b=>b.disabled=false);}}
      $('#search-provider-form').onsubmit=e=>{e.preventDefault();run(false);};$('#search-test').onclick=()=>run(true);
    }catch(error){host.textContent=error.message;}
  }
  (window.DongranSettingsModules||=[]).push({categories:[{id:'websearch',label:'网页搜索',icon:'globe',group:'集成',subtitle:'为 Agent 配置网页搜索服务。',searchKeywords:'web_search Tavily Brave Bing 搜索 API'}],render(category){if(category!=='websearch')return '';const token=++generation;setTimeout(()=>mount(token),0);return '<section id="web-search-settings">正在加载搜索配置…</section>';}});
})();
