"""Verify the real macOS .app starts its bundled backend, without cloud requests."""
import json
import pathlib
import time
import urllib.request

root = pathlib.Path.home() / 'Library/Application Support/com.dongran.work'
deadline = time.monotonic() + 45
while time.monotonic() < deadline:
    try:
        runtime = json.loads((root / 'runtime.json').read_text())
        port = runtime['port']
        with urllib.request.urlopen(f'http://127.0.0.1:{port}/api/health', timeout=2) as response:
            assert json.load(response)['status'] == 'ok'
        with urllib.request.urlopen(f'http://127.0.0.1:{port}/window-chrome.js', timeout=2) as response:
            assert b'mac-titlebar' in response.read()
        print('Bundled macOS app and backend healthy')
        break
    except (OSError, ValueError, KeyError, AssertionError):
        time.sleep(0.5)
else:
    for name in ('backend.out.log', 'backend.err.log'):
        path = root / name
        if path.exists():
            print(path.read_text()[-4000:])
    raise SystemExit('macOS installed-app smoke test failed')
