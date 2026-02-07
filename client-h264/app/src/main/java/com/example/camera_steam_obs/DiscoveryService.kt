package com.example.camera_steam_obs

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DiscoveryService — Découverte automatique du serveur sur le réseau local.
 *
 * Protocole UDP:
 *   1. Envoie "CAMSTREAM_DISCOVER" en broadcast sur le port 8555
 *   2. Le serveur répond "CAMSTREAM_SERVER:<tcp_port>"
 *   3. L'IP du serveur est extraite de l'adresse source du paquet
 */
class DiscoveryService {
    companion object {
        private const val TAG = "DiscoveryService"
        private const val DISCOVERY_PORT = 8555
        private const val DISCOVERY_MESSAGE = "CAMSTREAM_DISCOVER"
        private const val RESPONSE_PREFIX = "CAMSTREAM_SERVER:"
        private const val TIMEOUT_MS = 3000
        private const val MAX_RETRIES = 3
    }

    /**
     * Résultat de la découverte.
     */
    data class ServerInfo(
        val ip: String,
        val port: Int
    )

    private val isSearching = AtomicBoolean(false)

    /**
     * Recherche un serveur sur le réseau local.
     * 
     * @param onFound Callback appelé quand un serveur est trouvé
     * @param onNotFound Callback appelé si aucun serveur n'est trouvé après les retries
     * @param onError Callback appelé en cas d'erreur
     */
    fun discover(
        onFound: (ServerInfo) -> Unit,
        onNotFound: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (isSearching.getAndSet(true)) {
            Log.w(TAG, "Recherche déjà en cours")
            return
        }

        Thread {
            try {
                discoverSync()?.let { server ->
                    isSearching.set(false)
                    onFound(server)
                } ?: run {
                    isSearching.set(false)
                    onNotFound()
                }
            } catch (e: Exception) {
                isSearching.set(false)
                Log.e(TAG, "Erreur discovery: ${e.message}")
                onError(e.message ?: "Erreur inconnue")
            }
        }.apply {
            name = "DiscoveryThread"
            start()
        }
    }

    /**
     * Recherche synchrone (blocking).
     */
    private fun discoverSync(): ServerInfo? {
        var socket: DatagramSocket? = null

        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = TIMEOUT_MS
            }

            val message = DISCOVERY_MESSAGE.toByteArray(Charsets.UTF_8)
            val broadcastAddress = InetAddress.getByName("255.255.255.255")

            for (attempt in 1..MAX_RETRIES) {
                Log.d(TAG, "Tentative $attempt/$MAX_RETRIES...")

                // Envoyer le broadcast
                val sendPacket = DatagramPacket(
                    message,
                    message.size,
                    broadcastAddress,
                    DISCOVERY_PORT
                )
                socket.send(sendPacket)

                // Attendre une réponse
                val responseBuffer = ByteArray(256)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)

                try {
                    socket.receive(responsePacket)

                    val response = String(
                        responsePacket.data,
                        0,
                        responsePacket.length,
                        Charsets.UTF_8
                    ).trim()

                    Log.d(TAG, "Réponse reçue: $response de ${responsePacket.address}")

                    if (response.startsWith(RESPONSE_PREFIX)) {
                        val portStr = response.removePrefix(RESPONSE_PREFIX)
                        val port = portStr.toIntOrNull() ?: 8554
                        val serverIp = responsePacket.address.hostAddress ?: continue

                        Log.i(TAG, "Serveur trouvé: $serverIp:$port")
                        return ServerInfo(serverIp, port)
                    }
                } catch (e: SocketTimeoutException) {
                    Log.d(TAG, "Timeout, nouvelle tentative...")
                }
            }

            Log.w(TAG, "Aucun serveur trouvé après $MAX_RETRIES tentatives")
            return null

        } finally {
            socket?.close()
        }
    }

    /**
     * Annule la recherche en cours.
     */
    fun cancel() {
        isSearching.set(false)
    }

    fun isSearching(): Boolean = isSearching.get()
}
