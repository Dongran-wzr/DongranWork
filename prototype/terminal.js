(() => {
  const dock = document.querySelector('#terminal-dock');
  const toggle = document.querySelector('#terminal-toggle');
  if (!dock || !toggle) return;

  let project = null;
  let activeRun = null;
  let submitting = false;
  let history = [];
  let historyIndex = 0;
  let draft = '';

  dock.innerHTML = `
    <div class="terminal-heading">
      <div class="terminal-tab"><i data-lucide="terminal"></i><strong>终端</strong><span class="terminal-demo">演示</span></div>
      <span class="terminal-project" id="terminal-project">未选择项目</span>
      <span class="terminal-project" id="terminal-shell">PowerShell</span>
      <button type="button" class="terminal-sandbox" id="terminal-sandbox" title="查看命令沙箱状态"><i data-lucide="shield"></i><span>检测执行环境</span></button>
      <div class="terminal-actions">
        <button type="button" class="icon-btn" id="terminal-stop" title="停止命令" aria-label="停止命令" hidden><i data-lucide="square"></i></button>
        <button type="button" class="icon-btn" id="terminal-clear" title="清空终端" aria-label="清空终端"><i data-lucide="eraser"></i></button>
        <button type="button" class="icon-btn" id="terminal-expand" title="展开终端" aria-label="展开终端" aria-pressed="false"><i data-lucide="maximize-2"></i></button>
        <button type="button" class="icon-btn" id="terminal-close" title="关闭终端" aria-label="关闭终端"><i data-lucide="x"></i></button>
      </div>
    </div>
    <div id="terminal-sandbox-notice" class="terminal-sandbox-notice" role="status" hidden><span></span><button type="button" data-sandbox-refresh>重新检测</button></div>
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
  const live = () => /^https?:$/.test(location.protocol) || !!window.DongranBackendBase;

  function applyAvailability() {
    const state = window.DongranSandbox?.snapshot();
    const available = !live() || state?.available === true;
    input.disabled = !project || !!activeRun || submitting || !available;
    input.placeholder = !project ? '选择项目后输入命令' : !available ? '命令沙箱未就绪' : '输入命令…';
    const badge = dock.querySelector('#terminal-sandbox');
    badge.querySelector('span').textContent = !live() ? '演示模式' : state?.checking ? '检测中…' : available ? '沙箱 · 网络关闭' : '沙箱不可用';
    badge.title = !live() ? '当前为视觉演示，不执行真实命令' : state?.reason || '查看命令沙箱状态';
    const notice = dock.querySelector('#terminal-sandbox-notice');
    notice.hidden = !live() || available;
    notice.querySelector('span').textContent = state?.checking ? '正在检查执行环境，检查完成后才能运行命令。'
      : '命令已阻止：'+(state?.reason || '尚未获取沙箱状态。');
    notice.querySelector('button').disabled = state?.checking === true;
    dock.querySelector('.terminal-input-status').textContent = !live() ? '演示' : available ? '隔离执行' : '未就绪';
  }

  dock.querySelector('#terminal-sandbox').onclick = () => window.DongranSettings?.open('permissions');
  window.addEventListener('sandboxchange', applyAvailability);
  window.addEventListener('backendready', applyAvailability);

  function appendLine(text, kind = '') {
    const line = document.createElement('div');
    line.className = `terminal-line${kind ? ` terminal-line-${kind}` : ''}`;
    line.textContent = text;
    output.append(line);
    output.scrollTop = output.scrollHeight;
  }

  function resetOutput() {
    output.replaceChildren();
    appendLine(live()?'隔离命令 · 在项目快照中执行，成功后检查冲突并同步修改 · 网络关闭':'Dongran Terminal · 演示会话', 'muted');
    if (project) appendLine(project.path || project.name, 'muted');
    else appendLine('尚未选择项目。', 'muted');
  }

    async function executeReal(command){
    if(activeRun||submitting){appendLine('上一条命令仍在运行。','muted');return;}
    if(window.DongranSandbox?.snapshot().available!==true){
      appendLine('命令未执行：'+(window.DongranSandbox?.snapshot().reason||'命令沙箱未就绪。'),'error');
      applyAvailability();return;
    }
    const origin=project?.id;submitting=true;input.disabled=true;
    appendLine('> '+command,'command');
    const text=document.createElement('pre');text.className='terminal-line';output.append(text);
    const stop=dock.querySelector('#terminal-stop');stop.hidden=false;
    try{
      const result=await window.DongranRuntime.request('/api/commands','POST',{projectId:origin,command,confirmed:true,timeout:120});
      activeRun=result.id;
      while(true){
        const run=await window.DongranRuntime.request('/api/commands/'+activeRun);
        text.textContent=run.output||'';
        output.scrollTop=output.scrollHeight;
        if(!['queued','running'].includes(run.status)){
          appendLine(run.status+' · exit '+(run.exit_code??'—'),'muted');
          if(run.workspacePath)appendLine('执行快照：'+run.workspacePath,'muted');
          if(run.syncStatus)appendLine('文件同步：'+({pending:'等待同步',applied:'已同步',unchanged:'没有变更',conflict:'回写中断；请检查项目差异与执行快照','not-applied':'未回写原项目'}[run.syncStatus]||run.syncStatus),run.syncStatus==='conflict'?'error':'muted');
          break;
        }
        await new Promise(resolve=>setTimeout(resolve,300));
      }
    }catch(error){
      appendLine(error.message,'error');
      if(String(error.code||'').startsWith('SANDBOX'))void window.DongranSandbox?.refresh();
    }
    finally{activeRun=null;submitting=false;applyAvailability();stop.hidden=true;}
  }
  dock.querySelector('#terminal-stop').onclick=async()=>{
    if(!activeRun)return;
    try{await window.DongranRuntime.request('/api/commands/'+activeRun+'/cancel','POST');}
    catch(error){appendLine(error.message,'error');}
  };

  function setOpen(open) {
    dock.hidden = !open;
  toggle.setAttribute('aria-expanded', String(open));
    toggle.classList.toggle('active', open);
    if (open) {
      if(live())void window.DongranSandbox?.refresh();
      if (input.disabled) output.focus();
      else input.focus();
    } else toggle.focus();
  }

  function execute(command) {
    if(window.DongranRuntime?.enabled)return executeReal(command);
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
    if (!command || !project || input.disabled) return;
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
      id: detail.id,
      name: String(detail.name),
      path: detail.path ? String(detail.path) : String(detail.name),
      sample: detail.sample === true
    } : null;
    history = [];
    historyIndex = 0;
    draft = '';
    input.value = '';
    applyAvailability();
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
  applyAvailability();
  renderIcons();
})();
