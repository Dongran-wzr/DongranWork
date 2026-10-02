# Dongran Work desktop shell

The Tauri 2 shell starts the Spring Boot JAR on loopback, waits for its health endpoint, and opens that same authenticated origin. The packaged resource mapping includes the backend and native sandbox helper. A packaged launch passes the installed helper's absolute path to Java; it never searches the opened project or PATH for a helper.

## Developer builds

Java 21, the Rust toolchain, and the platform's Tauri build prerequisites are needed on the developer/CI machine. Windows Rust builds also need the MSVC C++ build tools and Windows SDK. A repository-local toolchain in `.tools/rust` is supported without modifying the system PATH.

Prepare the backend and native helper on the same operating system and architecture as the desktop application:

```powershell
# From the repository root on Windows; Java 21 must already be available.
.\scripts\prepare-desktop.ps1
```

```sh
# From the repository root on macOS or Linux.
sh scripts/prepare-desktop.sh
```

Preparation builds the backend with its tests, builds `sandbox/` in release mode, and copies:

```text
desktop/src-tauri/resources/
  backend.jar
  sandbox/dongran-sandbox.exe     # Windows
  sandbox/dongran-sandbox         # macOS / Linux
```

The generated resource directory is ignored by Git. After preparation, run the Tauri CLI from `desktop/` (for example `cargo tauri dev` or `cargo tauri build` if the CLI is installed). Stop any existing development backend on the selected port first; the shell rejects an occupied port instead of attaching to another process. There is deliberately no `devUrl` server wait: the shell itself starts Java, then navigates the window to the backend origin. The shell's debug build resolves source paths from its crate directory. `DONGRAN_BACKEND_JAR` and `DONGRAN_SANDBOX_HELPER` overrides apply only to a debug shell; release shells use installed resource paths. `DONGRAN_PORT` chooses the loopback port.

To preview without compiling Tauri:

```powershell
.\scripts\start-dev.ps1 -Build -BuildSandbox -JavaHome 'C:\path\to\jdk21' -Restart
```

This runs live copies of the JAR and helper from `.runtime/live` so rebuilding does not overwrite the running artifacts. Building the helper is optional for opening the application: when missing or unable to establish isolation, the backend reports it as unavailable and sandboxed commands fail closed.

## Runtime and release boundary

End users do not need Docker or Rust. The current native backends are:

| Platform | Mechanism and runtime prerequisites | Validation boundary |
| --- | --- | --- |
| Windows | AppContainer with no network capabilities; per-task workspace ACLs; Job Object for process lifetime and limits | Runtime probe must pass. System shells and installed toolchains have different AppContainer compatibility; a successful probe is not a toolchain certification. |
| Linux | `bubblewrap` at `/usr/bin/bwrap` or `/bin/bwrap`; kernel and system policy must permit user/mount/PID/network namespaces | Native Linux integration has not been exercised on the Windows development machine. If missing or denied, commands stay disabled. RLIMIT_AS limits virtual address space and may reject JVM/V8 reservations. |
| macOS | Native backend not yet enabled | The helper reports unsupported and commands remain disabled; this is not a delivered macOS sandbox. |

Installing the helper does not prove that every local Java, Node, Maven, or other toolchain is compatible with isolation. No network capabilities are granted for downloading Maven/npm dependencies in this first version. Read [the repository sandbox notes](../README.md#agent-命令沙箱).

The shell still starts `java` from PATH. Bundling a JVM, graceful shutdown/tray behavior, signing/notarizing the application and helper, and testing complete installers on Windows, macOS and Linux remain release work. Copying the helper into resources is packaging integration, not an installer certification. In particular, Unix executable permissions and macOS signing of the nested helper must be verified in the installed application.

The Java process, controlled file/Git tools, model calls, and connections run outside the command sandbox. They retain their own application permissions. The shell does not claim to sandbox the entire desktop application.

The Windows shell has passed `cargo check --locked --jobs 2 --manifest-path desktop/src-tauri/Cargo.toml` with staged backend/helper resources. This verifies Rust types, build scripts and resource generation, not linking, launching a desktop window, or installing a release package. `Cargo.lock` is kept for reproducible dependency resolution; generated schemas and staged binaries are ignored.


Host terminals use the backend's pty4j native bridge (ConPTY on Windows, PTY on Unix) and xterm.js. The shell requests authenticated terminal cleanup before killing Java on normal application exit. Panel hiding keeps sessions alive; refreshing the webview reconnects them; restarting the application does not restore processes. These are explicitly user-operated host sessions and do not change Agent sandbox policy. Windows packaged-JAR browser integration and Rust type checks are covered; macOS/Linux terminal behavior and full desktop installers still need native testing.
