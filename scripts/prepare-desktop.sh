#!/usr/bin/env sh
set -eu
task_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$task_root"
sh ./scripts/build-sandbox.sh
if [ "${1:-}" != "--skip-backend-build" ]; then sh ./mvnw -q package; fi
mkdir -p desktop/src-tauri/resources/sandbox
cp backend/target/dongran-backend-0.1.0-SNAPSHOT.jar desktop/src-tauri/resources/backend.jar
cp sandbox/target/release/dongran-sandbox desktop/src-tauri/resources/sandbox/dongran-sandbox
chmod 755 desktop/src-tauri/resources/sandbox/dongran-sandbox
echo "Desktop resources staged. Build Tauri on this same operating system and architecture."
: "${JAVA_HOME:?JAVA_HOME must point to JDK 21}"
task_runtime="$task_root/desktop/src-tauri/resources/runtime"
if [ -e "$task_runtime" ]; then
  echo "Generated runtime already exists; use a clean build directory." >&2
  exit 1
fi
"$JAVA_HOME/bin/jlink" --add-modules ALL-MODULE-PATH --strip-debug --no-header-files --no-man-pages --output "$task_runtime"
test -x "$task_runtime/bin/java"
echo "Bundled Java runtime ready."
