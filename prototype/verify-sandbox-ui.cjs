const { chromium } = require('playwright');
const {pathToFileURL}=require('node:url'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{const browser=await chromium.launch({executablePath:process.env.DONGRAN_BROWSER||'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',headless:true});try{
 const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];page.on('pageerror',error=>errors.push(error.message));await page.goto(pathToFileURL(path.join(__dirname,'index.html')).href);
 await page.waitForFunction(()=>window.DongranSandbox&&window.DongranSettings);await page.evaluate(()=>DongranSettings.open('permissions'));assert.match(await page.locator('[data-sandbox-card]').innerText(),/演示模式/);await page.evaluate(()=>DongranSettings.close());
 await page.evaluate(()=>{
  window.DongranBackendBase='http://127.0.0.1:3210';window.fixture={protocolVersion:1,available:false,reason:'隔离助手缺失',networkIsolation:true,policy:{mode:'required',network:'deny'}};window.commandRequests=0;
  window.DongranBackend={request:async(route,options={})=>{
   if(route.startsWith('/api/sandbox')){if(window.failure)throw window.failure;return structuredClone(window.fixture);}
   if(route==='/api/terminals')return [];
   if(route==='/api/commands'&&options.method==='POST'){window.commandRequests++;return {id:'run-fixture'};}
   if(route==='/api/commands/run-fixture')return {status:'completed',output:'sandbox output',exit_code:0,syncStatus:'applied'};
   throw Error('Unexpected request '+route);
  }};
  window.dispatchEvent(new CustomEvent('projectchange',{detail:{id:'fixture',name:'demo',path:'C:\\demo'}}));
 });
 await page.locator('#terminal-toggle').click();await page.locator('#terminal-empty-sandbox').click();await page.waitForFunction(()=>document.querySelector('#toast').textContent.includes('隔离助手缺失'));assert.equal(await page.locator('.terminal-session-tab').count(),0);assert.equal(await page.evaluate(()=>commandRequests),0);
 await page.evaluate(()=>{fixture.available=true;fixture.reason='';});await page.locator('#terminal-empty-sandbox').click();await page.waitForSelector('#terminal-form:not([hidden])');await page.locator('#terminal-input').fill('echo test');await page.locator('#terminal-input').press('Enter');await page.waitForFunction(()=>window.commandRequests===1&&!document.querySelector('#terminal-input').disabled);assert.match(await page.locator('.xterm-rows').innerText(),/sandbox output/);
 for(const kind of ['network','protocol','missing']){
  await page.evaluate(async kind=>{fixture.networkIsolation=kind!=='network';fixture.protocolVersion=kind==='protocol'?2:1;window.failure=kind==='missing'?{status:404,message:'Not Found'}:null;await DongranSandbox.refresh();},kind);
  assert.equal(await page.locator('#terminal-input').isDisabled(),true);assert.match(await page.locator('#terminal-status').innerText(),/沙箱不可用/);
 }
 assert.equal(await page.evaluate(()=>commandRequests),1);assert.deepEqual(errors,[]);console.log('PASS: sandbox unavailable, malformed policy and protocol fail closed; separate sandbox tab executes only after valid capability check.');
}finally{await browser.close();}})().catch(error=>{console.error(error);process.exitCode=1;});
