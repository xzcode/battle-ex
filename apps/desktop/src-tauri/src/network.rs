//! Local desktop-to-agent bridge. Only the fixed installed sidecar may execute.
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    io::{BufRead, BufReader, Write},
    path::{Path, PathBuf},
    process::{Child, ChildStdin, Command, Stdio},
    sync::{Arc, Mutex},
    thread,
    time::{Duration, Instant},
};

#[derive(Clone, Default)]
pub struct NetworkAgent(Arc<Mutex<Option<Process>>>);

struct Process {
    child: Child,
    stdin: Option<ChildStdin>,
    status: Arc<Mutex<Value>>,
    stderr: Arc<Mutex<String>>,
}

impl Process {
    fn stop(&mut self) -> Result<(), String> {
        // EOF is the agent's graceful shutdown signal; do not immediately kill a tunnel.
        drop(self.stdin.take());
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline {
            if self.child.try_wait().map_err(|e| e.to_string())?.is_some() {
                return Ok(());
            }
            thread::sleep(Duration::from_millis(100));
        }
        let _ = self.child.kill();
        let _ = self.child.wait();
        Err("Network agent did not stop gracefully; check virtual adapter cleanup.".into())
    }
}

impl Drop for Process {
    fn drop(&mut self) {
        if self.stdin.is_some() {
            let _ = self.stop();
        }
    }
}

fn agent_binary() -> Result<PathBuf, String> {
    // Explicit developer override only in debug builds. Release uses packaged sibling.
    #[cfg(debug_assertions)]
    if let Some(path) = std::env::var_os("BATTLE_AGENT_PATH") {
        let path = PathBuf::from(path);
        if path.is_absolute() && path.is_file() {
            return Ok(path);
        }
        return Err("BATTLE_AGENT_PATH must refer to an absolute local executable.".into());
    }
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let path = exe
        .parent()
        .ok_or("Application executable has no parent directory")?
        .join(if cfg!(windows) {
            "battle-agent.exe"
        } else {
            "battle-agent"
        });
    if !path.is_file() {
        return Err("Battle EX network agent is not installed.".into());
    }
    Ok(path)
}

impl NetworkAgent {
    pub fn start(
        &self,
        data_dir: &Path,
        origin: String,
        token: String,
        issuer: String,
        user_id: String,
    ) -> Result<(), String> {
        if issuer.len() != 64
            || !issuer
                .bytes()
                .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
            || token.len() != 43
            || !token
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
            || user_id.len() != 36
            || !user_id.bytes().all(|b| b.is_ascii_hexdigit() || b == b'-')
            || origin.len() > 512
            || origin.contains(['\r', '\n', '\0'])
        {
            return Err("Invalid network identity or server configuration.".into());
        }
        let mut current = self
            .0
            .lock()
            .map_err(|_| "Network agent state unavailable")?;
        if let Some(mut previous) = current.take() {
            previous.stop()?;
        }
        let dir_name = format!(
            "{:x}",
            Sha256::digest(format!("{origin}\0{issuer}\0{user_id}").as_bytes())
        );
        let state_dir = data_dir.join("room-network").join(dir_name);
        let binary = agent_binary()?;
        let mut command = Command::new(binary);
        command
            .arg("--state-dir")
            .arg(&state_dir)
            .arg("--issuer")
            .arg(&issuer)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x08000000); // CREATE_NO_WINDOW; no elevation.
        }
        // Dev-only trust store overrides, never supplied by web content.
        #[cfg(debug_assertions)]
        for (env, flag) in [
            ("BATTLE_AGENT_API_CA", "--api-ca"),
            ("BATTLE_AGENT_RELAY_CA", "--relay-ca"),
        ] {
            if let Some(path) = std::env::var_os(env) {
                command.arg(flag).arg(path);
            }
        }
        let mut child = command
            .spawn()
            .map_err(|e| format!("Cannot start network agent: {e}"))?;
        let mut input = child
            .stdin
            .take()
            .ok_or("Network agent stdin unavailable")?;
        let output = child
            .stdout
            .take()
            .ok_or("Network agent stdout unavailable")?;
        let errors = child
            .stderr
            .take()
            .ok_or("Network agent stderr unavailable")?;
        let status = Arc::new(Mutex::new(json!({"state":"starting","peers":[]})));
        let stderr = Arc::new(Mutex::new(String::new()));
        let latest = status.clone();
        thread::spawn(move || {
            for line in BufReader::new(output).lines().map_while(Result::ok) {
                if let Ok(value) = serde_json::from_str::<Value>(&line) {
                    if value.get("state").and_then(Value::as_str).is_some() {
                        if let Ok(mut dest) = latest.lock() {
                            *dest = value;
                        }
                    }
                }
            }
        });
        let last_error = stderr.clone();
        thread::spawn(move || {
            for line in BufReader::new(errors).lines().map_while(Result::ok) {
                if let Ok(mut dest) = last_error.lock() {
                    *dest = line.chars().take(400).collect();
                }
            }
        });
        // Bearer token exists in the private parent/child pipe only, not argv or disk.
        let send = serde_json::to_writer(&mut input, &json!({"origin":origin,"token":token}))
            .map_err(|e| e.to_string())
            .and_then(|_| input.write_all(b"\n").map_err(|e| e.to_string()))
            .and_then(|_| input.flush().map_err(|e| e.to_string()));
        let mut process = Process {
            child,
            stdin: Some(input),
            status,
            stderr,
        };
        if let Err(e) = send {
            let _ = process.stop();
            return Err(format!("Cannot send login session to network agent: {e}"));
        }
        *current = Some(process);
        Ok(())
    }

    pub fn status(&self) -> Result<Value, String> {
        let mut current = self
            .0
            .lock()
            .map_err(|_| "Network agent state unavailable")?;
        let Some(process) = current.as_mut() else {
            return Ok(json!({"state":"idle","peers":[]}));
        };
        let mut status = process
            .status
            .lock()
            .map_err(|_| "Network status unavailable")?
            .clone();
        if let Some(exit) = process.child.try_wait().map_err(|e| e.to_string())? {
            if status["state"] != "revoked" {
                let last_error = process
                    .stderr
                    .lock()
                    .map_err(|_| "Network status unavailable")?
                    .clone();
                status = json!({"state":if exit.success() {"idle"} else {"error"},
                                "peers":[], "error":last_error});
            }
        }
        Ok(status)
    }

    pub fn stop(&self) -> Result<(), String> {
        let mut current = self
            .0
            .lock()
            .map_err(|_| "Network agent state unavailable")?;
        if let Some(mut process) = current.take() {
            process.stop()?;
        }
        Ok(())
    }
}
