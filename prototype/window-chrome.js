(() => {
  const api=window.__TAURI__?.window;
  if(!api)return;
  const win=api.getCurrentWindow(),bar=document.querySelector('.desktop-bar');
  if(!bar)return;
  document.documentElement.classList.add('native-titlebar');
  const controls=document.createElement('div');controls.className='window-controls';
  controls.innerHTML='<button aria-label="最小化" title="最小化" data-window="minimize">−</button><button aria-label="最大化" title="最大化" data-window="maximize">□</button><button aria-label="关闭窗口" title="关闭窗口" data-window="close">×</button>';
  bar.querySelector('[data-action="fullscreen"]')?.remove();bar.append(controls);
  const report=e=>{console.error('Window action failed',e);if(typeof toast==='function')toast('窗口操作失败，请重试');};
  const sync=async()=>{const maximized=await win.isMaximized();const b=controls.querySelector('[data-window="maximize"]');b.textContent=maximized?'❐':'□';b.title=maximized?'还原':'最大化';b.setAttribute('aria-label',b.title);};
  controls.addEventListener('click',async e=>{const action=e.target.closest('button')?.dataset.window;try{if(action==='minimize')await win.minimize();else if(action==='maximize'){await win.toggleMaximize();await sync();}else if(action==='close')await win.close();}catch(error){report(error);}});
  const blank=e=>!e.target.closest('button,a,input,nav,.window-controls');
  bar.addEventListener('mousedown',e=>{if(e.button===0&&e.detail===1&&blank(e))win.startDragging().catch(report);});
  bar.addEventListener('dblclick',e=>{if(blank(e))win.toggleMaximize().then(sync).catch(report);});
  window.addEventListener('resize',()=>sync().catch(report));sync().catch(report);
})();
