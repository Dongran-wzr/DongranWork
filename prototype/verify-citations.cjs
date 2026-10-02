const {chromium}=require('playwright');
const {spawn}=require('node:child_process');
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),http=require('node:http'),assert=require('node:assert/strict');
(async()=>{
 const root=path.resolve(__dirname,'..'),temporary=fs.mkdtempSync(path.join(os.tmpdir(),'dongran-citations-')),data=path.join(temporary,'data');
 let backend,browser,page,base,logs='',repairs=0;const errors=[];
 const model=http.createServer(async(req,res)=>{
  let raw='';for await(const b of req)raw+=b;const input=JSON.parse(raw),prompt=input.messages.filter(m=>m.role==='user').at(-1)?.content||'';
  res.writeHead(200,{'Content-Type':'text/event-stream'});
  const emit=delta=>res.write('data: '+JSON.stringify({choices:[{delta,finish_reason:delta.tool_calls?'tool_calls':null}]})+'\n\n');
  if(prompt==='Reply exactly OK.')emit({content:'OK'});
  else if(prompt.startsWith('Return only valid JSON'))emit({content:'{"ok":true}'});
  else if(prompt.startsWith('Call capability_probe'))emit({tool_calls:[{index:0,id:'probe',type:'function',function:{name:'capability_probe',arguments:'{"value":"ping"}'}}]});
  else if(prompt.startsWith('系统已执行')){const material=JSON.parse(prompt.slice(prompt.indexOf('\n')+1));const row=material.find(item=>item.tool==='search_knowledge').result[0];emit({content:'资料依据：[定位资料](knowledge://'+row.id+'/'+row.chunkId+')'});}
  else if(prompt.startsWith('你刚才只列了来源')){repairs++;const evidence=input.messages.find(m=>m.role==='user'&&m.content.startsWith('系统已执行')).content;const material=JSON.parse(evidence.slice(evidence.indexOf('\n')+1));const row=material.find(item=>item.tool==='search_knowledge').result[0];emit({content:'验收定位词对应真实片段，可以点击参考资料查看原文位置。资料中的脚本标签仅作为文本显示，不会执行。\n\n参考资料：[定位资料](knowledge://'+row.id+'/'+row.chunkId+')'});}
  else if(prompt.startsWith('引用')){
    const tool=input.messages.find(m=>m.tool_call_id==='citation-fixture');
    if(!tool)emit({tool_calls:[{index:0,id:'citation-fixture',type:'function',function:{name:'search_knowledge',arguments:JSON.stringify({query:'验收定位词'})}}]});
    else{const row=JSON.parse(tool.content)[0];emit({content:'验收定位词对应真实片段，可以点击参考资料查看原文位置。资料中的脚本标签仅作为文本显示，不会执行。\n\n参考资料：[定位资料](knowledge://'+row.id+'/'+row.chunkId+')'});}
  }else{
    if(prompt.startsWith('继续'))await new Promise(r=>setTimeout(r,1800));
    for(let i=0;i<4;i++){emit({content:('公开测试回答第 '+i+' 部分\n').repeat(24)});await new Promise(r=>setTimeout(r,300));}
  }
  res.end('data: [DONE]\n\n');
 });await new Promise(r=>model.listen(0,'127.0.0.1',r));
 const java=process.platform==='win32'?'D:\\RunEnvironment\\Java\\jdk21\\bin\\java.exe':'java';
 const call=(endpoint,method='GET',body)=>page.evaluate(({endpoint,method,body})=>DongranRuntime.request(endpoint,method,body),{endpoint,method,body});
 async function start(){logs='';backend=spawn(java,['-jar',path.join(root,'backend/target/dongran-backend-0.1.0-SNAPSHOT.jar'),'--server.port=0','--dongran.data-dir='+data],{cwd:root,windowsHide:true,stdio:['ignore','pipe','pipe']});backend.stdout.on('data',b=>logs+=b);backend.stderr.on('data',b=>logs+=b);const deadline=Date.now()+45000;while(Date.now()<deadline){if(backend.exitCode!==null)throw Error(logs);try{const runtime=JSON.parse(fs.readFileSync(path.join(data,'runtime.json'),'utf8'));if(runtime.pid===backend.pid){base='http://127.0.0.1:'+runtime.port;return;}}catch{}await new Promise(r=>setTimeout(r,150));}throw Error(logs);}
 async function stop(){if(backend&&backend.exitCode===null)await new Promise(r=>{backend.once('exit',r);backend.kill();});}
 async function completed(){await page.waitForFunction(()=>document.querySelector('.task-heading .badge')?.textContent==='已完成');}
 try{
  await start();browser=await chromium.launch({headless:true,...(process.platform==='win32'?{executablePath:'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'}:{})});page=await browser.newPage({viewport:{width:1360,height:900}});page.on('pageerror',e=>errors.push(e.message));await page.goto(base);await page.waitForFunction(()=>DongranBackend.isAvailable());
  const provider=await call('/api/model-providers','POST',{name:'引用测试',kind:'custom',baseUrl:'http://127.0.0.1:'+model.address().port+'/v1',modelName:'fixture',temperature:0,contextWindow:4096,apiKey:'synthetic-key',rememberKey:false});await call('/api/model-providers/'+provider.id+'/activate','POST');await page.evaluate(()=>DongranRuntime.connect());
  await page.evaluate(()=>DongranSettings.open('models'));await page.locator('[data-provider-action="capabilities"]').click();await page.waitForFunction(()=>document.querySelector('.provider-notice')?.textContent.includes('工具调用 通过'));assert.equal((await call('/api/model-providers/'+provider.id+'/capabilities')).tools,'passed');await page.evaluate(()=>DongranSettings.close());
  await page.evaluate(()=>DongranSettings.open('websearch'));await page.waitForSelector('#search-provider-picker');
  await page.locator('#search-provider-picker').click();await page.getByRole('menuitemradio',{name:'Tavily Search API'}).click();
  await page.locator('#search-api-key').fill('synthetic-search-key');await page.locator('#search-provider-form button[type="submit"]').click();await page.waitForFunction(()=>document.querySelector('#search-notice')?.textContent.includes('已保存'));
  const searchSettings=await call('/api/web-search');assert.equal(searchSettings.provider,'tavily');assert.equal(searchSettings.tavilyConfigured,true);assert.ok(!JSON.stringify(searchSettings).includes('synthetic-search-key'));
  await call('/api/web-search','PUT',{provider:'tavily',clearKey:true});await call('/api/web-search','PUT',{provider:'bing'});await page.evaluate(()=>DongranSettings.close());
  await page.locator('[data-action="new"]').first().click();
  await page.locator('#prompt').fill('长回答滚动测试');await page.locator('#prompt').press('Enter');await completed();
  const bottom=()=>page.locator('#messages').evaluate(el=>el.scrollHeight-el.clientHeight-el.scrollTop<80);
  assert.ok(await bottom());await page.locator('#messages').evaluate(el=>{el.dispatchEvent(new WheelEvent('wheel',{deltaY:-300}));el.scrollTop=0;});await page.waitForFunction(()=>!document.querySelector('#chat-latest').hidden);await page.screenshot({path:path.join(root,'.runtime/chat-latest-button.png')});
  const animationSamples=await page.evaluate(async()=>{const scroller=document.querySelector('#messages'),samples=[];document.querySelector('#chat-latest').click();const start=performance.now();while(performance.now()-start<450){await new Promise(requestAnimationFrame);samples.push(scroller.scrollTop);}return {samples,max:scroller.scrollHeight-scroller.clientHeight};});
  assert.ok(animationSamples.samples.some(value=>value>0&&value<animationSamples.max-20),'Scroll must have intermediate animation frames');
  await page.locator('#messages').evaluate(el=>{el.dispatchEvent(new WheelEvent('wheel',{deltaY:-300}));el.scrollTop=0;});await page.waitForFunction(()=>!document.querySelector('#chat-latest').hidden);
  await page.locator('#chat-latest').click();await page.waitForFunction(()=>{const el=document.querySelector('#messages');return el.scrollHeight-el.clientHeight-el.scrollTop<2;});assert.ok(await bottom());await page.locator('#messages').evaluate(el=>{el.dispatchEvent(new WheelEvent('wheel',{deltaY:-300}));el.scrollTop=0;});await page.waitForFunction(()=>!document.querySelector('#chat-latest').hidden);
  await page.locator('#prompt').fill('继续生成长回答');await page.locator('#prompt').press('Enter');await page.waitForFunction(()=>document.querySelector('.task-heading .badge')?.textContent==='执行中');await page.waitForFunction(()=>{const el=document.querySelector('#messages');return el.scrollHeight-el.clientHeight-el.scrollTop<2;});assert.ok(await bottom(),'Sending should move to latest');
  await page.locator('#messages').evaluate(el=>{el.dispatchEvent(new WheelEvent('wheel',{deltaY:-300}));el.scrollTop=0;});await page.waitForFunction(()=>!document.querySelector('#chat-latest').hidden);await completed();assert.ok(await page.locator('#messages').evaluate(el=>el.scrollTop<40),'Streaming must preserve user history position');await page.locator('#chat-latest').click();await page.waitForFunction(()=>{const el=document.querySelector('#messages');return el.scrollHeight-el.clientHeight-el.scrollTop<2;});assert.ok(await bottom());
  await call('/api/knowledge','POST',{name:'引用定位样例',content:'前言\n验收定位词对应真实片段。\n<script>not executable</script>'});
  await call('/api/knowledge','POST',{name:'另一份召回资料',content:'验收定位词的另一份候选参考，未被生成答案使用。'});
  await page.locator('#prompt').fill('知识库 验收定位词');await page.locator('#prompt').press('Enter');await page.waitForFunction(()=>document.querySelector('.runtime-message .knowledge-citation'));await completed();
  assert.equal(repairs,1,'Citation-only answer must be repaired once');
  const taskId=await page.evaluate(()=>state.active);assert.equal(await page.locator('.knowledge-sources .knowledge-citation').count(),1);assert.ok(await page.locator('.knowledge-sources').evaluate(el=>el.previousElementSibling?.classList.contains('runtime-message')),'References must follow the answer');await page.locator('.knowledge-sources .knowledge-citation').click();await page.waitForFunction(()=>document.querySelector('.knowledge-frame')?.contentDocument?.querySelector('#knowledge-hit .keyword-hit'));
  assert.equal(await page.locator('.knowledge-frame').evaluate(f=>f.contentDocument.querySelector('.keyword-hit').textContent),'验收定位词');assert.equal(await page.locator('.knowledge-frame').evaluate(f=>f.contentDocument.querySelectorAll('script').length),0);await page.screenshot({path:path.join(root,'.runtime/knowledge-citation-highlight.png')});await page.locator('#knowledge-preview [data-preview="close"]').click();await page.waitForFunction(()=>!document.querySelector('#knowledge-preview').open);
  await stop();await start();await page.goto(base);await page.waitForFunction(id=>DongranBackend.isAvailable()&&state.tasks.some(t=>t.id===id)&&document.querySelector('#runtime-recent')?.textContent.includes('选择'),taskId);await page.evaluate(async id=>{state.active=id;await DongranRuntime.renderTask();},taskId);assert.equal(await page.locator('.knowledge-sources .knowledge-citation').count(),1);await page.locator('.runtime-message .knowledge-citation').click();await page.waitForFunction(()=>document.querySelector('.knowledge-frame')?.contentDocument?.querySelector('#knowledge-hit'));
  assert.deepEqual(errors,[]);console.log('PASS: follow latest, history scroll preservation during streaming, send scroll, source cards, answer citations, highlight, safe text and restart persistence.');
 }catch(e){console.error(logs.slice(-1500));if(page)console.error((await page.locator('body').innerText().catch(()=>'' )).slice(-3500));throw e;}finally{await browser?.close();await stop();await new Promise(r=>model.close(r));console.log('Test data: '+temporary);}
})().catch(e=>{console.error(e);process.exitCode=1;});
