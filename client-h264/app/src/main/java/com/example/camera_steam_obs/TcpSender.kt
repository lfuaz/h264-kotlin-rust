package com.example.camera_steam_obs

import android.util.Log
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TcpSender — Envoi de frames H.264 via TCP au serveur PC.
 *
 * Protocole simple:
 *   - Chaque frame est précédée de sa taille (4 bytes, big-endian)
 *   - Puis les données brutes de la frame H.264
 *
 * Thread-safe: les frames sont ajoutées à une queue et envoyées par un thread dédié.
 */
class TcpSender(
    private val serverIp: String,
    private val serverPort: Int
) {
    companion object {
        private const val TAG = "TcpSender"
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_QUEUE_SIZE = 30  // Limite pour éviter memory overflow
        val CTRL_MAGIC = byteArrayOf(0x43, 0x54, 0x52, 0x4C) // "CTRL"
    }

    private val frameQueue: BlockingQueue<ByteArray> = LinkedBlockingQueue(MAX_QUEUE_SIZE)
    private val isRunning = AtomicBoolean(false)
    private var senderThread: Thread? = null

    // Callback pour notifier le status de connexion
    var onStatusChanged: ((String) -> Unit)? = null

    /**
     * Démarre le thread d'envoi TCP.
     */
    fun start() {
        if (isRunning.get()) return

        isRunning.set(true)
        senderThread = Thread {
            sendLoop()
        }.apply {
            name = "TcpSenderThread"
            start()
        }
        Log.i(TAG, "TcpSender démarré pour $serverIp:$serverPort")
    }

    /**
     * Arrête proprement le sender.
     */
    fun stop() {
        isRunning.set(false)
        senderThread?.interrupt()
        senderThread = null
        frameQueue.clear()
        Log.i(TAG, "TcpSender arrêté")
    }

    /**
     * Ajoute une frame H.264 à la queue d'envoi.
     * Si la queue est pleine, la frame la plus ancienne est supprimée.
     */
    fun sendFrame(frameData: ByteArray) {
        if (!isRunning.get()) return

        // Si la queue est pleine, on drop la frame la plus ancienne
        if (!frameQueue.offer(frameData)) {
            frameQueue.poll()  // Supprimer la plus ancienne
            frameQueue.offer(frameData)
            Log.w(TAG, "Queue pleine, frame droppée")
        }
    }

    /**
     * Envoie un message de contrôle au viewer.
     * Format: [4 bytes length][CTRL magic][1 byte type][payload]
     *
     * Types de messages:
     *   0x01 = Rotation (payload: 2 bytes big-endian, angle en degrés)
     */
    fun sendControlMessage(type: Byte, payload: ByteArray) {
        if (!isRunning.get()) return

        val data = ByteArray(4 + 1 + payload.size)
        System.arraycopy(CTRL_MAGIC, 0, data, 0, 4)
        data[4] = type
        System.arraycopy(payload, 0, data, 5, payload.size)

        // Priority: put at head of queue (don't drop control messages)
        sendFrame(data)
        Log.i(TAG, "Control message sent: type=$type, payload=${payload.size} bytes")
    }

    /**
     * Boucle principale d'envoi avec reconnexion automatique.
     */
    private fun sendLoop() {
        while (isRunning.get()) {
            var socket: Socket? = null
            var outputStream: DataOutputStream? = null

            try {
                // Tentative de connexion
                notifyStatus("Connexion à $serverIp:$serverPort...")
                socket = Socket(serverIp, serverPort)
                socket.tcpNoDelay = true  // Désactiver Nagle pour réduire la latence
                outputStream = DataOutputStream(socket.getOutputStream())

                notifyStatus("Connecté! Streaming en cours...")
                Log.i(TAG, "Connecté au serveur")

                // Boucle d'envoi des frames
                while (isRunning.get() && !socket.isClosed) {
                    val frame = frameQueue.take()  // Bloquant

                    // Écrire la taille (4 bytes) puis les données
                    outputStream.writeInt(frame.size)
                    outputStream.write(frame)
                    outputStream.flush()
                }

            } catch (e: InterruptedException) {
                Log.d(TAG, "Thread interrompu")
            } catch (e: IOException) {
                Log.e(TAG, "Erreur réseau: ${e.message}")
                notifyStatus("Erreur: ${e.message}")
            } finally {
                // Nettoyage
                try {
                    outputStream?.close()
                    socket?.close()
                } catch (e: IOException) {
                    Log.w(TAG, "Erreur fermeture socket: ${e.message}")
                }
            }

            // Attendre avant de reconnecter
            if (isRunning.get()) {
                notifyStatus("Reconnexion dans ${RECONNECT_DELAY_MS / 1000}s...")
                try {
                    Thread.sleep(RECONNECT_DELAY_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }

        notifyStatus("Déconnecté")
    }

    private fun notifyStatus(status: String) {
        onStatusChanged?.invoke(status)
    }
}
