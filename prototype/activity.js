(() => {
  const STORAGE_KEY = 'dongran.activity.v1';
  const sampleWeeks = [
    '0100000','0012000','0121100','1012000','0201310','0112200','0023100',
    '1121000','0233210','0123410','0022200','0132100','1234300','0243210',
    '0134510','1223200','0234300','1343210','0234120','0123100','1234520',
    '2343410','0234320','1345200','2345310','1234200','0245310','1234500'
  ];
  const views = new WeakMap();
  const dateKey = date => `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`;
  const asDate = value => {
    if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)) {
      const [year,month,day] = value.split('-').map(Number);
      return new Date(year,month-1,day);
    }
    return new Date(value);
  };
  const validDay = value => /^\d{4}-\d{2}-\d{2}$/.test(value) && dateKey(asDate(value)) === value;
  const validCounts = counts => counts && typeof counts === 'object' && !Array.isArray(counts) && Object.keys(counts).length <= 196 && Object.entries(counts).every(([day,count]) => validDay(day) && Number.isInteger(count) && count >= 0 && count <= 1000000);
  let projects = [];
  try {
    const stored = JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null');
    if (stored?.version === 1 && Array.isArray(stored.projects) && stored.projects.length <= 200 && new Set(stored.projects.map(item => item?.id)).size === stored.projects.length && stored.projects.every(item => typeof item?.id === 'string' && item.id.length > 0 && item.id.length <= 2048 && validCounts(item.counts))) projects = stored.projects;
  } catch {}

  const tooltip = document.createElement('div');
  tooltip.id = 'activity-tooltip';
  tooltip.className = 'activity-tooltip';
  tooltip.setAttribute('role','tooltip');
  tooltip.hidden = true;
  document.body.append(tooltip);
  let activeCell = null;

  function hideTooltip() {
    activeCell?.removeAttribute('aria-describedby');
    activeCell = null;
    tooltip.hidden = true;
  }

  function showTooltip(cell) {
    if (!cell || cell.disabled) return;
    hideTooltip();
    activeCell = cell;
    tooltip.textContent = cell.getAttribute('aria-label');
    tooltip.hidden = false;
    const box = cell.getBoundingClientRect();
    const top = box.top - tooltip.offsetHeight - 10;
    tooltip.style.left = `${Math.max(10,Math.min(innerWidth-tooltip.offsetWidth-10,box.left+box.width/2-tooltip.offsetWidth/2))}px`;
    tooltip.style.top = `${top < 10 ? box.bottom+10 : top}px`;
    cell.setAttribute('aria-describedby',tooltip.id);
  }

  function getCounts(projectId = null) {
    const counts = {};
    for (const project of projects) {
      if (projectId !== null && project.id !== projectId) continue;
      for (const [day,count] of Object.entries(project.counts)) counts[day] = (counts[day] || 0)+count;
    }
    return counts;
  }

  function render(container,options = {}) {
    if (!container) return;
    hideTooltip();
    views.set(container,{...options});
    const today = asDate(options.date || new Date());
    if (!Number.isFinite(today.getTime())) return;
    today.setHours(0,0,0,0);
    const start = new Date(today);
    start.setDate(start.getDate()-start.getDay()-27*7);
    const counts = options.counts || getCounts(options.projectId ?? null);
    const sample = options.sample === true && !Object.values(counts).some(count => count > 0);
    container.classList.add('activity-map');
    container.dataset.sample = String(sample);
    container.setAttribute('role','group');
    container.setAttribute('aria-label',sample ? '近 28 周活动热力图，示例数据' : '近 28 周任务活动');
    const fragment = document.createDocumentFragment();
    for (let index=0;index<196;index++) {
      const date = new Date(start);
      date.setDate(start.getDate()+index);
      const day = dateKey(date);
      const future = date > today;
      const rawCount = sample ? Number(sampleWeeks[Math.floor(index/7)][index%7]) : counts[day];
      const count = future ? 0 : Number.isFinite(rawCount) ? Math.max(0,Math.floor(rawCount)) : 0;
      const cell = document.createElement('button');
      cell.type = 'button';
      cell.className = 'activity-cell';
      cell.dataset.date = day;
      cell.dataset.count = String(count);
      cell.dataset.level = String(Math.min(5,count));
      cell.disabled = future;
      cell.tabIndex = day === dateKey(today) ? 0 : -1;
      cell.setAttribute('aria-label',`${sample ? '示例 · ' : ''}${day} · ${future ? '尚未到来' : `${count} 个任务`}`);
      fragment.append(cell);
    }
    container.replaceChildren(fragment);
    if (container.dataset.activityBound) return;
    container.dataset.activityBound = 'true';
    container.addEventListener('pointerover',event => showTooltip(event.target.closest('.activity-cell')));
    container.addEventListener('pointerleave',hideTooltip);
    container.addEventListener('focusin',event => showTooltip(event.target.closest('.activity-cell')));
    container.addEventListener('focusout',hideTooltip);
    container.addEventListener('click',event => {
      const cell = event.target.closest('.activity-cell');
      if (!cell || cell.disabled) return;
      container.querySelectorAll('.activity-cell').forEach(button => {button.tabIndex=button===cell?0:-1;});
      showTooltip(cell);
    });
    container.addEventListener('keydown',event => {
      if (event.key === 'Escape') {hideTooltip();event.preventDefault();return;}
      if (!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Home','End'].includes(event.key)) return;
      const cells = [...container.querySelectorAll('.activity-cell')];
      const index = cells.indexOf(event.target);
      if (index < 0) return;
      event.preventDefault();
      const last = cells.findLastIndex(cell => !cell.disabled);
      const delta = {ArrowLeft:-7,ArrowRight:7,ArrowUp:-1,ArrowDown:1};
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? last : Math.max(0,Math.min(last,index+delta[event.key]));
      cells[index].tabIndex = -1;
      cells[next].tabIndex = 0;
      cells[next].focus({preventScroll:true});
    });
  }

  function refresh() {
    for (const container of document.querySelectorAll('.activity-map')) {
      if (views.has(container)) render(container,views.get(container));
    }
  }

  function mark(projectId,date = new Date()) {
    if (typeof projectId !== 'string' || !projectId || projectId.length > 2048) return false;
    const day = asDate(date);
    if (!Number.isFinite(day.getTime())) return false;
    const key = dateKey(day);
    const cutoff = new Date(day);
    cutoff.setDate(day.getDate()-195);
    let project = projects.find(entry => entry.id === projectId);
    if (!project) {project={id:projectId,counts:{}};projects.push(project);}
    project.counts[key] = Math.min(1000000,(project.counts[key] || 0)+1);
    for (const entry of projects) entry.counts = Object.fromEntries(Object.entries(entry.counts).filter(([date]) => date >= dateKey(cutoff)).sort(([a],[b])=>a.localeCompare(b)).slice(-196));
    projects = projects.filter(entry => Object.keys(entry.counts).length).slice(-200);
    let saved = true;
    try {localStorage.setItem(STORAGE_KEY,JSON.stringify({version:1,projects}));} catch {saved=false;}
    refresh();
    return saved;
  }

  window.addEventListener('resize',hideTooltip);
  document.addEventListener('scroll',hideTooltip,true);
  window.addEventListener('settingschange',hideTooltip);
  new MutationObserver(() => {
    if (activeCell && (!activeCell.isConnected || document.querySelector('#settings-screen')?.hidden === false)) hideTooltip();
  }).observe(document.body,{childList:true,subtree:true,attributes:true,attributeFilter:['hidden']});
  window.DongranActivity = {render,mark,getCounts,refresh,dateKey};
})();
