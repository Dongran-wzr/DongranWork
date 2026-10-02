use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    io::{self, BufRead, Read, Write},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    thread,
    time::{Duration, Instant},
};

#[cfg(unix)]
mod unix;
#[cfg(windows)]
mod windows;

const REQUEST_LIMIT: usize = 64 * 1024;
const OUTPUT_LIMIT: usize = 2 * 1024 * 1024;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Request {
    pub protocol_version: u32,
    pub execution_id: String,
    pub workspace: PathBuf,
    pub argv: Vec<String>,
    pub timeout_seconds: u64,
    #[serde(default = "default_memory")]
    pub memory_mb: u64,
    #[serde(default = "default_processes")]
    pub max_processes: u32,
    pub network: String,
    #[serde(default)]
    pub read_roots: Vec<PathBuf>,
}
fn default_memory() -> u64 {
    512
}
fn default_processes() -> u32 {
    32
}

impl Request {
    fn validate(&mut self) -> Result<(), String> {
        if self.protocol_version != 1 {
            return Err("Unsupported protocol version".into());
        }
        if self.execution_id.is_empty()
            || self.execution_id.len() > 128
            || !self
                .execution_id
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
        {
            return Err("Invalid executionId".into());
        }
        if self.network != "deny" {
            return Err("Only network=deny is supported; no implicit host fallback".into());
        }
        if !(1..=3600).contains(&self.timeout_seconds)
            || !(64..=8192).contains(&self.memory_mb)
            || !(1..=128).contains(&self.max_processes)
        {
            return Err("Execution resource limits are outside permitted ranges".into());
        }
        if !self.workspace.is_absolute() || !self.workspace.is_dir() {
            return Err("Workspace must be an existing absolute directory".into());
        }
        reject_links(&self.workspace, 0)?;
        self.workspace = self
            .workspace
            .canonicalize()
            .map_err(|e| format!("Workspace: {e}"))?;
        if self.workspace.parent().is_none() || self.workspace.components().count() < 3 {
            return Err("A dedicated task workspace is required".into());
        }
        if self.argv.is_empty()
            || self.argv.len() > 256
            || self.argv.iter().any(|a| a.contains('\0'))
        {
            return Err("An executable and at most 255 arguments are required".into());
        }
        if !Path::new(&self.argv[0]).is_absolute() || !Path::new(&self.argv[0]).is_file() {
            return Err("argv[0] must name an existing absolute executable".into());
        }
        if self.read_roots.len() > 16 {
            return Err("Too many readRoots".into());
        }
        for root in &mut self.read_roots {
            if !root.is_absolute() || !root.is_dir() {
                return Err("readRoots must be absolute existing directories".into());
            }
            if is_link(root)? {
                return Err("readRoots cannot be symbolic links or reparse points".into());
            }
            *root = root.canonicalize().map_err(|e| e.to_string())?;
            if root.parent().is_none() || root.components().count() < 3 {
                return Err("readRoots cannot expose a filesystem root".into());
            }
        }
        Ok(())
    }
}

fn is_link(path: &Path) -> Result<bool, String> {
    let metadata = std::fs::symlink_metadata(path).map_err(|e| e.to_string())?;
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        Ok(metadata.file_attributes() & 0x400 != 0)
    }
    #[cfg(not(windows))]
    {
        Ok(metadata.file_type().is_symlink())
    }
}
fn reject_links(path: &Path, depth: usize) -> Result<(), String> {
    if depth > 128 {
        return Err("Workspace directory nesting exceeds 128 levels".into());
    }
    if is_link(path)? {
        return Err("Workspace contains a symbolic link or reparse point".into());
    }
    if path.is_dir() {
        for child in std::fs::read_dir(path).map_err(|e| e.to_string())? {
            reject_links(&child.map_err(|e| e.to_string())?.path(), depth + 1)?;
        }
    }
    Ok(())
}

pub fn event(value: Value) {
    let stdout = io::stdout();
    let mut out = stdout.lock();
    let _ = serde_json::to_writer(&mut out, &value);
    let _ = out.write_all(b"\n");
    let _ = out.flush();
}

fn read_line_bounded(reader: &mut impl BufRead) -> Result<String, String> {
    let mut bytes = Vec::new();
    let n = reader
        .take((REQUEST_LIMIT + 1) as u64)
        .read_until(b'\n', &mut bytes)
        .map_err(|e| e.to_string())?;
    if n == 0 {
        return Err("Input closed before a request was received".into());
    }
    if n > REQUEST_LIMIT || bytes.last() != Some(&b'\n') {
        return Err("Request must be a newline-terminated JSON object of at most 64 KiB".into());
    }
    String::from_utf8(bytes).map_err(|_| "Request must be valid UTF-8".into())
}

fn output_pump(
    mut reader: impl Read + Send + 'static,
    stream: &'static str,
    budget: Arc<Mutex<usize>>,
    exceeded: Arc<AtomicBool>,
) -> thread::JoinHandle<()> {
    thread::spawn(move || {
        let mut buffer = [0_u8; 8192];
        let mut pending = Vec::new();
        loop {
            let n = match reader.read(&mut buffer) {
                Ok(0) | Err(_) => break,
                Ok(n) => n,
            };
            {
                let mut total = budget.lock().unwrap();
                if *total + n > OUTPUT_LIMIT {
                    exceeded.store(true, Ordering::SeqCst);
                    break;
                }
                *total += n;
            }
            pending.extend_from_slice(&buffer[..n]);
            let valid_len = match std::str::from_utf8(&pending) {
                Ok(_) => pending.len(),
                Err(e) if e.error_len().is_none() => e.valid_up_to(),
                Err(_) => pending.len(),
            };
            if valid_len > 0 {
                event(
                    json!({"type":"output","stream":stream,"text":String::from_utf8_lossy(&pending[..valid_len])}),
                );
                pending.drain(..valid_len);
            }
        }
        if !pending.is_empty() {
            event(
                json!({"type":"output","stream":stream,"text":String::from_utf8_lossy(&pending)}),
            );
        }
    })
}

fn run() -> Result<(), String> {
    let stdin = io::stdin();
    let mut reader = io::BufReader::new(stdin);
    let line = read_line_bounded(&mut reader)?;
    let mut request: Request =
        serde_json::from_str(&line).map_err(|e| format!("Invalid request: {e}"))?;
    request.validate()?;
    #[cfg(windows)]
    let mut process = windows::spawn(&request)?;
    #[cfg(unix)]
    let mut process = unix::spawn(&request)?;
    #[cfg(not(any(windows, unix)))]
    return Err("Unsupported platform".into());

    #[cfg(any(windows, unix))]
    {
        let cancelled = Arc::new(AtomicBool::new(false));
        let signal = cancelled.clone();
        thread::spawn(move || {
            // Closing the control pipe is cancellation, never permission to keep running.
            let _ = read_line_bounded(&mut reader);
            signal.store(true, Ordering::SeqCst);
        });
        event(json!({"type":"started","backend":process.backend(),"pid":process.id()}));
        let budget = Arc::new(Mutex::new(0));
        let exceeded = Arc::new(AtomicBool::new(false));
        let (stdout, stderr) = process.take_output();
        let stdout_thread = output_pump(stdout, "stdout", budget.clone(), exceeded.clone());
        let stderr_thread = output_pump(stderr, "stderr", budget, exceeded.clone());
        let start = Instant::now();
        let (code, status) = loop {
            if cancelled.load(Ordering::SeqCst) {
                process.kill()?;
                break (130, "cancelled");
            }
            if exceeded.load(Ordering::SeqCst) {
                event(
                    json!({"type":"error","message":"Combined command output exceeded 2 MiB; process tree terminated"}),
                );
                process.kill()?;
                break (125, "failed");
            }
            if start.elapsed() >= Duration::from_secs(request.timeout_seconds) {
                process.kill()?;
                break (124, "timed_out");
            }
            if let Some(code) = process.try_wait()? {
                process.kill()?; // Descendants must not survive the primary command.
                break (code, if code == 0 { "completed" } else { "failed" });
            }
            thread::sleep(Duration::from_millis(25));
        };
        let _ = stdout_thread.join();
        let _ = stderr_thread.join();
        process.cleanup()?; // Report cleanup failure before the caller can synchronize files.
        drop(process);
        event(json!({"type":"exit","exitCode":code,"status":status}));
        Ok(())
    }
}

fn main() {
    let command = std::env::args().nth(1).unwrap_or_default();
    match command.as_str() {
        "probe" => {
            #[cfg(windows)]
            let probe = windows::probe();
            #[cfg(unix)]
            let probe = unix::probe();
            #[cfg(not(any(windows, unix)))]
            let probe = json!({"protocolVersion":1,"available":false,"platform":std::env::consts::OS,"backend":"unsupported","reason":"Unsupported operating system","networkIsolation":false});
            event(probe);
        }
        "run" => {
            if let Err(message) = run() {
                event(json!({"type":"error","message":message}));
                event(json!({"type":"exit","exitCode":125,"status":"failed"}));
                std::process::exit(1);
            }
        }
        _ => {
            eprintln!("Usage: dongran-sandbox probe | run");
            std::process::exit(2);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_partial_line() {
        assert!(read_line_bounded(&mut io::Cursor::new(b"{}")).is_err());
    }
    #[test]
    fn rejects_oversized_line() {
        assert!(read_line_bounded(&mut io::Cursor::new(vec![b'a'; REQUEST_LIMIT + 2])).is_err());
    }
    #[test]
    fn rejects_unknown_settings() {
        assert!(serde_json::from_str::<Request>(r#"{"protocolVersion":1,"executionId":"t","workspace":"/a/b","argv":["/bin/sh"],"timeoutSeconds":3,"network":"deny","privileged":true}"#).is_err());
    }
    #[test]
    fn preserves_request_defaults() {
        let request: Request = serde_json::from_str(r#"{"protocolVersion":1,"executionId":"t","workspace":"/a/b","argv":["/bin/sh"],"timeoutSeconds":3,"network":"deny"}"#).unwrap();
        assert_eq!(request.memory_mb, 512);
        assert_eq!(request.max_processes, 32);
    }
}
