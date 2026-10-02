const {chromium}=require('playwright');
const {spawn}=require('node:child_process');
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
(async()=>{
 const root=path.resolve(__dirname,'..'),temporary=fs.mkdtempSync(path.join(os.tmpdir(),'dongran-terminal-')),project=path.join(temporary,'project'),data=path.join(temporary,'data');fs.mkdirSync(project);fs.mkdirSync(path.join(project,'sub'));
 const windows=process.platform==='win32',java=windows?'D:\\RunEnvironment\\Java\\jdk21\\bin\\java.exe':'java';
 let backend,browser,page,logs='',base;const errors=[];
 const call=(endpoint,method='GET',body)=>page.evaluate(({endpoint,method,body})=>DongranRuntime.request(endpoint,method,body),{endpoint,method,body});
 const rows=()=>call('/api/terminals');
 const output=async id=>(await call('/api/terminals/poll','POST',{[id]:0}))[0].data;
 async function waitOutput(id,text){const deadline=Date.now()+20000;while(Date.now()<deadline){if((await output(id)).includes(text))return;await new Promise(r=>setTimeout(r,150));}throw Error('Missing output '+text+'\n'+await output(id));}
 async function type(command){const input=page.locator('.terminal-pane.is-focused .xterm-helper-textarea');await input.focus();await page.keyboard.type(command);await page.keyboard.press('Enter');}
 try{
  const helper=path.join(root,'sandbox/target/release',windows?'dongran-sandbox.exe':'dongran-sandbox');
  backend=spawn(java,['-jar',path.join(root,'backend/target/dongran-backend-0.1.0-SNAPSHOT.jar'),'--server.port=0','--dongran.data-dir='+data,'--dongran.sandbox-helper='+helper],{cwd:root,windowsHide:true,stdio:['ignore','pipe','pipe']});backend.stdout.on('data',b=>logs+=b);backend.stderr.on('data',b=>logs+=b);
  const deadline=Date.now()+45000;while(Date.now()<deadline){if(backend.exitCode!==null)throw Error(logs);try{const runtime=JSON.parse(fs.readFileSync(path.join(data,'runtime.json'),'utf8'));if(runtime.pid===backend.pid){base='http://127.0.0.1:'+runtime.port;break;}}catch{}await new Promise(r=>setTimeout(r,150));}assert.ok(base,logs);
  browser=await chromium.launch({headless:true,...(windows?{executablePath:'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'}:{})});page=await browser.newPage({viewport:{width:1440,height:1000}});page.on('pageerror',e=>errors.push(e.message));
  await page.goto(base);await page.waitForFunction(()=>DongranBackend.isAvailable());await page.evaluate(async project=>{await DongranRuntime.open(project);DongranSettings.set('terminalHeight',360);},project);
  // A mutation without the application's client header is not accepted.
  const unauthorized=await page.evaluate(()=>fetch('/api/terminals',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'}).then(r=>r.status));assert.equal(unauthorized,403);
  await page.locator('#terminal-toggle').click();await page.locator('#terminal-empty-host').click();await page.waitForFunction(()=>document.querySelectorAll('.terminal-session-tab').length===1);
  const first=(await rows())[0].id;await type(windows?"cd sub; $env:PTY_BROWSER='kept'":"cd sub; export PTY_BROWSER=kept");
  await type(windows?"Write-Output ('BROWSER_' + $env:PTY_BROWSER)":"printf 'BROWSER_%s\\n' \"$PTY_BROWSER\"");await waitOutput(first,'BROWSER_kept');
  await page.locator('#terminal-search').click();await page.locator('#terminal-find-input').fill('BROWSER_kept');await page.waitForFunction(()=>document.querySelector('#terminal-find-state').textContent==='已定位');await page.locator('[data-find="close"]').click();
  await page.locator('#terminal-more').click();await page.getByRole('menuitem',{name:'重命名会话',exact:true}).click();await page.getByRole('textbox',{name:'会话名称'}).fill('开发服务');await page.locator('.terminal-rename button').click();await page.waitForFunction(()=>document.querySelector('.terminal-session-select').textContent.includes('开发服务'));
  await page.locator('#terminal-split').click();await page.waitForFunction(()=>document.querySelectorAll('.terminal-pane:not([hidden])').length===2);assert.equal((await rows()).length,2);
  const second=(await rows()).find(row=>row.id!==first).id;await type(windows?"Write-Output ('SECOND_'+'SESSION')":"printf 'SECOND_%s\\n' SESSION");await waitOutput(second,'SECOND_SESSION');
  await page.context().grantPermissions(['clipboard-read','clipboard-write'],{origin:base});
  await page.locator('#terminal-more').click();await page.getByRole('menuitem',{name:'全选',exact:true}).click();await page.locator('#terminal-more').click();await page.getByRole('menuitem',{name:'复制选中内容',exact:true}).click();assert.ok((await page.evaluate(()=>navigator.clipboard.readText())).includes('SECOND_SESSION'));
  await page.evaluate(()=>navigator.clipboard.writeText("Write-Output ('PASTE_'+'OK')"));await page.locator('#terminal-more').click();await page.getByRole('menuitem',{name:'粘贴',exact:true}).click();await page.locator('.terminal-pane.is-focused .xterm-helper-textarea').focus();await page.keyboard.press('Enter');await waitOutput(second,'PASTE_OK');
  await page.locator('#terminal-more').click();const download=page.waitForEvent('download');await page.getByRole('menuitem',{name:'保存输出',exact:true}).click();assert.equal((await download).suggestedFilename(),'terminal-output.txt');
  await page.locator('#terminal-divider').focus();await page.keyboard.press('ArrowLeft');await page.waitForTimeout(200);assert.notEqual(await page.locator('.terminal-pane:not([hidden])').first().evaluate(el=>el.style.flex),await page.locator('.terminal-pane:not([hidden])').last().evaluate(el=>el.style.flex));
  await page.locator('#terminal-expand').click();await page.waitForTimeout(350);assert.equal(await page.locator('.work-body').isVisible(),false);await page.screenshot({path:path.join(root,'.runtime/terminal-light.png')});
  await page.evaluate(()=>DongranSettings.set('theme','dark'));await page.waitForTimeout(200);await page.screenshot({path:path.join(root,'.runtime/terminal-dark.png')});
  await page.locator('#terminal-close').click();assert.equal((await rows()).filter(s=>s.status==='running').length,2);await page.locator('#terminal-toggle').click();
  await page.reload();await page.waitForFunction(()=>DongranBackend.isAvailable());await page.locator('#terminal-toggle').click();await page.waitForFunction(()=>document.querySelectorAll('.terminal-session-tab').length===2);assert.ok((await output(first)).includes('BROWSER_kept'));
  await page.evaluate(project=>DongranRuntime.open(project),project);
  await page.route('**/api/sandbox/status?refresh=true',route=>route.fulfill({json:{protocolVersion:1,available:false,reason:'测试沙箱未就绪',networkIsolation:false}}));await page.locator('#terminal-mode').click();await page.getByRole('menuitemradio',{name:'沙箱命令',exact:false}).click();await page.waitForFunction(()=>document.querySelector('#toast').textContent.includes('测试沙箱未就绪'));assert.equal(await page.locator('.terminal-session-tab').count(),2);await page.unroute('**/api/sandbox/status?refresh=true');
  await page.locator('#terminal-mode').click();await page.getByRole('menuitemradio',{name:'沙箱命令',exact:false}).click();await page.waitForSelector('#terminal-form:not([hidden])');await page.locator('#terminal-input').fill(windows?"Write-Output ('SANDBOX_'+'UI_OK')":"printf 'SANDBOX_%s\\n' UI_OK");await page.locator('#terminal-input').press('Enter');await page.waitForFunction(()=>document.querySelector('#terminal-input').disabled);await page.waitForFunction(()=>!document.querySelector('#terminal-input').disabled,{},{timeout:30000});
  assert.match(await page.locator('.terminal-pane.is-focused .xterm-rows').innerText(),/SANDBOX_UI_OK/);
  await page.setViewportSize({width:1024,height:768});await page.waitForTimeout(300);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  await call('/api/terminals','DELETE');assert.equal((await rows()).length,0);
  assert.deepEqual(errors,[]);console.log('PASS: packaged native PTY, authenticated API, persistent shell input, search, rename, split, themes, panel hiding, refresh recovery, sandbox unavailable gate and real sandbox command.');
 }catch(e){console.error(logs.slice(-2200));if(page)console.error((await page.locator('body').innerText().catch(()=>'' )).slice(-2200));throw e;}
 finally{if(page)try{for(const s of await rows())await call('/api/terminals/'+s.id,'DELETE');}catch{}await browser?.close();if(backend&&backend.exitCode===null)await new Promise(r=>{backend.once('exit',r);backend.kill();});console.log('Test data: '+temporary);}
})().catch(e=>{console.error(e);process.exitCode=1;});
