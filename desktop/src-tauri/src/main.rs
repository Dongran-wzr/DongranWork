#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::{env, fs, path::PathBuf, process::{Child, Command, Stdio}, sync::Mutex, thread, time::Duration};
use tauri::{Manager, RunEvent};

struct Backend(Mutex<Option<Child>>);

fn backend_jar(app: &tauri::AppHandle) -> PathBuf {
    if cfg!(debug_assertions) {
        PathBuf::from(env::var("DONGRAN_BACKEND_JAR").unwrap_or_else(|_| "backend/target/dongran-backend-0.1.0-SNAPSHOT.jar".into()))
    } else {
        app.path().resource_dir().unwrap().join("backend/target/dongran-backend-0.1.0-SNAPSHOT.jar")
    }
}

fn start_backend(app: &tauri::AppHandle) -> Result<Child, Box<dyn std::error::Error>> {
    let jar = backend_jar(app);
    if !jar.exists() { return Err(format!("backend JAR not found: {}", jar.display()).into()); }
    let data = app.path().app_data_dir()?;
    fs::create_dir_all(&data)?;
    let port = env::var("DONGRAN_PORT").unwrap_or_else(|_| "3210".into());
    let child = Command::new("java")
        .arg("-XX:MaxRAMPercentage=40")
        .arg("-jar").arg(jar)
        .env("DONGRAN_PORT", &port)
        .env("DONGRAN_DATA_DIR", data)
        .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).spawn()?;
    for _ in 0..100 {
        if let Ok(response) = ureq::get(&format!("http://127.0.0.1:{port}/api/health")).call() {
            if response.status() == 200 { return Ok(child); }
        }
        thread::sleep(Duration::from_millis(100));
    }
    Err("backend did not become ready within 10 seconds".into())
}

fn main() {
    let builder = tauri::Builder::default().setup(|app| {
        let child = start_backend(app.handle()).map_err(|error| error.to_string())?;
        app.manage(Backend(Mutex::new(Some(child))));
        Ok(())
    });
    builder.build(tauri::generate_context!()).expect("error while building tauri application").run(|app, event| {
        if let RunEvent::ExitRequested { api, .. } = event { api.prevent_exit(); if let Some(state) = app.try_state::<Backend>() { if let Some(mut child) = state.0.lock().unwrap().take() { let _ = child.kill(); let _ = child.wait(); } } app.exit(0); }
    });
}
