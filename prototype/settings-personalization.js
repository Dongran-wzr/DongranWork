(() => {
  const presets = [
    { value: 'neutral', label: '中性', color: '#38383D' },
    { value: 'forest', label: '森林', color: '#43785D' },
    { value: 'ocean', label: '海洋', color: '#477AA8' },
    { value: 'custom', label: '自定义', color: '#716181' }
  ];
  const swatches = [
    ['石墨', '#54545C'], ['松绿', '#43785D'], ['海蓝', '#477AA8'],
    ['青瓷', '#448C8A'], ['莓灰', '#9B647F'], ['琥珀', '#98783A']
  ];
  const hexColor = value => typeof value === 'string' && /^#[0-9a-f]{6}$/i.test(value);
  const plainObject = value => !!value && typeof value === 'object' && !Array.isArray(value) && Object.getPrototypeOf(value) === Object.prototype;
  const validThemes = value => Array.isArray(value) && value.length <= 20 && new Set(value.map(item => item?.id)).size === value.length && value.every(item => plainObject(item) && Object.keys(item).every(key => ['id', 'name', 'mode', 'color'].includes(key)) && typeof item.id === 'string' && /^[a-zA-Z0-9_-]{1,80}$/.test(item.id) && typeof item.name === 'string' && item.name.trim().length > 0 && item.name.length <= 32 && ['light', 'dark', 'system'].includes(item.mode) && hexColor(item.color));
  const validPosition = value => value === null || plainObject(value) && Object.keys(value).length === 2 && ['x', 'y'].every(key => typeof value[key] === 'number' && Number.isFinite(value[key]) && value[key] >= 0 && value[key] <= 1);
  const choices = entries => entries.map(([value, label]) => ({ value, label }));
  const definitions = [
    { key: 'themePreset', category: 'appearance', section: '主题配色', title: '主题配色', type: 'select', default: 'neutral', hidden: true, options: presets },
    { key: 'customAccent', category: 'appearance', section: '主题配色', title: '自定义强调色', type: 'text', default: '#477AA8', maxLength: 7, validate: hexColor, hidden: true },
    { key: 'themeName', category: 'appearance', section: '主题配色', title: '主题名称', type: 'text', default: '', maxLength: 32, hidden: true },
    { key: 'activeThemeId', category: 'appearance', section: '主题配色', title: '当前自定义主题', type: 'text', default: '', maxLength: 80, hidden: true },
    { key: 'savedThemes', category: 'appearance', section: '主题配色', title: '已保存主题', type: 'collection', default: [], validate: validThemes, hidden: true },
    { key: 'petEnabled', category: 'pet', section: '桌面伙伴', title: '显示桌面宠物', description: '在工作台中显示你的机器人伙伴。', icon: 'bot', type: 'toggle', default: false, hidden: true },
    { key: 'petName', category: 'pet', section: '桌面伙伴', title: '名字', description: '', icon: 'badge', type: 'text', default: '小然', maxLength: 16, validate: value => value.trim().length > 0, hidden: true },
    { key: 'petStyle', category: 'pet', section: '桌面伙伴', title: '形象', type: 'select', default: 'cubo', options: choices([['cubo', '小方'], ['capsule', '胶囊'], ['pixel', '像素']]), hidden: true },
    { key: 'petSize', category: 'pet', section: '外观与动作', title: '大小', description: '', icon: 'maximize-2', type: 'range', default: 80, min: 56, max: 128, step: 8, unit: 'px', hidden: true },
    { key: 'petOpacity', category: 'pet', section: '外观与动作', title: '不透明度', description: '', icon: 'blend', type: 'range', default: 100, min: 40, max: 100, step: 5, unit: '%', hidden: true },
    { key: 'petActivity', category: 'pet', section: '外观与动作', title: '活动程度', description: '', icon: 'activity', type: 'segment', default: 'gentle', options: choices([['quiet', '安静'], ['gentle', '轻盈'], ['lively', '活跃']]), hidden: true },
    { key: 'petPosition', category: 'pet', section: '位置与状态', title: '停靠位置', description: '', icon: 'scan', type: 'select', default: 'bottom-right', options: choices([['bottom-right', '右下角'], ['bottom-left', '左下角'], ['top-right', '右上角']]), hidden: true },
    { key: 'petCustomPosition', category: 'pet', section: '位置与状态', title: '拖动位置', type: 'collection', default: null, validate: validPosition, hidden: true },
    { key: 'petDnd', category: 'pet', section: '位置与状态', title: '勿扰模式', description: '暂停动作和主动提醒。', icon: 'moon', type: 'toggle', default: false, hidden: true }
  ];
  const defaults = Object.fromEntries(definitions.map(item => [item.key, item.default]));
  let renderApi = null;
  let pendingFrame = false;
  let pet = null;
  let drag = null;
  let suppressClickUntil = 0;
  let bubbleTimer = null;
  let animationFrame = null;
  let lastPaint = 0;
  let happyUntil = 0;
  let observedScreen = null;
  const preferences = () => window.DongranSettings;
  const get = key => preferences()?.get(key) ?? defaults[key];
  const set = (key, value) => preferences()?.set(key, value);
  const escape = value => String(value).replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character]));
  const icon = name => `<i data-lucide="${name}"></i>`;
  const refresh = () => preferences()?.refresh?.();
  const announce = (message, error = false) => renderApi?.showStatus(message, error);

  function activeColor() {
    const preset = get('themePreset');
    if (preset === 'custom') return hexColor(get('customAccent')) ? get('customAccent') : defaults.customAccent;
    if (preset === 'neutral' && document.documentElement.dataset.theme === 'dark') return '#DEDEE3';
    return presets.find(item => item.value === preset)?.color || presets[0].color;
  }

  function applyAccent() {
    const root = document.documentElement;
    const color = activeColor();
    const rgb = [1, 3, 5].map(index => parseInt(color.slice(index, index + 2), 16));
    const light = (rgb[0] * 299 + rgb[1] * 587 + rgb[2] * 114) / 1000 > 156;
    root.dataset.accentPreset = get('themePreset');
    root.style.setProperty('--accent', color);
    root.style.setProperty('--green', color);
    root.style.setProperty('--accent-contrast', light ? '#202024' : '#FFFFFF');
    root.style.setProperty('--accent-soft', `rgba(${rgb.join(',')},${root.dataset.theme === 'dark' ? '.18' : '.09'})`);
    root.style.setProperty('--accent-outline', `rgba(${rgb.join(',')},.34)`);
  }

  function syncThemeControls() {
    document.querySelectorAll('[data-theme-preset]').forEach(button => {
      const selected = button.dataset.themePreset === get('themePreset');
      button.setAttribute('aria-checked', String(selected));
      button.tabIndex = selected ? 0 : -1;
      if (button.dataset.themePreset === 'custom') button.querySelector('.theme-miniature')?.style.setProperty('--sample-accent', get('customAccent'));
    });
    document.querySelectorAll('[data-theme-color]').forEach(button => button.setAttribute('aria-pressed', String(get('themePreset') === 'custom' && button.dataset.themeColor === get('customAccent').toUpperCase())));
    const input = document.getElementById('theme-accent-input');
    if (input && input !== document.activeElement && !input.hasAttribute('aria-invalid')) input.value = get('customAccent');
    const dot = document.getElementById('theme-current-color');
    if (dot) dot.style.background = get('customAccent');
    const current = (get('savedThemes') || []).find(item => item.id === get('activeThemeId'));
    const label = document.querySelector('#saved-theme-trigger > span');
    if (label) label.textContent = current?.name || '选择已保存主题';
    const remove = document.getElementById('theme-delete');
    if (remove) remove.disabled = !current;
  }

  function miniature(color, name) {
    return `<span class="theme-miniature" style="--sample-accent:${color}" aria-hidden="true"><span class="theme-mini-sidebar"><b></b><i></i><i></i></span><span class="theme-mini-main"><i></i><i></i><span><b></b></span></span></span><span class="theme-preset-name">${escape(name)}</span>`;
  }

  function renderAppearance(api) {
    const selected = api.get('themePreset');
    const themes = api.get('savedThemes') || [];
    const activeTheme = themes.find(item => item.id === api.get('activeThemeId'));
    return `<section class="settings-section personalization-section" id="theme-studio">
      <h2>主题配色</h2>
      <div class="theme-presets" role="radiogroup" aria-label="主题配色">${presets.map(item => `<button type="button" class="theme-preset" data-theme-preset="${item.value}" role="radio" aria-checked="${selected === item.value}" tabindex="${selected === item.value ? 0 : -1}">${miniature(item.value === 'custom' ? api.get('customAccent') : item.color, item.label)}<span class="theme-preset-check">${icon('check')}</span></button>`).join('')}</div>
      <div class="theme-color-row"><label for="theme-accent-input">强调色</label><div class="theme-swatches">${swatches.map(([name, color]) => `<button type="button" class="theme-swatch" data-theme-color="${color}" style="--swatch:${color}" title="${name} ${color}" aria-label="${name} ${color}" aria-pressed="${selected === 'custom' && api.get('customAccent').toUpperCase() === color}"></button>`).join('')}</div><span class="theme-hex-field"><span id="theme-current-color" style="background:${api.get('customAccent')}"></span><input id="theme-accent-input" aria-label="HEX 强调色" type="text" value="${escape(api.get('customAccent'))}" maxlength="7" spellcheck="false" autocomplete="off"></span></div>
      <p id="theme-color-error" class="theme-field-error" role="status" hidden>请输入有效的 HEX 颜色，例如 #477AA8。</p>
      <div class="theme-save-row"><label for="theme-name-input">主题名称</label><input id="theme-name-input" type="text" value="${escape(api.get('themeName'))}" placeholder="为这套配色起个名字" maxlength="32" autocomplete="off"><button id="theme-save" class="settings-command" type="button">${icon('save')}保存主题</button></div>
      <div class="theme-saved-row"><span>我的主题 <span class="theme-count">${themes.length}</span></span><button id="saved-theme-trigger" class="settings-select" type="button" aria-haspopup="menu" aria-expanded="false" ${themes.length ? '' : 'disabled'}><span>${escape(activeTheme?.name || '选择已保存主题')}</span>${icon('chevron-down')}</button><button id="theme-delete" class="icon-btn" type="button" title="删除当前主题" aria-label="删除当前主题" ${activeTheme ? '' : 'disabled'}>${icon('trash-2')}</button></div>
    </section>`;
  }

  function renderPet(api) {
    const row = key => api.rowMarkup(definitions.find(item => item.key === key));
    const name = api.get('petName');
    return `<section class="settings-section pet-settings-section" id="pet-studio">
      <h2>桌面伙伴</h2>${row('petEnabled')}
      <div class="pet-preview-stage"><div class="pet-preview-subject"><canvas id="pet-preview" width="256" height="256" role="img" aria-label="${escape(name)}预览"></canvas><strong id="pet-preview-name">${escape(name)}</strong><span id="pet-preview-status">${api.get('petDnd') ? '勿扰中' : api.get('petEnabled') ? '工作台中已开启' : '未开启'}</span></div></div>
      <div class="pet-style-options" role="radiogroup" aria-label="机器人形象">${[['cubo', '小方'], ['capsule', '胶囊'], ['pixel', '像素']].map(([value, label]) => `<button type="button" class="pet-style-option" data-pet-style="${value}" role="radio" aria-checked="${api.get('petStyle') === value}" tabindex="${api.get('petStyle') === value ? 0 : -1}"><canvas width="128" height="128" data-pet-thumbnail="${value}" aria-hidden="true"></canvas><span>${label}</span>${icon('check')}</button>`).join('')}</div>
      ${row('petName')}
    </section><section class="settings-section"><h2>外观与动作</h2>${row('petSize')}${row('petOpacity')}${row('petActivity')}</section><section class="settings-section"><h2>位置与状态</h2>${row('petPosition')}${row('petDnd')}<div class="pet-position-footer"><span id="pet-position-label">${api.get('petCustomPosition') ? '使用拖动后的位置' : '使用预设停靠位置'}</span><button id="pet-reset-position" type="button" class="settings-command" ${api.get('petCustomPosition') ? '' : 'disabled'}>${icon('locate-fixed')}恢复停靠</button></div></section>`;
  }

  (window.DongranSettingsModules ||= []).push({
    categories: [{ id: 'pet', label: '桌面宠物', icon: 'bot', group: '个人偏好', subtitle: '为工作台选择一位安静的机器人伙伴。' }],
    definitions,
    render(category, api) {
      renderApi = api;
      scheduleUpdate();
      if (category === 'appearance') return renderAppearance(api);
      if (category === 'pet') return renderPet(api);
      return '';
    }
  });

  function drawRobot(canvas, style = 'cubo', blink = false, happy = false, gaze = 0) {
    if (!canvas) return;
    const context = canvas.getContext('2d');
    if (!context) return;
    const scale = canvas.width / 128;
    context.setTransform(scale, 0, 0, scale, 0, 0);
    context.clearRect(0, 0, 128, 128);
    const pixel = style === 'pixel';
    const fill = (x, y, width, height, radius, color, stroke) => {
      context.beginPath();
      context.roundRect(x, y, width, height, pixel ? 1 : radius);
      context.fillStyle = color;
      context.fill();
      if (stroke) { context.strokeStyle = stroke; context.lineWidth = 1.6; context.stroke(); }
    };
    context.fillStyle = 'rgba(35,45,50,.10)';
    context.beginPath(); context.ellipse(64, 114, 30, 5, 0, 0, Math.PI * 2); context.fill();
    fill(43, 99, 16, 10, 4, '#B9C3C8');
    fill(69, 99, 16, 10, 4, '#B9C3C8');
    fill(38, 71, 52, 32, 11, '#E3E9EA', '#ADB9BE');
    fill(23, happy ? 67 : 78, 10, 23, 5, '#C2CDD0', '#ACBABF');
    fill(95, happy ? 67 : 78, 10, 23, 5, '#C2CDD0', '#ACBABF');
    fill(59, 80, 10, 8, 3, activeColor());
    fill(61, 16, 6, 16, 3, '#ABBABF');
    fill(58, 13, 12, 9, 4, activeColor());
    const capsule = style === 'capsule';
    fill(capsule ? 31 : 25, 29, capsule ? 66 : 78, 50, capsule ? 25 : 17, '#EEF2F2', '#AEBBBF');
    fill(capsule ? 38 : 33, 38, capsule ? 52 : 62, 32, capsule ? 16 : 11, '#2E3B41');
    context.fillStyle = '#D5ECE8';
    if (happy) {
      context.strokeStyle = '#D5ECE8'; context.lineWidth = 3; context.lineCap = 'round';
      [49, 74].forEach(x => { context.beginPath(); context.moveTo(x - 4, 55); context.quadraticCurveTo(x, 47, x + 4, 55); context.stroke(); });
    } else {
      fill(45 + gaze, blink ? 54 : 47, 8, blink ? 2 : 12, 4, '#D5ECE8');
      fill(75 + gaze, blink ? 54 : 47, 8, blink ? 2 : 12, 4, '#D5ECE8');
    }
    fill(60, 62, 8, 2, 1, '#8FA5A9');
    context.fillStyle = 'rgba(255,255,255,.35)';
    context.beginPath(); context.arc(37, 34, 3, 0, Math.PI * 2); context.fill();
  }

  function petBounds() {
    const area = document.querySelector('.messages') || document.querySelector('.workspace');
    const rectangle = area?.getBoundingClientRect();
    const configuredSize = Number(get('petSize')) || 80;
    const availableSize = Math.max(40, Math.min((rectangle?.width || innerWidth) - 24, (rectangle?.height || innerHeight) - 40));
    const size = Math.min(configuredSize, availableSize);
    const left = Math.max(12, rectangle?.left || 12) + 10;
    const top = Math.max(64, rectangle?.top || 64) + 10;
    return { left, top, right: Math.max(left, (rectangle?.right || innerWidth) - size - 10), bottom: Math.max(top, (rectangle?.bottom || innerHeight - 64) - size - 28), size };
  }

  function positionPet() {
    if (!pet || drag) return;
    const bounds = petBounds();
    pet.style.setProperty('--pet-size', `${bounds.size}px`);
    const custom = get('petCustomPosition');
    const position = get('petPosition');
    const x = custom?.x ?? (position === 'bottom-left' ? 0 : 1);
    const y = custom?.y ?? (position === 'top-right' ? 0 : 1);
    pet.style.left = `${bounds.left + (bounds.right - bounds.left) * x}px`;
    pet.style.top = `${bounds.top + (bounds.bottom - bounds.top) * y}px`;
    pet.dataset.bubbleSide = parseFloat(pet.style.top) < 160 ? 'bottom' : 'top';
  }

  function createPet() {
    if (pet) return;
    pet = document.createElement('div');
    pet.id = 'dongran-pet';
    pet.className = 'workspace-pet';
    pet.hidden = true;
    pet.innerHTML = `<div id="pet-bubble" class="pet-bubble" role="status" hidden></div><button id="pet-close" class="pet-close" type="button" title="关闭桌面宠物" aria-label="关闭桌面宠物">${icon('x')}</button><button id="pet-avatar" class="pet-avatar" type="button" title="机器人伙伴"><span class="pet-motion"><canvas id="pet-live-canvas" width="256" height="256" aria-hidden="true"></canvas></span></button><span id="pet-live-name" class="pet-live-name"></span>`;
    document.body.append(pet);
    window.lucide?.createIcons();
    const avatar = pet.querySelector('#pet-avatar');
    avatar.addEventListener('pointerdown', event => {
      if (event.button !== 0) return;
      const r = pet.getBoundingClientRect();
      drag = { id: event.pointerId, x: event.clientX, y: event.clientY, left: r.left, top: r.top, moved: false };
      avatar.setPointerCapture(event.pointerId);
    });
    avatar.addEventListener('pointermove', event => {
      if (!drag || drag.id !== event.pointerId) return;
      const deltaX = event.clientX - drag.x;
      const deltaY = event.clientY - drag.y;
      if (Math.hypot(deltaX, deltaY) < 4 && !drag.moved) return;
      drag.moved = true;
      pet.classList.add('is-dragging');
      pet.querySelector('#pet-bubble').hidden = true;
      const bounds = petBounds();
      pet.style.left = `${Math.max(bounds.left, Math.min(bounds.right, drag.left + deltaX))}px`;
      pet.style.top = `${Math.max(bounds.top, Math.min(bounds.bottom, drag.top + deltaY))}px`;
    });
    const finishDrag = event => {
      if (!drag || drag.id !== event.pointerId) return;
      const moved = drag.moved;
      drag = null;
      pet.classList.remove('is-dragging');
      if (avatar.hasPointerCapture(event.pointerId)) avatar.releasePointerCapture(event.pointerId);
      if (!moved) return;
      suppressClickUntil = Date.now() + 300;
      const bounds = petBounds();
      const x = (parseFloat(pet.style.left) - bounds.left) / Math.max(1, bounds.right - bounds.left);
      const y = (parseFloat(pet.style.top) - bounds.top) / Math.max(1, bounds.bottom - bounds.top);
      set('petCustomPosition', { x: Math.max(0, Math.min(1, x)), y: Math.max(0, Math.min(1, y)) });
    };
    avatar.addEventListener('pointerup', finishDrag);
    avatar.addEventListener('pointercancel', finishDrag);
    avatar.addEventListener('click', () => {
      if (Date.now() < suppressClickUntil) return;
      clearTimeout(bubbleTimer);
      const bubble = pet.querySelector('#pet-bubble');
      bubble.textContent = get('petDnd') ? `${get('petName')}正在安静陪伴。` : `${get('petName')}已就绪，今天一起完成什么？`;
      bubble.hidden = false;
      happyUntil = Date.now() + 3200;
      drawRobot(pet.querySelector('canvas'), get('petStyle'), false, !get('petDnd'));
      bubbleTimer = setTimeout(() => { bubble.hidden = true; drawRobot(pet.querySelector('canvas'), get('petStyle')); }, 3200);
    });
    pet.querySelector('#pet-close').addEventListener('click', () => set('petEnabled', false));
    const messages = document.querySelector('.messages');
    if (messages) new ResizeObserver(() => positionPet()).observe(messages);
  }

  function animateRobots(time) {
    animationFrame = null;
    const preview = document.getElementById('pet-preview');
    const visiblePet = pet && !pet.hidden;
    const visiblePreview = preview && preview.offsetParent !== null;
    if ((!visiblePet && !visiblePreview) || document.hidden || get('petDnd') || get('reduceMotion') || get('petActivity') === 'quiet' || matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    if (time - lastPaint > 85) {
      lastPaint = time;
      const speed = get('petActivity') === 'lively' ? 1400 : 2500;
      const blink = time % (speed * 2) < 120;
      const gaze = Math.sin(time / speed) * 2;
      if (visiblePet) drawRobot(pet.querySelector('canvas'), get('petStyle'), blink, Date.now() < happyUntil && !get('petDnd'), gaze);
      if (visiblePreview) drawRobot(preview, get('petStyle'), blink, false, gaze);
    }
    animationFrame = requestAnimationFrame(animateRobots);
  }

  function syncPet() {
    createPet();
    const screen = document.getElementById('settings-screen');
    if (screen && observedScreen !== screen) {
      new MutationObserver(scheduleUpdate).observe(screen, { attributes: true, attributeFilter: ['hidden', 'class'] });
      observedScreen = screen;
    }
    pet.hidden = !get('petEnabled') || !!screen && !screen.hidden || !!window.DongranPages?.current();
    pet.style.setProperty('--pet-size', `${petBounds().size}px`);
    pet.style.setProperty('--pet-opacity', String(get('petOpacity') / 100));
    pet.dataset.activity = get('petDnd') || get('reduceMotion') ? 'quiet' : get('petActivity');
    pet.querySelector('#pet-live-name').textContent = get('petName');
    pet.querySelector('#pet-avatar').setAttribute('aria-label', `${get('petName')}，机器人伙伴`);
    pet.querySelector('#pet-avatar').title = get('petName');
    drawRobot(pet.querySelector('canvas'), get('petStyle'));
    positionPet();
    const preview = document.getElementById('pet-preview');
    if (preview) {
      preview.style.width = `${Math.min(152, Number(get('petSize')) + 36)}px`;
      preview.style.height = preview.style.width;
      preview.style.opacity = String(get('petOpacity') / 100);
      preview.dataset.activity = get('petDnd') || get('reduceMotion') ? 'quiet' : get('petActivity');
      preview.setAttribute('aria-label', `${get('petName')}预览`);
      drawRobot(preview, get('petStyle'));
      document.getElementById('pet-preview-name').textContent = get('petName');
      document.getElementById('pet-preview-status').textContent = get('petDnd') ? '勿扰中' : get('petEnabled') ? '工作台中已开启' : '未开启';
    }
    document.querySelectorAll('[data-pet-thumbnail]').forEach(canvas => drawRobot(canvas, canvas.dataset.petThumbnail));
    document.querySelectorAll('[data-pet-style]').forEach(button => {
      const selected = button.dataset.petStyle === get('petStyle');
      button.setAttribute('aria-checked', String(selected));
      button.tabIndex = selected ? 0 : -1;
    });
    const positionLabel = document.getElementById('pet-position-label');
    if (positionLabel) positionLabel.textContent = get('petCustomPosition') ? '使用拖动后的位置' : '使用预设停靠位置';
    const resetPosition = document.getElementById('pet-reset-position');
    if (resetPosition) resetPosition.disabled = !get('petCustomPosition');
    if (animationFrame !== null) cancelAnimationFrame(animationFrame);
    animationFrame = requestAnimationFrame(animateRobots);
  }

  function scheduleUpdate() {
    if (pendingFrame) return;
    pendingFrame = true;
    requestAnimationFrame(() => { pendingFrame = false; if (!preferences()) return; applyAccent(); syncThemeControls(); syncPet(); });
  }

  function useColor(color) {
    if (!hexColor(color)) return false;
    set('customAccent', color.toUpperCase());
    set('themePreset', 'custom');
    set('activeThemeId', '');
    return true;
  }

  function saveTheme() {
    const name = document.getElementById('theme-name-input')?.value.trim() || get('themeName').trim();
    if (!name) { announce('请先填写主题名称。', true); document.getElementById('theme-name-input')?.focus(); return; }
    const input = document.getElementById('theme-accent-input');
    if (input && !hexColor(input.value)) { input.setAttribute('aria-invalid', 'true'); announce('强调色格式无效。', true); input.focus(); return; }
    const themes = get('savedThemes') || [];
    const prior = themes.find(item => item.name.toLocaleLowerCase() === name.toLocaleLowerCase());
    if (!prior && themes.length >= 20) { announce('最多保存 20 个主题，请先删除不再使用的主题。', true); return; }
    const entry = { id: prior?.id || `theme-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`, name, mode: get('theme'), color: activeColor() };
    set('savedThemes', prior ? themes.map(item => item.id === prior.id ? entry : { ...item }) : [...themes.map(item => ({ ...item })), entry]);
    set('themeName', name);
    set('activeThemeId', entry.id);
    refresh();
    announce('主题已保存。');
  }

  function chooseSavedTheme(trigger) {
    const themes = get('savedThemes') || [];
    window.DongranUI.showMenu(trigger, { label: '我的主题', items: themes.map(item => ({ value: item.id, label: item.name, checked: get('activeThemeId') === item.id })), onSelect(id) {
      const selected = (get('savedThemes') || []).find(item => item.id === id);
      if (!selected) return;
      set('theme', selected.mode);
      set('customAccent', selected.color);
      set('themePreset', 'custom');
      set('themeName', selected.name);
      set('activeThemeId', selected.id);
      refresh();
    } });
  }

  function deleteTheme() {
    const current = (get('savedThemes') || []).find(item => item.id === get('activeThemeId'));
    if (!current) return;
    window.DongranUI.showDialog('删除主题', `<p class="personalization-confirm">删除“${escape(current.name)}”后将恢复中性配色。</p><div class="dialog-actions"><button id="theme-delete-cancel" class="secondary" type="button">取消</button><button id="theme-delete-confirm" class="primary" type="button">删除主题</button></div>`);
    document.getElementById('theme-delete-cancel').addEventListener('click', () => window.DongranUI.closeDialog());
    document.getElementById('theme-delete-confirm').addEventListener('click', () => {
      set('savedThemes', (get('savedThemes') || []).filter(item => item.id !== current.id).map(item => ({ ...item })));
      set('activeThemeId', ''); set('themeName', ''); set('themePreset', 'neutral');
      window.DongranUI.closeDialog(); refresh(); announce('主题已删除。');
    });
  }

  document.addEventListener('click', event => {
    const button = event.target.closest('button');
    if (!button || button.disabled) return;
    if (button.dataset.themePreset) {
      set('themePreset', button.dataset.themePreset); set('activeThemeId', '');
    } else if (button.dataset.themeColor) useColor(button.dataset.themeColor);
    else if (button.dataset.petStyle) set('petStyle', button.dataset.petStyle);
    else if (button.id === 'theme-save') saveTheme();
    else if (button.id === 'saved-theme-trigger') chooseSavedTheme(button);
    else if (button.id === 'theme-delete') deleteTheme();
    else if (button.id === 'pet-reset-position') { set('petCustomPosition', null); refresh(); }
  });
  document.addEventListener('input', event => {
    if (event.target.id === 'theme-name-input') set('themeName', event.target.value);
    if (event.target.id === 'theme-accent-input') {
      event.target.removeAttribute('aria-invalid');
      const error = document.getElementById('theme-color-error'); if (error) error.hidden = true;
    }
  });
  document.addEventListener('change', event => {
    if (event.target.id !== 'theme-accent-input') return;
    if (!useColor(event.target.value.trim())) {
      event.target.setAttribute('aria-invalid', 'true');
      const error = document.getElementById('theme-color-error'); if (error) error.hidden = false;
    }
  });
  document.addEventListener('keydown', event => {
    const radio = event.target.closest('[data-theme-preset], [data-pet-style]');
    if (radio && ['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
      event.preventDefault();
      const entries = [...radio.parentElement.querySelectorAll('button[role="radio"]')];
      const index = entries.indexOf(radio);
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? entries.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + entries.length) % entries.length;
      const target = entries[next];
      const selector = target.dataset.themePreset ? `[data-theme-preset="${target.dataset.themePreset}"]` : `[data-pet-style="${target.dataset.petStyle}"]`;
      target.click(); document.querySelector(selector)?.focus();
    }
    if (event.target.id === 'saved-theme-trigger' && ['ArrowDown', 'ArrowUp'].includes(event.key)) { event.preventDefault(); chooseSavedTheme(event.target); }
    if (event.target.id === 'theme-name-input' && event.key === 'Enter') { event.preventDefault(); saveTheme(); }
  });
  window.addEventListener('settingschange', event => {
    if (event.detail?.key === 'petPosition' && get('petCustomPosition')) set('petCustomPosition', null);
    if (['reset', 'import'].includes(event.detail?.key)) {
      clearTimeout(bubbleTimer);
      if (pet) pet.querySelector('#pet-bubble').hidden = true;
    }
    scheduleUpdate();
  });
  window.addEventListener('resize', scheduleUpdate);
  window.addEventListener('workspaceviewchange', scheduleUpdate);
  document.addEventListener('visibilitychange', scheduleUpdate);
  matchMedia('(prefers-reduced-motion: reduce)').addEventListener('change', scheduleUpdate);
  new MutationObserver(scheduleUpdate).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'data-reduce-motion'] });
  scheduleUpdate();
})();
