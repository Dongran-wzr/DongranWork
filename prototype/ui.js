(() => {
  const dialog = document.querySelector('#dialog');
  const menu = document.createElement('div');
  menu.id = 'app-menu';
  menu.className = 'app-menu';
  menu.setAttribute('popover', 'manual');
  menu.setAttribute('role', 'menu');
  document.body.append(menu);

  let anchor = null;
  let selectItem = null;
  let dialogCloseTimer = null;
  let menuCloseTimer = null;
  let restoreMenuFocus = false;
  const reducedMotion = () => document.documentElement.dataset.reduceMotion==='true' || matchMedia('(prefers-reduced-motion: reduce)').matches;
  const menuOpen = () => menu.matches(':popover-open');
  const buttons = () => [...menu.querySelectorAll('button:not(:disabled)')];
  const refreshIcons = () => window.lucide?.createIcons();
  const menubarTrigger = '.desktop-bar nav [data-menu]';

  function finishMenuClose() {
    clearTimeout(menuCloseTimer);
    const focusWasInside = menu.contains(document.activeElement) || document.activeElement === document.body;
    if (menuOpen()) menu.hidePopover();
    menu.classList.remove('is-closing');
    if (restoreMenuFocus && focusWasInside && anchor?.isConnected) anchor.focus({ preventScroll: true });
    restoreMenuFocus = false;
    anchor = null;
  }

  function closeMenu({ restoreFocus = true, immediate = false } = {}) {
    if (!menuOpen()) return;
    restoreMenuFocus = restoreFocus;
    anchor?.setAttribute('aria-expanded', 'false');
    if (restoreFocus && anchor?.isConnected) anchor.focus({ preventScroll: true });
    if (immediate || reducedMotion()) finishMenuClose();
    else {
      menu.classList.add('is-closing');
      clearTimeout(menuCloseTimer);
      menuCloseTimer = setTimeout(finishMenuClose, 120);
    }
  }

  function positionMenu() {
    if (!anchor?.isConnected) { closeMenu({ immediate: true }); return; }
    const r = anchor.getBoundingClientRect();
    const viewport = window.visualViewport;
    const leftEdge = viewport?.offsetLeft || 0;
    const topEdge = viewport?.offsetTop || 0;
    const width = viewport?.width || innerWidth;
    const height = viewport?.height || innerHeight;
    const gutter = 10;
    menu.style.maxWidth = `${width - gutter * 2}px`;
    menu.style.maxHeight = `${height - gutter * 2}px`;
    const m = { width: menu.offsetWidth, height: menu.offsetHeight };
    const above = r.bottom + m.height + gutter + 7 > topEdge + height && r.top > m.height + gutter;
    const left = Math.max(leftEdge + gutter, Math.min(r.left, leftEdge + width - m.width - gutter));
    const top = above ? r.top - m.height - 7 : Math.min(r.bottom + 7, topEdge + height - m.height - gutter);
    menu.style.left = `${left}px`;
    menu.style.top = `${Math.max(topEdge + gutter, top)}px`;
    menu.style.transformOrigin = above ? 'left bottom' : 'left top';
    menu.dataset.side = above ? 'top' : 'bottom';
  }

  function showMenu(trigger, { label, items, onSelect, showHeading = false, showDescriptions = false }) {
    if (anchor === trigger && menuOpen() && !menu.classList.contains('is-closing')) {
      closeMenu(); return;
    }
    closeMenu({ immediate: true, restoreFocus: false });
    clearTimeout(menuCloseTimer);
    anchor = trigger;
    selectItem = onSelect;
    const host = trigger.closest('dialog') || document.body;
    if (menu.parentElement !== host) host.append(menu);
    menu.replaceChildren();
    menu.setAttribute('aria-label', label);
    if (showHeading) {
      const heading = document.createElement('div');
      heading.className = 'menu-heading'; heading.textContent = label; menu.append(heading);
    }
    for (const item of items) {
      if (item.type === 'separator') {
        const separator = document.createElement('div');
        separator.className = 'menu-separator'; separator.setAttribute('role', 'separator'); menu.append(separator);
        continue;
      }
      const button = document.createElement('button');
      button.type = 'button'; button.className = 'menu-option'; button.dataset.value = item.value;
      button.setAttribute('role', item.checked === undefined ? 'menuitem' : 'menuitemradio');
      button.tabIndex = -1;
      if (item.disabled) { button.disabled = true; button.setAttribute('aria-disabled', 'true'); }
      if (item.checked !== undefined) button.setAttribute('aria-checked', String(item.checked));
      if (item.icon) {
        const glyph = document.createElement('i'); glyph.setAttribute('data-lucide', item.icon); button.append(glyph);
      }
      const text = document.createElement('span'); text.className = 'menu-option-text';
      const title = document.createElement('span'); title.className = 'menu-option-title'; title.textContent = item.label;
      text.append(title);
      if (showDescriptions && item.description) {
        const description = document.createElement('small'); description.textContent = item.description; text.append(description);
      }
      button.append(text);
      if (item.shortcut) {
        const shortcut = document.createElement('span'); shortcut.className = 'menu-shortcut'; shortcut.textContent = item.shortcut;
        button.append(shortcut);
      }
      if (item.checked !== undefined) {
        const mark = document.createElement('span'); mark.className = 'menu-option-check';
        if (item.checked) { const check = document.createElement('i'); check.setAttribute('data-lucide', 'check'); mark.append(check); }
        button.append(mark);
      }
      menu.append(button);
    }
    trigger.setAttribute('aria-haspopup', 'menu');
    trigger.setAttribute('aria-expanded', 'true');
    trigger.setAttribute('aria-controls', menu.id);
    menu.showPopover();
    refreshIcons();
    positionMenu();
    const selected = menu.querySelector('[aria-checked="true"]:not(:disabled)') || buttons()[0];
    selected?.focus({ preventScroll: true });
  }

  menu.addEventListener('click', event => {
    const option = event.target.closest('.menu-option');
    if (!option || option.disabled || menu.classList.contains('is-closing')) return;
    const callback = selectItem;
    closeMenu({ immediate: true });
    callback?.(option.dataset.value);
  });
  menu.addEventListener('keydown', event => {
    const entries = buttons();
    let index = entries.indexOf(document.activeElement);
    if (event.key === 'Escape') {
      event.preventDefault(); event.stopPropagation(); closeMenu(); return;
    }
    if (event.key === 'Tab') { closeMenu({ immediate: true }); return; }
    if (['ArrowLeft', 'ArrowRight'].includes(event.key) && anchor?.matches(menubarTrigger)) {
      event.preventDefault(); event.stopPropagation();
      const triggers = [...document.querySelectorAll(menubarTrigger)].filter(trigger => !trigger.disabled);
      const next = (triggers.indexOf(anchor) + (event.key === 'ArrowRight' ? 1 : -1) + triggers.length) % triggers.length;
      const trigger = triggers[next];
      trigger?.focus({ preventScroll: true }); trigger?.click();
      return;
    }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    if (event.key === 'Home') index = 0;
    else if (event.key === 'End') index = entries.length - 1;
    else index = (index + (event.key === 'ArrowDown' ? 1 : -1) + entries.length) % entries.length;
    entries[index]?.focus({ preventScroll: true });
  });
  document.addEventListener('pointerdown', event => {
    if (menuOpen() && !menu.contains(event.target) && !anchor?.contains(event.target)) closeMenu();
  }, true);
  document.addEventListener('pointerenter', event => {
    const trigger = event.target instanceof Element && event.target.matches(menubarTrigger) ? event.target : null;
    if (trigger && !trigger.disabled && menuOpen() && !menu.classList.contains('is-closing') && trigger !== anchor) trigger.click();
  }, true);
  document.addEventListener('keydown', event => {
    const trigger = event.target.closest('[data-menu]');
    if (trigger && ['ArrowDown', 'ArrowUp'].includes(event.key)) {
      event.preventDefault(); trigger.click();
    }
  });
  window.addEventListener('resize', () => closeMenu({ immediate: true }));
  document.addEventListener('scroll', event => {
    if (!menu.contains(event.target)) closeMenu({ immediate: true, restoreFocus: false });
  }, true);

  function closeDialog() {
    if (!dialog.open) return;
    closeMenu({ immediate: true, restoreFocus: false });
    clearTimeout(dialogCloseTimer);
    const finish = () => {
      dialog.close(); dialog.classList.remove('is-visible', 'is-closing');
    };
    if (reducedMotion()) finish();
    else {
      dialog.classList.remove('is-visible'); dialog.classList.add('is-closing');
      dialogCloseTimer = setTimeout(finish, 160);
    }
  }

  function showDialog(title, body) {
    clearTimeout(dialogCloseTimer);
    closeMenu({ immediate: true, restoreFocus: false });
    document.querySelector('#dialog-title').textContent = title;
    document.querySelector('#dialog-content').innerHTML = body;
    dialog.classList.remove('is-visible', 'is-closing');
    if (!dialog.open) dialog.showModal();
    void dialog.offsetWidth;
    dialog.classList.add('is-visible');
    refreshIcons();
  }
  dialog.addEventListener('cancel', event => { event.preventDefault(); closeDialog(); });
  window.DongranUI = { showMenu, closeMenu, showDialog, closeDialog };
})();
