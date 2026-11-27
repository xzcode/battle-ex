use std::{
    ffi::OsString,
    mem::{size_of, zeroed},
    os::windows::ffi::OsStringExt,
    path::PathBuf,
    ptr,
};
use windows_sys::Win32::UI::{
    Controls::Dialogs::{
        CommDlgExtendedError, GetOpenFileNameW, GetSaveFileNameW, OFN_EXPLORER, OFN_FILEMUSTEXIST,
        OFN_NOCHANGEDIR, OFN_PATHMUSTEXIST, OPENFILENAMEW,
    },
    WindowsAndMessaging::{
        MessageBoxW, IDOK, MB_DEFBUTTON2, MB_ICONWARNING, MB_OKCANCEL, MB_SETFOREGROUND,
    },
};
fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}
pub fn choose(ext: &str, save: Option<&str>) -> Result<Option<PathBuf>, String> {
    let mut buffer = vec![0u16; 32768];
    if let Some(name) = save {
        let encoded = wide(name);
        if encoded.len() > buffer.len() {
            return Err("Replay filename too long".into());
        }
        buffer[..encoded.len()].copy_from_slice(&encoded);
    }
    let filter = wide(&format!("Game replay (*.{ext})\0*.{ext}\0\0"));
    let title = wide(if save.is_some() {
        "Export replay to a new file / 导出录像"
    } else {
        "Import local game replay / 导入本地录像"
    });
    let extension = wide(ext);
    unsafe {
        let mut opts: OPENFILENAMEW = zeroed();
        opts.lStructSize = size_of::<OPENFILENAMEW>() as u32;
        opts.lpstrFilter = filter.as_ptr();
        opts.lpstrFile = buffer.as_mut_ptr();
        opts.nMaxFile = buffer.len() as u32;
        opts.nFilterIndex = 1;
        opts.lpstrTitle = title.as_ptr();
        opts.lpstrDefExt = extension.as_ptr();
        opts.Flags = OFN_EXPLORER | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR;
        let ok = if save.is_some() {
            GetSaveFileNameW(&mut opts)
        } else {
            opts.Flags |= OFN_FILEMUSTEXIST;
            GetOpenFileNameW(&mut opts)
        };
        if ok == 0 {
            let e = CommDlgExtendedError();
            return if e == 0 {
                Ok(None)
            } else {
                Err(format!("Replay file chooser failed ({e})"))
            };
        }
    }
    let len = buffer
        .iter()
        .position(|x| *x == 0)
        .ok_or("Replay path missing terminator")?;
    Ok(Some(PathBuf::from(OsString::from_wide(&buffer[..len]))))
}
pub fn confirm_remove(name: &str) -> bool {
    let title = wide("Battle EX");
    let text=wide(&format!("Remove this local archive copy? The original game file is not deleted.\n删除本地归档副本？不会删除游戏原始文件。\n\n{name}"));
    unsafe {
        MessageBoxW(
            ptr::null_mut(),
            text.as_ptr(),
            title.as_ptr(),
            MB_OKCANCEL | MB_ICONWARNING | MB_SETFOREGROUND | MB_DEFBUTTON2,
        ) == IDOK
    }
}
