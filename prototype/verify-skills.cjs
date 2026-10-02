const {chromium}=require('playwright');
const {spawn}=require('node:child_process');
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),http=require('node:http'),assert=require('node:assert/strict');
(async()=>{
 const root=path.resolve(__dirname,'..'),temporary=fs.mkdtempSync(path.join(os.tmpdir(),'dongran-skills-ui-')),data=path.join(temporary,'data');
 let backend,browser,page,base,logs='';const errors=[];
 let skillId='',skillHash='',requests=[];
 const model=http.createServer(async(req,res)=>{
  let raw='';for await(const b of req)raw+=b;const input=JSON.parse(raw);requests.push(input);
  const prompt=input.messages.filter(m=>m.role==='user').at(-1)?.content||'';
  let delta={content:'已按照技能流程阅读资料并给出说明。'},finish='stop';
  if(prompt.includes('自动技能测试')&&!input.messages.some(m=>m.role==='tool')){
    delta={tool_calls:[{index:0,id:'load-fixture',type:'function',function:{name:'load_skill',arguments:JSON.stringify({id:skillId})}}]};finish='tool_calls';
  }
  res.writeHead(200,{'Content-Type':'text/event-stream'});res.end('data: '+JSON.stringify({choices:[{delta,finish_reason:finish}]})+'\n\n'+'data: [DONE]\n\n');
 });await new Promise(r=>model.listen(0,'127.0.0.1',r));
 async function until(check){const end=Date.now()+15000;while(Date.now()<end){if(await check())return;await new Promise(r=>setTimeout(r,100));}throw new Error('Timed out waiting for fixture state');}
 const java='D:/RunEnvironment/Java/jdk21/bin/java.exe';
 const call=(endpoint,method='GET',body)=>page.evaluate(({endpoint,method,body})=>DongranRuntime.request(endpoint,method,body),{endpoint,method,body});
 try{
 backend=spawn(java,['-Xms32m','-Xmx256m','-XX:+UseSerialGC','-XX:MaxMetaspaceSize=160m','-jar',path.join(root,'backend/target/dongran-backend-0.1.0-SNAPSHOT.jar'),'--server.port=0','--dongran.data-dir='+data],{cwd:root,windowsHide:true,stdio:['ignore','pipe','pipe']});backend.stdout.on('data',b=>logs+=b);backend.stderr.on('data',b=>logs+=b);
 const deadline=Date.now()+45000;while(Date.now()<deadline){if(backend.exitCode!==null)throw Error(logs);try{const runtime=JSON.parse(fs.readFileSync(path.join(data,'runtime.json'),'utf8'));if(runtime.pid===backend.pid){base='http://127.0.0.1:'+runtime.port;break;}}catch{}await new Promise(r=>setTimeout(r,150));}
 assert.ok(base,logs);
 browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});page=await browser.newPage({viewport:{width:1440,height:960}});page.on('pageerror',e=>errors.push(e.message));await page.goto(base);await page.waitForFunction(()=>DongranBackend.isAvailable());
 const provider=await call('/api/model-providers','POST',{name:'上下文测试',kind:'custom',baseUrl:'http://127.0.0.1:'+model.address().port+'/v1',modelName:'fixture',temperature:0,contextWindow:32768,apiKey:'synthetic',rememberKey:false});await call('/api/model-providers/'+provider.id+'/activate','POST');await page.evaluate(()=>DongranRuntime.connect());
 const contents='---\nname: fixture-review\ndescription: 自动技能测试 代码审查 code review\nversion: 1.0.0\n---\n\n# 审查步骤\n先读取真实资料。回答包含 SKILL_FIXTURE_MARKER，不得编造运行结果。';
 const source=path.join(temporary,'source');fs.mkdirSync(path.join(source,'references'),{recursive:true});fs.writeFileSync(path.join(source,'SKILL.md'),contents);fs.writeFileSync(path.join(source,'references','notes.md'),'fixture reference');
 await page.evaluate(()=>DongranSettings.open('skills'));await page.waitForSelector('#skill-manager [data-import]');await page.locator('[data-import]').click();await page.locator('#skill-directory').fill(source);await page.locator('#skill-directory-form button[type=submit]').click();await page.waitForFunction(()=>!document.querySelector('#skill-editor').open);
 let row=(await call('/api/skills')).find(r=>r.name==='fixture-review');assert.ok(row);skillId=row.id;skillHash=row.content_hash;
 await page.locator('[data-edit="'+skillId+'"]').click();await page.waitForSelector('#skill-content');assert.ok((await page.locator('#skill-content').inputValue()).includes('SKILL_FIXTURE_MARKER'));await page.locator('#skill-content').fill(contents.replace('1.0.0','1.1.0'));await page.locator('#skill-form button[type=submit]').click();await page.waitForFunction(()=>!document.querySelector('#skill-editor').open);assert.equal((await call('/api/skills/'+skillId)).version,'1.1.0');
 const JSZip=require('jszip');const archive=new JSZip();archive.file('bundle/SKILL.md',contents.replace('fixture-review','zip-review'));archive.file('bundle/templates/report.md','safe template');
 await page.locator('[data-import]').click();await page.locator('#skill-zip').setInputFiles({name:'review.zip',mimeType:'application/zip',buffer:await archive.generateAsync({type:'nodebuffer'})});await page.waitForFunction(()=>!document.querySelector('#skill-editor').open);assert.ok((await call('/api/skills')).some(r=>r.name==='zip-review'));
 await page.screenshot({path:path.join(root,'.runtime/skills-settings.png')});await page.evaluate(()=>DongranSettings.close());
 await page.locator('#prompt').fill('/fixture');await page.waitForSelector('#skill-picker:not([hidden]) [role=option]');await page.locator('#prompt').press('Enter');assert.equal(await page.locator('#skill-selected button').count(),1);assert.equal(await page.locator('#prompt').inputValue(),'');await page.locator('#skill-auto-toggle').uncheck();
 await page.locator('#prompt').fill('按已选技能解释审查流程');await page.screenshot({path:path.join(root,'.runtime/skills-composer.png')});await page.locator('#prompt').press('Enter');await page.waitForFunction(()=>document.querySelector('.task-heading .badge')?.textContent==='已完成');assert.ok(requests.some(r=>JSON.stringify(r.messages).includes('SKILL_FIXTURE_MARKER')));assert.ok((await page.locator('#messages').innerText()).includes('已加载 fixture-review'));
 const task=await page.evaluate(()=>state.active);const inspection=await call('/api/tasks/'+task+'/context');assert.ok(JSON.parse(inspection.rounds[0].document).skills.some(s=>s.name==='fixture-review'));
 await page.locator('#prompt').fill('自动技能测试 代码审查 code review 的流程是什么');await page.locator('#prompt').press('Enter');await page.waitForFunction(async()=>{const r=await DongranRuntime.request('/api/tasks/'+state.active);return r.status==='completed'&&r.messages.filter(m=>m.role==='assistant').length>=2;});
 await until(()=>requests.some(r=>r.messages.some(m=>m.role==='tool'&&m.content.includes('SKILL_FIXTURE_MARKER'))));
 await page.evaluate(()=>DongranSettings.open('skills'));await page.waitForSelector('[data-auto="'+skillId+'"]');await page.locator('[data-auto="'+skillId+'"]').uncheck();await until(async()=>(await call('/api/skills/'+skillId)).auto_match===0);
 await page.locator('[data-enabled="'+skillId+'"]').uncheck();await until(async()=>(await call('/api/skills/'+skillId)).enabled===0);await page.evaluate(()=>DongranSettings.close());await page.locator('#prompt').fill('/fixture');await page.waitForFunction(()=>!document.querySelector('#skill-picker').hidden);assert.equal(await page.locator('#skill-picker [role=option]').count(),0);await page.locator('#prompt').press('Escape');
 await page.reload();await page.waitForFunction(()=>DongranBackend.isAvailable());assert.equal((await call('/api/skills/'+skillId)).enabled,0);
 await page.evaluate(()=>DongranSettings.open('skills'));await page.waitForSelector('[data-delete="'+skillId+'"]');await page.locator('[data-delete="'+skillId+'"]').click();await page.locator('[data-confirm-delete]').click();await page.waitForFunction(()=>!document.querySelector('#skill-editor').open);assert.ok(!(await call('/api/skills')).some(r=>r.id===skillId));assert.ok(fs.existsSync(path.join(source,'SKILL.md')));assert.deepEqual(errors,[]);
 console.log('PASS: directory import, editor, slash selection, explicit/automatic actual model context, provenance, disable, reload and uninstall.');
 }catch(e){console.error(logs.slice(-1200));if(page)console.error((await page.locator('body').innerText().catch(()=>'' )).slice(-1800));throw e;}finally{await browser?.close();if(backend?.exitCode===null)await new Promise(r=>{backend.once('exit',r);backend.kill();});await new Promise(r=>model.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1;});
