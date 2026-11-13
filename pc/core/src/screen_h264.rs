//! 远程画面的 H.264 视频（SCN2）：ffmpeg 用 ddagrab（DXGI 桌面复制）抓主显示器，编成 H.264 发给手机。
//!
//! ffmpeg 是外面的程序（仓库里不放 exe），按顺序找：环境变量 SUANNIAO_FFMPEG → 和 SuanNIAO-core.exe
//! 同一个文件夹里的 ffmpeg.exe（安装包可以带一个放在这里）→ PATH 里的 ffmpeg.exe。
//! 编码器按 NVIDIA（h264_nvenc）→ Intel（h264_qsv）→ AMD（h264_amf）→ Windows 自带（h264_mf，先硬件后软件）
//! → libx264 的顺序，用合成画面试编几帧，第一个能用的记下来一直用：局域网一就绪就在后台先试
//! （probe_in_background），还没试完时手机连进来就等结果；选中的推流时一帧都出不来，重新试一次。
//! 都不能用就报错，手机连续失败几次后改用原来的 JPEG（SCN1）。
//! 视频走已有的 TLS + 手机配对认证，截图不落盘。
//!
//! SCN2: magic[4], desktop width u32, desktop height u32,
//! encoded width u32, encoded height u32, H264 Annex-B access unit length u32,
//! access unit bytes. New Android can also consume legacy SCN1.
//!
//! Unlike JPEG frames, H.264 P frames depend on earlier frames. We use a tiny
//! bounded queue (12 complete AUs) and IDR-resync on overload rather than
//! accumulating unbounded video or disconnecting the control session.
#[cfg(windows)]
use std::os::windows::process::CommandExt;

use std::{
    ffi::OsString,
    io::{self, Read},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{mpsc::{self, Receiver, SyncSender, TrySendError}, atomic::{AtomicBool, Ordering}, Arc, Mutex, PoisonError},
    thread,
    time::{Duration, Instant},
};

use suanniao_lan::SecureConn;
use windows::Win32::UI::WindowsAndMessaging::{GetSystemMetrics, SM_CXSCREEN, SM_CYSCREEN};

use crate::{lan, link, log};

const MAX_H264_FRAME: usize = 3 * 1024 * 1024;
static H264_ACTIVE: AtomicBool = AtomicBool::new(false);
const WAIT: Duration = Duration::from_millis(500);
/// 连着这么多个 WAIT 一帧都没收到，就当编码卡住了（8 × 500ms = 4 秒），断开让手机重连
const MAX_MISSES: u32 = 8;
/// Discard stale dependent frames and resume at the next IDR rather than
/// presenting a second of already-obsolete desktop content.
const MAX_QUEUE_AGE: Duration = Duration::from_millis(120);
const FFMPEG_ENV: &str = "SUANNIAO_FFMPEG";
/// 不让 ffmpeg 弹出黑色的控制台窗口
const CREATE_NO_WINDOW: u32 = 0x0800_0000;
/// 试一个编码器最多等多久：本机 Intel QSV 试一次要 1.7–6 秒（多半花在初始化），给足余量；
/// 超时就结束进程、试下一个（Intel 上下一个 h264_mf 硬件编码用的也是核显）
const PROBE_TIMEOUT: Duration = Duration::from_secs(8);
/// 都不能用时，过这么久才重新试：手机几秒内连着重试的那几次直接用结果；
/// 装好 ffmpeg 以后过一会儿重新打开远程画面就能用上，不用重启后台
const PROBE_RETRY: Duration = Duration::from_secs(60);

/// Queue insertion timestamp is local monotonic time. It measures Windows
/// sender backlog only, not Android decode or touch-to-photon latency.
struct QueuedAu {
    bytes: Vec<u8>,
    queued_at: Instant,
}

/// ffmpeg 是从哪找到的（写日志用）
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum FfmpegSource {
    Env,
    ExeDir,
    PathVar,
}

impl FfmpegSource {
    fn label(self) -> &'static str {
        match self {
            FfmpegSource::Env => "环境变量 SUANNIAO_FFMPEG 指定",
            FfmpegSource::ExeDir => "蒜鸟程序文件夹里",
            FfmpegSource::PathVar => "PATH 里",
        }
    }
}

/// 按顺序找 ffmpeg：SUANNIAO_FFMPEG（开发时临时换一个，原样用，可以只写名字）→ exe_dir 里的 ffmpeg.exe
/// （安装包带的，别的电脑不用自己装 ffmpeg、改 PATH）→ PATH 里第一个 ffmpeg.exe。都没有返回 None。
/// is_file 由调用的传进来，方便测试
fn locate_ffmpeg_in(
    env: Option<OsString>, exe_dir: Option<&Path>, path_var: Option<OsString>,
    is_file: impl Fn(&Path) -> bool,
) -> Option<(PathBuf, FfmpegSource)> {
    if let Some(value) = env.filter(|value| !value.is_empty()) {
        return Some((PathBuf::from(value), FfmpegSource::Env));
    }
    if let Some(beside) = exe_dir.map(|dir| dir.join("ffmpeg.exe")).filter(|path| is_file(path)) {
        return Some((beside, FfmpegSource::ExeDir));
    }
    let path_var = path_var?;
    std::env::split_paths(&path_var)
        .filter(|dir| !dir.as_os_str().is_empty())
        .map(|dir| dir.join("ffmpeg.exe"))
        .find(|path| is_file(path))
        .map(|path| (path, FfmpegSource::PathVar))
}

fn locate_ffmpeg() -> Option<(PathBuf, FfmpegSource)> {
    let exe = std::env::current_exe().ok();
    locate_ffmpeg_in(std::env::var_os(FFMPEG_ENV), exe.as_deref().and_then(Path::parent), std::env::var_os("PATH"), Path::is_file)
}

/// 能用的 H.264 编码器，CANDIDATES 是试的先后：先显卡硬件编码，最后才是占 CPU 的软件编码
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Encoder {
    /// NVIDIA 显卡
    Nvenc,
    /// Intel 核显（Quick Sync）
    Qsv,
    /// AMD 显卡
    Amf,
    /// Windows 自带的 Media Foundation，用显卡驱动带的硬件编码
    MfHardware,
    /// Media Foundation 的软件编码（微软自带，没有显卡编码也能用）
    MfSoftware,
    /// x264 软件编码（只有 GPL 版的 ffmpeg 带）
    X264,
}

const CANDIDATES: [Encoder; 6] = [
    Encoder::Nvenc, Encoder::Qsv, Encoder::Amf, Encoder::MfHardware, Encoder::MfSoftware, Encoder::X264,
];

impl Encoder {
    fn codec(self) -> &'static str {
        match self {
            Encoder::Nvenc => "h264_nvenc",
            Encoder::Qsv => "h264_qsv",
            Encoder::Amf => "h264_amf",
            Encoder::MfHardware | Encoder::MfSoftware => "h264_mf",
            Encoder::X264 => "libx264",
        }
    }

    /// 写日志用
    fn label(self) -> &'static str {
        match self {
            Encoder::MfHardware => "h264_mf（硬件）",
            Encoder::MfSoftware => "h264_mf（软件）",
            other => other.codec(),
        }
    }
}

/// 编码参数：分辨率、帧率、码率和以前一样，换编码器只换低延迟选项
#[derive(Clone, Debug, PartialEq)]
struct EncodeParams {
    fps: u32,
    /// 编码的目标高度（1080 或 720），宽按桌面比例算
    target_height: u32,
    /// 两个 IDR 之间多少帧
    gop: u32,
    bitrate: &'static str,
    bufsize: &'static str,
}

impl EncodeParams {
    fn new(fps: u32, target_height: u32) -> Self {
        let small = target_height == 720;
        Self {
            fps,
            target_height,
            gop: gop_frames(fps),
            bitrate: if small { "3500k" } else { "4500k" },
            bufsize: if small { "500k" } else { "650k" },
        }
    }
}

/// 按目标帧率大约 1 秒一个 IDR（以前半秒一个）。
/// IDR 约 64KB，P 帧不到 10KB：半秒一个时码率有两成多花在 IDR 上，一秒还有两次突发，现在都减半。
/// 不能再长：发送堵了要丢帧时（队列满、帧太旧），只能等下一个自然出现的 IDR 才能接着发，
/// 等的时候手机画面停着。ffmpeg 命令行没法临时要一个关键帧；重启 ffmpeg 能换来新的 IDR，但本机光是
/// 起一个 NVENC 编几帧就要 0.7–1.2 秒（还没算 ddagrab），不比等快。等超过 MAX_MISSES × WAIT（4 秒）还会被当成卡住、断开重连；
/// 实际帧率掉到目标一半时 1 秒变 2 秒，还有余量。
/// 新连接、断开重连都是新起的 ffmpeg，第一帧一定是 IDR，和这个间隔无关。
fn gop_frames(fps: u32) -> u32 {
    fps.max(10)
}

fn strings(list: &[&str]) -> Vec<String> {
    list.iter().map(|s| s.to_string()).collect()
}

/// 编码器那一段参数：低延迟选项 + 不要 B 帧、GOP、固定码率 + AUD。
/// 选项名都在本机 ffmpeg 7.1 的 `ffmpeg -h encoder=…` 核对过，h264_nvenc 的和以前一样
fn encoder_args(encoder: Encoder, params: &EncodeParams) -> Vec<String> {
    let low_latency: &[&str] = match encoder {
        Encoder::Nvenc => &["-preset", "p1", "-tune", "ull", "-zerolatency", "1", "-delay", "0"],
        // async_depth 默认 4（攒几帧才出），改成 1
        Encoder::Qsv => &["-preset", "veryfast", "-look_ahead", "0", "-async_depth", "1"],
        Encoder::Amf => &["-usage", "ultralowlatency", "-quality", "speed"],
        Encoder::MfHardware => &["-scenario", "display_remoting", "-hw_encoding", "1"],
        // 不加 -hw_encoding 就只用软件编码（微软自带的）
        Encoder::MfSoftware => &["-scenario", "display_remoting"],
        Encoder::X264 => &["-preset", "ultrafast", "-tune", "zerolatency"],
    };
    // 固定码率：QSV 在 -b:v 等于 -maxrate 时自己就用 CBR；x264 靠 maxrate + bufsize 限住
    let rate_control: &[&str] = match encoder {
        Encoder::Nvenc | Encoder::Amf => &["-rc", "cbr"],
        Encoder::MfHardware | Encoder::MfSoftware => &["-rate_control", "cbr"],
        Encoder::Qsv | Encoder::X264 => &[],
    };
    // 拆帧（read_access_units）靠每帧开头的 AUD。MF 自己就带（本机硬件、软件都试过），别的要打开。
    // 不能给 MF 再加 h264_metadata=aud=insert：本机试过会变成两个 AUD，拆出只有 AUD 的空帧
    let aud: &[&str] = match encoder {
        Encoder::MfHardware | Encoder::MfSoftware => &[],
        _ => &["-aud", "1"],
    };
    let gop = params.gop.to_string();
    let mut args = vec!["-c:v", encoder.codec()];
    args.extend_from_slice(low_latency);
    args.extend_from_slice(&["-bf", "0", "-g", &gop]);
    args.extend_from_slice(rate_control);
    args.extend_from_slice(&["-b:v", params.bitrate, "-maxrate", params.bitrate, "-bufsize", params.bufsize]);
    args.extend_from_slice(aud);
    strings(&args)
}

/// 输出：原样的时间戳，每帧写完就冲出去，裸 H.264（Annex-B）写到 stdout
fn output_args() -> Vec<String> {
    strings(&["-fps_mode", "passthrough", "-flush_packets", "1", "-f", "h264", "-"])
}

/// 正式推流的 ffmpeg 参数
fn stream_args(encoder: Encoder, params: &EncodeParams, coded_w: u32, coded_h: u32) -> Vec<String> {
    let mut args = strings(&["-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i"]);
    args.push(format!("ddagrab=output_idx=0:framerate={}:draw_mouse=1", params.fps));
    // ddagrab 出来的是显卡上的 D3D11 画面。不管什么编码器、要不要缩放，都先下载到内存、转成 NV12 再编码：
    // QSV、MF、x264 只收内存里的画面；NVENC 能直接收 D3D11，但只收 NVIDIA 显卡上的——笔记本的屏幕
    // 常接在核显上，ddagrab 抓到的是核显的画面，本机试过 NVENC 打不开（no encode device）。
    // 下载到内存这条路本机 2560×1440 一直在用（以前只有不缩放时才把 D3D11 直接给 NVENC）；hwmap 到 CUDA 以前试过，不行。
    args.push("-vf".into());
    args.push(format!("hwdownload,format=bgra,scale={coded_w}:{coded_h}:flags=fast_bilinear,format=nv12"));
    args.push("-an".into());
    args.extend(encoder_args(encoder, params));
    args.extend(output_args());
    args
}

/// 试编码的参数：合成的测试画面（不抓桌面）编 10 帧，编码参数和正式推流一样，输出到 stdout 检查
fn probe_args(encoder: Encoder, params: &EncodeParams) -> Vec<String> {
    let mut args = strings(&[
        "-hide_banner", "-loglevel", "error", "-nostdin",
        "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30", "-frames:v", "10", "-vf", "format=nv12", "-an",
    ]);
    args.extend(encoder_args(encoder, params));
    args.extend(output_args());
    args
}

#[derive(Clone, Debug, PartialEq)]
struct Choice {
    ffmpeg: PathBuf,
    encoder: Encoder,
}

enum Probed {
    Ready(Choice),
    /// 都不能用，什么时候试的
    Failed(Instant),
}

/// 试出来的编码器：试一次，选中的一直用；选中的推流时起不来，重新试一次（见 encoder_failed_to_start）
static CHOICE: Mutex<Option<Probed>> = Mutex::new(None);
/// 因为起不来已经重新试过一次了：再起不来也不再试，等哪次推流出了画面才再给一次机会
static REPROBED: AtomicBool = AtomicBool::new(false);

/// 推流的编码参数（帧率、高度可以用环境变量临时改，比较 1080p60、720p60 不用重新编译）
fn stream_params() -> EncodeParams {
    // 60FPS is an opt-in performance target, not proof that the phone can
    // decode/render at 60. Keep runtime overrides so we can compare 1080p60,
    // 720p60 and lower-FPS fallback without rebuilding the APK or PC core.
    let fps = std::env::var("SUANNIAO_SCREEN_FPS").ok()
        .and_then(|v| v.parse::<u32>().ok())
        .filter(|n| (15..=60).contains(n)).unwrap_or(60);
    let target_height = if std::env::var("SUANNIAO_SCREEN_HEIGHT").ok().as_deref() == Some("720") {
        720u32
    } else { 1080u32 };
    EncodeParams::new(fps, target_height)
}

/// 局域网一就绪就在后台先试出编码器（审查 #10）：以前等手机第一次连 H.264 才试，Intel 核显试一次要好几秒，
/// 全算在手机等第一帧的 8 秒里，容易连着失败几次、这次全屏就退回 JPEG。
/// 试的时候拿着 CHOICE 的锁，手机这时连进来就等它试完直接用结果。一个进程只开一次
pub fn probe_in_background() {
    static STARTED: AtomicBool = AtomicBool::new(false);
    if STARTED.swap(true, Ordering::AcqRel) {
        return;
    }
    let _ = thread::Builder::new().name("suanniao-h264-probe".to_owned()).spawn(|| {
        choose_encoder(&stream_params());
    });
}

/// 选中的编码器这次一帧都没出来（NVENC 会话被别的程序占满、驱动重置、换了显卡……）：作废这个选择，
/// 马上在后台重新试一遍，手机重连时就用新试出来的（审查 #14：以前选中了就一直用到后台退出）。
/// 只重新试一次：试完还是起不来（比如锁屏时抓不了桌面，换编码器也没用），就不再每次连接都白试几秒，
/// 等哪次推流出了画面才再给一次机会
fn encoder_failed_to_start(choice: &Choice) {
    if REPROBED.swap(true, Ordering::AcqRel) {
        return;
    }
    {
        let mut probed = CHOICE.lock().unwrap_or_else(PoisonError::into_inner);
        match &*probed {
            Some(Probed::Ready(current)) if current == choice => *probed = None,
            _ => return,
        }
    }
    log!("📺 H264: 编码器 {} 这次一帧都没出来，重新试一遍编码器", choice.encoder.label());
    let _ = thread::Builder::new().name("suanniao-h264-probe".to_owned()).spawn(|| {
        choose_encoder(&stream_params());
    });
}

/// 拿要用的编码器：第一次（或者上次都不能用、已经过了 PROBE_RETRY）先试一遍。
/// 试的时候拿着锁：同时来的连接等它试完直接用结果，不会一起试
fn choose_encoder(params: &EncodeParams) -> Option<Choice> {
    let mut probed = CHOICE.lock().unwrap_or_else(PoisonError::into_inner);
    match &*probed {
        Some(Probed::Ready(choice)) => return Some(choice.clone()),
        Some(Probed::Failed(at)) if at.elapsed() < PROBE_RETRY => return None,
        _ => {}
    }
    let choice = probe_encoders(params);
    *probed = Some(match &choice {
        Some(choice) => Probed::Ready(choice.clone()),
        None => Probed::Failed(Instant::now()),
    });
    choice
}

/// 找 ffmpeg，按 CANDIDATES 的顺序试，返回第一个能用的
fn probe_encoders(params: &EncodeParams) -> Option<Choice> {
    let Some((ffmpeg, source)) = locate_ffmpeg() else {
        log!("📺 H264: 没找到 ffmpeg（{FFMPEG_ENV}、蒜鸟程序文件夹、PATH 里都没有），只能用 JPEG 画面");
        return None;
    };
    log!("📺 H264: 用{}的 ffmpeg：{}", source.label(), ffmpeg.display());
    for encoder in CANDIDATES {
        let started = Instant::now();
        match probe_encoder(&ffmpeg, encoder, params) {
            Ok(()) => {
                log!("📺 H264: 编码器 {}（试编码 {}ms）", encoder.label(), started.elapsed().as_millis());
                return Some(Choice { ffmpeg, encoder });
            }
            Err(ProbeError::Spawn(error)) => {
                log!("📺 H264: ffmpeg 启动不了（{:?}），只能用 JPEG 画面", error.kind());
                return None;
            }
            Err(ProbeError::Unusable(why)) => log!("📺 H264: {} 不能用：{why}", encoder.label()),
        }
    }
    log!("📺 H264: 没有能用的 H264 编码器，只能用 JPEG 画面");
    None
}

enum ProbeError {
    /// ffmpeg 本身起不来（找不到、没权限）：别的编码器也不用试了
    Spawn(io::Error),
    /// 这个编码器不能用（没有这家显卡、驱动不支持、输出不对），试下一个
    Unusable(String),
}

/// 用合成画面试编几帧：要在 PROBE_TIMEOUT 内正常结束，输出要能拆帧、第一帧是带 SPS/PPS 的 IDR
fn probe_encoder(ffmpeg: &Path, encoder: Encoder, params: &EncodeParams) -> Result<(), ProbeError> {
    let mut cmd = Command::new(ffmpeg);
    cmd.args(probe_args(encoder, params)).stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped());
    #[cfg(windows)]
    cmd.creation_flags(CREATE_NO_WINDOW);
    let mut child = cmd.spawn().map_err(ProbeError::Spawn)?;
    // 两个管道都要边跑边读：不读的话 ffmpeg 写满管道就停住，只能等超时
    let output = read_pipe(child.stdout.take(), 4 * 1024 * 1024);
    let errors = read_pipe(child.stderr.take(), 16 * 1024);
    let finished = wait_or_kill(&mut child, PROBE_TIMEOUT);
    let output = output.and_then(|reader| reader.join().ok()).unwrap_or_default();
    let errors = errors.and_then(|reader| reader.join().ok()).unwrap_or_default();
    match finished {
        None => Err(ProbeError::Unusable(format!("{} 秒内没有正常结束", PROBE_TIMEOUT.as_secs()))),
        Some(false) => Err(ProbeError::Unusable(first_error_line(&errors))),
        Some(true) => check_probe_stream(&output).map_err(|why| ProbeError::Unusable(why.into())),
    }
}

/// 在另一个线程里把管道读完（最多 limit 字节）
fn read_pipe(pipe: Option<impl Read + Send + 'static>, limit: u64) -> Option<thread::JoinHandle<Vec<u8>>> {
    let pipe = pipe?;
    thread::Builder::new()
        .name("suanniao-h264-probe".to_owned())
        .spawn(move || {
            let mut buffer = Vec::new();
            let _ = pipe.take(limit).read_to_end(&mut buffer);
            buffer
        })
        .ok()
}

/// 等进程结束，Some(是不是正常退出)；到时间还没结束（或者查不了）就结束它，返回 None
fn wait_or_kill(child: &mut Child, timeout: Duration) -> Option<bool> {
    let deadline = Instant::now() + timeout;
    loop {
        match child.try_wait() {
            Ok(Some(status)) => return Some(status.success()),
            Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(20)),
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                return None;
            }
        }
    }
}

/// 试编码的输出能不能用：拆帧靠每帧开头的 AUD（至少要有两帧才看得出），
/// 手机端靠第一帧里的 SPS/PPS 配置解码器、从 IDR 开始解
fn check_probe_stream(stream: &[u8]) -> Result<(), &'static str> {
    if find_aud(stream, 0) != Some(0) {
        return Err("输出开头没有 AUD，没法拆帧");
    }
    let Some(second) = find_aud(stream, 5) else {
        return Err("只有一个 AUD，没法拆帧");
    };
    let first = &stream[..second];
    if !contains_nal(first, 7) || !contains_nal(first, 8) {
        return Err("第一帧没带 SPS/PPS");
    }
    if !has_idr(first) {
        return Err("第一帧不是 IDR");
    }
    Ok(())
}

/// ffmpeg 报的第一行错（一般就是原因，比如「DLL amfrt64.dll failed to open」），
/// 去掉「[h264_amf @ 000001…] 」这种带内存地址的前缀，太长截断。写日志用
fn first_error_line(stderr: &[u8]) -> String {
    let text = String::from_utf8_lossy(stderr);
    let Some(line) = text.lines().map(str::trim).find(|line| !line.is_empty()) else {
        return "ffmpeg 出错退出，没说原因".into();
    };
    let line = match line.strip_prefix('[').and_then(|rest| rest.split_once("] ")) {
        Some((_, message)) => message,
        None => line,
    };
    line.chars().take(160).collect()
}

/// A return means that the encoder failed, the connection closed, or the
/// paired phone disconnected. The caller may use JPEG for a new session.
pub fn stream_h264(conn: &SecureConn, phone_id: &str) -> io::Result<()> {
    let params = stream_params();
    let (fps, target_height) = (params.fps, params.target_height);
    // 还没试过编码器就先试（一般后台启动时已经在后台试过了，见 probe_in_background）。放在占用 H264_ACTIVE 之前：
    // 试的时候手机重连进来就等着用结果，不会因为「已经在推流」被拒、白算一次失败
    let choice = choose_encoder(&params)
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no usable H264 encoder"))?;

    // main.rs enables PerMonitorV2 DPI awareness before accepting connections.
    // At most one encoder for this PC; reconnections may overlap briefly.
    if H264_ACTIVE.swap(true, Ordering::AcqRel) {
        return Err(io::Error::other("screen H264 stream already active"));
    }
    struct Busy;
    impl Drop for Busy { fn drop(&mut self) { H264_ACTIVE.store(false, Ordering::Release); } }
    let _busy = Busy;
    let (w, h) = unsafe { (GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN)) };
    if w < 16 || h < 16 || w > 3840 || h > 3840 {
        return Err(io::Error::other("desktop dimensions unsupported"));
    }
    // Keep physical desktop coordinate space unchanged for remote input.
    let ratio = (f64::from(target_height) * 16.0 / 9.0 / f64::from(w))
        .min(f64::from(target_height) / f64::from(h)).min(1.0);
    let coded_w = ((f64::from(w) * ratio).floor() as u32) & !1;
    let coded_h = ((f64::from(h) * ratio).floor() as u32) & !1;
    if coded_w < 16 || coded_h < 16 {
        return Err(io::Error::other("H264 target too small"));
    }
    let mut cmd = Command::new(&choice.ffmpeg);
    cmd.args(stream_args(choice.encoder, &params, coded_w, coded_h));
    // Hide the Windows console window created by the FFmpeg child process.
    // No shell window should ever pop over Cursor/Chrome when remote view starts.
    #[cfg(windows)]
    cmd.creation_flags(CREATE_NO_WINDOW);
    let spawned = cmd
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn();
    let mut encoder = match spawned {
        Ok(child) => child,
        Err(error) => {
            // ffmpeg 起不来（被删了、挪走了）：重新找一遍
            encoder_failed_to_start(&choice);
            return Err(error);
        }
    };
    log!("📺 H264: 本地编码器启动（{}），桌面 {w}×{h} → 编码 {coded_w}×{coded_h} / {fps}FPS 目标", choice.encoder.label());

    let reader = encoder.stdout.take().ok_or_else(|| io::Error::other("video encoder pipe missing"))?;
    // Allow brief NVENC/Windows scheduling bursts without dropping a
    // dependent P frame. Still bounded: 12 frames at 60 fps = 200 ms max
    // queued video; on sustained overload terminate and resync at an IDR.
    let (tx, rx) = mpsc::sync_channel::<QueuedAu>(12);
    let resync_requested = Arc::new(AtomicBool::new(false));
    let parser_resync = Arc::clone(&resync_requested);
    let output_thread = thread::Builder::new()
        .name("suanniao-h264-parse".to_owned())
        .spawn(move || read_access_units(reader, tx, parser_resync))?;

    let mut produced = false;
    let result = transmit_access_units(conn, phone_id, w as u32, h as u32, coded_w, coded_h, &rx, &resync_requested, &mut produced);
    let _ = encoder.kill();
    let _ = encoder.wait();
    let _ = output_thread.join();
    if produced {
        REPROBED.store(false, Ordering::Release);
    } else if encoder_never_started(&result) {
        encoder_failed_to_start(&choice);
    }
    result
}

/// 编码器一帧都没出来就结束了，是不是编码器自己的问题：它退出了（BrokenPipe）、或者一直不出画面（TimedOut）。
/// 第一帧出来之前不会往手机写东西，所以这两种不会是手机那边断了；手机解除绑定、断开是别的错误
fn encoder_never_started(result: &io::Result<()>) -> bool {
    result.as_ref().is_err_and(|e| matches!(e.kind(), io::ErrorKind::BrokenPipe | io::ErrorKind::TimedOut))
}

/// produced：编码器出过画面（收到过第一帧）
#[allow(clippy::too_many_arguments)]
fn transmit_access_units(
    conn: &SecureConn, phone_id: &str, width: u32, height: u32,
    coded_w: u32, coded_h: u32, rx: &Receiver<QueuedAu>,
    resync_requested: &AtomicBool, produced: &mut bool,
) -> io::Result<()> {
    let mut misses = 0u32;
    let mut sent_first = false;
    let mut sample_at = Instant::now();
    let mut sample_frames: u64 = 0;
    let mut sample_blocked: u64 = 0;
    let mut sample_write_sum_ms: u128 = 0;
    let mut sample_write_max_ms: u128 = 0;
    let mut sample_queue_age_max_ms: u128 = 0;
    let mut sample_queue_age_ge100: u64 = 0;
    loop {
        if link::lan_phone().as_deref() != Some(phone_id) || !lan::known_phone(phone_id) {
            return Err(io::Error::other("video pairing no longer active"));
        }
        match rx.recv_timeout(WAIT) {
            Ok(queued) => {
                *produced = true;
                let queue_age_ms = queued.queued_at.elapsed().as_millis();
                let mut frame = queued.bytes;
                sample_queue_age_max_ms = sample_queue_age_max_ms.max(queue_age_ms);
                if queue_age_ms >= 100 { sample_queue_age_ge100 += 1; }
                misses = 0;
                if queue_age_ms >= MAX_QUEUE_AGE.as_millis() {
                    log!("📺 H264: 队列帧龄{}ms超过{}ms，清理旧GOP并等待IDR", queue_age_ms, MAX_QUEUE_AGE.as_millis());
                    frame = receive_fresh_idr(rx, resync_requested)?;
                }
                // ffmpeg's ddagrab output_idx=0 is the primary output. The
                // encoded dimensions match physical desktop pixels.
                let mut header = [0u8; 24];
                header[0..4].copy_from_slice(b"SCN2");
                header[4..8].copy_from_slice(&width.to_be_bytes());
                header[8..12].copy_from_slice(&height.to_be_bytes());
                header[12..16].copy_from_slice(&coded_w.to_be_bytes());
                header[16..20].copy_from_slice(&coded_h.to_be_bytes());
                header[20..24].copy_from_slice(&(frame.len() as u32).to_be_bytes());
                let write_started = Instant::now();
                conn.write_all(&header)?;
                conn.write_all(&frame)?;
                let write_ms = write_started.elapsed().as_millis();
                sample_frames += 1;
                sample_write_sum_ms += write_ms;
                sample_write_max_ms = sample_write_max_ms.max(write_ms);
                if write_ms >= 20 { sample_blocked += 1; }
                if sample_at.elapsed() >= Duration::from_secs(5) {
                    let avg = sample_write_sum_ms / (sample_frames.max(1) as u128);
                    log!("📺 H264发送诊断: 5秒发送{}帧, 写入平均{}ms, 最大{}ms, >=20ms阻塞{}帧, 排队帧龄最大{}ms, >=100ms排队{}帧",
                        sample_frames, avg, sample_write_max_ms, sample_blocked,
                        sample_queue_age_max_ms, sample_queue_age_ge100);
                    sample_at = Instant::now();
                    sample_frames = 0;
                    sample_blocked = 0;
                    sample_write_sum_ms = 0;
                    sample_write_max_ms = 0;
                    sample_queue_age_max_ms = 0;
                    sample_queue_age_ge100 = 0;
                }
                if !sent_first {
                    log!("📺 H264: 已发送首个编码帧（{} 字节）", frame.len());
                    sent_first = true;
                }
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {
                misses += 1;
                if misses >= MAX_MISSES { return Err(io::Error::new(io::ErrorKind::TimedOut, "video encoder stalled")); }
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => {
                return Err(io::Error::new(io::ErrorKind::BrokenPipe, "video encoder ended"));
            }
        }
    }
}

fn find_aud(buf: &[u8], from: usize) -> Option<usize> {
    if buf.len() < 5 { return None; }
    (from..=buf.len() - 5).find(|&i| {
        buf[i..i + 4] == [0, 0, 0, 1] && (buf[i + 4] & 0x1f) == 9
    })
}

/// An IDR resets H264 reference prediction. Dropping arbitrary P frames
/// without waiting for a new IDR would corrupt the rest of the GOP.
fn has_idr(access_unit: &[u8]) -> bool {
    contains_nal(access_unit, 5)
}

/// AU 里有没有某种 NAL（5 = IDR，7 = SPS，8 = PPS），3 字节、4 字节起始码都认
fn contains_nal(access_unit: &[u8], nal_type: u8) -> bool {
    let mut i = 0usize;
    while i + 4 <= access_unit.len() {
        let header = if access_unit[i..].starts_with(&[0, 0, 0, 1]) {
            i + 4
        } else if access_unit[i..].starts_with(&[0, 0, 1]) {
            i + 3
        } else {
            i += 1;
            continue;
        };
        if header < access_unit.len() && (access_unit[header] & 0x1f) == nal_type {
            return true;
        }
        i = header.saturating_add(1);
    }
    false
}

/// On temporary send congestion discard AUs until the *next* IDR. Keep
/// reading FFmpeg stdout (avoids backpressure and multi-second buffering)
/// and preserve the TLS session. The consumer's already-queued AUs remain
/// intact; later P frames can start only after the new IDR.
fn queue_video_au(
    tx: &SyncSender<QueuedAu>, frame: Vec<u8>,
    waiting_for_idr: &mut bool, skipped: &mut usize,
    resync_requested: &AtomicBool,
) -> bool {
    if resync_requested.swap(false, Ordering::AcqRel) {
        *waiting_for_idr = true;
        *skipped = 0;
    }
    if *waiting_for_idr && !has_idr(&frame) {
        *skipped += 1;
        return true;
    }
    match tx.try_send(QueuedAu { bytes: frame, queued_at: Instant::now() }) {
        Ok(()) => {
            if *waiting_for_idr {
                log!("📺 H264: 等待关键帧后重新同步，跳过{}帧，TLS连接未断", *skipped);
                *waiting_for_idr = false;
                *skipped = 0;
            }
            true
        }
        Err(TrySendError::Full(_)) => {
            if !*waiting_for_idr {
                log!("📺 H264: 12帧队列暂满，丢弃本GOP剩余帧并等待IDR（不中断连接）");
            }
            *waiting_for_idr = true;
            *skipped += 1;
            true
        }
        Err(TrySendError::Disconnected(_)) => {
            log!("📺 H264: 手机视频发送者已退出，终止解析");
            false
        }
    }
}

fn receive_fresh_idr(rx: &Receiver<QueuedAu>, resync_requested: &AtomicBool) -> io::Result<Vec<u8>> {
    resync_requested.store(true, Ordering::Release);
    let mut skipped = 0usize;
    let mut misses = 0u32;
    loop {
        let queued = match rx.try_recv() {
            Ok(q) => q,
            Err(mpsc::TryRecvError::Disconnected) => return Err(io::Error::new(io::ErrorKind::BrokenPipe, "video parser ended during IDR resync")),
            Err(mpsc::TryRecvError::Empty) => match rx.recv_timeout(WAIT) {
                Ok(q) => q,
                Err(mpsc::RecvTimeoutError::Disconnected) => return Err(io::Error::new(io::ErrorKind::BrokenPipe, "video parser ended during IDR resync")),
                Err(mpsc::RecvTimeoutError::Timeout) => {
                    misses += 1;
                    if misses >= MAX_MISSES { return Err(io::Error::new(io::ErrorKind::TimedOut, "waiting for fresh H264 IDR")); }
                    continue;
                }
            },
        };
        misses = 0;
        if has_idr(&queued.bytes) {
            resync_requested.store(false, Ordering::Release);
            log!("📺 H264: 旧队列已清理，IDR重新同步，跳过{}帧", skipped);
            return Ok(queued.bytes);
        }
        skipped += 1;
    }
}

/// Monotonic timing of complete Annex-B AUs emitted by FFmpeg.  This is
/// upstream of the bounded sender queue, TLS, Android decode, and presentation.
/// It does NOT establish capture, NVENC, or physical display latency alone.
struct ProducerCadence {
    window_at: Instant,
    previous_at: Option<Instant>,
    frames: u64,
    gap_max_ms: u128,
    gap_ge40: u64,
    gap_ge100: u64,
    idr_count: u64,
}

impl ProducerCadence {
    fn new() -> Self {
        Self {
            window_at: Instant::now(), previous_at: None, frames: 0,
            gap_max_ms: 0, gap_ge40: 0, gap_ge100: 0, idr_count: 0,
        }
    }

    fn record_gap(&mut self, gap: Duration) {
        let ms = gap.as_millis();
        self.gap_max_ms = self.gap_max_ms.max(ms);
        if ms >= 40 { self.gap_ge40 += 1; }
        if ms >= 100 { self.gap_ge100 += 1; }
    }

    fn observe_au(&mut self, frame: &[u8]) {
        let now = Instant::now();
        if let Some(previous) = self.previous_at {
            self.record_gap(now.duration_since(previous));
        }
        self.previous_at = Some(now);
        self.frames += 1;
        if has_idr(frame) { self.idr_count += 1; }
        let elapsed = now.duration_since(self.window_at);
        if elapsed >= Duration::from_secs(5) {
            log!("📺 H264编码输出节拍: {}ms内AU {}帧, 平均{:.1}FPS, 最大相邻间隔{}ms, >=40ms {}次, >=100ms {}次, IDR {}帧",
                elapsed.as_millis(), self.frames,
                self.frames as f64 / elapsed.as_secs_f64(), self.gap_max_ms,
                self.gap_ge40, self.gap_ge100, self.idr_count);
            self.window_at = now;
            self.frames = 0;
            self.gap_max_ms = 0;
            self.gap_ge40 = 0;
            self.gap_ge100 = 0;
            self.idr_count = 0;
        }
    }
}

fn read_access_units(mut reader: impl Read, tx: SyncSender<QueuedAu>, resync_requested: Arc<AtomicBool>) {
    let mut waiting_for_idr = false;
    let mut skipped = 0usize;
    let mut cadence = ProducerCadence::new();
    let mut buffer = Vec::with_capacity(128 * 1024);
    let mut chunk = [0u8; 64 * 1024];
    loop {
        let n = match reader.read(&mut chunk) {
            Ok(0) => {
                log!("📺 H264: FFmpeg stdout EOF");
                break;
            }
            Err(error) => {
                log!("📺 H264: FFmpeg stdout read failed ({:?})", error.kind());
                break;
            }
            Ok(n) => n,
        };
        buffer.extend_from_slice(&chunk[..n]);
        if let Some(start) = find_aud(&buffer, 0) {
            if start > 0 { buffer.drain(..start); }
        } else {
            if buffer.len() > MAX_H264_FRAME { return; }
            continue;
        }
        while let Some(next) = find_aud(&buffer, 5) {
            let access_unit: Vec<u8> = buffer.drain(..next).collect();
            if access_unit.len() > MAX_H264_FRAME { return; }
            cadence.observe_au(&access_unit);
            if !queue_video_au(&tx, access_unit,
                &mut waiting_for_idr, &mut skipped, &resync_requested) {
                return;
            }
        }
        if buffer.len() > MAX_H264_FRAME { return; }
    }
    // The final AU is valid if the encoder terminated normally.
    if buffer.len() >= 5 && find_aud(&buffer, 0) == Some(0) {
        cadence.observe_au(&buffer);
        let _ = queue_video_au(&tx, buffer,
            &mut waiting_for_idr, &mut skipped, &resync_requested);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn producer_gap_counters_preserve_threshold_boundaries() {
        let mut tracker = ProducerCadence::new();
        for milliseconds in [39, 40, 99, 100, 141] {
            tracker.record_gap(Duration::from_millis(milliseconds));
        }
        assert_eq!(tracker.gap_ge40, 4);
        assert_eq!(tracker.gap_ge100, 2);
        assert_eq!(tracker.gap_max_ms, 141);
    }

    #[test]
    fn only_idr_resets_prediction_chain() {
        let p_frame = [0,0,0,1,9,0x10,0,0,0,1,1,0x20];
        let idr = [0,0,0,1,9,0x10,0,0,0,1,7,0x42,
                   0,0,0,1,8,0xaa,0,0,0,1,5,0x88];
        assert!(!has_idr(&p_frame));
        assert!(has_idr(&idr));
    }

    #[test]
    fn bounded_queue_drops_p_frames_and_resumes_only_at_idr() {
        let (tx, rx) = mpsc::sync_channel(1);
        let mut waiting = false;
        let mut skipped = 0;
        let resync_requested = AtomicBool::new(false);
        let p_frame = vec![0,0,0,1,9,0x10,0,0,0,1,1,0x20];
        let idr = vec![0,0,0,1,9,0x10,0,0,0,1,5,0x88];
        assert!(queue_video_au(&tx, idr.clone(), &mut waiting, &mut skipped, &resync_requested));
        assert!(!waiting);
        assert!(queue_video_au(&tx, p_frame.clone(), &mut waiting, &mut skipped, &resync_requested));
        assert!(waiting); // overflow, no disconnect
        assert_eq!(skipped, 1);
        assert_eq!(rx.try_recv().unwrap().bytes, idr);
        assert!(queue_video_au(&tx, p_frame, &mut waiting, &mut skipped, &resync_requested));
        assert_eq!(skipped, 2);
        assert!(rx.try_recv().is_err());
        assert!(queue_video_au(&tx, idr.clone(), &mut waiting, &mut skipped, &resync_requested));
        assert!(!waiting);
        assert_eq!(skipped, 0);
        assert_eq!(rx.try_recv().unwrap().bytes, idr);
    }

    #[test]
    fn consumer_resync_request_makes_parser_drop_p_frames_until_idr() {
        let (tx, rx) = mpsc::sync_channel(4);
        let resync = AtomicBool::new(true);
        let mut waiting = false;
        let mut skipped = 0;
        let p_frame = vec![0,0,0,1,9,0x10,0,0,0,1,1,0x20];
        let idr = vec![0,0,0,1,9,0x10,0,0,0,1,5,0x88];
        assert!(queue_video_au(&tx, p_frame.clone(), &mut waiting, &mut skipped, &resync));
        assert!(waiting);
        assert!(rx.try_recv().is_err());
        assert!(queue_video_au(&tx, idr.clone(), &mut waiting, &mut skipped, &resync));
        assert!(!waiting);
        assert_eq!(rx.try_recv().unwrap().bytes, idr);
    }

    #[test]
    fn stale_backlog_is_discarded_until_a_fresh_idr() {
        let (tx, rx) = mpsc::sync_channel(4);
        let resync = AtomicBool::new(false);
        let p_frame = vec![0,0,0,1,9,0x10,0,0,0,1,1,0x20];
        let idr = vec![0,0,0,1,9,0x10,0,0,0,1,5,0x88];
        for _ in 0..3 {
            tx.try_send(QueuedAu { bytes: p_frame.clone(), queued_at: Instant::now() }).unwrap();
        }
        tx.try_send(QueuedAu { bytes: idr.clone(), queued_at: Instant::now() }).unwrap();
        let fresh = receive_fresh_idr(&rx, &resync).unwrap();
        assert_eq!(fresh, idr);
        assert!(rx.try_recv().is_err());
        assert!(!resync.load(Ordering::Acquire));
    }

    #[test]
    fn recognizes_aud_and_non_aud_nal_units() {
        let packet = [0,0,0,1,9,0x10,0,0,0,1,7,0x42,0,0,0,1,9,0x10];
        assert_eq!(find_aud(&packet,0),Some(0));
        assert_eq!(find_aud(&packet,5),Some(12));
        assert_eq!(find_aud(&packet,13),None);
    }
    #[test]
    fn parser_splits_access_units_across_read_boundaries() {
        let input = vec![
            0,0,0,1,9,0x10,0,0,0,1,7,0x42, // AU 1
            0,0,0,1,9,0x10,0,0,0,1,1,0xAA, // AU 2
            0,0,0,1,9,0x10,0,0,0,1,1,0xBB, // AU 3
        ];
        struct OneByte(std::io::Cursor<Vec<u8>>);
        impl Read for OneByte {
            fn read(&mut self, b: &mut [u8]) -> io::Result<usize> {
                if b.is_empty() { return Ok(0); }
                self.0.read(&mut b[..1])
            }
        }
        let (tx,rx) = mpsc::sync_channel(4);
        read_access_units(OneByte(std::io::Cursor::new(input)), tx, Arc::new(AtomicBool::new(false)));
        let frames: Vec<_> = rx.try_iter().collect();
        assert_eq!(frames.len(),3);
        assert_eq!(frames[0].bytes,vec![0,0,0,1,9,0x10,0,0,0,1,7,0x42]);
        assert_eq!(frames[1].bytes[4] & 0x1f,9);
        assert_eq!(frames[2].bytes[4] & 0x1f,9);
    }

    fn params() -> EncodeParams {
        EncodeParams::new(60, 1080)
    }

    #[test]
    fn ffmpeg_lookup_order_is_env_then_exe_dir_then_path() {
        let exe_dir = Path::new("C:\\SuanNIAO");
        let path_var = Some(OsString::from("C:\\tools;D:\\ffmpeg\\bin"));
        let everywhere = |_: &Path| true;
        // 环境变量最优先，原样用（可以只写名字）
        assert_eq!(locate_ffmpeg_in(Some("my-ffmpeg".into()), Some(exe_dir), path_var.clone(), everywhere),
            Some((PathBuf::from("my-ffmpeg"), FfmpegSource::Env)));
        // 空的环境变量当没设：用程序文件夹里的
        assert_eq!(locate_ffmpeg_in(Some(OsString::new()), Some(exe_dir), path_var.clone(), everywhere),
            Some((exe_dir.join("ffmpeg.exe"), FfmpegSource::ExeDir)));
        // 程序文件夹里没有，按 PATH 的顺序找第一个有的
        let only_d = |p: &Path| p.starts_with("D:\\ffmpeg");
        assert_eq!(locate_ffmpeg_in(None, Some(exe_dir), path_var.clone(), only_d),
            Some((PathBuf::from("D:\\ffmpeg\\bin\\ffmpeg.exe"), FfmpegSource::PathVar)));
        assert_eq!(locate_ffmpeg_in(None, Some(exe_dir), path_var, |_: &Path| false), None);
        assert_eq!(locate_ffmpeg_in(None, None, None, everywhere), None);
    }

    #[test]
    fn nvenc_keeps_the_old_arguments() {
        // 和以前写死的一样（只是 GOP 从半秒变成 1 秒）
        assert_eq!(encoder_args(Encoder::Nvenc, &params()), strings(&[
            "-c:v", "h264_nvenc", "-preset", "p1", "-tune", "ull", "-zerolatency", "1", "-delay", "0",
            "-bf", "0", "-g", "60", "-rc", "cbr", "-b:v", "4500k", "-maxrate", "4500k", "-bufsize", "650k",
            "-aud", "1",
        ]));
    }

    #[test]
    fn every_encoder_gets_low_latency_rate_gop_and_aud_options() {
        let has = |args: &[String], pair: [&str; 2]| args.windows(2).any(|w| w[0] == pair[0] && w[1] == pair[1]);
        for encoder in CANDIDATES {
            let args = encoder_args(encoder, &EncodeParams::new(30, 720));
            assert!(has(&args, ["-c:v", encoder.codec()]), "{encoder:?}");
            assert!(has(&args, ["-bf", "0"]) && has(&args, ["-g", "30"]), "{encoder:?}");
            assert!(has(&args, ["-b:v", "3500k"]) && has(&args, ["-maxrate", "3500k"]) && has(&args, ["-bufsize", "500k"]), "{encoder:?}");
            // 拆帧靠 AUD：MF 自己带，别的要打开
            let mf = matches!(encoder, Encoder::MfHardware | Encoder::MfSoftware);
            assert_eq!(has(&args, ["-aud", "1"]), !mf, "{encoder:?}");
        }
        let args = |encoder| encoder_args(encoder, &params());
        assert!(has(&args(Encoder::Qsv), ["-async_depth", "1"]) && has(&args(Encoder::Qsv), ["-look_ahead", "0"]));
        assert!(has(&args(Encoder::Amf), ["-usage", "ultralowlatency"]) && has(&args(Encoder::Amf), ["-rc", "cbr"]));
        assert!(has(&args(Encoder::MfHardware), ["-hw_encoding", "1"]));
        assert!(!args(Encoder::MfSoftware).contains(&"-hw_encoding".to_string()));
        assert!(has(&args(Encoder::X264), ["-tune", "zerolatency"]));
    }

    #[test]
    fn hardware_encoders_are_tried_before_software() {
        let order: Vec<_> = CANDIDATES.iter().map(|e| e.label()).collect();
        assert_eq!(order, ["h264_nvenc", "h264_qsv", "h264_amf", "h264_mf（硬件）", "h264_mf（软件）", "libx264"]);
    }

    #[test]
    fn stream_downloads_to_nv12_and_probe_never_grabs_the_desktop() {
        let args = stream_args(Encoder::Nvenc, &params(), 1920, 1080);
        assert!(args.windows(2).any(|w| w[0] == "-i" && w[1] == "ddagrab=output_idx=0:framerate=60:draw_mouse=1"));
        // 不缩放时也一样下载到内存（核显上的 D3D11 画面 NVENC 打不开）
        assert!(args.windows(2).any(|w| w[0] == "-vf"
            && w[1] == "hwdownload,format=bgra,scale=1920:1080:flags=fast_bilinear,format=nv12"));
        assert!(args.ends_with(&output_args()));
        let probe = probe_args(Encoder::Qsv, &params());
        assert!(probe.iter().all(|a| !a.contains("ddagrab")));
        assert!(probe.windows(2).any(|w| w[0] == "-frames:v" && w[1] == "10"));
        assert!(probe.ends_with(&output_args()));
    }

    #[test]
    fn idr_about_once_a_second_and_resync_wait_stays_under_stall_limit() {
        for fps in [15, 30, 60] {
            assert_eq!(gop_frames(fps), fps);
            // 实际帧率只有目标一半时，等下一个 IDR 也到不了「卡住」的判定（4 秒）
            let worst = Duration::from_secs_f64(f64::from(gop_frames(fps)) / (f64::from(fps) / 2.0));
            assert!(worst < WAIT * MAX_MISSES, "{fps}");
        }
    }

    #[test]
    fn probe_output_must_split_and_start_with_parameter_sets() {
        let idr_au = [0,0,0,1,9,0x10, 0,0,0,1,0x67,0x42, 0,0,0,1,0x68,0xce, 0,0,0,1,0x65,0x88];
        let p_au = [0,0,0,1,9,0x30, 0,0,0,1,0x41,0x9a];
        let good = [&idr_au[..], &p_au[..]].concat();
        assert_eq!(check_probe_stream(&good), Ok(()));
        assert!(check_probe_stream(&good[6..]).is_err()); // 开头不是 AUD
        assert!(check_probe_stream(&idr_au).is_err()); // 只有一个 AUD
        assert!(check_probe_stream(&[]).is_err());
        let no_sps = [&[0,0,0,1,9,0x10, 0,0,0,1,0x65,0x88][..], &p_au[..]].concat();
        assert!(check_probe_stream(&no_sps).is_err());
        let no_idr = [&[0,0,0,1,9,0x10, 0,0,0,1,0x67,0x42, 0,0,0,1,0x68,0xce, 0,0,0,1,0x41,0x9a][..], &p_au[..]].concat();
        assert!(check_probe_stream(&no_idr).is_err());
        // 两个 AUD 挨着（空帧）也不行
        let double_aud = [&[0,0,0,1,9,0x10][..], &good[..]].concat();
        assert!(check_probe_stream(&double_aud).is_err());
    }

    /// 一帧都没出来时：编码器退出、一直不出画面才算编码器起不来（重新试一次编码器）；
    /// 手机解除绑定、断开（别的错误）不算，推流正常结束也不算
    #[test]
    fn only_encoder_errors_before_the_first_frame_trigger_a_reprobe() {
        let err = |kind, text: &str| -> io::Result<()> { Err(io::Error::new(kind, text.to_string())) };
        assert!(encoder_never_started(&err(io::ErrorKind::BrokenPipe, "video encoder ended")));
        assert!(encoder_never_started(&err(io::ErrorKind::TimedOut, "video encoder stalled")));
        assert!(!encoder_never_started(&Err(io::Error::other("video pairing no longer active"))));
        assert!(!encoder_never_started(&Ok(())));
        // 两次「起不来」之间没有成功过：只重新试一次（第二次直接返回，不动选好的编码器）
        let choice = Choice { ffmpeg: PathBuf::from("not-a-real-ffmpeg.exe"), encoder: Encoder::X264 };
        REPROBED.store(true, Ordering::Release);
        *CHOICE.lock().unwrap() = Some(Probed::Ready(choice.clone()));
        encoder_failed_to_start(&choice);
        assert!(matches!(&*CHOICE.lock().unwrap(), Some(Probed::Ready(c)) if *c == choice));
        *CHOICE.lock().unwrap() = None;
        REPROBED.store(false, Ordering::Release);
    }

    #[test]
    fn error_line_drops_ffmpeg_context_prefix() {
        let stderr = b"\r\n[h264_amf @ 000001932ba51f40] DLL amfrt64.dll failed to open\r\n[vost#0:0/h264_amf @ 0000] Error while opening encoder\n";
        assert_eq!(first_error_line(stderr), "DLL amfrt64.dll failed to open");
        assert_eq!(first_error_line(b"plain failure"), "plain failure");
        assert!(!first_error_line(b"").is_empty());
        assert_eq!(first_error_line("x".repeat(500).as_bytes()).chars().count(), 160);
    }

    /// 用本机的 ffmpeg 把每个编码器真试一遍（合成画面，不抓桌面）：
    /// cargo test -p suanniao-core -- --ignored --nocapture probe_local_encoders
    #[test]
    #[ignore]
    fn probe_local_encoders() {
        let (ffmpeg, source) = locate_ffmpeg().expect("没找到 ffmpeg");
        println!("ffmpeg（{}）：{}", source.label(), ffmpeg.display());
        for encoder in CANDIDATES {
            let started = Instant::now();
            let result = match probe_encoder(&ffmpeg, encoder, &params()) {
                Ok(()) => "能用".to_string(),
                Err(ProbeError::Spawn(error)) => format!("启动不了：{error}"),
                Err(ProbeError::Unusable(why)) => format!("不能用：{why}"),
            };
            println!("{:16} {:5}ms {result}", encoder.label(), started.elapsed().as_millis());
        }
    }
}
