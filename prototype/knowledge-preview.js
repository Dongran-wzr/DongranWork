(() => {
  const esc=escapeHtml;
  const dialog=document.createElement('dialog');
  dialog.id='knowledge-preview';dialog.className='tools-window knowledge-window';dialog.setAttribute('aria-label','文档预览');document.body.append(dialog);
  let generation=0,pdf=null,pdfTask=null,renderTask=null,page=1,zoom=1,source=null,returnFocus=null,closeTimer;
  const loaded=new Map();
  const request=(path,method='GET',body)=>window.DongranRuntime.request('/api/knowledge/'+path,method,body);
  const icons=()=>window.lucide?.createIcons();
  const script=path=>{if(!loaded.has(path))loaded.set(path,new Promise((resolve,reject)=>{const s=document.createElement('script');s.src=path;s.onload=resolve;s.onerror=()=>{loaded.delete(path);s.remove();reject(Error('无法加载预览组件，请重新打开。'));};document.head.append(s);}));return loaded.get(path);};
  function cleanup(){renderTask?.cancel();renderTask=null;pdfTask?.destroy();pdfTask=null;pdf=null;}
  function close(){generation++;cleanup();dialog.classList.add('is-leaving');clearTimeout(closeTimer);closeTimer=setTimeout(()=>{dialog.close();dialog.innerHTML='';returnFocus?.focus({preventScroll:true});},140);}
  async function sourceBytes(url){const r=await fetch(window.DongranBackend.state.base+url,{credentials:'include',headers:{'X-Dongran-Client':'desktop'}});if(!r.ok)throw Error('原始文件读取失败，请重新连接本地服务。');return r.arrayBuffer();}
  async function frame(host){
    const iframe=document.createElement('iframe');iframe.className='knowledge-frame';iframe.title='文档内容';iframe.setAttribute('sandbox','allow-same-origin');
    const ready=new Promise(resolve=>iframe.onload=resolve);
    iframe.srcdoc=`<!doctype html><html><head><meta charset="UTF-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data: blob:; font-src data: blob:;"><style>body{margin:0;background:#eeeef0;color:#303038;font:14px/1.8 system-ui,sans-serif}.content{background:white;margin:24px auto;padding:36px;max-width:880px;box-sizing:border-box;overflow-wrap:anywhere}.content pre{white-space:pre-wrap;font:13px/1.8 Consolas,monospace}.content table{border-collapse:collapse;max-width:100%;display:block;overflow:auto}.content td,.content th{border:1px solid #ddd;padding:8px}.content img{max-width:100%}a{pointer-events:none;color:inherit}mark.fragment-hit{background:#fff2c4;color:inherit;padding:3px 0;scroll-margin:80px}mark.keyword-hit{background:#ffce54;color:#27221c;border-radius:2px;outline:1px solid #dca123}.docx-wrapper{padding:24px!important}.docx-wrapper>section{max-width:100%!important;overflow:hidden}.docx-wrapper>section:not([style*="width"]){width:794px;min-height:1056px;padding:64px;box-sizing:border-box}</style></head><body><div id="content"></div></body></html>`;
    host.replaceChildren(iframe);await ready;return iframe.contentDocument;
  }
  function error(message){const node=dialog.querySelector('.knowledge-preview-note');if(node){node.textContent=message;node.hidden=false;}}
  async function pdfPage(){
    if(!pdf)return;renderTask?.cancel();const token=++generation;const doc=pdf;const number=page;
    try{
      const p=await doc.getPage(number);if(token!==generation)return;
      const host=dialog.querySelector('.knowledge-document'),natural=p.getViewport({scale:1});
      const scale=Math.min((host.clientWidth-48)/natural.width,1.5)*zoom;
      const viewport=p.getViewport({scale}),ratio=Math.min(devicePixelRatio||1,2);
      const canvas=document.createElement('canvas');canvas.setAttribute('aria-label','PDF 第 '+number+' 页');
      canvas.width=Math.min(5000,Math.ceil(viewport.width*ratio));canvas.height=Math.min(6500,Math.ceil(viewport.height*ratio));
      canvas.style.width=viewport.width+'px';canvas.style.height=viewport.height+'px';
      const dpr=Math.min(canvas.width/viewport.width,canvas.height/viewport.height);
      host.replaceChildren(canvas);renderTask=p.render({canvasContext:canvas.getContext('2d'),viewport,transform:[dpr,0,0,dpr,0,0]});await renderTask.promise;
      if(token!==generation)return;
      dialog.querySelector('#knowledge-page-index').value=number;
      dialog.querySelector('#knowledge-page-total').textContent='/ '+doc.numPages;
      dialog.querySelector('[data-preview="previous"]').disabled=number<=1;
      dialog.querySelector('[data-preview="next"]').disabled=number>=doc.numPages;
      dialog.querySelector('#knowledge-zoom-value').textContent=Math.round(zoom*100)+'%';
    }catch(e){if(e.name!=='RenderingCancelledException'&&token===generation)error(e.message);}
  }
  async function open(id,{edit,onDeleted,chunkId,query=''}={}){
    cleanup();clearTimeout(closeTimer);const token=++generation;returnFocus=document.activeElement;
    dialog.classList.remove('is-leaving');if(!dialog.open)dialog.showModal();
    dialog.innerHTML='<div class="tools-loading">正在加载文档…</div>';
    try{
      const data=await request(id+'/preview');let fragment=chunkId?await request(id+'/fragments/'+encodeURIComponent(chunkId)):null;if(token!==generation||!dialog.open)return;source=data;page=1;zoom=1;
      dialog.innerHTML=`<header class="tools-heading"><span class="tools-title-icon"><i data-lucide="files"></i></span><div><h2>${esc(data.name)}</h2><p>${data.projectId?'项目资料':'全局资料'} · ${data.sourceName?'原始文件已保存在本机':'本机文本资料'}</p></div><button class="icon-btn" data-preview="close" aria-label="关闭文档预览"><i data-lucide="x"></i></button></header><div class="knowledge-preview-toolbar"><button class="git-button" data-preview="formatted">文档预览</button><button class="git-button" data-preview="text">提取文本</button><span class="knowledge-preview-format"></span><div class="knowledge-pdf-controls" hidden><button class="icon-btn" data-preview="previous" aria-label="上一页"><i data-lucide="chevron-left"></i></button><input id="knowledge-page-index" type="number" min="1" value="1" aria-label="页码"><span id="knowledge-page-total"></span><button class="icon-btn" data-preview="next" aria-label="下一页"><i data-lucide="chevron-right"></i></button><button class="icon-btn" data-preview="minus" aria-label="缩小"><i data-lucide="minus"></i></button><span id="knowledge-zoom-value">100%</span><button class="icon-btn" data-preview="plus" aria-label="放大"><i data-lucide="plus"></i></button></div>${data.sourceUrl?`<a class="git-button" href="${esc(window.DongranBackend.state.base+data.sourceUrl)}" download>下载原文件</a>`:`<button class="git-button" data-preview="edit">编辑资料</button>`}${data.format==='pdf'?'<button class="git-button" data-preview="reextract">重新提取文本</button>':''}<button class="git-button" data-preview="index">建立向量索引</button><span id="knowledge-index-status" role="status"></span><button class="git-button" data-preview="delete">删除</button></div><p class="knowledge-preview-note" role="status" hidden></p><div class="knowledge-document"></div><footer class="git-footer"><span>本机预览 · 不上传至在线预览服务</span><span>Esc 关闭</span></footer>`;
      icons();
      const indexStatus=async()=>{const status=await request(id+'/index');if(!dialog.open||source?.id!==data.id)return;const label=dialog.querySelector('#knowledge-index-status');if(!label)return;label.textContent=`${status.embedded}/${status.chunks} 已索引 · ${status.message||''}`;dialog.querySelector('[data-preview="index"]').disabled=data.extractionStatus==='needs_ocr'||['queued','running'].includes(status.state);if(['queued','running'].includes(status.state))setTimeout(()=>indexStatus().catch(()=>{}),1000);};
      indexStatus().catch(()=>{});
      const text=async()=>{cleanup();const current=++generation;dialog.querySelector('.knowledge-pdf-controls').hidden=true;dialog.querySelector('.knowledge-preview-format').textContent='提取文本';const doc=await frame(dialog.querySelector('.knowledge-document'));if(current!==generation)return;const article=doc.createElement('article');article.className='content';const pre=doc.createElement('pre');pre.textContent=data.content||data.extractionWarning||'没有可提取的文本；扫描件需要先进行 OCR。';
        if(fragment){
          const selected=data.content.slice(fragment.start,fragment.end);const terms=[...new Set(query.trim().split(/\s+/).filter(Boolean))].sort((a,b)=>b.length-a.length);
          let marked='',position=0;const lower=selected.toLocaleLowerCase();
          while(position<selected.length){let next=-1,term='';for(const candidate of terms){const hit=lower.indexOf(candidate.toLocaleLowerCase(),position);if(hit>=0&&(next<0||hit<next)){next=hit;term=candidate;}}if(next<0){marked+=esc(selected.slice(position));break;}marked+=esc(selected.slice(position,next))+'<mark class="keyword-hit">'+esc(selected.slice(next,next+term.length))+'</mark>';position=next+term.length;}
          pre.innerHTML=esc(data.content.slice(0,fragment.start))+'<mark id="knowledge-hit" class="fragment-hit">'+(marked||esc(selected))+'</mark>'+esc(data.content.slice(fragment.end));
          dialog.querySelector('.knowledge-preview-format').textContent='片段 '+(fragment.ordinal+1)+' · 提取文本定位';
        }article.append(pre);doc.getElementById('content').append(article);if(fragment)requestAnimationFrame(()=>doc.getElementById('knowledge-hit')?.scrollIntoView({block:'center'}));};
      const formatted=async()=>{
        cleanup();const current=++generation;
        const host=dialog.querySelector('.knowledge-document');host.innerHTML='<div class="tools-loading">正在渲染文档…</div>';
        dialog.querySelector('.knowledge-preview-note').hidden=true;dialog.querySelector('.knowledge-pdf-controls').hidden=data.format!=='pdf';
        dialog.querySelector('.knowledge-preview-format').textContent={pdf:'PDF.js · 分页预览',docx:'DOCX · 排版预览',markdown:'Markdown',text:'内容预览'}[data.format];
        try{
          if(data.format==='pdf'){
            const [lib,bytes]=await Promise.all([import('./node_modules/pdfjs-dist/build/pdf.mjs'),sourceBytes(data.sourceUrl)]);
            if(current!==generation)return;lib.GlobalWorkerOptions.workerSrc='./node_modules/pdfjs-dist/build/pdf.worker.mjs';
            const task=lib.getDocument({data:bytes,isEvalSupported:false,cMapUrl:'./node_modules/pdfjs-dist/cmaps/',cMapPacked:true,standardFontDataUrl:'./node_modules/pdfjs-dist/standard_fonts/',wasmUrl:'./node_modules/pdfjs-dist/wasm/'});
            pdfTask=task;const loadedPdf=await task.promise;if(current!==generation){task.destroy();return;}pdf=loadedPdf;await pdfPage();
          }else if(data.format==='docx'){
            const [bytes]=await Promise.all([sourceBytes(data.sourceUrl),script('node_modules/jszip/dist/jszip.min.js')]);
            await script('node_modules/docx-preview/dist/docx-preview.min.js');if(current!==generation)return;
            const doc=await frame(host);if(current!==generation)return;
            await window.docx.renderAsync(bytes,doc.getElementById('content'),null,{ignoreLastRenderedPageBreak:false,renderAltChunks:false,useBase64URL:true,ignoreFonts:true,renderComments:false});
            if(current!==generation)return;
            doc.querySelectorAll('a').forEach(a=>a.removeAttribute('href'));
            error('DOCX 为浏览器排版预览，复杂分页和字体可能与 Word 不同。');
          }else if(data.format==='markdown'){
            await Promise.all([script('node_modules/marked/lib/marked.umd.js'),script('node_modules/dompurify/dist/purify.min.js')]);if(current!==generation)return;
            const doc=await frame(host);if(current!==generation)return;
            const article=doc.createElement('article');article.className='content';article.innerHTML=DOMPurify.sanitize(marked.parse(data.content||''),{FORBID_TAGS:['img','iframe','style','script','form'],FORBID_ATTR:['style','href','src','srcset']});doc.getElementById('content').append(article);
          }else{await text();if(data.sourceName)error('此格式提供提取内容预览；需要原始版式时可下载原文件。');}
        }catch(e){if(current!==generation)return;await text();error('排版预览不可用，已切换到提取文本：'+e.message);}
      };
      dialog.onclick=async e=>{const action=e.target.closest('[data-preview]')?.dataset.preview;if(!action)return;
        if(action==='close'){close();return;}if(action==='text'){await text();return;}if(action==='formatted'){await formatted();return;}
        if(action==='index'){const button=e.target.closest('button');button.disabled=true;try{await request(id+'/index','POST');await indexStatus();}catch(err){error(err.message);button.disabled=false;}return;}
        if(action==='reextract'){const button=e.target.closest('button');button.disabled=true;try{const updated=await request(id+'/reextract','POST');Object.assign(data,updated);fragment=null;await text();error(updated.extractionWarning||'文本已重新提取；旧向量已失效，请重新建立索引。');await indexStatus();}catch(err){error(err.message);}finally{button.disabled=false;}return;}
        if(action==='edit'){close();setTimeout(()=>edit?.(),150);return;}
        if(action==='delete'){
          const button=e.target.closest('button');
          if(button.dataset.confirm!=='true'){button.dataset.confirm='true';button.textContent='确认删除资料';error('删除将同时移除原文件与检索索引，无法撤销。');return;}
          button.disabled=true;try{await request(id,'DELETE');close();await onDeleted?.();}catch(err){error(err.message);button.disabled=false;}return;
        }
        if(pdf){if(action==='previous')page=Math.max(1,page-1);if(action==='next')page=Math.min(pdf.numPages,page+1);if(action==='minus')zoom=Math.max(.5,zoom-.25);if(action==='plus')zoom=Math.min(2,zoom+.25);await pdfPage();}
      };
      dialog.querySelector('#knowledge-page-index').onchange=e=>{if(pdf){page=Math.max(1,Math.min(pdf.numPages,Number(e.target.value)||1));pdfPage();}};
      if(fragment)await text();else await formatted();
      if(data.extractionWarning)error(data.extractionWarning);
    }catch(e){if(token===generation){dialog.innerHTML=`<header class="tools-heading"><h2>预览失败</h2><button class="icon-btn" id="knowledge-preview-failed-close" aria-label="关闭">×</button></header><div class="tools-empty">${esc(e.message)}</div>`;dialog.querySelector('button').onclick=close;}}
  }
  dialog.addEventListener('cancel',e=>{e.preventDefault();close();});
  window.DongranKnowledgePreview={open};
})();
