#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::{
    env, fs,
    net::TcpListener,
    path::PathBuf,
    process::{Child, Command, Stdio},
    sync::Mutex,
    thread,
    time::Duration,
};
use tauri::{Manager, RunEvent};

struct Backend(Mutex<Option<Child>>);

fn repository_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(|path| path.parent())
        .expect("desktop crate must be inside the repository")
        .to_path_buf()
}

fn backend_jar(app: &tauri::AppHandle) -> Result<PathBuf, Box<dyn std::error::Error>> {
    if cfg!(debug_assertions) {
        Ok(env::var_os("DONGRAN_BACKEND_JAR")
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                repository_root().join("backend/target/dongran-backend-0.1.0-SNAPSHOT.jar")
            }))
    } else {
        Ok(app.path().resource_dir()?.join("resources/backend.jar"))
    }
}

fn sandbox_helper(app: &tauri::AppHandle) -> Result<PathBuf, Box<dyn std::error::Error>> {
    let name = if cfg!(target_os = "windows") {
        "dongran-sandbox.exe"
    } else {
        "dongran-sandbox"
    };
    if cfg!(debug_assertions) {
        Ok(env::var_os("DONGRAN_SANDBOX_HELPER")
            .map(PathBuf::from)
            .unwrap_or_else(|| repository_root().join("sandbox/target/release").join(name)))
    } else {
        // A packaged helper is an application resource, never a binary found on PATH
        // or a user-selectable executable from the opened project.
        Ok(app.path().resource_dir()?.join("resources/sandbox").join(name))
    }
}

fn start_backend(
    app: &tauri::AppHandle,
) -> Result<(Child, String), Box<dyn std::error::Error>> {
    let jar = backend_jar(app)?;
    if !jar.is_file() {
        return Err(format!("backend JAR not found: {}", jar.display()).into());
    }
    let helper = sandbox_helper(app)?;
    let data = app.path().app_data_dir()?;
    fs::create_dir_all(&data)?;
    let port: u16 = env::var("DONGRAN_PORT")
        .unwrap_or_else(|_| "0".into())
        .parse()?;
    // Do not attach the desktop UI to an unrelated process occupying the port.
    let listener = TcpListener::bind(("127.0.0.1", port))?;
    let port = listener.local_addr()?.port();
    drop(listener);
    let url = format!("http://127.0.0.1:{port}");
    let java = if cfg!(debug_assertions) {
        PathBuf::from("java")
    } else {
        app.path().resource_dir()?.join(if cfg!(target_os = "windows") {
            "resources/runtime/bin/java.exe"
        } else {
            "resources/runtime/bin/java"
        })
    };
    let mut command = Command::new(java);
    command
        .arg("-Xms32m")
        .arg("-Xmx512m")
        .arg("-XX:+UseSerialGC")
        .current_dir(jar.parent().ok_or("missing backend directory")?)
        .arg("-jar")
        .arg(jar.file_name().ok_or("missing backend filename")?)
        .arg(format!("--server.port={port}"))
        .arg(format!("--dongran.data-dir={}", data.display()))
        .arg(format!("--dongran.sandbox-helper={}", helper.display()))
        .stdin(Stdio::null())
        .stdout(Stdio::from(fs::File::create(data.join("backend.out.log"))?))
        .stderr(Stdio::from(fs::File::create(data.join("backend.err.log"))?));
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000); // CREATE_NO_WINDOW
    }
    let mut child = command.spawn()?;
    for _ in 0..300 {
        if let Some(status) = child.try_wait()? {
            return Err(format!("backend exited before becoming ready: {status}").into());
        }
        if let Ok(response) = ureq::get(&format!("{url}/api/health")).call() {
            if response.status() == 200 {
                return Ok((child, url));
            }
        }
        thread::sleep(Duration::from_millis(100));
    }
    let _ = child.kill();
    let _ = child.wait();
    Err("backend did not become ready within the startup deadline".into())
}

// Stop host PTYs before terminating Java; force-killing the JVM skips Spring cleanup.
fn close_terminals(app: &tauri::AppHandle, pid: u32) -> Result<(), Box<dyn std::error::Error>> {
    let bytes = fs::read(app.path().app_data_dir()?.join("runtime.json"))?;
    let runtime: serde_json::Value = serde_json::from_slice(&bytes)?;
    if runtime["pid"].as_u64() != Some(pid as u64) { return Ok(()); }
    let port = runtime["port"].as_u64().filter(|p| *p > 0 && *p <= 65535).ok_or("invalid backend port")?;
    let token = runtime["token"].as_str().ok_or("missing backend session")?;
    let client: ureq::Agent = ureq::Agent::config_builder()
        .timeout_global(Some(Duration::from_secs(8)))
        .max_redirects(0)
        .build().into();
    client.delete(&format!("http://127.0.0.1:{port}/api/terminals"))
        .header("Authorization", &format!("Bearer {token}"))
        .header("X-Dongran-Client", "desktop").call()?;
    Ok(())
}

fn main() {
    let builder = tauri::Builder::default().setup(|app| {
        let (mut child, url) = start_backend(app.handle()).map_err(|error| error.to_string())?;
        // Both development and packaged windows use the authenticated backend
        // origin. Bundled static files alone cannot serve relative API requests.
        if let Some(window) = app.get_webview_window("main") {
            if let Err(error) = window.navigate(url.parse()?) {
                let _ = child.kill();
                let _ = child.wait();
                return Err(error.into());
            }
        }
        app.manage(Backend(Mutex::new(Some(child))));
        Ok(())
    });
    builder
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, event| {
            if let RunEvent::ExitRequested { api, .. } = event {
                api.prevent_exit();
                if let Some(state) = app.try_state::<Backend>() {
                    if let Some(mut child) = state.0.lock().unwrap().take() {
                        let _ = close_terminals(app, child.id());
                        let _ = child.kill();
                        let _ = child.wait();
                    }
                }
                app.exit(0);
            }
        });
}
