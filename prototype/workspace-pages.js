(() => {
  const page = document.getElementById('workspace-page');
  const content = document.getElementById('workspace-page-content');
  const conversation = document.querySelector('.work-body');
  const header = document.getElementById('header-title');
  const panelToggle = document.querySelector('.title-actions [data-action="panel"]');
  let currentView = null;
  let taskTitle = header.textContent;
  let returnFocus = null;

  function syncNavigation() {
    document.querySelectorAll('.main-nav [aria-controls="workspace-page"]').forEach(button => {
      const active = button.dataset.action === currentView;
      button.classList.toggle('active', active);
      if (active) button.setAttribute('aria-current', 'page');
      else button.removeAttribute('aria-current');
    });
  }

  function renderKnowledge() {
    if(window.DongranRuntime?.enabled)return window.DongranRuntime.knowledge(content);
    content.innerHTML = `<div class="knowledge-page">
      <header class="knowledge-heading"><h1 id="workspace-page-title" tabindex="-1">知识库</h1><span class="workspace-pending"><i data-lucide="plug"></i>待接入</span></header>
      <div class="knowledge-context"><i data-lucide="folder-open"></i><span id="knowledge-project"></span></div>
      <div class="knowledge-empty"><i data-lucide="library"></i><h2>知识库尚未连接</h2><p>等待后端接入后，项目资料将在这里集中管理。</p><span class="knowledge-status"><span></span>等待后端接入</span></div>
    </div>`;
    document.getElementById('knowledge-project').textContent = window.DongranProjects?.current()?.name || '工作区';
  }

  function open(view) {
    if (!['knowledge', 'schedules'].includes(view)) return;
    window.DongranUI?.closeMenu({ immediate: true, restoreFocus: false });
    if (currentView === view) return;
    if (!currentView) {
      taskTitle = header.textContent;
      returnFocus = conversation.contains(document.activeElement) ? document.activeElement : null;
    }
    currentView = view;
    document.body.dataset.workspaceView = view;
    conversation.hidden = true;
    page.hidden = false;
    panelToggle.hidden = true;
    header.textContent = view === 'knowledge' ? '知识库' : '定时任务';
    syncNavigation();
    content.replaceChildren();
    if (view === 'knowledge') renderKnowledge();
    else window.DongranSchedules.render(content);
    const title = content.querySelector('h1');
    if (title) { title.id = 'workspace-page-title'; title.tabIndex = -1; }
    window.lucide?.createIcons();
    page.scrollTop = 0;
    title?.focus({ preventScroll: true });
    window.dispatchEvent(new CustomEvent('workspaceviewchange', { detail: { view } }));
  }

  function close({ restoreFocus = false } = {}) {
    if (!currentView) return;
    window.DongranUI?.closeMenu({ immediate: true, restoreFocus: false });
    currentView = null;
    delete document.body.dataset.workspaceView;
    page.hidden = true;
    conversation.hidden = false;
    panelToggle.hidden = false;
    header.textContent = taskTitle;
    syncNavigation();
    if (restoreFocus) {
      const target = returnFocus?.isConnected ? returnFocus : document.getElementById('prompt');
      target?.focus({ preventScroll: true });
    }
    window.dispatchEvent(new CustomEvent('workspaceviewchange', { detail: { view: null } }));
  }

  document.getElementById('workspace-page-back').addEventListener('click', () => close({ restoreFocus: true }));
  window.addEventListener('projectchange', () => { if (currentView === 'knowledge') renderKnowledge(); });
  window.DongranPages = {
    open, close, current: () => currentView,
    updateTaskTitle(title) { taskTitle = title; if (!currentView) header.textContent = title; }
  };
})();
