package com.example.camera_steam_obs

import android.content.res.Configuration
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * MainActivity — Point d'entrée de l'application CamStream.
 *
 * Orchestration:
 *   1. L'utilisateur entre l'IP du PC Windows
 *   2. Appuie sur "Démarrer"
 *   3. La caméra s'ouvre, l'encodeur H.264 démarre
 *   4. Les frames encodées sont envoyées via TCP au PC
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CAMERA_PERMISSION = 100
        private const val STREAM_PORT = 8554
    }

    private lateinit var textureView: TextureView
    private lateinit var editIpAddress: EditText
    private lateinit var buttonStream: Button
    private lateinit var buttonDiscover: Button
    private lateinit var spinnerCamera: Spinner
    private lateinit var textStatus: TextView

    private var cameraStreamer: CameraStreamer? = null
    private var tcpSender: TcpSender? = null
    private var discoveryService: DiscoveryService? = null
    private var isStreaming = false
    private var discoveredPort: Int = STREAM_PORT
    private var cameraList: List<CameraStreamer.Companion.CameraInfo> = emptyList()
    private var currentVideoWidth = 0
    private var currentVideoHeight = 0
    private var currentSensorRotation = 0
    private var sensorRotationDetected = false
    private var isFrontCamera = false

    // ─── Lifecycle ──────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        editIpAddress = findViewById(R.id.editIpAddress)
        buttonStream = findViewById(R.id.buttonStream)
        buttonDiscover = findViewById(R.id.buttonDiscover)
        spinnerCamera = findViewById(R.id.spinnerCamera)
        textStatus = findViewById(R.id.textStatus)

        // Lister les caméras disponibles
        populateCameraList()

        // Bouton de découverte automatique
        buttonDiscover.setOnClickListener {
            discoverServer()
        }

        // Lancer la découverte automatiquement au démarrage
        discoverServer()

        buttonStream.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                requestCameraAndStart()
            }
        }

        // Attendre que la TextureView soit prête
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                Log.d(TAG, "Surface prête: ${w}x${h}")
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                // Recalculer le transform quand la vue change de taille
                if (currentVideoWidth > 0 && currentVideoHeight > 0) {
                    configureTransform(currentVideoWidth, currentVideoHeight, currentSensorRotation)
                }
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                stopStreaming()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    override fun onPause() {
        super.onPause()
        stopStreaming()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Recalculer le transform quand l'orientation change
        if (currentVideoWidth > 0 && currentVideoHeight > 0) {
            textureView.post {
                configureTransform(currentVideoWidth, currentVideoHeight, currentSensorRotation)
            }
        }
        // Notifier le viewer de la nouvelle rotation
        sendRotationToViewer()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraStreamer?.release()
    }

    // ─── Permissions ────────────────────────────────────────────────────

    private fun requestCameraAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
        } else {
            startStreaming()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startStreaming()
            } else {
                Toast.makeText(this, "Permission caméra requise", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ─── Streaming ──────────────────────────────────────────────────────

    private fun startStreaming() {
        val ip = editIpAddress.text.toString().trim()
        if (ip.isEmpty()) {
            Toast.makeText(this, "Entrez l'adresse IP du PC", Toast.LENGTH_SHORT).show()
            return
        }

        val surfaceTexture = textureView.surfaceTexture
        if (surfaceTexture == null) {
            Toast.makeText(this, "Surface pas encore prête", Toast.LENGTH_SHORT).show()
            return
        }

        isStreaming = true
        buttonStream.text = "Arrêter"
        editIpAddress.isEnabled = false
        spinnerCamera.isEnabled = false

        // 1. Démarrer le sender TCP (utiliser le port découvert ou par défaut)
        val port = discoveredPort
        tcpSender = TcpSender(ip, port).apply {
            onStatusChanged = { status ->
                runOnUiThread {
                    textStatus.text = status
                }
            }
            start()
        }

        // 2. Démarrer la caméra + encodeur (avec la caméra sélectionnée)
        val selectedCamera = cameraList.getOrNull(spinnerCamera.selectedItemPosition)
        val selectedCameraId = selectedCamera?.id
        isFrontCamera = selectedCamera?.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        cameraStreamer = CameraStreamer(this, surfaceTexture, selectedCameraId).apply {
            onResolutionDetected = { width, height, sensorRotation ->
                runOnUiThread {
                    // Sauvegarder immédiatement la rotation du capteur
                    currentSensorRotation = sensorRotation
                    sensorRotationDetected = true
                    
                    // Utiliser post{} pour s'assurer que la TextureView est mesurée
                    textureView.post {
                        configureTransform(width, height, sensorRotation)
                        // Envoyer la rotation initiale au viewer APRÈS configureTransform
                        sendRotationToViewer()
                    }
                }
            }
            onEncodedFrame = { frameData ->
                // Envoyer chaque frame H.264 via TCP
                tcpSender?.sendFrame(frameData)
            }
            start()
        }

        updateStatus("Démarrage du streaming vers $ip:$port...")
        Log.i(TAG, "Streaming démarré vers $ip:$port")
    }

    private fun stopStreaming() {
        if (!isStreaming) return
        isStreaming = false

        cameraStreamer?.stop()
        cameraStreamer = null

        tcpSender?.stop()
        tcpSender = null
        
        sensorRotationDetected = false

        runOnUiThread {
            buttonStream.text = "Démarrer"
            editIpAddress.isEnabled = true
            spinnerCamera.isEnabled = true
            textStatus.text = "Arrêté"
        }

        Log.i(TAG, "Streaming arrêté")
    }

    private fun updateStatus(msg: String) {
        runOnUiThread {
            textStatus.text = msg
        }
    }

    /**
     * Corrige l'aspect ratio et la rotation de la preview caméra.
     *
     * IMPORTANT: Camera2 compense DÉJÀ l'orientation du capteur quand il
     * envoie des frames au TextureView/SurfaceTexture. On ne doit donc
     * PAS appliquer de rotation basée sur sensorOrientation.
     *
     * Le seul cas où un transform est nécessaire : quand l'ÉCRAN lui-même
     * est en paysage (ROTATION_90/270). C'est l'approche exacte de
     * l'exemple officiel Google Camera2Basic.
     */
    private fun configureTransform(videoWidth: Int, videoHeight: Int, sensorRotation: Int) {
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()

        if (viewWidth == 0f || viewHeight == 0f || videoWidth == 0 || videoHeight == 0) return

        // Sauvegarder pour recalculer lors d'un changement d'orientation
        currentVideoWidth = videoWidth
        currentVideoHeight = videoHeight
        currentSensorRotation = sensorRotation

        val matrix = Matrix()
        val displayRotation = windowManager.defaultDisplay.rotation

        val viewRect = RectF(0f, 0f, viewWidth, viewHeight)
        val bufferRect = RectF(0f, 0f, videoHeight.toFloat(), videoWidth.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        Log.i(TAG, "Transform: displayRotation=$displayRotation, video=${videoWidth}x${videoHeight}, view=${viewWidth.toInt()}x${viewHeight.toInt()}, front=$isFrontCamera")

        when (displayRotation) {
            android.view.Surface.ROTATION_90, android.view.Surface.ROTATION_270 -> {
                // Écran en paysage : il faut corriger le mapping
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                val scale = Math.max(
                    viewHeight / videoHeight.toFloat(),
                    viewWidth / videoWidth.toFloat()
                )
                matrix.postScale(scale, scale, centerX, centerY)
                // ROTATION_90 → rotate -90° (= 90*(1-2)=-90), ROTATION_270 → rotate +90° (= 90*(3-2)=90)
                matrix.postRotate(90f * (displayRotation - 2), centerX, centerY)
            }
            android.view.Surface.ROTATION_180 -> {
                matrix.postRotate(180f, centerX, centerY)
            }
            // ROTATION_0 (portrait) : Camera2 gère déjà → identité
        }

        // Miroir horizontal pour la caméra frontale
        if (isFrontCamera) {
            matrix.postScale(-1f, 1f, centerX, centerY)
        }

        textureView.setTransform(matrix)
        Log.i(TAG, "Transform applied: displayRotation=$displayRotation, mirror=$isFrontCamera")
    }

    /**
     * Envoie l'angle de rotation au viewer Rust pour qu'il affiche
     * l'image dans la bonne orientation.
     *
     * L'encodeur H.264 produit toujours des frames dans l'orientation
     * native du capteur (paysage). Le viewer doit les tourner pour
     * correspondre à l'orientation réelle du téléphone.
     *
     * IMPORTANT: La formule de rotation est différente pour les caméras
     * avant et arrière à cause du mirroring du capteur avant.
     */
    private fun sendRotationToViewer() {
        if (!sensorRotationDetected) return // Pas encore détecté

        val displayRotation = windowManager.defaultDisplay.rotation
        val displayDegrees = when (displayRotation) {
            android.view.Surface.ROTATION_0 -> 0
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }

        // Formule différente pour caméra avant vs arrière
        // - Arrière: (sensorOrientation - displayDegrees + 360) % 360
        // - Avant: le capteur est monté à l'envers (mirrored), donc la rotation
        //   doit compenser dans l'autre sens
        val viewerRotation = if (isFrontCamera) {
            (currentSensorRotation + displayDegrees) % 360
        } else {
            (currentSensorRotation - displayDegrees + 360) % 360
        }

        Log.i(TAG, "Sending viewer rotation: ${viewerRotation}° (sensor=$currentSensorRotation, display=$displayDegrees, front=$isFrontCamera)")

        val payload = byteArrayOf(
            ((viewerRotation shr 8) and 0xFF).toByte(),
            (viewerRotation and 0xFF).toByte()
        )
        tcpSender?.sendControlMessage(0x01, payload)
    }

    // ─── Discovery ───────────────────────────────────────────────────────

    private fun populateCameraList() {
        cameraList = CameraStreamer.listCameras(this)
        Log.i(TAG, "Caméras trouvées: ${cameraList.size}")
        cameraList.forEach { Log.i(TAG, "  - ${it.label} (id=${it.id})") }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            cameraList.map { it.label }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerCamera.adapter = adapter

        // Sélectionner la caméra arrière principale par défaut
        val backIndex = cameraList.indexOfFirst {
            it.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                    && it.label.contains("Principal")
        }
        if (backIndex >= 0) {
            spinnerCamera.setSelection(backIndex)
        }
    }

    private fun discoverServer() {
        if (isStreaming) return

        buttonDiscover.isEnabled = false
        updateStatus("Recherche du serveur...")

        discoveryService = DiscoveryService().apply {
            discover(
                onFound = { server ->
                    runOnUiThread {
                        editIpAddress.setText(server.ip)
                        discoveredPort = server.port
                        buttonDiscover.isEnabled = true
                        updateStatus("Serveur trouvé: ${server.ip}:${server.port}")
                        Toast.makeText(
                            this@MainActivity,
                            "Serveur trouvé!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onNotFound = {
                    runOnUiThread {
                        buttonDiscover.isEnabled = true
                        updateStatus("Aucun serveur trouvé")
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        buttonDiscover.isEnabled = true
                        updateStatus("Erreur: $error")
                    }
                }
            )
        }
    }
}