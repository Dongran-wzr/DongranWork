#!/usr/bin/env sh
set -eu
task_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$task_root"
sh ./scripts/build-sandbox.sh
sh ./mvnw -q package
mkdir -p desktop/src-tauri/resources/sandbox
cp backend/target/dongran-backend-0.1.0-SNAPSHOT.jar desktop/src-tauri/resources/backend.jar
cp sandbox/target/release/dongran-sandbox desktop/src-tauri/resources/sandbox/dongran-sandbox
chmod 755 desktop/src-tauri/resources/sandbox/dongran-sandbox
echo "Desktop resources staged. Build Tauri on this same operating system and architecture."
echo "Java runtime packaging and installer signing still require release setup."
