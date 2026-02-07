//! Network module: accepts TCP connections and extracts H.264 NAL units.
//!
//! Supports two framing modes:
//! - **Length-prefixed**: each NAL is preceded by a 4-byte big-endian length.
//! - **Annex-B**: standard H.264 byte stream with 0x00000001 / 0x000001 start codes.

use crate::decoder::H264Decoder;
use crate::{FramingMode, RgbFrame};
use anyhow::{Context, Result};
use crossbeam_channel::Sender;
use log::{debug, info, warn};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;
use tokio::io::{AsyncReadExt, BufReader};
use tokio::net::TcpListener;

const MAX_NAL_SIZE: u32 = 16 * 1024 * 1024;
const CTRL_MAGIC: &[u8; 4] = b"CTRL";

/// Accept one TCP client and stream decoded frames until disconnect.
pub async fn accept_and_stream(
    port: u16,
    mode: FramingMode,
    frame_tx: &Sender<RgbFrame>,
    rotation: &Arc<AtomicU32>,
    running: &Arc<AtomicBool>,
) -> Result<()> {
    let listener = TcpListener::bind(format!("0.0.0.0:{}", port))
        .await
        .with_context(|| format!("Failed to bind TCP on port {}", port))?;

    let (socket, addr) = listener.accept().await?;
    info!("Client connected from {}", addr);

    let mut reader = BufReader::with_capacity(256 * 1024, socket);
    let mut decoder = H264Decoder::new()?;

    // Auto-detect framing mode from first 4 bytes
    match mode {
        FramingMode::Auto => {
            let mut peek = [0u8; 4];
            reader.read_exact(&mut peek).await?;

            if peek == [0x00, 0x00, 0x00, 0x01] {
                info!("Auto-detected Annex-B framing");
                process_annexb_with_initial(&mut reader, &peek, &mut decoder, frame_tx, running)
                    .await?;
            } else {
                info!("Auto-detected length-prefixed framing");
                let first_len = u32::from_be_bytes(peek);
                // Read first payload and check if it's a control message
                read_one_payload(&mut reader, first_len, &mut decoder, frame_tx, rotation).await?;
                read_length_prefixed(&mut reader, &mut decoder, frame_tx, rotation, running).await?;
            }
        }
        FramingMode::LengthPrefixed => {
            read_length_prefixed(&mut reader, &mut decoder, frame_tx, rotation, running).await?;
        }
        FramingMode::AnnexB => {
            read_annexb(&mut reader, &mut decoder, frame_tx, running).await?;
        }
    }

    Ok(())
}

// ─── Length-prefixed reader ─────────────────────────────────────────────────

async fn read_length_prefixed<R: tokio::io::AsyncRead + Unpin>(
    reader: &mut R,
    decoder: &mut H264Decoder,
    frame_tx: &Sender<RgbFrame>,
    rotation: &Arc<AtomicU32>,
    running: &Arc<AtomicBool>,
) -> Result<()> {
    let mut len_buf = [0u8; 4];
    while running.load(Ordering::Relaxed) {
        if reader.read_exact(&mut len_buf).await.is_err() {
            info!("Connection closed (length read)");
            return Ok(());
        }
        let payload_len = u32::from_be_bytes(len_buf);
        read_one_payload(reader, payload_len, decoder, frame_tx, rotation).await?;
    }
    Ok(())
}

/// Read one length-prefixed payload: either a control message or an H.264 NAL.
async fn read_one_payload<R: tokio::io::AsyncRead + Unpin>(
    reader: &mut R,
    payload_len: u32,
    decoder: &mut H264Decoder,
    frame_tx: &Sender<RgbFrame>,
    rotation: &Arc<AtomicU32>,
) -> Result<()> {
    if payload_len == 0 || payload_len > MAX_NAL_SIZE {
        warn!("Suspicious payload length: {} — skipping", payload_len);
        return Ok(());
    }

    let mut buf = vec![0u8; payload_len as usize];
    reader.read_exact(&mut buf).await?;

    // Check for control message (starts with "CTRL" magic)
    if buf.len() >= 4 && &buf[0..4] == CTRL_MAGIC {
        handle_control_message(&buf[4..], rotation);
        return Ok(());
    }

    // Otherwise, decode as H.264 NAL unit
    decode_nal_buffer(&buf, decoder, frame_tx)
}

/// Handle a control message from the Android client.
fn handle_control_message(data: &[u8], rotation: &Arc<AtomicU32>) {
    if data.is_empty() {
        warn!("Empty control message");
        return;
    }

    let msg_type = data[0];
    match msg_type {
        0x01 => {
            // Rotation: 2 bytes big-endian angle in degrees
            if data.len() >= 3 {
                let angle = u16::from_be_bytes([data[1], data[2]]) as u32;
                let old = rotation.swap(angle, Ordering::Relaxed);
                if old != angle {
                    info!("Control: rotation changed {}° → {}°", old, angle);
                }
            } else {
                warn!("Rotation control message too short: {} bytes", data.len());
            }
        }
        _ => {
            warn!("Unknown control message type: 0x{:02x}", msg_type);
        }
    }
}

/// Decode a pre-read buffer as an H.264 NAL unit.
fn decode_nal_buffer(
    nal_buf: &[u8],
    decoder: &mut H264Decoder,
    frame_tx: &Sender<RgbFrame>,
) -> Result<()> {
    // Check if data already has Annex-B start code
    let has_start_code = nal_buf.len() >= 4 
        && nal_buf[0] == 0x00 
        && nal_buf[1] == 0x00 
        && (nal_buf[2] == 0x01 || (nal_buf[2] == 0x00 && nal_buf[3] == 0x01));

    let packet = if has_start_code {
        let nal_type = if nal_buf[2] == 0x01 {
            nal_buf[3] & 0x1F
        } else {
            nal_buf[4] & 0x1F
        };
        debug!("NAL with start code: type={} len={}", nal_type, nal_buf.len());
        nal_buf.to_vec()
    } else {
        let nal_type = nal_buf[0] & 0x1F;
        debug!("NAL without start code: type={} len={}", nal_type, nal_buf.len());
        
        let mut packet = Vec::with_capacity(4 + nal_buf.len());
        packet.extend_from_slice(&[0x00, 0x00, 0x00, 0x01]);
        packet.extend_from_slice(nal_buf);
        packet
    };

    match decoder.decode(&packet) {
        Ok(Some(frame)) => {
            debug!("Decoded frame: {}x{}", frame.width, frame.height);
            let _ = frame_tx.try_send(frame);
        }
        Ok(None) => {
            debug!("No frame output (buffering)");
        }
        Err(e) => {
            warn!("Decode error (continuing): {}", e);
        }
    }

    Ok(())
}

// ─── Annex-B byte-stream reader ────────────────────────────────────────────

async fn read_annexb<R: tokio::io::AsyncRead + Unpin>(
    reader: &mut R,
    decoder: &mut H264Decoder,
    frame_tx: &Sender<RgbFrame>,
    running: &Arc<AtomicBool>,
) -> Result<()> {
    let mut buf = Vec::with_capacity(512 * 1024);
    let mut tmp = [0u8; 64 * 1024];

    while running.load(Ordering::Relaxed) {
        let n = reader.read(&mut tmp).await?;
        if n == 0 {
            info!("Connection closed (Annex-B)");
            return Ok(());
        }
        buf.extend_from_slice(&tmp[..n]);
        extract_and_decode_nals(&mut buf, decoder, frame_tx)?;
    }
    Ok(())
}

async fn process_annexb_with_initial<R: tokio::io::AsyncRead + Unpin>(
    reader: &mut R,
    initial: &[u8],
    decoder: &mut H264Decoder,
    frame_tx: &Sender<RgbFrame>,
    running: &Arc<AtomicBool>,
) -> Result<()> {
    let mut buf = Vec::with_capacity(512 * 1024);
    buf.extend_from_slice(initial);

    let mut tmp = [0u8; 64 * 1024];
    while running.load(Ordering::Relaxed) {
        let n = reader.read(&mut tmp).await?;
        if n == 0 {
            info!("Connection closed (Annex-B)");
            return Ok(());
        }
        buf.extend_from_slice(&tmp[..n]);
        extract_and_decode_nals(&mut buf, decoder, frame_tx)?;
    }
    Ok(())
}

/// Find Annex-B start codes and extract complete NAL units.
fn extract_and_decode_nals(
    buf: &mut Vec<u8>,
    decoder: &mut H264Decoder,
    frame_tx: &Sender<RgbFrame>,
) -> Result<()> {
    loop {
        let start = match find_start_code(buf, 0) {
            Some(pos) => pos,
            None => break,
        };

        let end = match find_start_code(buf, start + 3) {
            Some(pos) => pos,
            None => break, // NAL not yet complete
        };

        let nal_packet = buf[start..end].to_vec();
        debug!("Annex-B NAL extracted: {} bytes", nal_packet.len());

        if let Some(frame) = decoder.decode(&nal_packet)? {
            let _ = frame_tx.try_send(frame);
        }

        buf.drain(..end);
    }

    // Prevent unbounded growth
    if buf.len() > 4 * 1024 * 1024 {
        let keep = buf.len() - 1024 * 1024;
        buf.drain(..keep);
    }

    Ok(())
}

/// Locate the next Annex-B start code (0x00000001 or 0x000001).
fn find_start_code(buf: &[u8], offset: usize) -> Option<usize> {
    if buf.len() < offset + 3 {
        return None;
    }
    for i in offset..buf.len() - 2 {
        if buf[i] == 0x00 && buf[i + 1] == 0x00 {
            if buf[i + 2] == 0x01 {
                return Some(i);
            }
            if i + 3 < buf.len() && buf[i + 2] == 0x00 && buf[i + 3] == 0x01 {
                return Some(i);
            }
        }
    }
    None
}

// ─── UDP Discovery Service ─────────────────────────────────────────────────

const DISCOVERY_PORT: u16 = 8555;
const DISCOVERY_MESSAGE: &[u8] = b"CAMSTREAM_DISCOVER";
const RESPONSE_PREFIX: &str = "CAMSTREAM_SERVER:";

/// Run UDP discovery responder.
/// Listens for "CAMSTREAM_DISCOVER" broadcasts and responds with "CAMSTREAM_SERVER:<tcp_port>".
pub async fn run_discovery_service(tcp_port: u16, running: &Arc<AtomicBool>) -> Result<()> {
    use tokio::net::UdpSocket;
    
    let socket = UdpSocket::bind(format!("0.0.0.0:{}", DISCOVERY_PORT))
        .await
        .with_context(|| format!("Failed to bind UDP discovery on port {}", DISCOVERY_PORT))?;
    
    info!("Discovery service listening on UDP port {}", DISCOVERY_PORT);
    
    let mut buf = [0u8; 256];
    
    while running.load(Ordering::Relaxed) {
        // Use a timeout to periodically check if we should stop
        match tokio::time::timeout(
            std::time::Duration::from_secs(1),
            socket.recv_from(&mut buf)
        ).await {
            Ok(Ok((len, src))) => {
                let message = &buf[..len];
                
                if message == DISCOVERY_MESSAGE {
                    info!("Discovery request from {}", src);
                    
                    let response = format!("{}{}", RESPONSE_PREFIX, tcp_port);
                    if let Err(e) = socket.send_to(response.as_bytes(), src).await {
                        warn!("Failed to send discovery response: {}", e);
                    } else {
                        info!("Sent discovery response to {}: {}", src, response);
                    }
                } else {
                    debug!("Unknown discovery message from {}: {:?}", src, message);
                }
            }
            Ok(Err(e)) => {
                warn!("UDP receive error: {}", e);
            }
            Err(_) => {
                // Timeout - just continue the loop to check running flag
            }
        }
    }
    
    info!("Discovery service stopped");
    Ok(())
}