(() => {
  const esc=escapeHtml,dialog=document.createElement('dialog');
  dialog.id='knowledge-retrieval-settings';dialog.className='tools-window knowledge-retrieval-window';dialog.setAttribute('aria-label','知识库检索设置');document.body.append(dialog);
  let busy=false,returnFocus,closeTimer;
  const api=(method='GET',body)=>DongranRuntime.request('/api/knowledge/retrieval/settings',method,body);
  function close(){if(busy)return;DongranUI.closeMenu({immediate:true});dialog.classList.add('is-leaving');clearTimeout(closeTimer);closeTimer=setTimeout(()=>{dialog.close();returnFocus?.focus({preventScroll:true});},140);}
  async function open(){
    try{
      const [config,providers]=await Promise.all([api(),DongranRuntime.request('/api/model-providers')]);
      returnFocus=document.activeElement;clearTimeout(closeTimer);dialog.classList.remove('is-leaving');
      const endpoint=(key,label)=>{const e=config[key];return `<fieldset><legend>${label}</legend><label class="check"><input type="checkbox" id="kb-${key}-enabled" ${e.enabled?'checked':''}>启用 ${key}</label><small>${key==='embedding'?'将资料片段转换为向量，与聊天模型独立配置。':'可选的语义重排模型。BM25 不需要此模型，也不需要 API Key。'}</small><label>凭据来源<button type="button" class="secondary" data-kb-provider="${key}" aria-haspopup="menu">${esc(providers.find(p=>p.id===e.providerId)?.name||'独立凭据')}</button></label><label>API 根地址<input id="kb-${key}-url" type="url" maxlength="2048" value="${esc(e.baseUrl)}" placeholder="https://your-provider.example/v1"></label><label>模型 ID<input id="kb-${key}-model" maxlength="200" value="${esc(e.model)}" placeholder="填写 ${key} 模型标识"></label><label>API Key<input id="kb-${key}-key" type="password" autocomplete="new-password" maxlength="16000" placeholder="${e.credentialConfigured?'已保存 · 留空保留':'密钥不会回显'}"></label><div class="knowledge-retrieval-grid"><label class="check"><input type="checkbox" id="kb-${key}-remember" checked>系统凭据存储</label><label class="check"><input type="checkbox" id="kb-${key}-clear">清除独立密钥</label></div><label class="check"><input type="checkbox" id="kb-${key}-consent" ${e.enabled?'checked':''}>允许发送至下列模型接口</label><small id="kb-${key}-destination"></small><div class="knowledge-test-row"><button type="button" class="secondary" data-kb-test="${key}">检验连通性</button><span id="kb-${key}-test-result" role="status"></span></div><small>测试当前填写的配置，仅向上方接口发送固定英文测试文本；不会保存、启用配置或发送知识库文档。</small></fieldset>`;};
      dialog.innerHTML=`<header class="tools-heading"><span class="tools-title-icon"><i data-lucide="scan-search"></i></span><div><h2>知识库检索</h2><p>检索模型与 Agent 对话模型分开配置</p></div><button class="icon-btn" data-kb-close aria-label="关闭检索设置"><i data-lucide="x"></i></button></header><form id="knowledge-retrieval-form"><div class="knowledge-preview-note">向量保存在本机 SQLite。优先向量检索，不可用时自动回退 BM25。保存配置不会上传文档；请在文档预览中选择建立索引。</div><label>返回片段数<input type="number" id="kb-top-k" min="1" max="20" value="${config.topK}" required></label>${endpoint('embedding','Embedding 向量模型')}${endpoint('rerank','Rerank 语义重排（可选）')}<small>启用后的数据范围：Embedding 会接收所选文档片段和查询；Rerank 会接收查询和候选片段。模型 ID 必须是该接口实际支持的模型。</small><p id="knowledge-retrieval-error" class="runtime-error" role="status"></p><div class="knowledge-retrieval-footer"><button type="button" class="secondary" data-kb-close>取消</button><button type="submit" class="primary">保存配置</button></div></form>`;
      dialog.querySelectorAll('[data-kb-close]').forEach(b=>b.onclick=close);
      const selected={embedding:config.embedding.providerId,rerank:config.rerank.providerId};
      const read=key=>({enabled:dialog.querySelector('#kb-'+key+'-enabled').checked,consentTarget:dialog.querySelector('#kb-'+key+'-consent').checked?dialog.querySelector('#kb-'+key+'-url').value.trim().replace(/\/+$/,'')+'/'+(key==='embedding'?'embeddings':'rerank')+'\n'+dialog.querySelector('#kb-'+key+'-model').value.trim():'',baseUrl:dialog.querySelector('#kb-'+key+'-url').value,model:dialog.querySelector('#kb-'+key+'-model').value,providerId:selected[key],apiKey:selected[key]?'':dialog.querySelector('#kb-'+key+'-key').value,rememberKey:dialog.querySelector('#kb-'+key+'-remember').checked,clearKey:!selected[key]&&dialog.querySelector('#kb-'+key+'-clear').checked});
      const setProvider=key=>{
        const provider=providers.find(p=>p.id===selected[key]);const keyInput=dialog.querySelector('#kb-'+key+'-key');
        dialog.querySelector('[data-kb-provider="'+key+'"]').textContent=provider?.name||'独立凭据';
        keyInput.disabled=!!provider;dialog.querySelector('#kb-'+key+'-clear').disabled=!!provider;dialog.querySelector('#kb-'+key+'-remember').disabled=!!provider;
      };
      for(const key of ['embedding','rerank']){
        const updateDestination=(reset=false)=>{const url=dialog.querySelector('#kb-'+key+'-url').value.trim().replace(/\/+$/,'');const model=dialog.querySelector('#kb-'+key+'-model').value.trim();dialog.querySelector('#kb-'+key+'-destination').textContent=url+'/'+(key==='embedding'?'embeddings':'rerank')+' · '+model+' · '+(key==='embedding'?'所选文档片段、搜索词':'搜索词、候选文档片段');if(reset)dialog.querySelector('#kb-'+key+'-consent').checked=false;};
        const result=dialog.querySelector('#kb-'+key+'-test-result');
        for(const input of dialog.querySelector('#kb-'+key+'-url').closest('fieldset').querySelectorAll('input'))input.addEventListener('input',()=>{result.textContent='';});
        dialog.querySelector('[data-kb-test="'+key+'"]').onclick=async()=>{
          if(busy)return;
          const draft=read(key);draft.consentTarget=draft.baseUrl.trim().replace(/\/+$/,'')+'/'+(key==='embedding'?'embeddings':'rerank')+'\n'+draft.model.trim();
          busy=true;const controls=[...dialog.querySelectorAll('input,button')].map(node=>[node,node.disabled]);controls.forEach(([node])=>node.disabled=true);
          result.className='';result.textContent='正在验证…';
          try{const tested=await DongranRuntime.request('/api/knowledge/retrieval/test/'+key,'POST',draft);result.textContent='连接成功 · '+tested.elapsedMs+' ms · '+(key==='embedding'?tested.dimensions+' 维向量':tested.results+' 条重排结果');}
          catch(error){result.className='runtime-error';result.textContent='连接失败：'+error.message;}
          finally{busy=false;controls.forEach(([node,disabled])=>node.disabled=disabled);}
        };
        for(const field of ['url','model'])dialog.querySelector('#kb-'+key+'-'+field).addEventListener('input',()=>updateDestination(true));updateDestination();
        setProvider(key);dialog.querySelector('[data-kb-provider="'+key+'"]').onclick=e=>DongranUI.showMenu(e.currentTarget,{label:'凭据来源',items:[{value:'',label:'独立凭据',checked:!selected[key]},...providers.map(p=>({value:p.id,label:p.name,checked:p.id===selected[key]}))],onSelect:value=>{selected[key]=value;const provider=providers.find(p=>p.id===value);if(provider)dialog.querySelector('#kb-'+key+'-url').value=provider.baseUrl;setProvider(key);updateDestination(true);result.textContent='';}});
      }
      dialog.querySelector('form').onsubmit=async e=>{
        e.preventDefault();if(busy)return;busy=true;const button=dialog.querySelector('button[type=submit]');button.disabled=true;

        try{const saved=await api('PUT',{revision:config.revision,mode:dialog.querySelector('#kb-embedding-enabled').checked?'vector_bm25':'bm25',candidates:30,topK:Number(dialog.querySelector('#kb-top-k').value),embedding:read('embedding'),rerank:read('rerank')});busy=false;close();toast(Object.values(saved.credentialStorage||{}).includes('session')?'配置已保存；新密钥仅本次运行有效。':'检索配置已保存。');}
        catch(err){dialog.querySelector('#knowledge-retrieval-error').textContent=err.message;}
        finally{busy=false;button.disabled=false;}
      };
      if(!dialog.open)dialog.showModal();window.lucide?.createIcons();
    }catch(e){toast(e.message);}
  }
  dialog.addEventListener('cancel',e=>{e.preventDefault();close();});
  window.DongranKnowledgeSettings={open};
})();
