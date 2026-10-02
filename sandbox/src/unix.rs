#[cfg(target_os = "linux")]
mod linux {
    use crate::Request;
    use serde_json::{json, Value};
    use std::os::fd::{FromRawFd, IntoRawFd};
    use std::{
        fs::File,
        os::unix::process::CommandExt,
        path::{Path, PathBuf},
        process::{Child, Command, Stdio},
    };
    #[repr(C)]
    struct RLimit {
        current: u64,
        maximum: u64,
    }
    extern "C" {
        fn setrlimit(resource: i32, limit: *const RLimit) -> i32;
        fn setsid() -> i32;
        fn kill(pid: i32, signal: i32) -> i32;
    }
    pub struct Process {
        child: Child,
        stdout: Option<File>,
        stderr: Option<File>,
    }
    impl Process {
        pub fn backend(&self) -> &'static str {
            "linux-bubblewrap"
        }
        pub fn id(&self) -> u32 {
            self.child.id()
        }
        pub fn take_output(&mut self) -> (File, File) {
            (self.stdout.take().unwrap(), self.stderr.take().unwrap())
        }
        pub fn try_wait(&mut self) -> Result<Option<i32>, String> {
            self.child
                .try_wait()
                .map(|status| status.map(|s| s.code().unwrap_or(128)))
                .map_err(|e| e.to_string())
        }
        pub fn cleanup(&mut self) -> Result<(), String> {
            self.kill()
        }
        pub fn kill(&mut self) -> Result<(), String> {
            // bwrap's PID namespace is torn down when its PID 1 dies. --die-with-parent
            // also covers abrupt helper death; no host process execution fallback exists.
            unsafe {
                kill(-(self.child.id() as i32), 9);
            }
            let _ = self.child.kill();
            self.child.wait().map(|_| ()).map_err(|e| e.to_string())
        }
    }
    impl Drop for Process {
        fn drop(&mut self) {
            let _ = self.kill();
        }
    }
    fn bwrap() -> Result<PathBuf, String> {
        ["/usr/bin/bwrap", "/bin/bwrap"]
            .iter()
            .map(PathBuf::from)
            .find(|p| p.is_file())
            .ok_or_else(|| "bubblewrap is not installed; native isolation is unavailable".into())
    }
    fn command(request: &Request) -> Result<Command, String> {
        let mut command = Command::new(bwrap()?);
        command
            .env_clear()
            .arg("--unshare-all")
            .arg("--die-with-parent")
            .arg("--new-session")
            .arg("--cap-drop")
            .arg("ALL");
        // Mount only system runtime trees, not /, /home, /run, credentials or host sockets.
        for root in ["/usr", "/bin", "/sbin", "/lib", "/lib64"] {
            if Path::new(root).exists() {
                command.args(["--ro-bind", root, root]);
            }
        }
        for file in ["/etc/ld.so.cache", "/etc/ld.so.conf"] {
            if Path::new(file).exists() {
                command.args(["--ro-bind", file, file]);
            }
        }
        for root in &request.read_roots {
            command.arg("--ro-bind").arg(root).arg(root);
        }
        command.args([
            "--proc",
            "/proc",
            "--dev",
            "/dev",
            "--tmpfs",
            "/tmp",
            "--dir",
            "/home/sandbox",
        ]);
        command
            .arg("--bind")
            .arg(&request.workspace)
            .arg(&request.workspace)
            .arg("--chdir")
            .arg(&request.workspace);
        let mut paths = vec![
            "/usr/local/bin".to_string(),
            "/usr/bin".into(),
            "/bin".into(),
        ];
        for root in &request.read_roots {
            paths.push(root.join("bin").to_string_lossy().into_owned());
            paths.push(root.to_string_lossy().into_owned());
        }
        command.args([
            "--setenv",
            "PATH",
            &paths.join(":"),
            "--setenv",
            "HOME",
            "/home/sandbox",
            "--setenv",
            "TMPDIR",
            "/tmp",
            "--setenv",
            "LANG",
            "C.UTF-8",
        ]);
        for root in &request.read_roots {
            if root.join("bin/java").is_file() {
                command.arg("--setenv").arg("JAVA_HOME").arg(root);
                break;
            }
        }
        command
            .arg("--")
            .args(&request.argv)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        let memory = request.memory_mb * 1024 * 1024;
        let cpu = request.timeout_seconds + 1;
        let processes = request.max_processes as u64;
        unsafe {
            command.pre_exec(move || {
                if setsid() < 0 {
                    return Err(std::io::Error::last_os_error());
                }
                // Linux RLIMIT_AS bounds virtual address space, not resident working-set size.
                for (resource, value) in [
                    (9, memory),
                    (0, cpu),
                    (6, processes),
                    (1, 64 * 1024 * 1024),
                    (7, 256),
                ] {
                    if setrlimit(
                        resource,
                        &RLimit {
                            current: value,
                            maximum: value,
                        },
                    ) != 0
                    {
                        return Err(std::io::Error::last_os_error());
                    }
                }
                Ok(())
            });
        }
        Ok(command)
    }
    pub fn spawn(request: &Request) -> Result<Process, String> {
        let mut child = command(request)?
            .spawn()
            .map_err(|e| format!("Start bubblewrap: {e}; no host fallback"))?;
        let stdout = unsafe { File::from_raw_fd(child.stdout.take().unwrap().into_raw_fd()) };
        let stderr = unsafe { File::from_raw_fd(child.stderr.take().unwrap().into_raw_fd()) };
        Ok(Process {
            child,
            stdout: Some(stdout),
            stderr: Some(stderr),
        })
    }
    pub fn probe() -> Value {
        let result = bwrap().and_then(|path| {
            let result = Command::new(path)
                .env_clear()
                .args([
                    "--unshare-all",
                    "--die-with-parent",
                    "--ro-bind",
                    "/usr",
                    "/usr",
                    "--ro-bind",
                    "/bin",
                    "/bin",
                    "--ro-bind",
                    "/lib",
                    "/lib",
                    "--ro-bind-try",
                    "/lib64",
                    "/lib64",
                    "--proc",
                    "/proc",
                    "--dev",
                    "/dev",
                    "--",
                    "/bin/true",
                ])
                .output()
                .map_err(|e| e.to_string())?;
            if result.status.success() {
                Ok(())
            } else {
                Err(format!(
                    "bubblewrap self-test failed: {}",
                    String::from_utf8_lossy(&result.stderr)
                        .chars()
                        .take(1000)
                        .collect::<String>()
                ))
            }
        });
        match result {
            Ok(()) => {
                json!({"protocolVersion":1,"available":true,"platform":"linux","backend":"linux-bubblewrap","reason":"User, mount, PID and network namespaces available; limits use RLIMIT_AS/NPROC/CPU","networkIsolation":true})
            }
            Err(reason) => {
                json!({"protocolVersion":1,"available":false,"platform":"linux","backend":"linux-bubblewrap","reason":reason,"networkIsolation":false})
            }
        }
    }
}
#[cfg(target_os = "linux")]
pub use linux::*;

#[cfg(not(target_os = "linux"))]
mod unsupported {
    use crate::Request;
    use serde_json::{json, Value};
    use std::fs::File;
    pub struct Process;
    impl Process {
        pub fn backend(&self) -> &'static str {
            "unsupported"
        }
        pub fn id(&self) -> u32 {
            0
        }
        pub fn take_output(&mut self) -> (File, File) {
            unreachable!()
        }
        pub fn try_wait(&mut self) -> Result<Option<i32>, String> {
            Err("Unsupported platform".into())
        }
        pub fn cleanup(&mut self) -> Result<(), String> {
            self.kill()
        }
        pub fn kill(&mut self) -> Result<(), String> {
            Ok(())
        }
    }
    pub fn spawn(_: &Request) -> Result<Process, String> {
        Err("Native sandbox is not yet supported on this platform. No host execution fallback is permitted.".into())
    }
    pub fn probe() -> Value {
        json!({"protocolVersion":1,"available":false,"platform":std::env::consts::OS,"backend":"unsupported","reason":"macOS native process ownership and sandbox policies require further validation; execution is disabled","networkIsolation":false})
    }
}
#[cfg(not(target_os = "linux"))]
pub use unsupported::*;
