package com.example.camera_steam_obs

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface

class CameraStreamer(
    private val context: Context,
    private val previewSurface: SurfaceTexture,
    private val selectedCameraId: String? = null
) {
    companion object {
        private const val TAG = "CameraStreamer"
        private const val VIDEO_FPS = 30
        private const val VIDEO_BITRATE = 4_000_000
        private const val I_FRAME_INTERVAL = 1

        data class CameraInfo(
            val id: String,
            val label: String,
            val facing: Int,
            val maxResolution: Size?
        ) {
            override fun toString() = label
        }

        fun listCameras(context: Context): List<CameraInfo> {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            return manager.cameraIdList.map { id ->
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: -1
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val maxSize = map?.getOutputSizes(SurfaceTexture::class.java)
                    ?.maxByOrNull { it.width * it.height }

                val facingStr = when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "📷 Arrière"
                    CameraCharacteristics.LENS_FACING_FRONT -> "🤳 Avant"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "🔌 Externe"
                    else -> "?"
                }
                val resStr = maxSize?.let { "${it.width}x${it.height}" } ?: "?"
                CameraInfo(id, "$facingStr ($resStr)", facing, maxSize)
            }
        }
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaCodec: MediaCodec? = null
    private var codecInputSurface: Surface? = null
    private var videoWidth = 1920
    private var videoHeight = 1080
    private var sensorOrientation = 0

    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val codecThread = HandlerThread("CodecThread").apply { start() }
    private val codecHandler = Handler(codecThread.looper)

    var onEncodedFrame: ((ByteArray) -> Unit)? = null
    var onResolutionDetected: ((Int, Int, Int) -> Unit)? = null

    fun start() {
        detectResolution()
        setupEncoder()
        openCamera()
    }

    private fun detectResolution() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = selectedCameraId ?: manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return

        val chars = manager.getCameraCharacteristics(cameraId)
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java) ?: return

        // Préférer 1080p, sinon la plus grande ≤1920
        val best = sizes.find { it.width == 1920 && it.height == 1080 }
            ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: sizes.maxByOrNull { it.width * it.height }!!

        videoWidth = best.width
        videoHeight = best.height
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        
        Log.i(TAG, "Résolution: ${videoWidth}x$videoHeight, orientation: $sensorOrientation°")
        onResolutionDetected?.invoke(videoWidth, videoHeight, sensorOrientation)
    }

    fun stop() {
        runCatching { captureSession?.close() }
        cameraDevice?.close()
        cameraDevice = null
        runCatching { mediaCodec?.stop(); mediaCodec?.release() }
        mediaCodec = null
        codecInputSurface?.release()
        codecInputSurface = null
    }

    fun release() {
        stop()
        cameraThread.quitSafely()
        codecThread.quitSafely()
    }

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (info.size > 0) {
                        codec.getOutputBuffer(index)?.let { buf ->
                            val data = ByteArray(info.size)
                            buf.get(data)
                            onEncodedFrame?.invoke(data)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "Erreur codec: ${e.message}")
                }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
            }, codecHandler)
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codecInputSurface = createInputSurface()
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = selectedCameraId ?: manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession()
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDevice = null }
        }, cameraHandler)
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val encoderSurface = codecInputSurface ?: return

        previewSurface.setDefaultBufferSize(videoWidth, videoHeight)
        val previewOutput = Surface(previewSurface)

        camera.createCaptureSession(listOf(previewOutput, encoderSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(previewOutput)
                    addTarget(encoderSurface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }.build()
                session.setRepeatingRequest(request, null, cameraHandler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Session config failed")
            }
        }, cameraHandler)
    }
}