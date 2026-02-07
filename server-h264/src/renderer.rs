//! Renderer module: native window + softbuffer framebuffer.
//!
//! Uses winit 0.30 (ApplicationHandler) + softbuffer 0.4 for a
//! compatible software rendering pipeline.

use crate::RgbFrame;
use anyhow::{Context, Result};
use crossbeam_channel::Receiver;
use log::{error, info};
use std::num::NonZeroU32;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use winit::application::ApplicationHandler;
use winit::dpi::LogicalSize;
use winit::event::WindowEvent;
use winit::event_loop::{ActiveEventLoop, ControlFlow, EventLoop};
use winit::window::{Window, WindowId};

/// Run the main window event loop (must be called from main thread).
pub fn run_window(
    initial_width: u32,
    initial_height: u32,
    frame_rx: Receiver<RgbFrame>,
    rotation: Arc<AtomicU32>,
    running: Arc<AtomicBool>,
) -> Result<()> {
    let event_loop = EventLoop::new().context("Failed to create event loop")?;
    event_loop.set_control_flow(ControlFlow::Poll);

    let mut app = App {
        initial_width,
        initial_height,
        frame_rx,
        rotation,
        running,
        window: None,
        surface: None,
        video_width: initial_width,
        video_height: initial_height,
        last_rotation: 0,
        frame_data: vec![0u8; (initial_width * initial_height * 4) as usize],
        dirty: false,
        last_draw: Instant::now(),
        fps_counter: FpsCounter::new(),
        connected: false,
    };

    event_loop.run_app(&mut app).context("Event loop error")?;
    Ok(())
}

struct App {
    initial_width: u32,
    initial_height: u32,
    frame_rx: Receiver<RgbFrame>,
    rotation: Arc<AtomicU32>,
    running: Arc<AtomicBool>,
    window: Option<Arc<Window>>,
    surface: Option<softbuffer::Surface<Arc<Window>, Arc<Window>>>,
    video_width: u32,
    video_height: u32,
    last_rotation: u32,
    frame_data: Vec<u8>, // Current RGBA frame
    dirty: bool,
    last_draw: Instant,
    fps_counter: FpsCounter,
    connected: bool,
}

impl ApplicationHandler for App {
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.window.is_some() {
            return; // Already created
        }

        let attrs = Window::default_attributes()
            .with_title("H.264 TCP Viewer")
            .with_inner_size(LogicalSize::new(self.initial_width, self.initial_height))
            .with_min_inner_size(LogicalSize::new(320u32, 240u32));

        match event_loop.create_window(attrs) {
            Ok(window) => {
                let window = Arc::new(window);
                info!("Window created: {}×{}", self.initial_width, self.initial_height);

                // Create softbuffer surface
                let context = softbuffer::Context::new(window.clone())
                    .expect("Failed to create softbuffer context");
                let surface = softbuffer::Surface::new(&context, window.clone())
                    .expect("Failed to create softbuffer surface");

                self.window = Some(window);
                self.surface = Some(surface);
            }
            Err(e) => {
                error!("Failed to create window: {}", e);
                event_loop.exit();
            }
        }
    }

    fn window_event(
        &mut self,
        event_loop: &ActiveEventLoop,
        _window_id: WindowId,
        event: WindowEvent,
    ) {
        match event {
            WindowEvent::CloseRequested => {
                info!("Window close requested");
                self.running.store(false, Ordering::Relaxed);
                event_loop.exit();
            }
            WindowEvent::Resized(_) => {
                self.dirty = true;
            }
            WindowEvent::RedrawRequested => {
                self.redraw();
            }
            _ => {}
        }
    }

    fn about_to_wait(&mut self, _event_loop: &ActiveEventLoop) {
        // Poll for new decoded frames
        self.poll_frames();

        // Request redraw if dirty
        if self.dirty {
            let now = Instant::now();
            if now.duration_since(self.last_draw) >= Duration::from_micros(8_333) {
                if let Some(window) = &self.window {
                    window.request_redraw();
                }
            }
        } else {
            // Small sleep to avoid busy-spinning when idle
            std::thread::sleep(Duration::from_millis(2));
        }
    }
}

impl App {
    fn poll_frames(&mut self) {
        let mut latest: Option<RgbFrame> = None;
        while let Ok(frame) = self.frame_rx.try_recv() {
            latest = Some(frame);
        }

        if let Some(frame) = latest {
            if frame.width != self.video_width || frame.height != self.video_height {
                info!(
                    "Video resolution changed: {}×{} → {}×{}",
                    self.video_width, self.video_height, frame.width, frame.height
                );
                self.video_width = frame.width;
                self.video_height = frame.height;
                self.resize_window_to_video();
            }

            self.frame_data = frame.data;

            if !self.connected {
                self.connected = true;
                info!("First frame received — streaming active");
                self.resize_window_to_video();
            }

            self.fps_counter.tick();
            self.dirty = true;
        }

        // Check if rotation changed (set by network thread via control message)
        let current_rotation = self.rotation.load(Ordering::Relaxed);
        if current_rotation != self.last_rotation {
            info!("Rotation changed: {}° → {}°", self.last_rotation, current_rotation);
            self.last_rotation = current_rotation;
            self.resize_window_to_video();
            self.dirty = true;
        }
    }

    /// Resize the window to match the video aspect ratio (accounting for rotation).
    /// Keeps a reasonable size (max 900px on the longest side).
    fn resize_window_to_video(&self) {
        let window = match self.window.as_ref() {
            Some(w) => w,
            None => return,
        };

        let rot = self.last_rotation;
        // Effective dimensions after rotation
        let (vw, vh) = if rot == 90 || rot == 270 {
            (self.video_height as f64, self.video_width as f64)
        } else {
            (self.video_width as f64, self.video_height as f64)
        };
        if vw == 0.0 || vh == 0.0 {
            return;
        }

        // Target max dimension: 900 pixels
        let max_dim = 900.0;
        let scale = max_dim / vw.max(vh);
        let new_w = (vw * scale).round() as u32;
        let new_h = (vh * scale).round() as u32;

        info!("Resizing window to {}×{} (video {}×{}, rotation {}°)", 
              new_w, new_h, self.video_width, self.video_height, rot);
        let _ = window.request_inner_size(LogicalSize::new(new_w, new_h));
        window.set_title(&format!("H.264 Viewer — {}×{} ({}°)", self.video_width, self.video_height, rot));
    }

    fn redraw(&mut self) {
        let surface = match self.surface.as_mut() {
            Some(s) => s,
            None => return,
        };
        let window = match self.window.as_ref() {
            Some(w) => w,
            None => return,
        };

        let win_size = window.inner_size();
        if win_size.width == 0 || win_size.height == 0 {
            return;
        }

        let sw = NonZeroU32::new(win_size.width).unwrap();
        let sh = NonZeroU32::new(win_size.height).unwrap();

        if surface.resize(sw, sh).is_err() {
            error!("Failed to resize surface");
            return;
        }

        let mut buffer = match surface.buffer_mut() {
            Ok(b) => b,
            Err(e) => {
                error!("Failed to get surface buffer: {}", e);
                return;
            }
        };

        let dst_w = win_size.width as usize;
        let dst_h = win_size.height as usize;
        let src_w = self.video_width as usize;
        let src_h = self.video_height as usize;
        let rotation_deg = self.last_rotation;

        // Effective (post-rotation) dimensions
        let (eff_w, eff_h) = match rotation_deg {
            90 | 270 => (src_h, src_w),
            _ => (src_w, src_h),
        };

        // Letterbox / pillarbox: fit rotated video inside window keeping aspect ratio
        let scale_x = dst_w as f64 / eff_w as f64;
        let scale_y = dst_h as f64 / eff_h as f64;
        let scale = scale_x.min(scale_y);
        let fit_w = (eff_w as f64 * scale) as usize;
        let fit_h = (eff_h as f64 * scale) as usize;
        let offset_x = (dst_w.saturating_sub(fit_w)) / 2;
        let offset_y = (dst_h.saturating_sub(fit_h)) / 2;

        for dst_y in 0..dst_h {
            for dst_x in 0..dst_w {
                let pixel = if dst_x >= offset_x && dst_x < offset_x + fit_w
                    && dst_y >= offset_y && dst_y < offset_y + fit_h
                {
                    let rel_x = dst_x - offset_x;
                    let rel_y = dst_y - offset_y;
                    // Map to effective (rotated) coordinates
                    let eff_x = (rel_x * eff_w) / fit_w;
                    let eff_y = (rel_y * eff_h) / fit_h;

                    // Reverse-rotate to get actual source pixel coordinates
                    let (ax, ay) = match rotation_deg {
                        90  => (eff_y, eff_w.saturating_sub(1).saturating_sub(eff_x)),
                        180 => (eff_x, eff_y), // Était inversé avec 0°
                        270 => (eff_h.saturating_sub(1).saturating_sub(eff_y), eff_x),
                        _   => (src_w.saturating_sub(1).saturating_sub(eff_x),
                                src_h.saturating_sub(1).saturating_sub(eff_y)), // 0° = flip 180
                    };

                    let src_idx = (ay * src_w + ax) * 4;
                    if src_idx + 2 < self.frame_data.len() {
                        let r = self.frame_data[src_idx] as u32;
                        let g = self.frame_data[src_idx + 1] as u32;
                        let b = self.frame_data[src_idx + 2] as u32;
                        (r << 16) | (g << 8) | b
                    } else {
                        0x00222222
                    }
                } else {
                    // Black bars (letterbox/pillarbox)
                    0x00000000
                };

                let dst_idx = dst_y * dst_w + dst_x;
                if dst_idx < buffer.len() {
                    buffer[dst_idx] = pixel;
                }
            }
        }

        if buffer.present().is_err() {
            error!("Failed to present buffer");
        }

        self.dirty = false;
        self.last_draw = Instant::now();
    }
}

struct FpsCounter {
    frame_count: u64,
    last_report: Instant,
}

impl FpsCounter {
    fn new() -> Self {
        Self {
            frame_count: 0,
            last_report: Instant::now(),
        }
    }

    fn tick(&mut self) {
        self.frame_count += 1;
        let elapsed = self.last_report.elapsed();
        if elapsed >= Duration::from_secs(5) {
            let fps = self.frame_count as f64 / elapsed.as_secs_f64();
            info!("Display FPS: {:.1}", fps);
            self.frame_count = 0;
            self.last_report = Instant::now();
        }
    }
}