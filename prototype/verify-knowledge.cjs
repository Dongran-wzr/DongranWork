const {chromium}=require('playwright');
const {spawn}=require('node:child_process');
const fs=require('node:fs');
const path=require('node:path');
const os=require('node:os');
const assert=require('node:assert/strict');
const http=require('node:http');

(async()=>{
  const root=path.resolve(__dirname,'..'),temporary=fs.mkdtempSync(path.join(os.tmpdir(),'dongran-knowledge-ui-'));
  const data=path.join(temporary,'data'),project=path.join(temporary,'project');fs.mkdirSync(project);
  const fixtures=path.join(root,'.runtime','knowledge-fixtures');
  if(!fs.existsSync(path.join(fixtures,'requirements.docx')))throw Error('Run Maven tests first to generate document fixtures.');
  const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):(process.platform==='win32'?'D:\\RunEnvironment\\Java\\jdk21\\bin\\java.exe':'java');
  const modelRequests=[];
  const model=http.createServer(async(req,res)=>{let raw='';for await(const chunk of req)raw+=chunk;const input=JSON.parse(raw);modelRequests.push({url:req.url,input});
    const output=req.url.endsWith('/embeddings')?{data:input.input.map((_,index)=>({index,embedding:[1,0]}))}:{results:input.documents.slice(0,input.top_n).map((_,index)=>({index,relevance_score:1-index/100}))};
    res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(output));});
  await new Promise(r=>model.listen(0,'127.0.0.1',r));
  let backend,browser,page,base,logs='';
  const errors=[],external=[];
  async function start(){
    backend=spawn(java,['-jar',path.join(root,'backend/target/dongran-backend-0.1.0-SNAPSHOT.jar'),'--server.port=0','--dongran.data-dir='+data],{cwd:root,windowsHide:true,stdio:['ignore','pipe','pipe']});
    logs='';backend.stdout.on('data',b=>logs+=b);backend.stderr.on('data',b=>logs+=b);
    const deadline=Date.now()+45000;
    while(Date.now()<deadline){if(backend.exitCode!==null)throw Error(logs);try{const runtime=JSON.parse(fs.readFileSync(path.join(data,'runtime.json'),'utf8'));if(runtime.pid===backend.pid){base='http://127.0.0.1:'+runtime.port;return;}}catch{}await new Promise(r=>setTimeout(r,200));}
    throw Error('Startup timeout: '+logs);
  }
  async function stop(){if(backend&&backend.exitCode===null)await new Promise(r=>{backend.once('exit',r);backend.kill();});}
  const call=(endpoint,method='GET',body)=>page.evaluate(({endpoint,method,body})=>DongranRuntime.request(endpoint,method,body),{endpoint,method,body});
  async function visit(){
    page=await browser.newPage({viewport:{width:1440,height:1000}});
    page.on('pageerror',e=>errors.push(e.message));
    page.on('request',r=>{if(/^https?:/.test(r.url())&&!r.url().startsWith(base))external.push(r.url());});
    await page.goto(base);await page.waitForFunction(()=>DongranBackend.isAvailable());
    await page.evaluate(p=>DongranRuntime.open(p),project);
    await page.locator('[data-action="knowledge"]').first().click();
  }
  async function openDocument(name){await page.locator('[data-knowledge-id]').filter({hasText:name}).first().click();await page.waitForSelector('#knowledge-preview[open]');}
  async function close(){await page.locator('#knowledge-preview [data-preview="close"]').click();await page.waitForFunction(()=>!document.querySelector('#knowledge-preview').open);}
  try{
    await start();browser=await chromium.launch({headless:true,...(process.platform==='win32'?{executablePath:'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'}:{})});
    await visit();
    const names=['requirements.docx','guide.pdf','scan.pdf','permissions.xlsx','legacy.xls','review.pptx','legacy.ppt','notes.md','notes.rtf','report.csv'];
    await page.locator('#runtime-knowledge-upload').setInputFiles(names.map(name=>path.join(fixtures,name)));
    await page.waitForFunction(()=>document.querySelector('#knowledge-upload-state')?.textContent.includes('已导入 10 / 10'),null,{timeout:45000});
    await page.waitForFunction(()=>document.querySelectorAll('[data-knowledge-id]').length===10);
    assert.equal(await page.locator('[data-knowledge-id]').count(),10);
    await page.screenshot({path:path.join(root,'.runtime','knowledge-library.png'),animations:'disabled'});
    await openDocument('requirements.docx');
    await page.waitForFunction(()=>document.querySelector('.knowledge-frame')?.contentDocument?.body?.textContent.includes('账号权限管理'));
    assert.ok(await page.locator('.knowledge-frame').evaluate(frame=>!!frame.contentDocument.querySelector('table')));
    await page.screenshot({path:path.join(root,'.runtime','knowledge-docx-preview.png'),animations:'disabled'});
    await close();
    await openDocument('guide.pdf');
    await page.waitForFunction(()=>document.querySelector('#knowledge-page-total')?.textContent==='/ 2');
    assert.ok(await page.locator('.knowledge-document canvas').evaluate(c=>c.width>0&&c.height>0));
    await page.locator('[data-preview="next"]').click();
    await page.waitForFunction(()=>document.querySelector('#knowledge-page-index')?.value==='2');
    await page.locator('[data-preview="plus"]').click();
    await page.waitForFunction(()=>document.querySelector('#knowledge-zoom-value')?.textContent==='125%');
    await page.screenshot({path:path.join(root,'.runtime','knowledge-pdf-preview.png'),animations:'disabled'});
    await page.locator('[data-preview="text"]').click();
    await page.waitForFunction(()=>document.querySelector('.knowledge-frame')?.contentDocument?.body?.textContent.includes('Knowledge workspace'));
    await close();
    for(const name of ['permissions.xlsx','legacy.xls','review.pptx','legacy.ppt','notes.rtf','report.csv']){
      await openDocument(name);await page.waitForSelector('.knowledge-frame');
      await page.waitForFunction(()=>document.querySelector('.knowledge-frame')?.contentDocument?.querySelector('.content pre')?.textContent.trim().length>0);
      assert.match(await page.locator('.knowledge-preview-note').innerText(),/内容预览/);await close();
    }
    await openDocument('scan.pdf');await page.waitForFunction(()=>document.querySelector('#knowledge-page-total')?.textContent==='/ 1');
    await page.locator('[data-preview="reextract"]').click();await page.waitForFunction(()=>document.querySelector('.knowledge-frame')?.contentDocument?.body?.textContent.includes('OCR'));assert.equal(await page.locator('[data-preview="index"]').isDisabled(),true);await close();
    await openDocument('notes.md');await page.waitForFunction(()=>document.querySelector('.knowledge-frame')?.contentDocument?.querySelector('h1')?.textContent==='账号权限');
    assert.equal(await page.evaluate(()=>!!window.__unsafePreview),false);
    assert.equal(await page.locator('.knowledge-frame').evaluate(frame=>frame.contentDocument.querySelectorAll('script,img,iframe').length),0);
    await page.setViewportSize({width:1024,height:768});
    const box=await page.locator('#knowledge-preview').boundingBox();assert.ok(box.x>=0&&box.y>=0&&box.y+box.height<=769);
    await page.screenshot({path:path.join(root,'.runtime','knowledge-markdown-small.png'),animations:'disabled'});await close();
    await page.setViewportSize({width:1440,height:1000});
    await page.locator('#runtime-knowledge-query').fill('权限 回收');
    await page.waitForFunction(()=>document.querySelector('#knowledge-search-state')?.textContent.includes('相关片段'));
    assert.match(await page.locator('#runtime-knowledge-list').innerText(),/requirements.docx/);
    assert.match(await page.locator('#runtime-knowledge-list').innerText(),/片段/);
    await openDocument('requirements.docx');
    await page.waitForFunction(()=>document.querySelector('.knowledge-frame')?.contentDocument?.querySelector('#knowledge-hit .keyword-hit'));
    assert.match(await page.locator('.knowledge-frame').evaluate(frame=>frame.contentDocument.querySelector('#knowledge-hit').textContent),/权限/);
    await close();
    await page.locator('#runtime-knowledge-settings').click();await page.waitForSelector('#knowledge-retrieval-settings[open]');
    await page.locator('#kb-embedding-url').fill('https://example.invalid/v1');
    await page.locator('#kb-embedding-model').fill('future-embedding');
    await page.locator('#knowledge-retrieval-form button[type=submit]').click();
    await page.waitForFunction(()=>!document.querySelector('#knowledge-retrieval-settings').open);
    const config=await call('/api/knowledge/retrieval/settings');assert.equal(config.cloudRequestsAvailable,true);assert.equal(config.embedding.model,'future-embedding');
    assert.equal(modelRequests.length,0,'Draft settings must not call models');
    await page.locator('#runtime-knowledge-settings').click();
    for(const kind of ['embedding','rerank']){
      await page.locator('#kb-'+kind+'-url').fill('http://127.0.0.1:'+model.address().port+'/v1');
      await page.locator('#kb-'+kind+'-model').fill('fixture-'+kind);
      const revisionBefore=(await call('/api/knowledge/retrieval/settings')).revision;
      await page.locator('[data-kb-test="'+kind+'"]').click();
      await page.waitForFunction(kind=>document.querySelector('#kb-'+kind+'-test-result')?.textContent.includes('连接成功'),kind);
      assert.equal((await call('/api/knowledge/retrieval/settings')).revision,revisionBefore,'Probe must not save draft');
      assert.match(await page.locator('#kb-'+kind+'-test-result').innerText(),kind==='embedding'?/2 维向量/:/2 条重排结果/);
      await page.locator('#kb-'+kind+'-enabled').check();await page.locator('#kb-'+kind+'-consent').check();
      await page.locator('#kb-'+kind+'-model').fill('fixture-'+kind+'-2');
      assert.equal(await page.locator('#kb-'+kind+'-consent').isChecked(),false);
      await page.locator('#kb-'+kind+'-consent').check();
    }
    await page.screenshot({path:path.join(root,'.runtime','knowledge-model-settings.png'),animations:'disabled'});
    await page.locator('#knowledge-retrieval-form button[type=submit]').click();
    await page.waitForFunction(()=>!document.querySelector('#knowledge-retrieval-settings').open);
    await openDocument('requirements.docx');await page.locator('[data-preview="index"]').click();
    await page.waitForFunction(()=>document.querySelector('#knowledge-index-status')?.textContent.includes('向量索引已建立'));
    await close();
    const vectorSearch=await call('/api/knowledge/search?query='+encodeURIComponent('账号权限')+'&projectId='+await page.evaluate(()=>projectContext.id));
    assert.equal(vectorSearch.mode,'vector+rerank');assert.ok(vectorSearch.results.length>0);
    assert.ok(modelRequests.some(r=>r.url==='/v1/embeddings'));assert.ok(modelRequests.some(r=>r.url==='/v1/rerank'));
    await page.evaluate(()=>DongranSettings.set('theme','dark'));
    await page.locator('#runtime-knowledge-query').fill('');
    await page.waitForFunction(()=>document.querySelector('#knowledge-search-state')?.textContent==='10 份资料');
    await openDocument('requirements.docx');await page.waitForFunction(()=>document.querySelector('.knowledge-frame')?.contentDocument?.querySelector('.docx-wrapper section'));
    await page.screenshot({path:path.join(root,'.runtime','knowledge-preview-dark.png'),animations:'disabled'});await close();
    await page.close();await stop();await start();await visit();
    await page.waitForFunction(()=>document.querySelectorAll('[data-knowledge-id]').length===10);
    assert.equal(await page.locator('[data-knowledge-id]').count(),10);
    await openDocument('guide.pdf');await page.waitForFunction(()=>document.querySelector('#knowledge-page-total')?.textContent==='/ 2');await close();
    assert.equal((await call('/api/knowledge/search?query='+encodeURIComponent('账号权限')+'&projectId='+await page.evaluate(()=>projectContext.id))).mode,'vector+rerank','Vectors and configuration survive restart');
    assert.deepEqual(external,[]);assert.deepEqual(errors,[]);
    console.log('PASS: 10-format batch import, original sources, PDF pagination/zoom, DOCX layout, isolated Markdown, Office text preview, scanned PDF, BM25, draft settings, desktop layouts, restart, zero external requests.');
  }catch(e){if(page){await page.screenshot({path:path.join(root,'.runtime','knowledge-failure.png')}).catch(()=>{});console.error(await page.locator('body').innerText().catch(()=>''));}console.error({errors,external});throw e;}
  finally{await browser?.close();await stop();await new Promise(r=>model.close(r));console.log('Test data: '+temporary);}
})().catch(e=>{console.error(e);process.exitCode=1;});
