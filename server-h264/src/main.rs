mod decoder;
mod net;
mod renderer;

use anyhow::Result;
use crossbeam_channel::bounded;
use log::{info, warn, error};
use std::env;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;

/// Decoded RGBA frame ready for display.
pub struct RgbFrame {
    pub width: u32,
    pub height: u32,
    pub data: Vec<u8>, // RGBA pixels
}

/// Application configuration.
struct Config {
    port: u16,
    width: u32,
    height: u32,
    framing_mode: FramingMode,
}

#[derive(Clone, Copy, Debug)]
pub enum FramingMode {
    Auto,
    LengthPrefixed,
    AnnexB,
}

fn parse_args() -> Config {
    let args: Vec<String> = env::args().collect();
    let mut config = Config {
        port: 8554,
        width: 1280,
        height: 720,
        framing_mode: FramingMode::Auto,
    };

    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--port" => {
                i += 1;
                config.port = args[i].parse().expect("Invalid port");
            }
            "--width" => {
                i += 1;
                config.width = args[i].parse().expect("Invalid width");
            }
            "--height" => {
                i += 1;
                config.height = args[i].parse().expect("Invalid height");
            }
            "--mode" => {
                i += 1;
                config.framing_mode = match args[i].as_str() {
                    "length" => FramingMode::LengthPrefixed,
                    "annexb" => FramingMode::AnnexB,
                    "auto" => FramingMode::Auto,
                    _ => panic!("Invalid mode: use 'length', 'annexb', or 'auto'"),
                };
            }
            "--help" | "-h" => {
                println!("H.264 TCP Video Viewer");
                println!();
                println!("Usage: h264-viewer.exe [OPTIONS]");
                println!();
                println!("Options:");
                println!("  --port <PORT>      TCP listen port (default: 8554)");
                println!("  --width <WIDTH>    Video width hint (default: 1280)");
                println!("  --height <HEIGHT>  Video height hint (default: 720)");
                println!("  --mode <MODE>      'length', 'annexb', or 'auto' (default: auto)");
                std::process::exit(0);
            }
            _ => {
                warn!("Unknown argument: {}", args[i]);
            }
        }
        i += 1;
    }

    config
}

fn main() -> Result<()> {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    let config = parse_args();
    info!(
        "Starting H.264 Viewer — listening on port {}, initial window {}x{}",
        config.port, config.width, config.height
    );

    // Channel: decoder thread → render thread (bounded, drop-if-full for low latency)
    let (frame_tx, frame_rx) = bounded::<RgbFrame>(4);

    let running = Arc::new(AtomicBool::new(true));
    let rotation = Arc::new(AtomicU32::new(0)); // Rotation in degrees (0, 90, 180, 270)

    // Spawn network + decode pipeline in a background thread
    let running_clone = running.clone();
    let rotation_clone = rotation.clone();
    let port = config.port;
    let framing_mode = config.framing_mode;

    std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("Failed to create Tokio runtime");

        rt.block_on(async {
            // Spawn UDP discovery service
            let running_discovery = running_clone.clone();
            let discovery_port = port;
            tokio::spawn(async move {
                if let Err(e) = net::run_discovery_service(discovery_port, &running_discovery).await {
                    error!("Discovery service error: {:#}", e);
                }
            });

            // Main TCP accept loop
            loop {
                if !running_clone.load(Ordering::Relaxed) {
                    break;
                }
                info!("Waiting for TCP connection on 0.0.0.0:{} ...", port);
                match net::accept_and_stream(port, framing_mode, &frame_tx, &rotation_clone, &running_clone).await {
                    Ok(()) => info!("Client disconnected, waiting for new connection..."),
                    Err(e) => {
                        error!("Network/decode error: {:#}", e);
                        tokio::time::sleep(std::time::Duration::from_secs(1)).await;
                    }
                }
            }
        });
    });

    // Run the window + render loop on the main thread (required by winit on Windows)
    renderer::run_window(config.width, config.height, frame_rx, rotation, running)?;

    Ok(())
}