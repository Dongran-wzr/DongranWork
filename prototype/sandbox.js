(() => {
  const live = () => /^https?:$/.test(location.protocol) || !!window.DongranBackendBase;
  const escape = value => String(value ?? '').replace(/[&<>"']/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
  let status = null;
  let pending = null;
  let checking = false;
  let error = '';
  let checkedAt = null;

  function snapshot() {
    const available = status?.available === true && status.protocolVersion === 1
      && status.policy?.mode === 'required' && status.policy?.network === 'deny'
      && status.networkIsolation === true;
    return {
      ...status, checking, live: live(),
      // Treat unsupported or incomplete responses as unavailable.
      available: available && !error && !checking,
      reason: error || status?.reason || (available ? '' : '执行助手未就绪。'),
      checkedAt
    };
  }

  function publish() {
    document.querySelectorAll('[data-sandbox-card]').forEach(renderCard);
    window.dispatchEvent(new CustomEvent('sandboxchange', {detail:snapshot()}));
  }

  async function refresh() {
    if (!live()) { publish(); return snapshot(); }
    if (pending) return pending;
    checking = true;
    error = '';
    publish();
    pending = (async () => {
      try {
        status = await window.DongranBackend.request('/api/sandbox/status?refresh=true', {cache:'no-store'});
        if (status?.protocolVersion !== 1) error = '本地服务与执行助手的协议版本不兼容，请更新应用。';
        else if (status.available === true && (status.policy?.mode !== 'required' || status.policy?.network !== 'deny' || status.networkIsolation !== true)) {
          error = '执行环境没有提供当前版本要求的隔离策略，命令已阻止。';
        }
      } catch (failure) {
        status = null;
        error = failure.status === 404 ? '当前本地服务尚未提供沙箱，请更新并重启应用。' : failure.message || '无法读取执行环境状态。';
      } finally {
        checking = false;
        checkedAt = new Date().toLocaleTimeString('zh-CN', {hour12:false});
        pending = null;
        publish();
      }
      return snapshot();
    })();
    return pending;
  }

  function renderCard(host) {
    const state = snapshot();
    const label = !state.live ? '演示模式' : state.checking ? '正在检测' : state.available ? '沙箱可用' : '命令已阻止';
    const description = !state.live ? '连接本地服务后可查看真实执行环境。演示命令不在系统上执行。'
      : state.checking ? '正在检查本机执行助手和隔离能力。'
      : state.available ? '命令通过本机执行助手运行。每次执行仍会重新检查隔离条件。'
      : state.reason;
    const policy = state.policy;
    const limits = policy && Number.isFinite(policy.memoryMb) && Number.isFinite(policy.maxProcesses)
      ? (state.backend==='linux-bubblewrap'?'虚拟地址空间上限 ':'作业内存上限 ')+policy.memoryMb+' MB · 进程数限制 '+policy.maxProcesses : '限制由本地执行服务提供';
    host.dataset.state = state.available ? 'available' : state.checking ? 'checking' : 'unavailable';
    host.innerHTML = `
      <div class="sandbox-card-heading"><span class="sandbox-symbol"><i data-lucide="shield-check"></i></span><div><h2>命令沙箱</h2><p>本机隔离执行</p></div><span class="sandbox-state" role="status">${label}</span></div>
      <p class="sandbox-description" data-sandbox-reason>${escape(description)}</p>
      <div class="sandbox-policy"><span><i data-lucide="folder-lock"></i>项目快照内执行</span><span><i data-lucide="wifi-off"></i>命令网络关闭</span><span><i data-lucide="shield"></i>隔离失败即停止</span></div>
      <div class="sandbox-details"><span>${escape(limits)}</span>${state.backend?'<span>'+escape(state.platform)+' · '+escape(state.backend)+'</span>':''}</div>
      <p class="sandbox-scope">命令在独立项目快照中执行，排除的敏感路径不会进入快照。成功后检查原文件是否变化，再同步修改；失败或取消时不回写，保留目录可在执行记录中查看。<br>适用于 Agent、终端和任务钩子的 Shell 命令。模型连接、图形化 Git 和外部连接由宿主服务管理；文件工具另受项目权限约束。</p>
      <div class="sandbox-card-footer"><small>${state.checkedAt?'上次检测 '+escape(state.checkedAt):'无需安装 Docker'}</small><button type="button" class="settings-command" data-sandbox-refresh ${state.checking||!state.live?'disabled':''}><i data-lucide="refresh-cw"></i>${state.checking?'检测中…':'重新检测'}</button></div>`;
    window.lucide?.createIcons({root:host});
  }

  function mount(content) {
    const host = document.createElement('section');
    host.className = 'sandbox-card';
    host.dataset.sandboxCard = '';
    host.setAttribute('aria-label','命令沙箱');
    content.prepend(host);
    renderCard(host);
    if (live() && !pending) void refresh();
  }

  document.addEventListener('click', event => {
    if (event.target.closest('[data-sandbox-refresh]')) void refresh();
  });
  window.addEventListener('backendready', () => void refresh());
  window.DongranSandbox = {snapshot, refresh, mount};
  if (window.DongranBackend?.isAvailable()) void refresh();
})();
