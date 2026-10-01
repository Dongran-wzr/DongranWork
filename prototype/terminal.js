(() => {
  const dock = document.querySelector('#terminal-dock');
  const toggle = document.querySelector('#terminal-toggle');
  if (!dock || !toggle) return;

  let project = null;
  let history = [];
  let historyIndex = 0;
  let draft = '';

  dock.innerHTML = `
    <div class="terminal-heading">
      <div class="terminal-tab"><i data-lucide="terminal"></i><strong>终端</strong><span class="terminal-demo">演示</span></div>
      <span class="terminal-project" id="terminal-project">未选择项目</span>
      <span class="terminal-project" id="terminal-shell">PowerShell</span>
      <div class="terminal-actions">
        <button type="button" class="icon-btn" id="terminal-clear" title="清空终端" aria-label="清空终端"><i data-lucide="eraser"></i></button>
        <button type="button" class="icon-btn" id="terminal-expand" title="展开终端" aria-label="展开终端" aria-pressed="false"><i data-lucide="maximize-2"></i></button>
        <button type="button" class="icon-btn" id="terminal-close" title="关闭终端" aria-label="关闭终端"><i data-lucide="x"></i></button>
      </div>
    </div>
    <div id="terminal-output" class="terminal-output" role="log" aria-live="polite" aria-relevant="additions" aria-label="终端输出" tabindex="0"></div>
    <form id="terminal-form" class="terminal-form" autocomplete="off">
      <span class="terminal-prompt" aria-hidden="true">&gt;</span>
      <input id="terminal-input" aria-label="终端命令" placeholder="选择项目后输入命令" spellcheck="false" autocapitalize="off" autocomplete="off" disabled>
      <span class="terminal-input-status">本地</span>
    </form>`;

  const output = dock.querySelector('#terminal-output');
  const input = dock.querySelector('#terminal-input');
  const projectLabel = dock.querySelector('#terminal-project');
  const expand = dock.querySelector('#terminal-expand');
  const renderIcons = () => window.lucide?.createIcons();

  function appendLine(text, kind = '') {
    const line = document.createElement('div');
    line.className = `terminal-line${kind ? ` terminal-line-${kind}` : ''}`;
    line.textContent = text;
    output.append(line);
    output.scrollTop = output.scrollHeight;
  }

  function resetOutput() {
    output.replaceChildren();
    appendLine('Dongran Terminal · 演示会话', 'muted');
    if (project) appendLine(project.path || project.name, 'muted');
    else appendLine('尚未选择项目。', 'muted');
  }

  function setOpen(open) {
    dock.hidden = !open;
    toggle.setAttribute('aria-expanded', String(open));
    toggle.classList.toggle('active', open);
    if (open) {
      if (input.disabled) output.focus();
      else input.focus();
    } else toggle.focus();
  }

  function execute(command) {
    const normalized = command.trim().replace(/\s+/g, ' ');
    if (normalized === 'clear') {
      output.replaceChildren();
      return;
    }
    appendLine(`${project.name} > ${command}`, 'command');
    if (normalized === 'pwd') {
      appendLine(project.path || project.name);
    } else if (normalized === 'ls') {
      if (project.sample) {
        appendLine('docs/    src/    tests/    package.json    README.md');
      } else {
        appendLine('未连接本地 Shell，当前目录内容请在项目文件中查看。', 'muted');
      }
    } else if (normalized === 'npm run test -- permissions' || normalized === 'npm run test') {
      if (project.sample) {
        appendLine('演示结果 · 未执行真实命令', 'muted');
        appendLine('PASS  tests/permissions.test.ts', 'success');
        appendLine('Test Files  1 passed (1)\nTests       12 passed (12)\nDuration    3.2s', 'success');
      } else {
        appendLine('未连接本地 Shell，测试命令未执行。', 'muted');
      }
    } else if (normalized === 'help') {
      appendLine('pwd\nls\nclear\nnpm run test -- permissions', 'muted');
    } else {
      appendLine('此命令未执行。当前演示支持：pwd、ls、clear、npm run test -- permissions。', 'muted');
    }
    appendLine('');
  }

  toggle.setAttribute('aria-controls', 'terminal-dock');
  toggle.addEventListener('click', () => setOpen(dock.hidden));
  dock.querySelector('#terminal-close').addEventListener('click', () => setOpen(false));
  dock.querySelector('#terminal-clear').addEventListener('click', () => {
    output.replaceChildren();
    if (!input.disabled) input.focus();
  });
  expand.addEventListener('click', () => {
    const expanded = dock.classList.toggle('is-expanded');
    expand.setAttribute('aria-pressed', String(expanded));
    expand.title = expanded ? '还原终端高度' : '展开终端';
    expand.setAttribute('aria-label', expand.title);
    expand.innerHTML = `<i data-lucide="${expanded ? 'minimize-2' : 'maximize-2'}"></i>`;
    renderIcons();
  });
  dock.querySelector('#terminal-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const command = input.value.trim();
    if (!command || !project) return;
    if (window.DongranSettings?.get('rememberHistory')!==false && history.at(-1) !== command) history.push(command);
    historyIndex = history.length;
    draft = '';
    input.value = '';
    execute(command);
  });
  input.addEventListener('keydown', (event) => {
    if (event.isComposing) return;
    if (event.key === 'ArrowUp' && history.length) {
      event.preventDefault();
      if (historyIndex === history.length) draft = input.value;
      historyIndex = Math.max(0, historyIndex - 1);
      input.value = history[historyIndex];
    } else if (event.key === 'ArrowDown' && history.length) {
      event.preventDefault();
      historyIndex = Math.min(history.length, historyIndex + 1);
      input.value = historyIndex === history.length ? draft : history[historyIndex];
    }
  });
  window.addEventListener('projectchange', (event) => {
    const detail = event.detail;
    project = detail?.name ? {
      name: String(detail.name),
      path: detail.path ? String(detail.path) : String(detail.name),
      sample: detail.sample === true
    } : null;
    history = [];
    historyIndex = 0;
    draft = '';
    input.value = '';
    input.disabled = !project;
    input.placeholder = project ? '输入命令…' : '选择项目后输入命令';
    projectLabel.textContent = project?.name || '未选择项目';
    projectLabel.title = project?.path || '';
    resetOutput();
  });

  window.addEventListener('settingschange',event=>{
    const prefs=event.detail.settings;
    dock.style.setProperty('--terminal-font-size',`${prefs.terminalFontSize}px`);
    dock.style.setProperty('--terminal-height',`${prefs.terminalHeight}px`);
    dock.querySelector('#terminal-shell').textContent=prefs.shell;
    if(!prefs.rememberHistory){history=[];historyIndex=0;draft='';}
  });
  resetOutput();
  renderIcons();
})();
