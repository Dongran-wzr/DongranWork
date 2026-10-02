# Native command sandbox

Java remains the policy and approval authority. This Rust helper starts an OS-restricted command and returns NDJSON over stdio. There is no TCP listener, Docker dependency, implicit unrestricted fallback, JNI, or model credential in the child environment.

## Platform status

| Platform | Backend | Status |
| --- | --- | --- |
| Windows | AppContainer with zero capabilities + Job Object | Real local integration tests pass |
| Linux | bubblewrap user/mount/PID/network namespaces | Cross-compiled type check passes; Linux runtime testing remains required |
| macOS | None enabled | Probe reports unavailable; execution fails closed |

Windows profiles are unique per execution. The helper grants that profile's SID modify rights and a low integrity label only on the **disposable task workspace**. Original security descriptors are captured before mutation. Cleanup uses held object handles, so replacing a path cannot redirect privileged ACL restoration. The Job Object must reach zero active processes before cleanup and the final event. ACL/profile cleanup failures are execution failures.

The host project must never be passed directly as the workspace. Java owns snapshot creation, excludes private files and links, checks conflicts before synchronization, and copies file **contents**, not ACLs/attributes, back to the project. Newly created snapshot files may retain the temporary SID/low integrity label until the snapshot is deleted.

System runtime files that Windows already permits AppContainers to read remain available. This is Windows' AppContainer access boundary, not a virtual machine or a claim that only one directory exists. No network capabilities or loopback exemptions are granted. Only the three command stdio handles can be inherited. The child starts suspended, enters a non-breakaway kill-on-close Job Object, then resumes.

`readRoots` builds PATH/JAVA_HOME on Windows but **does not modify host toolchain ACLs**. A JDK/Node/Maven installation outside AppContainer-readable directories may fail with access denied. The helper must not weaken isolation to make it start. Linux exposes approved roots read-only. The caller must never approve a whole user profile or credential directory as a tool root.

Linux requires installed `bwrap` and usable unprivileged user namespaces. Its minimal filesystem exposes system runtime trees, approved read roots, the task workspace, private `/proc`, `/dev`, `/tmp`, and a private home. Linux memory limits use **RLIMIT_AS (virtual address space)**, not RSS/cgroup working-set limits; 512 MiB is insufficient for many JVM/V8 workloads. RLIMIT_NPROC can also be affected by the host UID's other processes. Distribution-specific validation and resource-policy refinement are still needed.

## Build

```sh
cargo fmt --manifest-path sandbox/Cargo.toml --check
cargo test --locked --manifest-path sandbox/Cargo.toml
cargo build --locked --release --manifest-path sandbox/Cargo.toml
```

Output: `sandbox/target/release/dongran-sandbox[.exe]`. The desktop package includes the platform binary; users do not need Rust.

## Protocol v1

`dongran-sandbox probe` emits one object:

```json
{"protocolVersion":1,"available":true,"platform":"windows","backend":"windows-appcontainer","reason":"...","networkIsolation":true}
```

`dongran-sandbox run` reads a newline-terminated request, at most 64 KiB:

```json
{
  "protocolVersion": 1,
  "executionId": "task-123",
  "workspace": "C:\\application-data\\sandboxes\\task-123\\workspace",
  "argv": ["C:\\Windows\\System32\\cmd.exe", "/d", "/c", "echo hello"],
  "timeoutSeconds": 60,
  "memoryMb": 512,
  "maxProcesses": 32,
  "network": "deny",
  "readRoots": []
}
```

Bounds: timeout 1–3600 seconds; memory 64–8192 MiB; processes 1–128; at most 256 argv items and 16 read roots. Only `network:"deny"` is supported. `argv[0]` must be an absolute existing executable. Workspace symlinks, junctions and other reparse points are rejected before execution.

The caller **keeps stdin open**. A subsequent line (normally `{"type":"cancel"}`), EOF, timeout, output overflow, or helper termination cancels execution. Command stdin receives EOF; this is noninteractive execution, not a PTY.

Stdout contains only events:

```json
{"type":"started","backend":"windows-appcontainer","pid":1234}
{"type":"output","stream":"stdout","text":"hello\n"}
{"type":"exit","exitCode":0,"status":"completed"}
```

Errors emit `{"type":"error","message":"..."}` and a failed exit. Status values are `completed`, `failed`, `timed_out`, `cancelled`. Combined raw output is limited to 2 MiB, emitted in chunks no larger than 8192 input bytes; overflow terminates the process tree. Invalid non-UTF-8 output is replaced rather than interpreted as protocol. Normal parent-command completion also terminates descendants.

PowerShell's filesystem provider cannot enumerate private ancestor directories in AppContainer. The Java wrapper creates a private drive instead:

```powershell
New-PSDrive -Name DongranTask -PSProvider FileSystem -Root $env:DONGRAN_SANDBOX_WORKSPACE -Scope Global -ErrorAction Stop | Out-Null
Set-Location 'DongranTask:\' -ErrorAction Stop
```

`DONGRAN_SANDBOX_WORKSPACE` is injected by the helper. No ancestor permissions are broadened. `cmd /c` command tails use cmd's quoting rules rather than CRT backslash escaping.

## Verification

On Windows:

```sh
cargo build --locked --manifest-path sandbox/Cargo.toml
cargo build --locked --manifest-path sandbox/Cargo.toml --examples
python sandbox/tests/verify_windows.py
```

The OS suite checks task read/write/delete, private-file read/write denial, loopback network denial against a local listening socket, removal of inherited secret environment variables, descendant termination after normal completion and timeout, cancellation/control-pipe EOF, and output limits. It uses disposable test files and no external network calls.

Additional local checks confirmed PowerShell's private drive supports relative writes, and a command attempting to create an external directory junction was denied without changing the external target ACL.

These tests verify the implemented boundaries; they are not an independent security audit. macOS isolation and Linux runtime validation remain release blockers for claiming three-platform native sandbox support.
