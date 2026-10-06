"""Seal the final beta app, then create and verify its distributable DMG."""
import pathlib
import subprocess
import tempfile
import plistlib


def run(*args):
    return subprocess.check_output(args, stderr=subprocess.STDOUT)


bundle = pathlib.Path('desktop/src-tauri/target/release/bundle')
apps = list((bundle / 'macos').glob('*.app'))
if len(apps) != 1:
    raise SystemExit('Expected one final macOS application')
app = apps[0]
# jlink copies Mach-O files whose original signatures may no longer describe
# the generated runtime. Sign each final file before sealing the outer app.
for file in sorted(app.rglob('*')):
    if file.is_file() and not file.is_symlink():
        description = run('file', '-b', str(file)).decode()
        if 'Mach-O' in description:
            run('codesign', '--force', '--sign', '-', '--timestamp=none', str(file))
run('codesign', '--force', '--sign', '-', '--timestamp=none', str(app))
print(run('codesign', '--verify', '--deep', '--strict', '--verbose=2', str(app)).decode())
with (app / 'Contents/Info.plist').open('rb') as stream:
    version = plistlib.load(stream)['CFBundleShortVersionString']
arch = run('uname', '-m').decode().strip()
output = bundle / 'dmg'
output.mkdir(exist_ok=True)
dmg = output / f'Dongran-Work_{version}_macos-{arch}.dmg'
with tempfile.TemporaryDirectory() as directory:
    stage = pathlib.Path(directory) / 'stage'
    stage.mkdir()
    run('ditto', str(app), str(stage / app.name))
    (stage / 'Applications').symlink_to('/Applications')
    run('hdiutil', 'create', '-volname', 'Dongran Work', '-srcfolder', str(stage),
        '-format', 'UDZO', str(dmg))
    run('hdiutil', 'verify', str(dmg))
    mount = pathlib.Path(directory) / 'mounted'
    mount.mkdir()
    run('hdiutil', 'attach', '-readonly', '-nobrowse', '-mountpoint', str(mount), str(dmg))
    try:
        print(run('codesign', '--verify', '--deep', '--strict', '--verbose=2',
                  str(mount / app.name)).decode())
    finally:
        run('hdiutil', 'detach', str(mount))
print(f'Verified DMG: {dmg}. Ad-hoc signed beta; not Apple notarized.')
