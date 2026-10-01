const { chromium } = require('playwright');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe', headless: true });
  try {
    const page = await browser.newPage({ viewport: {width:1440,height:1000} });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(pathToFileURL(path.join(__dirname,'index.html')).href);
    await page.evaluate(() => localStorage.removeItem('dongran.settings.v1'));
    await page.reload();
    await page.waitForFunction(() => window.DongranSettings && window.DongranMemory);
    const get = key => page.evaluate(key => window.DongranSettings.get(key),key);
    const open = async category => {
      await page.evaluate(category => window.DongranSettings.open(category),category);
      await page.locator('#settings-screen').waitFor({state:'visible'});
    };
    const closedDialog = () => page.waitForFunction(() => !document.querySelector('#dialog').open);
    const select = async (selector,value) => {
      await page.locator(selector).scrollIntoViewIfNeeded();
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      await page.locator(selector).click();
      await page.locator(`#app-menu [data-value="${value}"]`).click();
      await page.waitForFunction(() => !document.querySelector('#app-menu').matches(':popover-open'));
    };
    const shot = name => page.screenshot({path:path.join(__dirname,'screenshots',name),animations:'disabled'});
    const memory = async (title,content) => {
      await page.locator('#memory-add').click();
      await page.locator('#memory-title').fill(title);
      await page.locator('#memory-content').fill(content);
      await page.locator('#memory-save').click();
      await closedDialog();
    };

    await open('memory');
    assert.equal(await page.locator('[data-settings-category]').count(),15);
    await page.locator('#memory-project-tab').click();
    assert.equal(await page.locator('#memory-add').isDisabled(),true);
    await page.locator('#memory-global-tab').click();
    await memory('交付约定','需求与代码变更都要附验收标准。');
    await page.locator('[data-memory-edit]').click();
    await page.locator('#memory-content').fill('需求与代码变更都要附验收标准和验证结果。');
    await page.locator('#memory-save').click();
    await closedDialog();
    assert.match((await get('memoryEntries'))[0].content,/验证结果/);
    await page.evaluate(async () => {
      await window.DongranSettings.close();
      openProject('project-console',false,'project-a');
    });
    await open('memory');
    await page.locator('#memory-project-tab').click();
    await memory('权限边界','访客只能查看，不能编辑项目内容。');
    assert.equal(await page.evaluate(() => window.DongranMemory.getEffectiveEntries().length),2);
    await page.locator('#memory-inherit-global').click();
    assert.equal(await page.evaluate(() => window.DongranMemory.getEffectiveEntries().length),1);
    await page.locator('#memory-inherit-global').click();
    await shot('22-settings-memory.png');
    await page.evaluate(async () => {
      await window.DongranSettings.close();
      openProject('project-console',false,'project-b');
    });
    await open('memory');
    assert.equal(await page.locator('[data-memory-entry]').count(),0,'Same-name projects must have isolated memory');
    assert.equal(await page.evaluate(() => window.DongranMemory.getEffectiveEntries().length),1);
    await memory('另一个项目','此条目只属于项目 B。');
    await page.locator('#memory-clear').click();
    await page.locator('#memory-confirm-clear').click();
    await closedDialog();
    assert.equal((await get('memoryEntries')).length,2,'Clearing B must preserve A and global entries');
    await page.evaluate(async () => {
      await window.DongranSettings.close();
      openProject('project-console',false,'project-a');
    });
    await page.locator('#prompt').fill('补充成员权限验收标准');
    await page.locator('#send').click();
    assert.equal(await page.evaluate(() => state.tasks[0].memory.length),2);
    await open('memory');
    await page.locator('#memory-global-tab').click();
    await page.locator('#memory-search').fill('不存在的记忆');
    assert.equal(await page.locator('[data-memory-entry]').count(),0);
    await page.locator('#memory-search').fill('');

    await open('extensions');
    await page.locator('[data-integration-tab="available"]').click();
    await page.locator('[data-integration-action="extension-add"][data-integration-id="product-docs"]').click();
    await page.locator('[data-integration-tab="added"]').click();
    await page.locator('[data-integration-action="toggle"]').click();
    assert.equal((await get('installedExtensions'))[0].enabled,false);
    await page.locator('[data-integration-action="toggle"]').click();
    await shot('23-settings-extensions.png');

    await open('hooks');
    await page.locator('[data-integration-action="hook-add"]').click();
    await page.locator('#integration-name').fill('完成后运行检查');
    await page.locator('#integration-command').fill('npm run lint');
    await select('#integration-event','after-task');
    await page.locator('#integration-timeout').fill('60');
    await page.locator('#integration-editor button[type="submit"]').click();
    await closedDialog();
    assert.equal((await get('automationHooks'))[0].event,'after-task');
    await select('[data-integration-action="more"]','edit');
    await page.locator('#integration-command').fill('npm run lint && npm test');
    await page.locator('#integration-editor button[type="submit"]').click();
    await closedDialog();
    await shot('24-settings-hooks.png');

    await open('connections');
    await select('[data-integration-action="connection-add"]','mcp-http');
    await page.locator('#integration-name').fill('团队知识库');
    await page.locator('#integration-url').fill('javascript:alert(1)');
    await page.locator('#integration-editor button[type="submit"]').click();
    assert.equal(await page.locator('#integration-form-error').isVisible(),true);
    await page.locator('#integration-url').fill('https://example.com/mcp');
    await page.locator('#integration-editor button[type="submit"]').click();
    await closedDialog();
    assert.equal((await get('serviceConnections')).length,1);
    assert.match(await page.locator('.integration-state').innerText(),/未连接/);
    await shot('25-settings-connections.png');
    await select('[data-integration-action="more"]','remove');
    await page.locator('[data-integration-action="remove-confirm"]').click();
    await closedDialog();
    assert.equal((await get('serviceConnections')).length,0);

    await open('git');
    await page.locator('#setting-gitBranchPrefix').fill('work/');
    await page.locator('#setting-gitBranchPrefix').press('Tab');
    await select('#setting-worktreeMode','manual');
    assert.equal(await get('gitBranchPrefix'),'work/');
    await shot('26-settings-git.png');

    await page.locator('#settings-search').fill('MCP');
    assert.equal(await page.locator('#settings-content [data-settings-category="connections"]').count(),1);
    await page.locator('#settings-content [data-settings-category="connections"]').click();
    assert.equal(await page.locator('#settings-heading').innerText(),'连接');

    await open('appearance');
    await page.locator('[data-theme-preset="forest"]').click();
    await page.waitForFunction(() => document.documentElement.style.getPropertyValue('--accent') === '#43785D');
    await page.locator('#theme-accent-input').fill('#XYZ123');
    await page.locator('#theme-accent-input').press('Tab');
    assert.equal(await page.locator('#theme-accent-input').getAttribute('aria-invalid'),'true');
    await page.locator('#theme-accent-input').fill('#448C8A');
    await page.locator('#theme-accent-input').press('Tab');
    await page.locator('#theme-name-input').fill('团队青瓷');
    await page.locator('#theme-save').click();
    assert.equal((await get('savedThemes'))[0].color,'#448C8A');
    const themeId = (await get('savedThemes'))[0].id;
    await page.locator('[data-theme-preset="neutral"]').click();
    await select('#saved-theme-trigger',themeId);
    await page.waitForFunction(() => document.documentElement.style.getPropertyValue('--accent') === '#448C8A');
    await page.locator('.settings-main').evaluate(element => {element.scrollTop=0;});
    await shot('27-settings-themes.png');

    await open('pet');
    await page.waitForFunction(() => {
      const canvas=document.getElementById('pet-preview');
      return canvas && canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height).data.some((value,index)=>index%4===3 && value>0);
    });
    const firstRobot = await page.locator('#pet-preview').evaluate(canvas => canvas.toDataURL());
    await page.locator('[data-pet-style="pixel"]').click();
    await page.waitForFunction(previous => document.getElementById('pet-preview').toDataURL() !== previous,firstRobot);
    await page.locator('#setting-petEnabled').click();
    await page.locator('#setting-petName').fill('小然');
    await page.locator('#setting-petName').press('Tab');
    await page.locator('.settings-main').evaluate(element => {element.scrollTop=0;});
    await shot('28-settings-pet.png');
    await page.evaluate(() => window.DongranSettings.close());
    await page.locator('#dongran-pet').waitFor({state:'visible'});
    const livePixels = await page.locator('#pet-live-canvas').evaluate(canvas => canvas.toDataURL());
    await page.waitForFunction(previous => document.getElementById('pet-live-canvas').toDataURL() !== previous,livePixels);
    const box = await page.locator('#pet-avatar').boundingBox();
    await page.mouse.move(box.x+box.width/2,box.y+box.height/2);
    await page.mouse.down();
    await page.mouse.move(box.x+box.width/2-120,box.y+box.height/2-60,{steps:8});
    await page.mouse.up();
    assert.ok((await get('petCustomPosition')).x<1);
    await page.waitForTimeout(350);
    await page.locator('#pet-avatar').click();
    assert.equal(await page.locator('#pet-bubble').isVisible(),true);
    await shot('29-workspace-pet.png');
    await open('pet');
    assert.equal(await page.locator('#dongran-pet').isVisible(),false);
    await page.locator('#setting-petDnd').click();
    await page.waitForFunction(() => document.getElementById('pet-preview').dataset.activity === 'quiet');
    const quietPixels = await page.locator('#pet-preview').evaluate(canvas => canvas.toDataURL());
    await page.waitForTimeout(250);
    assert.equal(await page.locator('#pet-preview').evaluate(canvas=>canvas.toDataURL()),quietPixels);

    await page.reload();
    await page.waitForFunction(() => window.DongranSettings);
    assert.equal((await get('memoryEntries')).length,2);
    assert.equal((await get('automationHooks'))[0].command,'npm run lint && npm test');
    assert.equal((await get('installedExtensions'))[0].id,'product-docs');
    assert.equal(await get('worktreeMode'),'manual');
    assert.equal((await get('savedThemes'))[0].name,'团队青瓷');
    assert.equal(await get('petEnabled'),true);
    await open('data');
    const downloadReady = page.waitForEvent('download');
    await page.locator('#settings-export').click();
    const exported = await downloadReady;
    const chunks=[];
    for await(const chunk of await exported.createReadStream())chunks.push(chunk);
    const document=JSON.parse(Buffer.concat(chunks).toString('utf8'));
    const importSettings = async payload => {
      await page.locator('#settings-import-file').setInputFiles({name:'settings.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(payload))});
    };
    await importSettings({version:1,settings:{fontSize:15}});
    await page.waitForFunction(() => window.DongranSettings.get('fontSize') === 15);
    assert.equal((await get('memoryEntries')).length,2,'Importing old preferences must preserve existing memory');
    await importSettings(document);
    await page.waitForFunction(() => window.DongranSettings.get('savedThemes').length === 1);
    assert.equal((await get('memoryEntries')).length,2);
    assert.equal((await get('automationHooks')).length,1);
    await open('appearance');
    await page.locator('#theme-delete').click();
    await page.locator('#theme-delete-cancel').click();
    await closedDialog();
    assert.equal((await get('savedThemes')).length,1);
    await page.locator('#theme-delete').click();
    await page.locator('#theme-delete-confirm').click();
    await closedDialog();
    assert.equal((await get('savedThemes')).length,0);
    assert.equal(await get('themePreset'),'neutral');
    await page.evaluate(() => window.DongranSettings.reset());
    assert.equal((await get('memoryEntries')).length,2,'Reset preferences must preserve memory data');
    assert.equal((await get('automationHooks')).length,0);
    assert.equal(await get('worktreeMode'),'per-task');
    for (const width of [1024,1440,1920]) {
      await page.setViewportSize({width,height:width===1024?768:1080});
      for (const category of ['memory','extensions','hooks','connections','git','appearance','pet']) {
        await open(category);
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),true,`${category} overflows at ${width}`);
        assert.equal(await page.locator('select:visible').count(),0);
      }
      if(width===1024) await shot('30-settings-pet-desktop.png');
    }
    assert.deepEqual(errors,[]);
    console.log('PASS: scoped memory CRUD/isolation/inheritance/task snapshots, extensions, hooks, connections, Git preferences, saved themes, canvas pet rendering/animation/drag/DND, persistence/reset and desktop layouts.');
  } finally { await browser.close(); }
})().catch(error => {console.error(error);process.exitCode=1;});
