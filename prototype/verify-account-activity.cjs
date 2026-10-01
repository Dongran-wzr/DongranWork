const { chromium } = require('playwright');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const fs = require('node:fs');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe', headless: true });
  try {
    const page = await browser.newPage({ viewport: {width:1440,height:1000}, deviceScaleFactor:1 });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    fs.mkdirSync(path.join(__dirname,'screenshots'), {recursive:true});
    const screenshot = name => page.screenshot({path:path.join(__dirname,'screenshots',name),animations:'disabled'});
    const get = key => page.evaluate(key => window.DongranSettings.get(key),key);
    const waitReady = () => page.waitForFunction(() => window.DongranSettings && window.DongranActivity && document.querySelector('.activity-map'));
    const closeDialog = () => page.waitForFunction(() => !document.querySelector('#dialog').open);
    const openSettings = async category => {
      await page.evaluate(category => window.DongranSettings.open(category),category);
      await page.locator('#settings-screen').waitFor({state:'visible'});
    };
    const closeSettings = async () => {
      await page.evaluate(() => window.DongranSettings.close());
      await page.locator('#settings-screen').waitFor({state:'hidden'});
    };
    const assertNoOverflow = async () => {
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),true,'Desktop content must not overflow the viewport');
      const names = await page.locator('[data-account-name]').evaluateAll(elements => elements.filter(element => element.getBoundingClientRect().width > 0).map(element => {
        const bounds = element.getBoundingClientRect();
        const parent = element.parentElement.getBoundingClientRect();
        return {fits:bounds.left >= parent.left - 1 && bounds.right <= parent.right + 1,element:element.outerHTML.slice(0,200),left:bounds.left,right:bounds.right,parentLeft:parent.left,parentRight:parent.right,parentClass:element.parentElement.className};
      }));
      assert.equal(names.every(item=>item.fits),true,`Account names must stay inside their container: ${JSON.stringify(names.filter(item=>!item.fits))}`);
    };

    await page.goto(pathToFileURL(path.join(__dirname,'index.html')).href);
    await page.evaluate(() => localStorage.clear());
    await page.reload();
    await waitReady();
    const map = page.locator('.activity-map').first();
    assert.equal(await map.locator('img').count(),0,'The activity map must use interactive cells rather than an image');
    assert.equal(await map.locator('button.activity-cell').count(),196);
    assert.equal(await map.getAttribute('data-sample'),'true','An empty account shows clearly identified sample activity');
    assert.deepEqual(await page.evaluate(() => window.DongranActivity.getCounts()),{});

    for (const width of [1024,1440,1920]) {
      await page.setViewportSize({width,height:1000});
      await assertNoOverflow();
      const grid = await map.locator('.activity-cell').evaluateAll(cells => ({
        columns:new Set(cells.map(cell => Math.round(cell.getBoundingClientRect().left))).size,
        rows:new Set(cells.map(cell => Math.round(cell.getBoundingClientRect().top))).size,
        dimensions:cells.every(cell => { const rect=cell.getBoundingClientRect();return rect.width>0 && rect.height>0; })
      }));
      assert.equal(grid.columns,28,`The map must keep 28 week columns at ${width}px`);
      assert.equal(grid.rows,7,`The map must keep 7 day rows at ${width}px`);
      assert.equal(grid.dimensions,true);
    }
    await page.setViewportSize({width:1440,height:1000});
    await map.locator('.activity-cell:not(:disabled)').last().hover();
    await page.locator('.activity-tooltip:visible').waitFor();
    assert.match(await page.locator('.activity-tooltip:visible').innerText(),/示例/);
    await screenshot('31-home-activity.png');
    await page.mouse.move(10,10);

    await page.evaluate(() => {
      const host = document.createElement('div');
      host.id = 'activity-verification';
      host.style.cssText = 'position:fixed;top:140px;left:120px;right:120px;padding:30px;background:white;z-index:2;';
      document.body.append(host);
      window.DongranActivity.render(host,{date:'2026-10-01',counts:{'2026-09-30':2,'2026-10-01':7,'2026-10-02':9}});
    });
    const fixture = page.locator('#activity-verification');
    const cells = await fixture.locator('.activity-cell').evaluateAll(elements => elements.map(element => ({date:element.dataset.date,count:Number(element.dataset.count),level:Number(element.dataset.level),disabled:element.disabled,tag:element.tagName,label:element.getAttribute('aria-label')})));
    assert.equal(cells.length,196);
    assert.equal(new Set(cells.map(cell => cell.date)).size,196);
    assert.equal(cells[0].date,'2026-03-22');
    assert.equal(cells.at(-1).date,'2026-10-03');
    assert.equal(cells.every(cell => /^\d{4}-\d{2}-\d{2}$/.test(cell.date) && cell.tag === 'BUTTON' && cell.label),true);
    for (let index=1;index<cells.length;index++) assert.equal(Date.parse(cells[index].date)-Date.parse(cells[index-1].date),86400000);
    assert.equal(cells.find(cell=>cell.date==='2026-10-01').count,7);
    assert.equal(cells.find(cell=>cell.date==='2026-10-01').level,5);
    assert.equal(cells.find(cell=>cell.date==='2026-09-30').level,2);
    assert.equal(cells.find(cell=>cell.date==='2026-10-02').count,0,'Future dates must not display activity');
    assert.equal(cells.filter(cell=>cell.disabled).length,2);
    const todayCell = fixture.locator('[data-date="2026-10-01"]');
    await todayCell.focus();
    await page.locator('.activity-tooltip:visible').waitFor();
    assert.match(await page.locator('.activity-tooltip:visible').innerText(),/7/);
    assert.equal(await todayCell.evaluate(element=>element===document.activeElement),true);
    const tooltipFits = await page.locator('.activity-tooltip:visible').evaluate(element => {const rect=element.getBoundingClientRect();return rect.left>=0&&rect.top>=0&&rect.right<=innerWidth&&rect.bottom<=innerHeight;});
    assert.equal(tooltipFits,true);
    await page.evaluate(() => {
      const host=document.getElementById('activity-verification');
      window.DongranActivity.render(host,{date:'2026-10-01',counts:{'2026-10-01':1}});
    });
    assert.equal(await fixture.locator('.activity-cell').count(),196,'Repeated render must replace the map');
    assert.equal(await fixture.locator('[data-date="2026-10-01"]').getAttribute('data-count'),'1');
    await page.evaluate(() => document.getElementById('activity-verification').remove());

    const activity = await page.evaluate(() => {
      const now=new Date();
      const today=`${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}`;
      window.DongranActivity.mark('verify-project-a');
      window.DongranActivity.mark('verify-project-a');
      window.DongranActivity.mark('verify-project-b');
      return {today,global:window.DongranActivity.getCounts(),a:window.DongranActivity.getCounts('verify-project-a'),b:window.DongranActivity.getCounts('verify-project-b'),missing:window.DongranActivity.getCounts('no-such-project'),stored:JSON.parse(localStorage.getItem('dongran.activity.v1'))};
    });
    assert.equal(activity.global[activity.today],3);
    assert.equal(activity.a[activity.today],2);
    assert.equal(activity.b[activity.today],1);
    assert.deepEqual(activity.missing,{});
    assert.equal(activity.stored.version,1);
    assert.equal(activity.stored.projects.length,2);
    await page.reload();
    await waitReady();
    assert.equal(await page.evaluate(today=>window.DongranActivity.getCounts()[today],activity.today),3);
    assert.equal(await page.locator('.activity-map').first().getAttribute('data-sample'),'false');

    await openSettings('appearance');
    await page.locator('#setting-showActivity').click();
    assert.equal(await get('showActivity'),false);
    await closeSettings();
    assert.equal(await page.locator('.activity-map').first().isVisible(),false);
    assert.equal(await page.locator('.activity-tooltip:visible').count(),0);
    await openSettings('appearance');
    await page.locator('#setting-showActivity').click();
    await closeSettings();
    assert.equal(await page.locator('.activity-map').first().isVisible(),true);

    await page.evaluate(() => openProject('activity-project',false,'verify-project-ui'));
    assert.equal(await page.evaluate(today=>window.DongranActivity.getCounts()[today],activity.today),3,'Opening a project must not create activity');
    await page.locator('#prompt').fill('检查需求文档中的验收标准');
    await page.locator('#send').click();
    assert.equal(await page.evaluate(today=>window.DongranActivity.getCounts('verify-project-ui')[today],activity.today),1,'Sending a new task must add exactly one project event');
    assert.equal(await page.evaluate(today=>window.DongranActivity.getCounts()[today],activity.today),4);
    await page.locator('[data-action="new"]').first().click();
    assert.equal(await page.evaluate(today=>window.DongranActivity.getCounts()[today],activity.today),4,'Opening a new task view must not add activity');

    await openSettings('account');
    const nickname='研发与产品协同负责人研发与产品协同负责人Alex';
    assert.ok(nickname.length<=32 && nickname.length>20);
    await page.locator('#account-nickname').fill(nickname);
    await page.locator('#account-nickname').press('Tab');
    assert.equal(await get('accountNickname'),nickname);
    assert.equal(await page.locator('[data-account-name]').evaluateAll((elements,expected)=>elements.length>0&&elements.every(element=>element.textContent===expected),nickname),true);
    await page.locator('#account-nickname').fill('');
    await page.locator('#account-nickname').press('Tab');
    assert.equal(await get('accountNickname'),nickname,'An empty nickname must preserve the saved profile');
    assert.equal(await page.locator('#account-nickname-error').isVisible(),true);
    await page.locator('#account-nickname').fill(nickname);
    await page.locator('#account-nickname').press('Tab');
    await page.locator('#account-title').fill('产品与研发协同');
    await page.locator('#account-title').press('Tab');
    await page.locator('#account-bio').fill('把需求、实现和验证留在同一个项目中。');
    await page.locator('#account-bio').press('Tab');

    const imageData = await page.evaluate(() => {
      const canvas=document.createElement('canvas');
      canvas.width=512; canvas.height=384;
      const context=canvas.getContext('2d');
      context.fillStyle='#d9e4e7'; context.fillRect(0,0,512,384);
      context.fillStyle='#416574'; context.beginPath();context.arc(256,171,76,0,Math.PI*2);context.fill();
      context.beginPath();context.ellipse(256,367,153,118,0,0,Math.PI*2);context.fill();
      return canvas.toDataURL('image/png').split(',')[1];
    });
    const upload={name:'private-client-photo.png',mimeType:'image/png',buffer:Buffer.from(imageData,'base64')};
    await page.locator('#account-avatar-file').setInputFiles(upload);
    await page.locator('#account-crop-canvas').waitFor({state:'visible'});
    await page.locator('#account-crop-zoom').evaluate(input => {input.value='1.25';input.dispatchEvent(new Event('input',{bubbles:true}));});
    assert.equal(await page.locator('#account-crop-canvas').evaluate(canvas=>canvas.getContext('2d').getImageData(128,128,1,1).data[3]>0),true,'The crop preview must contain decoded image pixels');
    await page.locator('#account-crop-save').click();
    await closeDialog();
    const avatar=await get('accountAvatar');
    assert.match(avatar,/^data:image\/png;base64,/);
    const dimensions = await page.evaluate(async source => {const image=new Image();image.src=source;await image.decode();return {width:image.naturalWidth,height:image.naturalHeight};},avatar);
    assert.deepEqual(dimensions,{width:256,height:256});
    const syncedAvatars = await page.locator('[data-account-avatar]').evaluateAll((elements,expected)=>elements.length>0&&elements.every(element=>element.querySelector('img')?.getAttribute('src')===expected),avatar);
    assert.equal(syncedAvatars,true);

    for (const invalid of [
      {name:'not-an-image.txt',mimeType:'text/plain',buffer:Buffer.from('not an image')},
      {name:'damaged.png',mimeType:'image/png',buffer:Buffer.from('invalid PNG contents')},
      {name:'too-large.png',mimeType:'image/png',buffer:Buffer.alloc(5*1024*1024+1)}
    ]) {
      await page.locator('#account-avatar-file').setInputFiles(invalid);
      await page.waitForFunction(() => document.querySelector('#account-avatar-error')?.textContent.trim().length>0);
      assert.equal(await get('accountAvatar'),avatar,'Invalid uploads must preserve the current avatar');
      assert.equal(await page.locator('#account-crop-canvas:visible').count(),0);
    }
    await page.locator('#account-avatar-file').setInputFiles(upload);
    await page.locator('#account-crop-canvas').waitFor({state:'visible'});
    await page.locator('#account-crop-cancel').click();
    await closeDialog();
    assert.equal(await get('accountAvatar'),avatar,'Cancelling cropping must preserve the existing avatar');
    await page.locator('#account-nickname').fill('林知远');
    await page.locator('#account-nickname').press('Tab');
    await screenshot('32-settings-account.png');
    await closeSettings();
    await screenshot('33-account-home.png');
    await page.evaluate(value=>window.DongranSettings.set('accountNickname',value),nickname);
    for (const width of [1024,1440,1920]) {
      await page.setViewportSize({width,height:1000});
      await assertNoOverflow();
      await openSettings('account');
      await assertNoOverflow();
      await closeSettings();
    }
    await page.reload();
    await waitReady();
    assert.equal(await get('accountNickname'),nickname);
    assert.equal(await get('accountAvatar'),avatar);
    const saved=await page.evaluate(()=>JSON.parse(localStorage.getItem('dongran.settings.v1')));
    assert.deepEqual(Object.keys(saved.settings).filter(key=>key.startsWith('account')).sort(),['accountAvatar','accountBio','accountNickname','accountTitle']);
    assert.equal(JSON.stringify(saved).includes('private-client-photo.png'),false,'Original upload filenames must not be persisted');
    assert.equal(Object.keys(saved.settings).some(key=>/api.?key|password|secret|access.?token/i.test(key)),false,'Account preferences must not add credential fields');

    await openSettings('account');
    await page.locator('#account-avatar-remove').click();
    await page.waitForFunction(()=>window.DongranSettings.get('accountAvatar')==='');
    assert.equal(await page.locator('[data-account-avatar] img').count(),0);
    await page.reload();
    await waitReady();
    assert.equal(await get('accountAvatar'),'');
    assert.equal(await get('accountNickname'),nickname);
    assert.deepEqual(errors,[]);
    console.log('PASS: 196 interactive activity dates, 28x7 desktop grid, sample/real counts, tooltip hover/focus, future dates, persistence/project isolation, task tracking, activity visibility, account profile/avatar upload/crop/cancel/invalid/removal/persistence, desktop layout; no browser errors.');
  } finally {
    await browser.close();
  }
})().catch(error => {console.error(error);process.exitCode=1;});
