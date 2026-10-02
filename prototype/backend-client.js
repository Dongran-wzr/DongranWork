(() => {
  const state = { available: false, base: '', session: false, boot: null };
  let bootPromise = null;
  const json = async (response) => {
    const body = await response.json().catch(() => ({}));
    if (!response.ok) { const error = new Error(body.message || `请求失败（${response.status}）`); error.code = body.code; error.status = response.status; throw error; }
    return body;
  };
  async function connect(base = '') {
    if (bootPromise) return bootPromise;
    state.base = base || window.DongranBackendBase || `${location.protocol}//${location.host}`;
    if (!/^https?:\/\//.test(state.base)) { state.base = ''; return null; }
    bootPromise = (async () => {
      try {
        const health = await fetch(`${state.base}/api/health`, { cache: 'no-store' });
        if (!health.ok) throw new Error('本地服务未就绪');
        await json(await fetch(`${state.base}/api/session`, { method: 'POST', credentials: 'include', headers: { 'X-Dongran-Client': 'desktop' } }));
        const bootstrap = await json(await fetch(`${state.base}/api/bootstrap`, { credentials: 'include', headers: { 'X-Dongran-Client': 'desktop' } }));
        state.available = true; state.session = true; state.boot = bootstrap;
        window.dispatchEvent(new CustomEvent('backendready', { detail: bootstrap }));
        return bootstrap;
      } catch (error) { state.available=false; state.error=error.message; bootPromise=null; return null; }
    })();
    return bootPromise;
  }
  async function request(path, options = {}) {
    if (!state.available) await connect();
    if (!state.available) throw Object.assign(new Error('本地服务尚未启动。'), { code: 'BACKEND_UNAVAILABLE' });
    const headers = { 'X-Dongran-Client': 'desktop', ...(options.body && !(options.body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}), ...(options.headers || {}) };
    let response=await fetch(`${state.base}${path}`, { ...options, headers, credentials: 'include' });
    if(response.status===401){
      bootPromise=null;state.available=false;
      await connect();
      if(state.available)response=await fetch(`${state.base}${path}`, { ...options, headers, credentials: 'include' });
    }
    return json(response);
  }
  function events(taskId, options = {}) {
    if (!state.available) throw Object.assign(new Error('本地服务尚未启动。'), { code: 'BACKEND_UNAVAILABLE' });
    const query = options.after ? `?after=${encodeURIComponent(options.after)}` : '';
    const source = new EventSource(`${state.base}/api/tasks/${encodeURIComponent(taskId)}/events${query}`, { withCredentials: true });
    return source;
  }
  window.DongranBackend = Object.freeze({ state, connect, request, events, isAvailable: () => state.available });
  if (window.__TAURI__ || window.DongranBackendBase || /^https?:$/.test(location.protocol)) connect();
})();
