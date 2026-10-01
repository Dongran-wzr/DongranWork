const { chromium } = require('playwright');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const fs = require('node:fs');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({executablePath:'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',headless:true});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:1000},deviceScaleFactor:1});
    const errors = [];
    const network = [];
    page.on('pageerror',error=>errors.push(error.message));
    page.on('request',request=>{if (/^https?:/.test(request.url())) network.push(request.url());});
    fs.mkdirSync(path.join(__dirname,'screenshots'),{recursive:true});
    const screenshot = async name=>{
      await page.waitForFunction(()=>document.querySelector('#toast').hidden);
      return page.screenshot({path:path.join(__dirname,'screenshots',name),animations:'disabled'});
    };
    const waitReady = ()=>page.waitForFunction(()=>window.DongranPages && window.DongranSchedules && window.DongranSettings);
    const tasks = ()=>page.evaluate(()=>window.DongranSchedules.getAll());
    const currentPage = ()=>page.evaluate(()=>window.DongranPages.current());
    const dialogClosed = ()=>page.waitForFunction(()=>!document.querySelector('#dialog').open);
    const select = async (selector,value)=>{
      await page.locator(selector).click();
      await page.locator(`#app-menu [data-value="${value}"]`).click();
      await page.waitForFunction(()=>!document.querySelector('#app-menu').matches(':popover-open'));
    };
    const assertActive = async category=>{
      const selected=page.locator(`.main-nav [data-action="${category}"]`);
      const other=page.locator(category==='knowledge'?'[data-action=\"schedules\"]':'[data-action=\"knowledge\"]');
      assert.equal(await selected.getAttribute('aria-current'),'page');
      assert.equal(await selected.evaluate(element=>element.classList.contains('active')),true);
      assert.equal(await other.getAttribute('aria-current'),null);
      assert.equal(await page.locator('#workspace-page').isVisible(),true);
      assert.equal(await page.locator('.work-body').isVisible(),false);
      assert.equal(await currentPage(),category);
    };
    const assertDesktopLayout = async()=>{
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'Workspace pages must not cause horizontal overflow');
      for (const selector of ['[data-action=\"knowledge\"]','[data-action=\"schedules\"]','#account-profile','[data-page-action=\"back\"]']) {
        const result=await page.locator(selector).evaluate(element=>{
          const rect=element.getBoundingClientRect();
          const hit=document.elementFromPoint(rect.left+rect.width/2,rect.top+rect.height/2);
          return {visible:rect.width>0&&rect.height>0&&rect.top>=0&&rect.bottom<=innerHeight&&rect.left>=0&&rect.right<=innerWidth,clickable:element.contains(hit),selector:element.id};
        });
        assert.equal(result.visible&&result.clickable,true,`Desktop navigation must stay visible and clickable: ${JSON.stringify(result)}`);
      }
      const account=await page.locator('#account-profile [data-account-name]').evaluate(element=>{
        const rect=element.getBoundingClientRect();
        const parent=element.parentElement.getBoundingClientRect();
        return rect.left>=parent.left&&rect.right<=parent.right+1;
      });
      assert.equal(account,true,'The sidebar account name must fit its container');
      assert.equal(await page.evaluate(()=>document.querySelector('.main-nav').getBoundingClientRect().bottom<=document.querySelector('.section-label').getBoundingClientRect().top+1),true,'Main navigation and workspace controls must not overlap');
    };

    await page.goto(pathToFileURL(path.join(__dirname,'index.html')).href);
    await page.evaluate(()=>localStorage.clear());
    await page.reload();
    await waitReady();
    assert.equal(await page.locator('.main-nav [data-action="knowledge"]').count(),1);
    assert.equal(await page.locator('.main-nav [data-action="schedules"]').count(),1);

    await page.locator('[data-action=\"knowledge\"]').click();
    await assertActive('knowledge');
    assert.match(await page.locator('#workspace-page').innerText(),/知识库/);
    assert.match(await page.locator('#workspace-page').innerText(),/待接入/);
    assert.equal(await page.locator('#workspace-page input[type="file"]').count(),0,'The knowledge placeholder must not offer a fake upload workflow');
    assert.equal(await page.locator('#workspace-page .file-entry').count(),0,'The knowledge placeholder must not fabricate indexed project files');
    await screenshot('34-knowledge.png');
    await page.locator('#terminal-toggle').click();
    assert.equal(await page.locator('#terminal-dock').isVisible(),true);
    assert.equal(await page.locator('#terminal-input').isDisabled(),true);
    await assertActive('knowledge');
    await page.locator('#terminal-close').click();
    await page.locator('[data-page-action=\"back\"]').click();
    assert.equal(await page.locator('.work-body').isVisible(),true);
    assert.equal(await page.locator('#workspace-page').isVisible(),false);

    await page.locator('[data-action=\"schedules\"]').click();
    await assertActive('schedules');
    assert.equal((await tasks()).length,0);
    await page.locator('#schedule-add').click();
    await page.locator('#schedule-scope').click();
    assert.equal(await page.locator('#app-menu [data-value="project"]').isDisabled(),true,'Project scope requires an open project');
    await page.keyboard.press('Escape');
    await page.waitForFunction(()=>!document.querySelector('#app-menu').matches(':popover-open'));
    await page.locator('#schedule-save').click();
    assert.equal(await page.locator('#schedule-form-error').isVisible(),true);
    assert.equal((await tasks()).length,0,'Invalid forms must not create a schedule');
    await page.locator('#schedule-name').fill('团队每日进展');
    await page.locator('#schedule-prompt').fill('汇总产品需求与研发进展，整理当天需要跟进的事项。');
    await page.locator('#schedule-time').fill('25:61');
    await page.locator('#schedule-save').click();
    assert.equal(await page.locator('#schedule-form-error').isVisible(),true);
    assert.equal((await tasks()).length,0);
    await page.locator('#schedule-time').fill('09:30');
    await page.locator('#schedule-save').click();
    await dialogClosed();
    const daily=(await tasks())[0];
    assert.equal(daily.name,'团队每日进展');
    assert.equal(daily.scope,'global');
    assert.equal(await page.locator('[data-schedule-id]').count(),1);
    assert.match(await page.locator('#workspace-page').innerText(),/待接入/);
    await page.locator(`[data-schedule-toggle="${daily.id}"]`).click();
    assert.equal((await tasks()).find(item=>item.id===daily.id).enabled,false);
    await page.locator('[data-schedule-filter="enabled"]').click();
    assert.equal(await page.locator('[data-schedule-id]').count(),0);
    await page.locator('[data-schedule-filter="paused"]').click();
    assert.equal(await page.locator('[data-schedule-id]').count(),1);
    await page.locator(`[data-schedule-toggle="${daily.id}"]`).click();
    await page.locator('[data-schedule-filter="all"]').click();
    assert.equal((await tasks()).find(item=>item.id===daily.id).enabled,true);

    await page.locator('#schedule-add').click();
    await page.locator('#schedule-name').fill('版本发布验收');
    await page.locator('#schedule-prompt').fill('按验收标准检查版本交付内容，生成待确认事项。');
    await select('#schedule-frequency','once');
    await page.locator('#schedule-date').fill('2020-01-01');
    await page.locator('#schedule-time').fill('10:00');
    await page.locator('#schedule-save').click();
    assert.equal(await page.locator('#schedule-form-error').isVisible(),true,'An expired one-time schedule must be rejected');
    assert.equal((await tasks()).length,1);
    const future=await page.evaluate(()=>{const date=new Date();date.setDate(date.getDate()+7);return `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`;});
    await page.locator('#schedule-date').fill(future);
    await page.locator('#schedule-save').click();
    await dialogClosed();
    const once=(await tasks()).find(item=>item.name==='版本发布验收');
    assert.ok(once);

    await page.evaluate(()=>openProject('dongran-console',true));
    await page.locator('#prompt').fill('补充一条尚未发送的验收条件。');
    const preserved=await page.evaluate(()=>{
      window.__workspaceMessageNode=document.querySelector('#messages').firstElementChild;
      return {active:state.active,tasks:state.tasks.length,prompt:document.querySelector('#prompt').value,title:document.querySelector('#header-title').textContent};
    });
    await page.locator('[data-action=\"knowledge\"]').click();
    await assertActive('knowledge');
    await page.locator('[data-action=\"schedules\"]').click();
    await assertActive('schedules');
    await page.locator('.utility-nav [data-action="settings"]').click();
    await page.locator('#settings-screen').waitFor({state:'visible'});
    await page.locator('#settings-back').click();
    await page.locator('#settings-screen').waitFor({state:'hidden'});
    await assertActive('schedules');
    await page.locator('[data-page-action=\"back\"]').click();
    const restored=await page.evaluate(()=>({active:state.active,tasks:state.tasks.length,prompt:document.querySelector('#prompt').value,title:document.querySelector('#header-title').textContent,sameNode:window.__workspaceMessageNode===document.querySelector('#messages').firstElementChild}));
    assert.deepEqual(restored,{...preserved,sameNode:true},'Switching feature pages and settings must preserve the task DOM and unsent draft');

    await page.locator('#messages [data-action="run"]').click();
    await page.locator('[data-action="knowledge"]').click();
    await page.waitForFunction(()=>state.tasks.find(task=>task.id===1)?.done===true);
    await assertActive('knowledge');
    assert.equal(await page.locator('#header-title').textContent(),'知识库','Background task completion must not replace the utility page heading');

    await page.locator('[data-action=\"schedules\"]').click();
    await page.locator('#schedule-add').click();
    await page.locator('#schedule-name').fill('项目需求周检');
    await page.locator('#schedule-prompt').fill('检查当前项目的需求文档、验收标准与未完成开发事项。');
    await select('#schedule-scope','project');
    await select('#schedule-frequency','weekly');
    const weekdays=page.locator('[data-schedule-weekday]');
    for (let index=0;index<await weekdays.count();index++) {
      if (await weekdays.nth(index).getAttribute('aria-pressed')==='true') await weekdays.nth(index).click();
    }
    await page.locator('#schedule-time').fill('10:30');
    await page.locator('#schedule-save').click();
    assert.equal(await page.locator('#schedule-form-error').isVisible(),true,'A weekly schedule requires at least one weekday');
    await page.locator('[data-schedule-weekday="1"]').click();
    assert.equal(await page.locator('#schedule-form-error').isVisible(),false,'Choosing a weekday must clear the previous validation message');
    await page.locator('[data-schedule-weekday="3"]').click();
    await page.locator('#schedule-save').click();
    await dialogClosed();
    const weekly=(await tasks()).find(item=>item.name==='项目需求周检');
    assert.equal(weekly.scope,'project');
    assert.equal(weekly.projectId,'demo:dongran-console');
    await page.locator('#schedule-search').fill('需求周检');
    assert.equal(await page.locator('[data-schedule-id]').count(),1);
    await page.locator('#schedule-search').fill('不存在的任务');
    assert.equal(await page.locator('[data-schedule-id]').count(),0);
    await page.locator('#schedule-search').fill('');
    assert.equal(await page.locator('[data-schedule-id]').count(),3);
    await select(`[data-schedule-more="${weekly.id}"]`,'edit');
    await page.locator('#schedule-name').fill('项目需求与测试周检');
    await page.locator('#schedule-time').fill('11:00');
    await screenshot('36-schedule-editor.png');
    await page.locator('#schedule-save').click();
    await dialogClosed();
    assert.equal((await tasks()).find(item=>item.id===weekly.id).name,'项目需求与测试周检');
    await screenshot('35-schedules.png');
    assert.equal(await page.evaluate(()=>state.tasks.length),preserved.tasks,'Saving schedules must not execute agent tasks');
    const stored=await page.evaluate(()=>JSON.parse(localStorage.getItem('dongran.schedules.v1')));
    assert.equal(stored.version,1);
    assert.equal(stored.tasks.length,3);

    await page.locator('.utility-nav [data-action="settings"]').click();
    await page.locator('#settings-screen').waitFor({state:'visible'});
    await select('#view-menu-trigger','show-terminal');
    await page.locator('#settings-screen').waitFor({state:'hidden'});
    await assertActive('schedules');
    assert.equal(await page.locator('#terminal-dock').isVisible(),true,'View > Terminal must reveal the workspace when invoked from settings');
    await page.locator('#terminal-close').click();
    await page.locator('.utility-nav [data-action="settings"]').click();
    await page.locator('#settings-screen').waitFor({state:'visible'});
    await select('#view-menu-trigger','show-artifacts');
    await page.locator('#settings-screen').waitFor({state:'hidden'});
    assert.equal(await currentPage(),null);
    assert.equal(await page.locator('.work-body').isVisible(),true);
    assert.equal(await page.locator('#inspector').isVisible(),true,'View > Artifacts must return to the conversation');
    assert.equal(await page.locator('#prompt').inputValue(),preserved.prompt);

    await page.locator('[data-action=\"knowledge\"]').click();
    await page.locator('#terminal-toggle').click();
    assert.equal(await page.locator('#terminal-input').isDisabled(),false);
    await assertActive('knowledge');
    await page.locator('#terminal-close').click();
    await page.locator('#task-list [data-task="1"]').click();
    assert.equal(await page.locator('.work-body').isVisible(),true);
    assert.equal(await page.locator('#workspace-page').isVisible(),false);
    assert.equal(await page.evaluate(()=>state.active),1);
    await page.locator('[data-action=\"schedules\"]').click();
    await page.locator('.main-nav [data-action="new"]').click();
    assert.equal(await page.locator('.work-body').isVisible(),true);
    assert.equal(await page.locator('#workspace-page').isVisible(),false);
    assert.equal(await page.evaluate(()=>state.active),null);

    await page.evaluate(()=>window.DongranSettings.set('accountNickname','研发与产品协同负责人研发与产品协同负责人Alex'));
    for (const viewport of [{width:1920,height:1080},{width:1440,height:900},{width:1024,height:768},{width:1024,height:680}]) {
      await page.setViewportSize(viewport);
      await page.locator('[data-action=\"knowledge\"]').click();
      await assertDesktopLayout();
      await page.locator('[data-action=\"schedules\"]').click();
      await assertDesktopLayout();
      if (viewport.height===680) {
        await select(`[data-schedule-more="${weekly.id}"]`,'edit');
        await page.locator('#schedule-save').scrollIntoViewIfNeeded();
        assert.equal(await page.locator('#schedule-save').evaluate(element=>{
          const rect=element.getBoundingClientRect();
          return rect.top>=0&&rect.bottom<=innerHeight&&element.contains(document.elementFromPoint(rect.left+rect.width/2,rect.top+rect.height/2));
        }),true,'The editor save action must remain reachable at the minimum desktop height');
        await page.locator('#schedule-cancel').click();
        await dialogClosed();
      }
    }
    await page.setViewportSize({width:1440,height:900});
    await page.evaluate(()=>window.DongranSettings.set('theme','dark'));
    assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
    await assertDesktopLayout();
    const darkContrast=await page.locator('.schedules-page').evaluate(element=>{
      const brightness=color=>{const values=color.match(/[\d.]+/g).map(Number);return values.slice(0,3).reduce((sum,value)=>sum+value,0)/3;};
      let surface=element;
      while (surface.parentElement && ['transparent','rgba(0, 0, 0, 0)'].includes(getComputedStyle(surface).backgroundColor)) surface=surface.parentElement;
      return {text:brightness(getComputedStyle(element).color),background:brightness(getComputedStyle(surface).backgroundColor)};
    });
    assert.ok(darkContrast.text>darkContrast.background+70,`Dark mode must retain readable text: ${JSON.stringify(darkContrast)}`);
    await screenshot('37-schedules-dark.png');
    await page.evaluate(()=>window.DongranSettings.set('theme','light'));
    await page.reload();
    await waitReady();
    await page.locator('[data-action=\"schedules\"]').click();
    assert.equal((await tasks()).length,3);
    assert.equal((await tasks()).find(item=>item.id===weekly.id).projectId,'demo:dongran-console','Reloading without a project must preserve saved project scope');
    await select(`[data-schedule-more="${daily.id}"]`,'delete');
    await page.locator('#schedule-confirm-delete').click();
    await dialogClosed();
    assert.equal((await tasks()).length,2);
    await page.reload();
    await waitReady();
    assert.equal((await tasks()).length,2);
    assert.deepEqual(errors,[]);
    assert.deepEqual(network,[],'Knowledge and schedule prototypes must not call external services');
    console.log('PASS: knowledge/schedule navigation, active states, task/draft preservation, settings return, task navigation, terminal access, no-project states, schedule CRUD/filter/search/scope/date validation/persistence, desktop sidebar layout; no backend requests or browser errors.');
  } finally {
    await browser.close();
  }
})().catch(error=>{console.error(error);process.exitCode=1;});
