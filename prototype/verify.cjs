const { chromium } = require('playwright');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const fs = require('node:fs');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 960 }, deviceScaleFactor: 1 });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    fs.mkdirSync(path.join(__dirname, 'screenshots'), { recursive: true });
    const screenshot = name => page.screenshot({ path: path.join(__dirname, 'screenshots', name), animations: 'disabled' });
    const menu = page.locator('#app-menu');
    const waitMenuOpen = () => page.waitForFunction(() => document.querySelector('#app-menu')?.matches(':popover-open'));
    const waitMenuClosed = () => page.waitForFunction(() => !document.querySelector('#app-menu')?.matches(':popover-open'));
    const waitDialogClosed = () => page.waitForFunction(() => !document.querySelector('#dialog').open);
    const closeSettings = async () => {
      await page.locator('#settings-back').click();
      await page.locator('#settings-screen').waitFor({ state: 'hidden' });
      await page.locator('.app').waitFor({ state: 'visible' });
    };
    const getSetting = key => page.evaluate(key => window.DongranSettings.get(key), key);
    const settingsCategory = category => page.locator(`[data-settings-category="${category}"]`).click();
    const selectSetting = async (key, value) => {
      await page.locator(`#setting-${key}`).click();
      await waitMenuOpen();
      await menu.locator(`[data-value="${value}"]`).click();
      await waitMenuClosed();
      assert.equal(await getSetting(key), value);
    };
    const setRange = async (key, value) => {
      await page.locator(`#setting-${key}`).evaluate((input, next) => {
        input.value = String(next);
        input.dispatchEvent(new Event('input', { bubbles: true }));
        input.dispatchEvent(new Event('change', { bubbles: true }));
      }, value);
      assert.equal(await getSetting(key), value);
    };
    const assertDesktopLayout = async () => {
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'Desktop window must not overflow horizontally');
      assert.equal(await page.locator('select:visible').count(), 0);
    };
    const assertVisibleInViewport = async selector => {
      assert.equal(await page.locator(selector).isVisible(), true);
      assert.equal(await page.locator(selector).evaluate(el => {
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0 && r.left >= 0 && r.right <= innerWidth && r.top >= 0 && r.bottom <= innerHeight && el.contains(document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2));
      }), true, `${selector} must be visible and unobstructed`);
    };

    await page.goto(pathToFileURL(path.join(__dirname, 'index.html')).href);
    await page.waitForFunction(() => window.DongranSettings && document.querySelectorAll('svg').length > 5);
    await page.evaluate(() => localStorage.removeItem('dongran.settings.v1'));
    await page.reload();
    await page.waitForFunction(() => window.DongranSettings && document.querySelectorAll('svg').length > 5);
    assert.equal(await page.locator('.activity-map .activity-cell').count(), 196);
    assert.equal(await page.locator('.activity-map img').count(), 0);
    await assertDesktopLayout();
    await screenshot('01-open-project.png');
    await page.setViewportSize({ width: 1920, height: 1080 });
    await screenshot('10-wide-workspace.png');
    await page.setViewportSize({ width: 1440, height: 960 });

    await page.locator('#file-menu-trigger').click();
    await waitMenuOpen();
    assert.equal(await menu.locator('[role="separator"]').count(), 2);
    assert.equal(await menu.locator('small').count(), 0);
    await screenshot('14-file-menu.png');
    await menu.screenshot({ path: path.join(__dirname, 'screenshots', '15-file-menu-detail.png'), animations: 'disabled' });
    await page.keyboard.press('ArrowRight');
    await page.waitForFunction(() => document.querySelector('#edit-menu-trigger').getAttribute('aria-expanded') === 'true');
    await page.locator('#view-menu-trigger').hover();
    await page.waitForFunction(() => document.querySelector('#view-menu-trigger').getAttribute('aria-expanded') === 'true');
    await page.keyboard.press('Escape');
    await waitMenuClosed();
    assert.equal(await page.locator('#view-menu-trigger').evaluate(el => el === document.activeElement), true);
    await page.locator('#file-menu-trigger').click();
    await waitMenuOpen();
    await menu.locator('[data-value="settings"]').click();
    await page.locator('#settings-screen').waitFor({ state: 'visible' });
    assert.equal(await page.locator('.app').isVisible(), false);
    for (const [key, expected] of Object.entries({ theme: 'light', density: 'comfortable', fontSize: 14, sidebarWidth: 274, showSuggestions: true, defaultModel: 'Auto' })) {
      assert.equal(await getSetting(key), expected, `Default ${key}`);
    }
    await page.setViewportSize({ width: 1920, height: 1080 });
    await screenshot('17-settings-general.png');
    await page.setViewportSize({ width: 1440, height: 960 });
    for (const category of ['appearance', 'models', 'agents', 'permissions', 'terminal', 'shortcuts', 'data', 'general']) {
      await settingsCategory(category);
      await assertDesktopLayout();
    }
    assert.deepEqual(await page.evaluate(async () => {
      const first = window.DongranSettings.close();
      const second = window.DongranSettings.close();
      window.DongranSettings.open();
      return { same: first === second, closed: await first, visible: !document.querySelector('#settings-screen').hidden };
    }), { same: true, closed: false, visible: true });
    await closeSettings();
    assert.equal(await page.locator('.app').isVisible(), true);

    await page.locator('#mode-trigger').focus();
    await page.locator('#mode-trigger').press('ArrowDown');
    await waitMenuOpen();
    await page.keyboard.press('End');
    assert.equal(await page.evaluate(() => document.activeElement.dataset.value), '允许项目内修改');
    await page.keyboard.press('Home');
    assert.equal(await page.evaluate(() => document.activeElement.dataset.value), '修改前询问');
    await page.keyboard.press('Escape');
    await waitMenuClosed();
    assert.equal(await page.locator('#mode-trigger').evaluate(el => el === document.activeElement), true);
    await page.locator('.utility-nav [data-action="team"]').click();
    await page.waitForFunction(() => document.querySelector('#dialog').classList.contains('is-visible'));
    assert.equal(await page.evaluate(() => {
      document.querySelector('#dialog .dialog-heading [data-action="close-dialog"]').click();
      return document.querySelector('#dialog').classList.contains('is-closing');
    }), true);
    await waitDialogClosed();

    await page.locator('[data-action="demo"]').click();
    await screenshot('02-agent-workspace.png');
    await page.locator('.title-actions [data-action="panel"]').click();
    await page.locator('[data-action="edit-doc"]').click();
    await page.locator('#doc-content').press('End');
    await page.locator('#doc-content').press('Enter');
    await page.locator('#doc-content').pressSequentially('Review note');
    await page.locator('[data-action="edit-doc"]').click();
    await page.locator('.tabs [data-tab="diff"]').click();
    assert.equal(await page.locator('.diff-file').count(), 4);
    await screenshot('03-code-changes.png');
    await page.locator('.tabs [data-tab="doc"]').click();
    assert.match(await page.locator('#doc-content').innerText(), /Review note/);
    await page.locator('[data-action="run"]').click();
    await page.waitForFunction(() => document.querySelector('.terminal')?.textContent.includes('12 passed'));
    await screenshot('04-validation.png');
    await page.locator('.title-actions [data-action="panel"]').click();

    await page.locator('#toast').waitFor({ state: 'hidden' });
    const draft = '补充要求：访客只能查看项目，保留未发送草稿。';
    await page.locator('#prompt').fill(draft);
    const taskTitle = await page.locator('#header-title').innerText();
    const taskCount = await page.locator('.task-item').count();
    await page.locator('#settings-button').click();
    await page.locator('#settings-screen').waitFor({ state: 'visible' });
    await settingsCategory('general');
    await page.locator('#setting-showSuggestions').click();
    assert.equal(await getSetting('showSuggestions'), false);
    await page.locator('#settings-search').fill('模型');
    assert.equal(await page.locator('#setting-defaultModel').isVisible(), true);
    await page.locator('#settings-search').fill('');
    await settingsCategory('appearance');
    await setRange('fontSize', 16);
    await setRange('sidebarWidth', 300);
    await page.locator('#setting-density [data-setting-value="compact"]').click();
    assert.equal(await getSetting('density'), 'compact');
    assert.equal(await page.locator('html').getAttribute('data-density'), 'compact');
    assert.equal(await page.locator('html').evaluate(el => getComputedStyle(el).getPropertyValue('--ui-font-size').trim()), '16px');
    assert.equal(await page.locator('.sidebar').evaluate(el => el.style.width), '300px');
    await screenshot('18-settings-appearance.png');
    await page.locator('#setting-theme [data-setting-value="dark"]').click();
    assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark');
    await screenshot('20-settings-dark.png');
    await page.locator('#setting-theme [data-setting-value="light"]').click();
    assert.equal(await page.locator('html').getAttribute('data-theme'), 'light');
    await settingsCategory('models');
    await selectSetting('provider', '本地模型');
    await selectSetting('defaultModel', '自定义模型');
    await screenshot('19-settings-models.png');
    await settingsCategory('appearance');
    await page.setViewportSize({ width: 1024, height: 768 });
    await assertDesktopLayout();
    await assertVisibleInViewport('#settings-back');
    await screenshot('21-settings-small-desktop.png');
    await page.setViewportSize({ width: 1440, height: 960 });
    await closeSettings();
    assert.equal(await page.locator('#prompt').inputValue(), draft);
    assert.equal(await page.locator('#header-title').innerText(), taskTitle);
    assert.equal(await page.locator('.task-item').count(), taskCount);

    await page.keyboard.press('Control+,');
    await page.locator('#settings-screen').waitFor({ state: 'visible' });
    await settingsCategory('shortcuts');
    await selectSetting('settingsKey', 'Ctrl+Shift+,');
    await closeSettings();
    await page.keyboard.press('Control+Shift+,');
    await page.locator('#settings-screen').waitFor({ state: 'visible' });
    await page.evaluate(() => window.DongranSettings.set('settingsKey', 'Ctrl+,'));
    await page.keyboard.press('Control+`');
    await page.locator('#settings-screen').waitFor({ state: 'hidden' });
    await page.locator('#terminal-dock').waitFor({ state: 'visible' });
    await page.waitForFunction(() => document.activeElement.id === 'terminal-input');
    const terminalInput = page.locator('#terminal-input');
    const terminalOutput = page.locator('#terminal-output');
    const command = async value => { await terminalInput.fill(value); await terminalInput.press('Enter'); };
    await command('pwd');
    assert.match(await terminalOutput.innerText(), /D:\\Projects\\dongran-console/);
    await command('ls');
    assert.match(await terminalOutput.innerText(), /package\.json/);
    const injection = '<img src=x onerror="window.terminalInjection=true">';
    await command(injection);
    assert.equal((await terminalOutput.innerText()).includes(injection), true);
    assert.equal(await terminalOutput.locator('img').count(), 0);
    await terminalInput.press('ArrowUp');
    assert.equal(await terminalInput.inputValue(), injection);
    await command('clear');
    assert.equal(await terminalOutput.innerText(), '');
    await command('npm run test -- permissions');
    assert.match(await terminalOutput.innerText(), /12 passed/);
    assert.match(await terminalOutput.innerText(), /未执行真实命令/);
    await assertVisibleInViewport('#prompt');
    await assertVisibleInViewport('#terminal-input');
    await screenshot('08-terminal.png');
    await page.setViewportSize({ width: 1024, height: 768 });
    await assertDesktopLayout();
    await assertVisibleInViewport('#prompt');
    await assertVisibleInViewport('#terminal-input');
    await page.setViewportSize({ width: 1440, height: 960 });
    await page.locator('#terminal-close').click();
    await page.keyboard.press('Control+k');
    await page.locator('#search-input').fill('权限');
    assert.equal(await page.locator('#search-results button').count(), 1);
    await page.keyboard.press('Escape');
    await waitDialogClosed();
    await page.keyboard.press('Control+,');
    await page.locator('#settings-screen').waitFor({ state: 'visible' });
    await page.keyboard.press('Control+Alt+n');
    await page.locator('#settings-screen').waitFor({ state: 'hidden' });
    await page.waitForFunction(() => document.activeElement.id === 'prompt');
    assert.match(await page.locator('#header-title').innerText(), /新.*任务/);
    await page.locator('#prompt').fill('梳理订单管理需求');
    await page.locator('#send').click();
    await page.waitForFunction(() => document.querySelector('.task-heading .badge')?.textContent === '规划中');
    assert.equal(await page.locator('.task-item').count(), 2);

    await page.reload();
    await page.waitForFunction(() => window.DongranSettings);
    for (const [key, expected] of Object.entries({ fontSize: 16, sidebarWidth: 300, density: 'compact', showSuggestions: false, defaultModel: '自定义模型', provider: '本地模型' })) {
      assert.equal(await getSetting(key), expected, `${key} persists after reload`);
    }
    await page.evaluate(() => {
      for (const [key, value] of [['fontSize', 99], ['fontSize', NaN], ['sidebarWidth', 10]]) {
        try { window.DongranSettings.set(key, value); } catch {}
      }
    });
    assert.equal(await getSetting('fontSize'), 16);
    assert.equal(await getSetting('sidebarWidth'), 300);
    await page.locator('#settings-button').click();
    await settingsCategory('data');
    const downloadPromise = page.waitForEvent('download');
    await page.locator('#settings-export').click();
    const download = await downloadPromise;
    assert.equal(download.suggestedFilename(), 'dongran-settings.json');
    const chunks = [];
    for await (const chunk of await download.createReadStream()) chunks.push(chunk);
    const exported = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    assert.equal(exported.version, 1);
    assert.equal(exported.settings.fontSize, 16);
    assert.equal(Object.keys(exported.settings).some(key => /^(api.?key|secret|accessToken|refreshToken|password|authorization)$/i.test(key)), false);
    const importPayload = payload => page.locator('#settings-import-file').setInputFiles({ name: 'settings.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(payload)) });
    await importPayload({ version: 1, settings: { ...exported.settings, fontSize: 99 } });
    await page.waitForFunction(() => document.querySelector('#settings-status').classList.contains('is-error') && document.querySelector('#settings-status').textContent.includes('文字大小'));
    assert.equal(await getSetting('fontSize'), 16);
    await importPayload({ version: 1, settings: { ...exported.settings, apiKey: 'not-a-real-key' } });
    await page.waitForFunction(() => document.querySelector('#settings-status').classList.contains('is-error') && document.querySelector('#settings-status').textContent.includes('无法识别'));
    assert.equal(await getSetting('fontSize'), 16);
    assert.equal(await page.evaluate(() => localStorage.getItem('dongran.settings.v1').includes('not-a-real-key')), false);
    await importPayload({ version: 1, settings: { ...exported.settings, fontSize: 15 } });
    await page.waitForFunction(() => window.DongranSettings.get('fontSize') === 15);
    await page.locator('#settings-reset').click();
    await page.locator('#settings-cancel-reset').click();
    await waitDialogClosed();
    assert.equal(await getSetting('fontSize'), 15);
    await page.locator('#settings-reset').click();
    await page.locator('#settings-confirm-reset').click();
    await waitDialogClosed();
    await page.waitForFunction(() => window.DongranSettings.get('fontSize') === 14);
    assert.equal(await page.evaluate(() => Object.entries(window.DongranSettings.defaults).every(([key, value]) => JSON.stringify(window.DongranSettings.get(key)) === JSON.stringify(value))), true);
    await assertDesktopLayout();
    assert.deepEqual(errors, []);
    console.log('PASS: desktop layouts at 1920/1440/1024, full settings navigation/controls/themes/persistence/import/export/reset, task and draft restoration, menus and dialogs, document editing, diff, verification, shortcuts, terminal and no browser errors.');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
