//! Local replay storage. No remote path, arbitrary execution, or automatic
//! playback. Files are copied only after a native chooser returns a path.
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::{SystemTime, UNIX_EPOCH},
};
#[cfg(windows)]
#[path = "replays/windows.rs"]
mod windows;

#[derive(Clone, Default)]
pub struct ReplayLibrary(Arc<Mutex<()>>);

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Replay {
    pub id: String,
    pub game_id: String,
    pub filename: String,
    pub sha256: String,
    pub bytes: u64,
    pub imported_at: u64,
}
fn err(e: impl std::fmt::Display) -> String {
    e.to_string()
}
fn timestamp() -> Result<u64, String> {
    Ok(SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(err)?
        .as_secs())
}
fn extension(game: &str) -> Result<&'static str, String> {
    match game {
        "warcraft3" => Ok("w3g"),
        "cs16" => Ok("dem"),
        _ => Err("Replay import supports Warcraft III W3G and CS 1.6 DEM".into()),
    }
}
fn hex(s: &str) -> bool {
    s.len() == 64
        && s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn valid_id(id: &str) -> bool {
    id.split_once('.')
        .is_some_and(|(hash, ext)| hex(hash) && matches!(ext, "w3g" | "dem"))
}
fn scope(root: &Path, origin: &str, user: &str) -> Result<PathBuf, String> {
    if origin.len() > 512
        || !(origin.starts_with("https://")
            || origin.starts_with("http://127.0.0.1:")
            || origin.starts_with("http://localhost:")
            || origin.starts_with("http://[::1]:"))
        || user.len() != 36
        || !user.bytes().all(|b| b.is_ascii_hexdigit() || b == b'-')
    {
        return Err("Invalid local replay library scope".into());
    }
    Ok(root.join("replays").join(format!(
        "{:x}",
        Sha256::digest(format!("{origin}\0{user}").as_bytes())
    )))
}
fn local(path: &Path) -> Result<(), String> {
    if !path.is_absolute() {
        return Err("Choose an absolute local replay path".into());
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
fn is_regular(path: &Path) -> Result<(), String> {
    let md = fs::symlink_metadata(path).map_err(err)?;
    if !md.is_file() || md.file_type().is_symlink() {
        return Err("A regular replay file is required".into());
    }
    Ok(())
}
fn copy_hash(mut src: File, mut dest: Option<&mut File>) -> Result<(String, u64), String> {
    let start = src.metadata().map_err(err)?;
    if !start.is_file() || start.len() == 0 {
        return Err("Choose a non-empty replay file".into());
    }
    let stamp = start.modified().map_err(err)?;
    let mut h = Sha256::new();
    let mut total = 0u64;
    let mut buf = [0u8; 65536];
    loop {
        let n = src.read(&mut buf).map_err(err)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
        total = total.checked_add(n as u64).ok_or("Replay size overflow")?;
        if let Some(f) = dest.as_deref_mut() {
            f.write_all(&buf[..n]).map_err(err)?
        }
    }
    let end = src.metadata().map_err(err)?;
    if total != start.len() || end.len() != start.len() || end.modified().map_err(err)? != stamp {
        return Err("Replay changed during reading".into());
    }
    Ok((format!("{:x}", h.finalize()), total))
}
fn temp(dir: &Path) -> Result<(PathBuf, File), String> {
    let name = format!(
        ".import-{}-{}",
        std::process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(err)?
            .as_nanos()
    );
    let path = dir.join(name);
    let file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&path)
        .map_err(err)?;
    Ok((path, file))
}
fn load(dir: &Path, id: &str) -> Result<Replay, String> {
    if !valid_id(id) {
        return Err("Invalid replay ID".into());
    }
    let meta = dir.join(format!("{id}.json"));
    is_regular(&meta)?;
    let entry: Replay = serde_json::from_slice(&fs::read(&meta).map_err(err)?).map_err(err)?;
    if entry.id != id
        || !hex(&entry.sha256)
        || format!("{}.{}", entry.sha256, extension(&entry.game_id)?) != id
        || entry.bytes == 0
        || Path::new(&entry.filename)
            .file_name()
            .and_then(|x| x.to_str())
            != Some(&entry.filename)
    {
        return Err("Invalid local replay record".into());
    }
    Ok(entry)
}
fn verify_file(dir: &Path, r: &Replay) -> Result<(), String> {
    let path = dir.join(&r.id);
    is_regular(&path)?;
    let (hash, bytes) = copy_hash(File::open(&path).map_err(err)?, None)?;
    if hash != r.sha256 || bytes != r.bytes {
        return Err("Archived replay has changed".into());
    }
    Ok(())
}
fn import_path(dir: &Path, game: &str, source: &Path) -> Result<Replay, String> {
    let ext = extension(game)?;
    local(source)?;
    let source = fs::canonicalize(source).map_err(err)?;
    local(&source)?;
    is_regular(&source)?;
    let filename = source
        .file_name()
        .and_then(|n| n.to_str())
        .ok_or("Invalid replay filename")?
        .to_owned();
    if !source
        .extension()
        .and_then(|s| s.to_str())
        .is_some_and(|s| s.eq_ignore_ascii_case(ext))
    {
        return Err("Replay extension does not match the game".into());
    }
    fs::create_dir_all(dir).map_err(err)?;
    let (tmp, mut file) = temp(dir)?;
    let result = (|| {
        let (sha256, bytes) = copy_hash(File::open(&source).map_err(err)?, Some(&mut file))?;
        file.sync_all().map_err(err)?;
        drop(file);
        let id = format!("{sha256}.{ext}");
        let data = dir.join(&id);
        let meta = dir.join(format!("{id}.json"));
        if meta.exists() {
            let existing = load(dir, &id)?;
            verify_file(dir, &existing)?;
            return Ok(existing);
        }
        let entry = Replay {
            id,
            game_id: game.into(),
            filename,
            sha256,
            bytes,
            imported_at: timestamp()?,
        };
        if data.exists() {
            verify_file(dir, &entry)?;
        } else {
            fs::rename(&tmp, &data).map_err(err)?;
        }
        let (meta_tmp, mut meta_file) = temp(dir)?;
        let written = (|| {
            meta_file
                .write_all(&serde_json::to_vec_pretty(&entry).map_err(err)?)
                .map_err(err)?;
            meta_file.sync_all().map_err(err)?;
            drop(meta_file);
            fs::rename(&meta_tmp, &meta).map_err(err)
        })();
        if written.is_err() {
            let _ = fs::remove_file(&meta_tmp);
        }
        written?;
        Ok(entry)
    })();
    let _ = fs::remove_file(&tmp);
    result
}
fn export_path(dir: &Path, r: &Replay, dest: &Path) -> Result<(), String> {
    local(dest)?;
    let ext = extension(&r.game_id)?;
    if !dest
        .extension()
        .and_then(|s| s.to_str())
        .is_some_and(|s| s.eq_ignore_ascii_case(ext))
    {
        return Err("Use the original replay file extension".into());
    }
    let parent = dest
        .parent()
        .ok_or("Destination directory required")?
        .canonicalize()
        .map_err(err)?;
    local(&parent)?;
    let dest = parent.join(dest.file_name().ok_or("Destination filename required")?);
    let source = dir.join(&r.id);
    is_regular(&source)?;
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&dest)
        .map_err(err)?;
    let result = (|| {
        let (hash, bytes) = copy_hash(File::open(&source).map_err(err)?, Some(&mut file))?;
        if hash != r.sha256 || bytes != r.bytes {
            return Err("Archived replay has changed".into());
        }
        file.sync_all().map_err(err)
    })();
    drop(file);
    if result.is_err() {
        let _ = fs::remove_file(&dest);
    }
    result
}
impl ReplayLibrary {
    pub fn list(&self, root: &Path, origin: &str, user: &str) -> Result<Vec<Replay>, String> {
        let _guard = self.0.lock().map_err(|_| "Replay library unavailable")?;
        let dir = scope(root, origin, user)?;
        let entries = match fs::read_dir(&dir) {
            Ok(v) => v,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(e) => return Err(err(e)),
        };
        let mut result = Vec::new();
        for entry in entries {
            let path = entry.map_err(err)?.path();
            if let Some(id) = path
                .file_name()
                .and_then(|x| x.to_str())
                .and_then(|x| x.strip_suffix(".json"))
            {
                if valid_id(id) {
                    result.push(load(&dir, id)?)
                }
            }
        }
        result.sort_by(|a, b| b.imported_at.cmp(&a.imported_at).then(a.id.cmp(&b.id)));
        Ok(result)
    }
    pub fn choose_import(
        &self,
        root: &Path,
        origin: &str,
        user: &str,
        game: &str,
    ) -> Result<Option<Replay>, String> {
        let ext = extension(game)?;
        #[cfg(windows)]
        {
            let _guard = self.0.lock().map_err(|_| "Replay library unavailable")?;
            let dir = scope(root, origin, user)?;
            let Some(source) = windows::choose(ext, None)? else {
                return Ok(None);
            };
            return import_path(&dir, game, &source).map(Some);
        }
        #[cfg(not(windows))]
        {
            let _ = (root, origin, user, ext);
            Err("Replay file selection currently requires Windows".into())
        }
    }
    pub fn verify(
        &self,
        root: &Path,
        origin: &str,
        user: &str,
        id: &str,
    ) -> Result<Replay, String> {
        let _guard = self.0.lock().map_err(|_| "Replay library unavailable")?;
        let dir = scope(root, origin, user)?;
        let r = load(&dir, id)?;
        verify_file(&dir, &r)?;
        Ok(r)
    }
    pub fn export(&self, root: &Path, origin: &str, user: &str, id: &str) -> Result<bool, String> {
        #[cfg(windows)]
        {
            let _guard = self.0.lock().map_err(|_| "Replay library unavailable")?;
            let dir = scope(root, origin, user)?;
            let r = load(&dir, id)?;
            let Some(dest) = windows::choose(extension(&r.game_id)?, Some(&r.filename))? else {
                return Ok(false);
            };
            export_path(&dir, &r, &dest)?;
            Ok(true)
        }
        #[cfg(not(windows))]
        {
            let _ = (root, origin, user, id);
            Err("Replay export currently requires Windows".into())
        }
    }
    pub fn remove(&self, root: &Path, origin: &str, user: &str, id: &str) -> Result<bool, String> {
        #[cfg(windows)]
        {
            let _guard = self.0.lock().map_err(|_| "Replay library unavailable")?;
            let dir = scope(root, origin, user)?;
            let r = load(&dir, id)?;
            if !windows::confirm_remove(&r.filename) {
                return Ok(false);
            }
            let data = dir.join(id);
            if data.exists() {
                is_regular(&data)?;
                fs::remove_file(data).map_err(err)?;
            }
            fs::remove_file(dir.join(format!("{id}.json"))).map_err(err)?;
            Ok(true)
        }
        #[cfg(not(windows))]
        {
            let _ = (root, origin, user, id);
            Err("Replay removal currently requires Windows".into())
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn import_dedupe_verify_export_and_tampering() {
        let dir = std::env::temp_dir().join(format!(
            "battle-replay-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir(&dir).unwrap();
        let library = dir.join("library");
        let source = dir.join("fixture.dem");
        fs::write(&source, b"synthetic replay file; no format certification").unwrap();
        let first = import_path(&library, "cs16", &source).unwrap();
        assert_eq!(import_path(&library, "cs16", &source).unwrap(), first);
        assert!(import_path(&library, "warcraft3", &source).is_err());
        assert!(load(&library, "../fixture.dem").is_err());
        let dest = dir.join("export.dem");
        export_path(&library, &first, &dest).unwrap();
        assert_eq!(fs::read(&dest).unwrap(), fs::read(&source).unwrap());
        assert!(export_path(&library, &first, &dest).is_err());
        fs::write(library.join(&first.id), b"changed").unwrap();
        assert!(verify_file(&library, &first).is_err());
        let bad = dir.join("bad.dem");
        assert!(export_path(&library, &first, &bad).is_err());
        assert!(!bad.exists());
        fs::remove_dir_all(dir).unwrap();
    }
}
