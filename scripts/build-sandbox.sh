#!/usr/bin/env sh
set -eu
task_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -x "$task_root/.tools/rust/cargo/bin/cargo" ]; then
  CARGO_HOME="$task_root/.tools/rust/cargo"
  RUSTUP_HOME="$task_root/.tools/rust/rustup"
  PATH="$CARGO_HOME/bin:$PATH"
  export CARGO_HOME RUSTUP_HOME PATH
fi
if ! command -v cargo >/dev/null 2>&1; then
  echo "Building the helper requires Rust on the developer machine. Released applications include the helper." >&2
  exit 1
fi
cargo build --locked --release --manifest-path "$task_root/sandbox/Cargo.toml"
echo "Sandbox helper: $task_root/sandbox/target/release/dongran-sandbox"
