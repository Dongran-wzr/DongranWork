const {chromium}=require('playwright');
const {spawn}=require('node:child_process');
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),http=require('node:http'),assert=require('node:assert/strict');
(async()=>{
 const root=path.resolve(__dirname,'..'),temporary=fs.mkdtempSync(path.join(os.tmpdir(),'dongran-context-ui-')),data=path.join(temporary,'data');
 let backend,browser,page,base,logs='';const errors=[];
 const quote='以后回答统一使用中文，并且先给结论，再给说明。';
 const model=http.createServer(async(req,res)=>{
  let raw='';for await(const b of req)raw+=b;const input=JSON.parse(raw);
  let content='收到，后续会按这个偏好组织回答。';
  if(input.messages[0].content.includes('从用户原话提炼')){
   const source=JSON.parse(input.messages.at(-1).content).messages.find(m=>m.content.includes(quote));
   content=JSON.stringify({memories:source?[{title:'回答风格',content:quote,quote,scope:'global',sourceMessageId:source.id}]:[]});
  }
  res.writeHead(200,{'Content-Type':'text/event-stream'});res.end('data: '+JSON.stringify({choices:[{delta:{content},finish_reason:'stop'}]})+'\n\n'+'data: [DONE]\n\n');
 });await new Promise(r=>model.listen(0,'127.0.0.1',r));
 const java='D:/RunEnvironment/Java/jdk21/bin/java.exe';
 const call=(endpoint,method='GET',body)=>page.evaluate(({endpoint,method,body})=>DongranRuntime.request(endpoint,method,body),{endpoint,method,body});
 try{
 backend=spawn(java,['-jar',path.join(root,'backend/target/dongran-backend-0.1.0-SNAPSHOT.jar'),'--server.port=0','--dongran.data-dir='+data],{cwd:root,windowsHide:true,stdio:['ignore','pipe','pipe']});backend.stdout.on('data',b=>logs+=b);backend.stderr.on('data',b=>logs+=b);
 const deadline=Date.now()+45000;while(Date.now()<deadline){if(backend.exitCode!==null)throw Error(logs);try{const runtime=JSON.parse(fs.readFileSync(path.join(data,'runtime.json'),'utf8'));if(runtime.pid===backend.pid){base='http://127.0.0.1:'+runtime.port;break;}}catch{}await new Promise(r=>setTimeout(r,150));}
 assert.ok(base,logs);
 browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});page=await browser.newPage({viewport:{width:1440,height:960}});page.on('pageerror',e=>errors.push(e.message));await page.goto(base);await page.waitForFunction(()=>DongranBackend.isAvailable());
 const provider=await call('/api/model-providers','POST',{name:'上下文测试',kind:'custom',baseUrl:'http://127.0.0.1:'+model.address().port+'/v1',modelName:'fixture',temperature:0,contextWindow:32768,apiKey:'synthetic',rememberKey:false});await call('/api/model-providers/'+provider.id+'/activate','POST');await page.evaluate(()=>DongranRuntime.connect());
 await page.evaluate(()=>DongranSettings.open('memory'));await page.locator('[data-setting-toggle="memoryAutoCapture"]').click();
 await page.waitForFunction(()=>DongranSettings.get('memoryAutoCapture')===true);await page.evaluate(()=>DongranSettings.close());
 await page.locator('#prompt').fill(quote);await page.locator('#prompt').press('Enter');await page.waitForFunction(()=>document.querySelector('.task-heading .badge')?.textContent==='已完成');
 let rows=await call('/api/memory-records');assert.equal(rows.length,1);assert.equal(rows[0].status,'candidate');const id=rows[0].id;
 assert.ok((await page.locator('#messages').innerText()).includes('记忆候选'));
 await page.evaluate(()=>DongranSettings.open('memory'));await page.waitForSelector('[data-memory-state="active"]');await page.locator('[data-memory-state="active"]').click();await page.waitForSelector('[data-pin]');
 await page.locator('[data-pin]').click();await page.waitForFunction(()=>document.querySelector('[data-pin]')?.textContent==='取消固定');await page.locator('[data-history]').click();await page.waitForFunction(()=>!document.querySelector('.memory-version-view').hidden);
 await page.screenshot({path:path.join(root,'.runtime/context-memory-review.png')});await page.evaluate(()=>DongranSettings.close());
 await page.locator('#prompt').fill('请用中文说明你的回答风格');await page.locator('#prompt').press('Enter');await page.waitForFunction(async()=>{const task=await DongranRuntime.request('/api/tasks/'+state.active);return task.status==='completed'&&task.messages.filter(m=>m.role==='assistant').length>=2;});
 await page.waitForFunction(async()=>{const data=await DongranRuntime.request('/api/tasks/'+state.active+'/context');return data.state.status==='completed'&&data.rounds.length>=2&&JSON.parse(data.rounds[0].document).memories.some(m=>m.title==='回答风格');});await page.locator('[data-context-task]').click();await page.waitForSelector('#context-window[open]');assert.ok((await page.locator('#context-window .context-memory strong').allTextContents()).includes('回答风格'));assert.equal(await page.locator('#context-window progress').count()>0,true);await page.screenshot({path:path.join(root,'.runtime/context-inspector.png')});await page.locator('[data-context-close]').click();await page.waitForFunction(()=>!document.querySelector('#context-window').open);
 await page.evaluate(()=>DongranSettings.open('context'));await page.locator('[data-setting-input="contextOutputReserve"]').fill('2048');await page.locator('[data-setting-input="contextOutputReserve"]').press('Tab');await page.evaluate(()=>DongranSettings.close());
 await page.evaluate(()=>DongranSettings.open('memory'));await page.waitForSelector('[data-memory-state="archived"]');await page.locator('[data-memory-state="archived"]').click();await page.waitForSelector('[data-memory-state="active"]');await page.evaluate(()=>DongranSettings.close());
 await page.reload();await page.waitForFunction(()=>DongranBackend.isAvailable());assert.equal((await call('/api/settings')).contextOutputReserve,2048);assert.equal((await call('/api/memory-records')).find(r=>r.id===id).status,'archived');assert.deepEqual(errors,[]);
 console.log('PASS: automatic extraction, review, pin, version history, context inspection, archive and settings reload.');
 }catch(e){console.error(logs.slice(-1200));if(page)console.error((await page.locator('body').innerText().catch(()=>'' )).slice(-1800));throw e;}finally{await browser?.close();if(backend?.exitCode===null)await new Promise(r=>{backend.once('exit',r);backend.kill();});await new Promise(r=>model.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1;});
