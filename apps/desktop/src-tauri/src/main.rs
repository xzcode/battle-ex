#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
mod network;

use network::NetworkAgent;
use serde_json::Value;
use tauri::{AppHandle, Manager, State};

#[tauri::command]
async fn network_start(
    app: AppHandle,
    state: State<'_, NetworkAgent>,
    origin: String,
    token: String,
    issuer: String,
    user_id: String,
) -> Result<(), String> {
    let agent = state.inner().clone();
    let dir = app.path().app_local_data_dir().map_err(|e| e.to_string())?;
    tauri::async_runtime::spawn_blocking(move || agent.start(&dir, origin, token, issuer, user_id))
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn network_status(state: State<'_, NetworkAgent>) -> Result<Value, String> {
    let agent = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || agent.status())
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn network_stop(state: State<'_, NetworkAgent>) -> Result<(), String> {
    let agent = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || agent.stop())
        .await
        .map_err(|e| e.to_string())?
}

fn main() {
    tauri::Builder::default()
        .manage(NetworkAgent::default())
        .invoke_handler(tauri::generate_handler![
            network_start,
            network_status,
            network_stop
        ])
        .build(tauri::generate_context!())
        .expect("could not initialize Battle EX")
        .run(|app, event| {
            if let tauri::RunEvent::Exit = event {
                let _ = app.state::<NetworkAgent>().stop();
            }
        });
}
