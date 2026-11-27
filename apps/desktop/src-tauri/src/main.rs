#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
mod games;
mod network;

use games::{Entry, GameLibrary, Install};
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

#[tauri::command]
async fn game_list(app: AppHandle, state: State<'_, GameLibrary>) -> Result<Vec<Entry>, String> {
    let games = state.inner().clone();
    let dir = app.path().app_local_data_dir().map_err(|e| e.to_string())?;
    tauri::async_runtime::spawn_blocking(move || games.list(&dir))
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn game_detect(state: State<'_, GameLibrary>, id: String) -> Result<Vec<String>, String> {
    let games = state.inner().clone();

    tauri::async_runtime::spawn_blocking(move || games.detect(&id))
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn game_choose(
    app: AppHandle,
    state: State<'_, GameLibrary>,
    id: String,
) -> Result<Option<Install>, String> {
    let games = state.inner().clone();
    let dir = app.path().app_local_data_dir().map_err(|e| e.to_string())?;
    tauri::async_runtime::spawn_blocking(move || games.choose(&dir, &id))
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn game_verify(
    app: AppHandle,
    state: State<'_, GameLibrary>,
    id: String,
) -> Result<bool, String> {
    let games = state.inner().clone();
    let dir = app.path().app_local_data_dir().map_err(|e| e.to_string())?;
    tauri::async_runtime::spawn_blocking(move || games.verify(&dir, &id))
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn game_remove(
    app: AppHandle,
    state: State<'_, GameLibrary>,
    id: String,
) -> Result<(), String> {
    let games = state.inner().clone();
    let dir = app.path().app_local_data_dir().map_err(|e| e.to_string())?;
    tauri::async_runtime::spawn_blocking(move || games.remove(&dir, &id))
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn game_launch(
    app: AppHandle,
    state: State<'_, GameLibrary>,
    id: String,
) -> Result<Option<u32>, String> {
    let games = state.inner().clone();
    let dir = app.path().app_local_data_dir().map_err(|e| e.to_string())?;
    tauri::async_runtime::spawn_blocking(move || games.launch(&dir, &id))
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn game_adopt(
    app: AppHandle,
    state: State<'_, GameLibrary>,
    id: String,
    path: String,
) -> Result<Option<Install>, String> {
    let games = state.inner().clone();
    let dir = app.path().app_local_data_dir().map_err(|e| e.to_string())?;
    tauri::async_runtime::spawn_blocking(move || games.adopt(&dir, &id, &path))
        .await
        .map_err(|e| e.to_string())?
}

fn main() {
    tauri::Builder::default()
        .manage(NetworkAgent::default())
        .manage(GameLibrary::default())
        .invoke_handler(tauri::generate_handler![
            network_start,
            network_status,
            network_stop,
            game_list,
            game_detect,
            game_choose,
            game_adopt,
            game_verify,
            game_remove,
            game_launch
        ])
        .build(tauri::generate_context!())
        .expect("could not initialize Battle EX")
        .run(|app, event| {
            if let tauri::RunEvent::Exit = event {
                let _ = app.state::<NetworkAgent>().stop();
            }
        });
}
