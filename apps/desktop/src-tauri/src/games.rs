//! Local game library. Server data can select a catalog ID, never an executable or command.
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    fs::{self, File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::Path,
    process::Child,
    sync::{Arc, Mutex},
    time::UNIX_EPOCH,
};

#[cfg(test)]
use std::path::PathBuf;
#[cfg(any(windows, test))]
use std::process::{Command, Stdio};

#[cfg(windows)]
#[path = "games/windows.rs"]
mod windows;

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Profile {
    pub id: String,
    pub en: String,
    pub zh: String,
    pub short: String,
    pub color: String,
    pub executables: Vec<String>,
    pub folders: Vec<String>,
    pub args: Vec<String>,
    pub discovery: String,
    pub edition: String,
}

fn catalog() -> Vec<Profile> {
    serde_json::from_str(include_str!("../../game-catalog.json")).expect("built-in game catalog")
}
fn profile(id: &str) -> Result<Profile, String> {
    catalog()
        .into_iter()
        .find(|p| p.id == id)
        .ok_or("Unknown game profile".into())
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Install {
    pub path: String,
    pub sha256: String,
    pub file_version: Option<String>,
    pub architecture: String,
    pub size: u64,
    pub modified: u64,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Entry {
    pub id: String,
    pub install: Option<Install>,
    pub state: &'static str,
    pub pid: Option<u32>,
    pub exit_code: Option<i32>,
}
#[derive(Default)]
struct Running {
    child: Option<Child>,
    exit_code: Option<i32>,
}
#[derive(Clone, Default)]
pub struct GameLibrary(Arc<Mutex<BTreeMap<String, Running>>>);

fn metadata_stamp(m: &fs::Metadata) -> Result<(u64, u64), String> {
    let ms = m
        .modified()
        .map_err(err)?
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "Invalid file modification time")?
        .as_millis();
    Ok((
        m.len(),
        u64::try_from(ms).map_err(|_| "Invalid file modification time")?,
    ))
}
fn err(e: impl std::fmt::Display) -> String {
    e.to_string()
}

fn check_local_path(path: &Path) -> Result<(), String> {
    if !path.is_absolute() {
        return Err("Choose an absolute local executable path".into());
    }
    #[cfg(windows)]
    {
        use std::path::{Component, Prefix};
        match path.components().next() {
            Some(Component::Prefix(p))
                if matches!(p.kind(), Prefix::Disk(_) | Prefix::VerbatimDisk(_)) => {}
            _ => return Err("Network shares and device paths are not supported".into()),
        }
    }
    Ok(())
}

fn pe_architecture(f: &mut File, len: u64) -> Result<&'static str, String> {
    let mut dos = [0u8; 64];
    f.read_exact(&mut dos)
        .map_err(|_| "Not a Windows PE executable")?;
    if &dos[..2] != b"MZ" {
        return Err("Not a Windows PE executable".into());
    }
    let offset = u32::from_le_bytes(dos[60..64].try_into().unwrap()) as u64;
    if offset < 64 || offset.checked_add(24).is_none_or(|n| n > len) {
        return Err("Invalid PE header".into());
    }
    f.seek(SeekFrom::Start(offset)).map_err(err)?;
    let mut pe = [0u8; 24];
    f.read_exact(&mut pe).map_err(err)?;
    if &pe[..4] != b"PE\0\0" {
        return Err("Invalid PE signature".into());
    }
    let characteristics = u16::from_le_bytes([pe[22], pe[23]]);
    if characteristics & 0x2000 != 0 || characteristics & 0x0002 == 0 {
        return Err("Choose an executable, not a DLL or object file".into());
    }
    match u16::from_le_bytes([pe[4], pe[5]]) {
        0x14c => Ok("x86"),
        0x8664 => Ok("x64"),
        0xaa64 => Ok("ARM64"),
        _ => Err("Unsupported PE architecture".into()),
    }
}

fn inspect(p: &Profile, path: &Path) -> Result<Install, String> {
    check_local_path(path)?;
    let path = fs::canonicalize(path).map_err(err)?;
    check_local_path(&path)?;
    let name = path
        .file_name()
        .and_then(|s| s.to_str())
        .ok_or("Invalid executable name")?;
    if !name.to_ascii_lowercase().ends_with(".exe")
        || (!p.executables.is_empty()
            && !p.executables.iter().any(|v| v.eq_ignore_ascii_case(name)))
    {
        return Err("Executable does not match the selected game profile".into());
    }
    if p.id == "cs16"
        && !path
            .parent()
            .is_some_and(|dir| dir.join("cstrike").is_dir())
    {
        return Err("Select the Counter-Strike installation containing the cstrike folder".into());
    }
    let mut file = File::open(&path).map_err(err)?;
    let before = file.metadata().map_err(err)?;
    if !before.is_file() {
        return Err("Choose a regular executable file".into());
    }
    let (size, modified) = metadata_stamp(&before)?;
    let architecture = pe_architecture(&mut file, size)?.to_string();
    f_rewind(&mut file)?;
    let mut hash = Sha256::new();
    let mut chunk = [0u8; 65536];
    loop {
        let n = file.read(&mut chunk).map_err(err)?;
        if n == 0 {
            break;
        }
        hash.update(&chunk[..n]);
    }
    #[cfg(windows)]
    let file_version = windows::file_version(&path);
    #[cfg(not(windows))]
    let file_version = None;
    if metadata_stamp(&file.metadata().map_err(err)?)? != (size, modified)
        || metadata_stamp(&fs::metadata(&path).map_err(err)?)? != (size, modified)
    {
        return Err("Executable changed during inspection; choose it again".into());
    }
    let path = path
        .to_str()
        .ok_or("Executable path is not valid Unicode")?
        .to_string();
    Ok(Install {
        path,
        sha256: format!("{:x}", hash.finalize()),
        file_version,
        architecture,
        size,
        modified,
    })
}
fn f_rewind(f: &mut File) -> Result<(), String> {
    f.seek(SeekFrom::Start(0)).map(|_| ()).map_err(err)
}

fn read_library(dir: &Path) -> Result<BTreeMap<String, Install>, String> {
    let path = dir.join("game-library.json");
    match fs::read(path) {
        Ok(bytes) => serde_json::from_slice(&bytes).map_err(|_| {
            "Invalid local game library; restore or remove the configuration manually".into()
        }),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(BTreeMap::new()),
        Err(e) => Err(err(e)),
    }
}
fn save_library(dir: &Path, library: &BTreeMap<String, Install>) -> Result<(), String> {
    fs::create_dir_all(dir).map_err(err)?;
    // Avoid partial configuration on interruption. The main library is replaced only after sync.
    let tmp = dir.join(format!(
        ".game-library-{}-{}.tmp",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(err)?
            .as_nanos()
    ));
    let result = (|| {
        let mut f = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&tmp)
            .map_err(err)?;
        f.write_all(&serde_json::to_vec_pretty(library).map_err(err)?)
            .map_err(err)?;
        f.sync_all().map_err(err)?;
        drop(f);
        fs::rename(&tmp, dir.join("game-library.json")).map_err(err)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    result
}
fn stat_state(i: &Install) -> &'static str {
    if check_local_path(Path::new(&i.path)).is_err() {
        return "missing";
    }
    match fs::metadata(&i.path) {
        Ok(m) if m.is_file() && metadata_stamp(&m).ok() == Some((i.size, i.modified)) => {
            "configured"
        }
        Ok(_) => "changed",
        Err(_) => "missing",
    }
}
fn reaper(r: &mut Running) -> Result<(), String> {
    if let Some(child) = r.child.as_mut() {
        if let Some(status) = child.try_wait().map_err(err)? {
            r.exit_code = status.code();
            r.child = None;
        }
    }
    Ok(())
}

impl GameLibrary {
    pub fn list(&self, dir: &Path) -> Result<Vec<Entry>, String> {
        let mut running = self.0.lock().map_err(|_| "Game state unavailable")?;
        let installs = read_library(dir)?;
        catalog()
            .into_iter()
            .map(|p| {
                let r = running.entry(p.id.clone()).or_default();
                reaper(r)?;
                let install = installs.get(&p.id).cloned();
                let state = install.as_ref().map(stat_state).unwrap_or("unconfigured");
                Ok(Entry {
                    id: p.id,
                    install,
                    state,
                    pid: r.child.as_ref().map(Child::id),
                    exit_code: r.exit_code,
                })
            })
            .collect()
    }

    pub fn detect(&self, id: &str) -> Result<Vec<String>, String> {
        let p = profile(id)?;
        #[cfg(windows)]
        {
            Ok(windows::detect(&p))
        }
        #[cfg(not(windows))]
        {
            let _ = p;
            Err("Game installation detection currently requires Windows".into())
        }
    }

    pub fn choose(&self, dir: &Path, id: &str) -> Result<Option<Install>, String> {
        let p = profile(id)?;
        #[cfg(windows)]
        {
            let mut state = self.0.lock().map_err(|_| "Game state unavailable")?;
            let r = state.entry(id.to_string()).or_default();
            reaper(r)?;
            if r.child.is_some() {
                return Err("Close the running game before changing its executable".into());
            }
            let Some(path) = windows::choose(&p)? else {
                return Ok(None);
            };
            let installation = inspect(&p, &path)?;
            let mut library = read_library(dir)?;
            library.insert(id.into(), installation.clone());
            save_library(dir, &library)?;
            r.exit_code = None;
            Ok(Some(installation))
        }
        #[cfg(not(windows))]
        {
            let _ = (dir, p);
            Err("Game executable selection currently requires Windows".into())
        }
    }
    pub fn adopt(&self, dir: &Path, id: &str, path: &str) -> Result<Option<Install>, String> {
        let p = profile(id)?;
        #[cfg(windows)]
        {
            let mut state = self.0.lock().map_err(|_| "Game state unavailable")?;
            let r = state.entry(id.into()).or_default();
            reaper(r)?;
            if r.child.is_some() {
                return Err("Close the running game before changing its executable".into());
            }
            // The renderer may propose a candidate, but only a current local
            // detection result is accepted. Native confirmation is required.
            if !windows::detect(&p)
                .iter()
                .any(|candidate| candidate == path)
            {
                return Err("Detected installation is no longer available; scan again".into());
            }
            let installation = inspect(&p, Path::new(path))?;
            if !windows::confirm_install(&p, &installation) {
                return Ok(None);
            }
            let current = inspect(&p, Path::new(path))?;
            if current != installation {
                return Err("Executable changed during confirmation".into());
            }
            let mut library = read_library(dir)?;
            library.insert(id.into(), current.clone());
            save_library(dir, &library)?;
            r.exit_code = None;
            Ok(Some(current))
        }
        #[cfg(not(windows))]
        {
            let _ = (dir, p, path);
            Err("Game installation selection currently requires Windows".into())
        }
    }
    pub fn remove(&self, dir: &Path, id: &str) -> Result<(), String> {
        profile(id)?;
        let mut state = self.0.lock().map_err(|_| "Game state unavailable")?;
        let r = state.entry(id.into()).or_default();
        reaper(r)?;
        if r.child.is_some() {
            return Err("Close the running game before removing its configuration".into());
        }
        let mut library = read_library(dir)?;
        library.remove(id);
        save_library(dir, &library)
    }

    pub fn verify(&self, dir: &Path, id: &str) -> Result<bool, String> {
        let p = profile(id)?;
        let _lock = self.0.lock().map_err(|_| "Game state unavailable")?;
        let mut library = read_library(dir)?;
        let previous = library.get(id).ok_or("Select the game executable first")?;
        let now = inspect(&p, Path::new(&previous.path))?;
        if now.sha256 != previous.sha256 {
            return Ok(false);
        }
        if &now != previous {
            library.insert(id.into(), now);
            save_library(dir, &library)?;
        }
        Ok(true)
    }

    pub fn launch(&self, dir: &Path, id: &str) -> Result<Option<u32>, String> {
        self.launch_inner(dir, id, None, || Ok(()))
    }
    pub fn launch_cs_peer<F: FnOnce() -> Result<(), String>>(
        &self,
        dir: &Path,
        target: std::net::SocketAddrV4,
        validate: F,
    ) -> Result<Option<u32>, String> {
        self.launch_inner(dir, "cs16", Some(target), validate)
    }
    fn launch_inner<F: FnOnce() -> Result<(), String>>(
        &self,
        dir: &Path,
        id: &str,
        target: Option<std::net::SocketAddrV4>,
        validate: F,
    ) -> Result<Option<u32>, String> {
        let p = profile(id)?;
        #[cfg(windows)]
        {
            if windows::elevated()? {
                return Err("Run Battle EX as a normal user to launch a game. Elevated game launches are not supported.".into());
            }
            let mut state = self.0.lock().map_err(|_| "Game state unavailable")?;
            let r = state.entry(id.into()).or_default();
            reaper(r)?;
            if r.child.is_some() {
                return Err("This game process is already running".into());
            }
            let library = read_library(dir)?;
            let saved = library.get(id).ok_or("Select the game executable first")?;
            let inspected = inspect(&p, Path::new(&saved.path))?;
            if inspected.sha256 != saved.sha256 {
                return Err("Game executable has changed. Select and confirm the updated file before launching.".into());
            }
            if !windows::confirm_launch(&p, &inspected, target) {
                return Ok(None);
            }
            // Check again after the native confirmation. No downloaded commands, scripts,
            // arbitrary launch arguments or shell expansion are accepted by this API.
            let actual = inspect(&p, Path::new(&saved.path))?;
            if actual != inspected {
                return Err("Executable changed during confirmation".into());
            }
            validate()?;
            let mut command = launch_command(&p, Path::new(&actual.path), target)?;
            let child = command.spawn().map_err(err)?;
            let pid = child.id();
            r.child = Some(child);
            r.exit_code = None;
            Ok(Some(pid))
        }
        #[cfg(not(windows))]
        {
            let _ = (dir, p, target, validate);
            Err("Game launch currently requires Windows".into())
        }
    }
}

#[cfg(any(windows, test))]
fn launch_command(
    p: &Profile,
    path: &Path,
    target: Option<std::net::SocketAddrV4>,
) -> Result<Command, String> {
    let parent = path.parent().ok_or("Executable directory missing")?;
    let mut command = Command::new(path);
    command
        .current_dir(parent)
        .args(&p.args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    // GoldSrc's shared hl.exe needs the installed game directory selected.
    // The flag is fixed by local adapter code, not supplied by a game server.
    if p.id == "cs16"
        && path
            .file_name()
            .is_some_and(|n| n.to_string_lossy().eq_ignore_ascii_case("hl.exe"))
    {
        command.args(["-game", "cstrike"]);
    }
    if let Some(addr) = target {
        if p.id != "cs16" || addr.port() == 0 {
            return Err("Direct join is restricted to CS 1.6".into());
        }
        command.arg("+connect").arg(addr.to_string());
    }
    Ok(command)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "battle-games-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir(&dir).unwrap();
        dir
    }
    fn pe(path: &Path) {
        let mut b = vec![0; 128];
        b[..2].copy_from_slice(b"MZ");
        b[60..64].copy_from_slice(&64u32.to_le_bytes());
        b[64..68].copy_from_slice(b"PE\0\0");
        b[68..70].copy_from_slice(&0x14cu16.to_le_bytes());
        b[86..88].copy_from_slice(&2u16.to_le_bytes());
        fs::write(path, b).unwrap()
    }
    #[test]
    fn inspect_persist_and_revalidate() {
        let dir = fixture();
        let exe = dir.join("RA2.exe");
        pe(&exe);
        let p = profile("redalert2").unwrap();
        let i = inspect(&p, &exe).unwrap();
        assert_eq!(i.architecture, "x86");
        assert_eq!(i.sha256.len(), 64);
        let mut library = BTreeMap::new();
        library.insert(p.id.clone(), i.clone());
        save_library(&dir, &library).unwrap();
        assert_eq!(read_library(&dir).unwrap(), library);
        let games = GameLibrary::default();
        assert!(games.verify(&dir, &p.id).unwrap());
        let mut b = fs::read(&exe).unwrap();
        b[100] = 1;
        fs::write(&exe, b).unwrap();
        assert!(!games.verify(&dir, &p.id).unwrap());
        games.remove(&dir, &p.id).unwrap();
        assert!(read_library(&dir).unwrap().is_empty());
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn rejects_mismatched_files_and_missing_game_data() {
        let dir = fixture();
        let exe = dir.join("hl.exe");
        pe(&exe);
        assert!(inspect(&profile("redalert2").unwrap(), &exe).is_err());
        assert!(inspect(&profile("cs16").unwrap(), &exe).is_err());
        fs::create_dir(dir.join("cstrike")).unwrap();
        let p = profile("cs16").unwrap();
        assert!(inspect(&p, &exe).is_ok());
        let command = launch_command(&p, &exe, None).unwrap();
        assert_eq!(
            command
                .get_args()
                .map(|a| a.to_string_lossy().into_owned())
                .collect::<Vec<_>>(),
            vec!["-game", "cstrike"]
        );
        assert_eq!(command.get_current_dir(), Some(dir.as_path()));
        let join = launch_command(&p, &exe, Some("10.160.1.2:27015".parse().unwrap())).unwrap();
        assert_eq!(
            join.get_args()
                .map(|a| a.to_string_lossy().into_owned())
                .collect::<Vec<_>>(),
            vec!["-game", "cstrike", "+connect", "10.160.1.2:27015"]
        );
        assert!(launch_command(
            &profile("redalert2").unwrap(),
            &exe,
            Some("10.160.1.2:27015".parse().unwrap())
        )
        .is_err());

        fs::write(&exe, b"not executable").unwrap();
        assert!(inspect(&p, &exe).is_err());
        assert!(profile("unknown").is_err());
        fs::remove_dir_all(dir).unwrap();
    }
}
