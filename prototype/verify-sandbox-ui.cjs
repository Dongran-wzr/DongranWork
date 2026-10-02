const { chromium } = require('playwright');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({
    executablePath: process.env.DONGRAN_BROWSER || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    headless: true
  });
  try {
    const page = await browser.newPage({viewport:{width:1440,height:1000}});
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(pathToFileURL(path.join(__dirname,'index.html')).href);
    await page.waitForFunction(() => window.DongranSandbox && window.DongranSettings);
    await page.evaluate(() => window.DongranSettings.open('permissions'));
    assert.match(await page.locator('[data-sandbox-card]').innerText(), /演示模式/);
    assert.equal(await page.locator('[data-sandbox-card] [data-sandbox-refresh]').isDisabled(), true);

    // Mock only the backend boundary to exercise truthful states and fail-closed UI.
    await page.evaluate(async () => {
      window.DongranBackendBase = 'http://127.0.0.1:3210';
      window.sandboxFixture = {
        available:false,protocolVersion:1,platform:'windows',backend:'appcontainer',reason:'执行助手不存在 <script>unsafe()</script>',
        networkIsolation:true,scope:'agent-commands',
        policy:{mode:'required',network:'deny',memoryMb:512,maxProcesses:32,workspace:'snapshot'}
      };
      window.sandboxRequests = 0;
      window.commandRequests = 0;
      window.DongranBackend = {request:async () => {
        window.sandboxRequests++;
        if(window.sandboxFailure)throw window.sandboxFailure;
        await new Promise(resolve=>setTimeout(resolve,30));
        return structuredClone(window.sandboxFixture);
      }};
      window.DongranRuntime = {enabled:true,settingsPanel:()=>{},saveSettings:async()=>{},request:async (route,method) => {
        if(method==='POST'){window.commandRequests++;return {id:'test-run'};}
        return {id:'test-run',status:'completed',exit_code:0,output:'sandbox output',workspacePath:'C:\\tasks\\run-1',syncStatus:'applied'};
      }};
      window.dispatchEvent(new CustomEvent('projectchange',{detail:{id:'project-1',name:'示例项目',path:'D:\\Example'}}));
      await window.DongranSandbox.refresh();
    });
    assert.match(await page.locator('[data-sandbox-reason]').innerText(), /<script>unsafe/);
    assert.equal(await page.locator('[data-sandbox-card] script').count(),0);
    await page.evaluate(() => window.DongranSettings.close());
    await page.locator('#terminal-toggle').click();
    await page.waitForFunction(() => !window.DongranSandbox.snapshot().checking);
    assert.equal(await page.locator('#terminal-input').isDisabled(),true);
    assert.match(await page.locator('#terminal-sandbox-notice').innerText(),/命令已阻止/);
    await page.evaluate(() => {
      document.querySelector('#terminal-input').value='echo test';
      document.querySelector('#terminal-form').dispatchEvent(new Event('submit',{cancelable:true,bubbles:true}));
    });
    assert.equal(await page.evaluate(() => window.commandRequests),0);

    await page.evaluate(async () => {
      window.sandboxFixture.available=true;
      window.sandboxFixture.reason='';
      await window.DongranSandbox.refresh();
    });
    assert.equal(await page.locator('#terminal-input').isDisabled(),false);
    assert.match(await page.locator('#terminal-sandbox').innerText(), /沙箱 · 网络关闭/);
    assert.equal(await page.locator('#terminal-sandbox-notice').isHidden(),true);
    await page.locator('#terminal-input').fill('echo test');
    await page.locator('#terminal-input').press('Enter');
    await page.waitForFunction(() => document.querySelector('#terminal-output').textContent.includes('sandbox output'));
    assert.equal(await page.evaluate(() => window.commandRequests),1);
    assert.match(await page.locator('#terminal-output').innerText(),/文件同步：已同步/);

    await page.evaluate(async () => {
      window.sandboxFixture.networkIsolation=false;
      await window.DongranSandbox.refresh();
    });
    assert.equal(await page.locator('#terminal-input').isDisabled(),true);
    assert.match(await page.locator('#terminal-sandbox-notice').innerText(),/没有提供/);
    await page.evaluate(async () => {
      window.sandboxFixture.networkIsolation=true;
      window.sandboxFixture.protocolVersion=2;
      await window.DongranSandbox.refresh();
    });
    assert.match(await page.locator('#terminal-sandbox-notice').innerText(),/协议版本/);
    await page.evaluate(async () => {
      window.sandboxFailure={status:404,message:'Not Found'};
      await window.DongranSandbox.refresh();
    });
    assert.match(await page.locator('#terminal-sandbox-notice').innerText(),/更新并重启/);

    await page.evaluate(async () => {
      window.sandboxFailure=null;
      window.sandboxFixture.protocolVersion=1;
      await window.DongranSandbox.refresh();
      window.DongranSettings.open('permissions');
    });
    await page.waitForFunction(() => !window.DongranSandbox.snapshot().checking);
    await page.screenshot({path:path.join(__dirname,'../.runtime/sandbox-settings.png'),animations:'disabled'});
    await page.setViewportSize({width:1024,height:768});
    await page.evaluate(() => window.DongranSettings.set('theme','dark'));
    await page.screenshot({path:path.join(__dirname,'../.runtime/sandbox-settings-dark.png'),animations:'disabled'});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),true);
    assert.deepEqual(errors,[]);
    console.log('PASS: sandbox UI blocks unavailable, unsupported and incomplete policies; valid sandbox command output, settings refresh, demo, escaping and desktop layouts.');
  } finally {
    await browser.close();
  }
})().catch(error => {console.error(error);process.exitCode=1;});
