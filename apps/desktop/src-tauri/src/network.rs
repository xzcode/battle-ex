//! Local desktop-to-agent bridge. Only the fixed installed sidecar may execute.
use serde_json::{json, Value};
#[cfg(not(windows))]
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
        #[cfg(not(windows))]
        let dir_name = format!(
            "{:x}",
            Sha256::digest(format!("{origin}\0{issuer}\0{user_id}").as_bytes())
        );
        #[cfg(not(windows))]
        let state_dir = data_dir.join("room-network").join(dir_name);
        #[cfg(windows)]
        let _ = data_dir;
        let binary = agent_binary()?;
        let mut command = Command::new(binary);
        #[cfg(not(windows))]
        command
            .arg("--state-dir")
            .arg(&state_dir)
            .arg("--issuer")
            .arg(&issuer);
        #[cfg(windows)]
        command.arg("--service-client");
        command
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x08000000); // CREATE_NO_WINDOW; no elevation.
        }
        // Dev-only trust store overrides, never supplied by web content.
        #[cfg(all(debug_assertions, not(windows)))]
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
        let send = serde_json::to_writer(
            &mut input,
            &json!({"origin":origin,"token":token,"issuer":issuer,"userId":user_id}),
        )
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
            if !matches!(
                status["state"].as_str(),
                Some("revoked" | "error" | "expired")
            ) {
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

    // Only a current, authenticated native agent snapshot can supply a
    // GoldSrc join target. The renderer does not provide a network address.
    pub fn cs_peer(
        &self,
        room: &str,
        user: &str,
        port: u16,
    ) -> Result<std::net::SocketAddrV4, String> {
        let status = self.status()?;
        cs_target(&status, room, user, port)
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

fn cs_target(
    status: &Value,
    room: &str,
    user: &str,
    port: u16,
) -> Result<std::net::SocketAddrV4, String> {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|_| "Invalid clock")?
        .as_secs();
    if port == 0
        || status["state"] != "running"
        || status["gameId"] != "cs16"
        || status["roomId"].as_str() != Some(room)
        || status["expiresAt"].as_u64().unwrap_or(0) <= now
    {
        return Err("A current CS 1.6 room network is required".into());
    }
    let peer = status["peers"]
        .as_array()
        .and_then(|peers| peers.iter().find(|p| p["userId"].as_str() == Some(user)))
        .ok_or("Selected peer is not in the authorized room network")?;
    let ip: std::net::Ipv4Addr = peer["ip"]
        .as_str()
        .ok_or("Missing peer address")?
        .parse()
        .map_err(|_| "Invalid peer address")?;
    let local: std::net::Ipv4Addr = status["ip"]
        .as_str()
        .ok_or("Missing local address")?
        .parse()
        .map_err(|_| "Invalid local address")?;
    let a = ip.octets();
    let b = local.octets();
    if a[0] != 10
        || !(160..=175).contains(&a[1])
        || a[..3] != b[..3]
        || a[3] == 0
        || a[3] == 255
        || ip == local
    {
        return Err("Peer address is outside the authorized room subnet".into());
    }
    Ok(std::net::SocketAddrV4::new(ip, port))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cs_join_requires_current_room_peer() {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
        let mut s = json!({"state":"running","gameId":"cs16","roomId":"room","ip":"10.160.1.1","expiresAt":now+10,
            "peers":[{"userId":"peer","ip":"10.160.1.2"}]});
        assert_eq!(
            cs_target(&s, "room", "peer", 27015).unwrap().to_string(),
            "10.160.1.2:27015"
        );
        assert!(cs_target(&s, "another", "peer", 27015).is_err());
        assert!(cs_target(&s, "room", "unknown", 27015).is_err());
        s["peers"][0]["ip"] = json!("192.168.0.1");
        assert!(cs_target(&s, "room", "peer", 27015).is_err());
        s["peers"][0]["ip"] = json!("10.160.1.2");
        s["expiresAt"] = json!(now);
        assert!(cs_target(&s, "room", "peer", 27015).is_err());
    }
}
