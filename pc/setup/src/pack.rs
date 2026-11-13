//! 打安装包（发版时 release.yml 用）：suanniao-pack <启动程序> <输出的安装包> <要装的文件>...
//! 把文件压缩后接在启动程序后面（格式见 lib.rs），打完再解开核对一遍。
//! 设了环境变量 SUANNIAO_PACK_FFMPEG=某个 ffmpeg.exe 的路径，就把它也打进去（包里叫 ffmpeg.exe），
//! 远程画面要用它编码，别的电脑不用自己装 ffmpeg；不设和以前一样。

use std::path::PathBuf;

use suanniao_setup::{add_ffmpeg, build, open, Entry};

const FFMPEG_ENV: &str = "SUANNIAO_PACK_FFMPEG";

fn main() {
    if let Err(e) = run() {
        eprintln!("❌ {e}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let args: Vec<PathBuf> = std::env::args_os().skip(1).map(PathBuf::from).collect();
    if args.len() < 3 {
        return Err("用法：suanniao-pack <启动程序> <输出的安装包> <要装的文件>...".into());
    }
    let read = |path: &PathBuf| std::fs::read(path).map_err(|e| format!("读不了 {}：{e}", path.display()));
    let stub = read(&args[0])?;
    let mut entries = Vec::new();
    for path in &args[2..] {
        let name = path.file_name().and_then(|name| name.to_str()).ok_or_else(|| format!("文件名不对：{}", path.display()))?;
        entries.push(Entry { name: name.into(), data: read(path)? });
    }
    let ffmpeg = std::env::var_os(FFMPEG_ENV).filter(|path| !path.is_empty()).map(PathBuf::from);
    if let Some(path) = &ffmpeg {
        add_ffmpeg(&mut entries, read(path)?).map_err(|e| format!("{FFMPEG_ENV}={}：{e}", path.display()))?;
    }
    let installer = build(&stub, &entries)?;
    let back = open(&installer)?;
    if back.len() != entries.len() || back.iter().zip(&entries).any(|(a, b)| a.name != b.name || a.data != b.data) {
        return Err("打完解开对不上".into());
    }
    let out = &args[1];
    if let Some(dir) = out.parent().filter(|dir| !dir.as_os_str().is_empty()) {
        std::fs::create_dir_all(dir).map_err(|e| format!("建不了 {}：{e}", dir.display()))?;
    }
    std::fs::write(out, &installer).map_err(|e| format!("写不了 {}：{e}", out.display()))?;
    let mb = |bytes: usize| bytes as f64 / 1024.0 / 1024.0;
    let raw: usize = entries.iter().map(|entry| entry.data.len()).sum();
    let with = if ffmpeg.is_some() { "（带 ffmpeg.exe）" } else { "" };
    println!("✅ {}：{} 个文件{with} {:.1} MB，安装包 {:.1} MB", out.display(), entries.len(), mb(raw), mb(installer.len()));
    Ok(())
}
