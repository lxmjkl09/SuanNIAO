//! 受局域网配对认证保护的桌面预览流（第一阶段：JPEG 帧）。
//! 不保存截图、不依赖云服务；视频独立于原有 JSON 控制会话，避免阻塞按键。
use std::ffi::c_void;
use std::io;
use std::mem::size_of;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use image::{codecs::jpeg::JpegEncoder, RgbImage};
use suanniao_lan::SecureConn;
use windows::Win32::Graphics::Gdi::{
    BitBlt, CreateCompatibleDC, CreateDIBSection, DeleteDC, DeleteObject, GetDC, ReleaseDC,
    SelectObject, BITMAPINFO, BI_RGB, DIB_RGB_COLORS, HGDIOBJ, SRCCOPY,
};
use windows::Win32::UI::WindowsAndMessaging::{GetSystemMetrics, SM_CXSCREEN, SM_CYSCREEN};

use crate::{lan, link};

const MAX_CAPTURE_DIM: i32 = 3840;
const MAX_JPEG: usize = 3 * 1024 * 1024;
const INTERVAL: Duration = Duration::from_millis(250);
static STREAM_ACTIVE: AtomicBool = AtomicBool::new(false);

/// 已过 TLS + 配对认证的手机才能订阅；每台电脑同时只编码一路屏幕。
/// 连接中断、解除绑定或控制通道断开后停止截屏。
pub fn serve(conn: SecureConn, phone_id: &str) {
    if link::lan_phone().as_deref() != Some(phone_id) || !lan::known_phone(phone_id) {
        conn.shutdown();
        return;
    }
    if STREAM_ACTIVE.swap(true, Ordering::AcqRel) {
        conn.shutdown();
        return;
    }
    let _busy = Busy;
    loop {
        if link::lan_phone().as_deref() != Some(phone_id) || !lan::known_phone(phone_id) {
            break;
        }
        match capture_jpeg() {
            Ok((width, height, jpeg)) if jpeg.len() <= MAX_JPEG => {
                // SCN1 + 大端宽 + 高 + JPEG 长度。固定头部，限制手机端分配。
                let mut header = [0u8; 16];
                header[..4].copy_from_slice(b"SCN1");
                header[4..8].copy_from_slice(&width.to_be_bytes());
                header[8..12].copy_from_slice(&height.to_be_bytes());
                header[12..16].copy_from_slice(&(jpeg.len() as u32).to_be_bytes());
                if conn.write_all(&header).is_err() || conn.write_all(&jpeg).is_err() {
                    break;
                }
            }
            _ => break,
        }
        std::thread::sleep(INTERVAL);
    }
    conn.shutdown();
}

struct Busy;
impl Drop for Busy {
    fn drop(&mut self) {
        STREAM_ACTIVE.store(false, Ordering::Release);
    }
}

/// 读取 Windows 当前主显示器。首版只抓主显示器，后续再切换到 WGC/H.264。
/// GDI 截帧只在认证连接活跃期间执行，所有句柄在返回前释放。
fn capture_jpeg() -> io::Result<(u32, u32, Vec<u8>)> {
    let (width, height) = unsafe { (GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN)) };
    if width <= 0 || height <= 0 || width > MAX_CAPTURE_DIM || height > MAX_CAPTURE_DIM {
        return Err(io::Error::other("unsupported desktop size"));
    }
    let pixels = capture_bgra(width, height)?;
    let mut rgb = Vec::with_capacity((width as usize) * (height as usize) * 3);
    for p in pixels.chunks_exact(4) {
        rgb.extend_from_slice(&[p[2], p[1], p[0]]);
    }
    let image = RgbImage::from_raw(width as u32, height as u32, rgb)
        .ok_or_else(|| io::Error::other("invalid frame buffer"))?;
    let mut jpg = Vec::new();
    JpegEncoder::new_with_quality(&mut jpg, 72)
        .encode_image(&image)
        .map_err(io::Error::other)?;
    Ok((width as u32, height as u32, jpg))
}

fn capture_bgra(width: i32, height: i32) -> io::Result<Vec<u8>> {
    unsafe {
        let desktop = GetDC(None);
        if desktop.is_invalid() {
            return Err(io::Error::last_os_error());
        }
        let mem = CreateCompatibleDC(Some(desktop));
        if mem.is_invalid() {
            ReleaseDC(None, desktop);
            return Err(io::Error::last_os_error());
        }
        let mut info = BITMAPINFO::default();
        info.bmiHeader.biSize = size_of::<windows::Win32::Graphics::Gdi::BITMAPINFOHEADER>() as u32;
        info.bmiHeader.biWidth = width;
        info.bmiHeader.biHeight = -height; // 顶行在前
        info.bmiHeader.biPlanes = 1;
        info.bmiHeader.biBitCount = 32;
        info.bmiHeader.biCompression = BI_RGB.0;
        let mut bits: *mut c_void = std::ptr::null_mut();
        let result = CreateDIBSection(Some(desktop), &info, DIB_RGB_COLORS, &mut bits, None, 0);
        let bitmap = match result {
            Ok(b) if !bits.is_null() => b,
            _ => {
                let _ = DeleteDC(mem);
                ReleaseDC(None, desktop);
                return Err(io::Error::other("CreateDIBSection failed"));
            }
        };
        let old = SelectObject(mem, HGDIOBJ(bitmap.0));
        let copied = BitBlt(mem, 0, 0, width, height, Some(desktop), 0, 0, SRCCOPY);
        let length = width as usize * height as usize * 4;
        let image = if copied.is_ok() {
            Some(std::slice::from_raw_parts(bits as *const u8, length).to_vec())
        } else {
            None
        };
        SelectObject(mem, old);
        let _ = DeleteObject(HGDIOBJ(bitmap.0));
        let _ = DeleteDC(mem);
        ReleaseDC(None, desktop);
        image.ok_or_else(|| io::Error::other("desktop capture failed"))
    }
}
