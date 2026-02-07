//! H.264 decoder wrapper using OpenH264 (Cisco's open-source codec).
//!
//! Accepts Annex-B formatted NAL units, decodes them, converts YUV 4:2:0 → RGBA.

use crate::RgbFrame;
use anyhow::{Context, Result};
use log::{debug, info};
use openh264::decoder::Decoder;
use openh264::formats::YUVSource;

pub struct H264Decoder {
    decoder: Decoder,
    frame_count: u64,
}

impl H264Decoder {
    pub fn new() -> Result<Self> {
        let decoder = Decoder::new().context("Failed to initialize OpenH264 decoder")?;
        info!("OpenH264 decoder initialized");
        Ok(Self {
            decoder,
            frame_count: 0,
        })
    }

    /// Decode one Annex-B packet. Returns an RGBA frame if a picture was produced.
    pub fn decode(&mut self, annexb_packet: &[u8]) -> Result<Option<RgbFrame>> {
        let maybe_yuv = self
            .decoder
            .decode(annexb_packet)
            .context("H.264 decode error")?;

        let yuv = match maybe_yuv {
            Some(yuv) => yuv,
            None => return Ok(None),
        };

        // openh264 0.6 API: dimensions() returns (width, height)
        let (w, h) = yuv.dimensions();
        let width = w as u32;
        let height = h as u32;

        if width == 0 || height == 0 {
            return Ok(None);
        }

        self.frame_count += 1;
        if self.frame_count % 120 == 1 {
            info!("Decoded frame #{}: {}×{}", self.frame_count, width, height);
        }

        // openh264 0.6 API: strides() returns (y_stride, u_stride, v_stride)
        let (y_stride, u_stride, v_stride) = yuv.strides();

        // Get plane data
        let y_data = yuv.y();
        let u_data = yuv.u();
        let v_data = yuv.v();

        // Convert YUV 4:2:0 → RGBA
        let rgba = yuv420_to_rgba(
            y_data, u_data, v_data,
            y_stride, u_stride, v_stride,
            width as usize, height as usize,
        );

        debug!("Frame #{} decoded: {}×{}", self.frame_count, width, height);

        Ok(Some(RgbFrame {
            width,
            height,
            data: rgba,
        }))
    }
}

/// Convert YUV 4:2:0 planar to RGBA using BT.601 coefficients.
fn yuv420_to_rgba(
    y_data: &[u8],
    u_data: &[u8],
    v_data: &[u8],
    y_stride: usize,
    u_stride: usize,
    v_stride: usize,
    w: usize,
    h: usize,
) -> Vec<u8> {
    let mut rgba = vec![255u8; w * h * 4];

    for row in 0..h {
        for col in 0..w {
            let y_idx = row * y_stride + col;
            let uv_row = row / 2;
            let uv_col = col / 2;
            let u_idx = uv_row * u_stride + uv_col;
            let v_idx = uv_row * v_stride + uv_col;

            // Bounds check to avoid panics on malformed frames
            if y_idx >= y_data.len() || u_idx >= u_data.len() || v_idx >= v_data.len() {
                continue;
            }

            let y_val = y_data[y_idx] as f32;
            let u_val = u_data[u_idx] as f32 - 128.0;
            let v_val = v_data[v_idx] as f32 - 128.0;

            // BT.601 conversion
            let r = (y_val + 1.402 * v_val).clamp(0.0, 255.0) as u8;
            let g = (y_val - 0.344136 * u_val - 0.714136 * v_val).clamp(0.0, 255.0) as u8;
            let b = (y_val + 1.772 * u_val).clamp(0.0, 255.0) as u8;

            let idx = (row * w + col) * 4;
            rgba[idx] = r;
            rgba[idx + 1] = g;
            rgba[idx + 2] = b;
            // rgba[idx + 3] = 255 already set
        }
    }

    rgba
}