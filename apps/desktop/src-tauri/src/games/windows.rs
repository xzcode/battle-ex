use super::{check_local_path, Install, Profile};
use std::{
    collections::BTreeSet,
    env,
    ffi::OsString,
    mem::{size_of, zeroed},
    os::windows::ffi::{OsStrExt, OsStringExt},
    path::{Path, PathBuf},
    ptr,
};
use windows_sys::Win32::{
    Foundation::CloseHandle,
    Security::{GetTokenInformation, TokenElevation, TOKEN_ELEVATION, TOKEN_QUERY},
    Storage::FileSystem::{
        GetFileVersionInfoSizeW, GetFileVersionInfoW, VerQueryValueW, VS_FIXEDFILEINFO,
    },
    System::Threading::{GetCurrentProcess, OpenProcessToken},
    UI::{
        Controls::Dialogs::{
            CommDlgExtendedError, GetOpenFileNameW, OFN_EXPLORER, OFN_FILEMUSTEXIST,
            OFN_NOCHANGEDIR, OFN_PATHMUSTEXIST, OPENFILENAMEW,
        },
        WindowsAndMessaging::{
            MessageBoxW, IDOK, MB_DEFBUTTON2, MB_ICONWARNING, MB_OKCANCEL, MB_SETFOREGROUND,
        },
    },
};
fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}
pub fn choose(p: &Profile) -> Result<Option<PathBuf>, String> {
    let mut buf = vec![0u16; 32768];
    let title = wide(&format!("Choose {} / {} executable", p.en, p.zh));
    let filter = wide("Windows executable (*.exe)\0*.exe\0\0");
    unsafe {
        let mut options: OPENFILENAMEW = zeroed();
        options.lStructSize = size_of::<OPENFILENAMEW>() as u32;
        options.lpstrFilter = filter.as_ptr();
        options.nFilterIndex = 1;
        options.lpstrFile = buf.as_mut_ptr();
        options.nMaxFile = buf.len() as u32;
        options.lpstrTitle = title.as_ptr();
        options.Flags = OFN_EXPLORER | OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR;
        if GetOpenFileNameW(&mut options) == 0 {
            let e = CommDlgExtendedError();
            return if e == 0 {
                Ok(None)
            } else {
                Err(format!("File chooser failed ({e})"))
            };
        }
    }
    let len = buf
        .iter()
        .position(|x| *x == 0)
        .ok_or("File chooser path missing terminator")?;
    Ok(Some(PathBuf::from(OsString::from_wide(&buf[..len]))))
}
pub fn detect(p: &Profile) -> Vec<String> {
    if p.executables.is_empty() {
        return Vec::new();
    }
    let mut roots = BTreeSet::new();
    for key in [
        "ProgramFiles",
        "ProgramFiles(x86)",
        "ProgramW6432",
        "USERPROFILE",
    ] {
        if let Some(v) = env::var_os(key) {
            let base = PathBuf::from(v);
            for r in [
                base.clone(),
                base.join("Games"),
                base.join("Steam/steamapps/common"),
                base.join("EA Games"),
                base.join("Electronic Arts"),
            ] {
                roots.insert(r);
            }
        }
    }
    if let Some(drive) = env::var_os("SystemDrive") {
        let root = PathBuf::from(format!("{}\\", drive.to_string_lossy()));
        roots.insert(root.join("Games"));
    }
    let mut results = BTreeSet::new();
    // Fixed common folders, no whole-disk recursion and no automatic execution.
    for root in roots {
        if check_local_path(&root).is_err() {
            continue;
        }
        for folder in &p.folders {
            for exe in &p.executables {
                let path = root.join(folder).join(exe);
                if !path.is_file() {
                    continue;
                }
                if let Ok(path) = path.canonicalize() {
                    if check_local_path(&path).is_ok()
                        && (p.id != "cs16"
                            || path.parent().is_some_and(|x| x.join("cstrike").is_dir()))
                    {
                        if let Some(path) = path.to_str() {
                            results.insert(path.to_owned());
                        }
                    }
                }
            }
        }
    }
    results.into_iter().collect()
}
pub fn file_version(path: &Path) -> Option<String> {
    let path: Vec<u16> = path
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();
    unsafe {
        let mut unused = 0;
        let size = GetFileVersionInfoSizeW(path.as_ptr(), &mut unused);
        if size == 0 || size > 16 * 1024 * 1024 {
            return None;
        }
        let mut buf = vec![0u32; (size as usize).div_ceil(4)];
        if GetFileVersionInfoW(path.as_ptr(), 0, size, buf.as_mut_ptr().cast()) == 0 {
            return None;
        }
        let mut data = ptr::null_mut();
        let mut len = 0;
        let root = wide("\\");
        if VerQueryValueW(buf.as_ptr().cast(), root.as_ptr(), &mut data, &mut len) == 0
            || data.is_null()
            || len < size_of::<VS_FIXEDFILEINFO>() as u32
        {
            return None;
        }
        let info = ptr::read_unaligned(data.cast::<VS_FIXEDFILEINFO>());
        if info.dwSignature != 0xfeef04bd {
            return None;
        }
        Some(format!(
            "{}.{}.{}.{}",
            info.dwFileVersionMS >> 16,
            info.dwFileVersionMS & 0xffff,
            info.dwFileVersionLS >> 16,
            info.dwFileVersionLS & 0xffff
        ))
    }
}
pub fn elevated() -> Result<bool, String> {
    unsafe {
        let mut token = ptr::null_mut();
        if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) == 0 {
            return Err("Cannot verify process privilege".into());
        }
        let mut info: TOKEN_ELEVATION = zeroed();
        let mut len = 0;
        let ok = GetTokenInformation(
            token,
            TokenElevation,
            (&mut info as *mut TOKEN_ELEVATION).cast(),
            size_of::<TOKEN_ELEVATION>() as u32,
            &mut len,
        );
        CloseHandle(token);
        if ok == 0 {
            return Err("Cannot verify process privilege".into());
        }
        Ok(info.TokenIsElevated != 0)
    }
}
pub fn confirm_launch(p: &Profile, i: &Install) -> bool {
    let message=wide(&format!("Launch this local executable? / 启动此本地程序？\n\n{} / {}\n{}\nSHA-256: {}\n\nOnly continue if you trust this installation. / 请确认信任此安装。",p.en,p.zh,i.path,i.sha256));
    let title = wide("Battle EX — Confirm launch");
    unsafe {
        MessageBoxW(
            ptr::null_mut(),
            message.as_ptr(),
            title.as_ptr(),
            MB_OKCANCEL | MB_ICONWARNING | MB_SETFOREGROUND | MB_DEFBUTTON2,
        ) == IDOK
    }
}

pub fn confirm_install(p: &Profile, i: &Install) -> bool {
    let message=wide(&format!("Save this detected installation? / 保存检测到的安装？\n\n{} / {}\n{}\nSHA-256: {}\n\nOnly continue if you trust this file. / 请确认信任此文件。",p.en,p.zh,i.path,i.sha256));
    let title = wide("Battle EX — Confirm installation");
    unsafe {
        MessageBoxW(
            ptr::null_mut(),
            message.as_ptr(),
            title.as_ptr(),
            MB_OKCANCEL | MB_ICONWARNING | MB_SETFOREGROUND | MB_DEFBUTTON2,
        ) == IDOK
    }
}
