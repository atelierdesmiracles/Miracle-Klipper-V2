package fr.atelierdesmiracles.miracleklipper.v2

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Monitors successful print completion and reconnects automatically.
 * A new connection reads the initial subscription state, so a print that
 * completed during a brief network interruption is still detected, while
 * connecting for the first time to an already-completed job does not trigger.
 */
class MoonrakerPrintMonitor(
    private val onPrintCompleted: (filename: String) -> Unit,
    private val onConnectionState: (String) -> Unit = {}
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var armedForCompletion = false
    @Volatile private var currentFilename: String = ""
    @Volatile private var baseUrl: String? = null
    @Volatile private var apiKey: String? = null
    @Volatile private var reconnectDelayMs = 1_000L
    @Volatile private var reconnectScheduled = false

    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        connectInternal()
    }

    fun connect(baseUrl: String, apiKey: String? = null) {
        disconnect()
        this.baseUrl = baseUrl
        this.apiKey = apiKey
        armedForCompletion = false
        currentFilename = ""
        reconnectDelayMs = 1_000L
        running = true
        connectInternal()
    }

    private fun connectInternal() {
        if (!running) return
        val url = baseUrl ?: return
        val requestBuilder = try {
            Request.Builder().url(toWebSocketUrl(url))
        } catch (error: Exception) {
            onConnectionState("Adresse Moonraker invalide : ${error.message ?: "URL incorrecte"}")
            scheduleReconnect()
            return
        }
        apiKey?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("X-Api-Key", it.trim()) }
        onConnectionState("Connexion à Moonraker…")
        try {
            val newSocket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!running) {
                        webSocket.close(1000, "Monitor stopped")
                        return
                    }
                    socket = webSocket
                    reconnectDelayMs = 1_000L
                    onConnectionState("Connecté à Moonraker")
                    val subscribe = JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("method", "printer.objects.subscribe")
                        .put("params", JSONObject().put(
                            "objects",
                            JSONObject().put("print_stats", org.json.JSONArray().put("state").put("filename"))
                        ))
                        .put("id", 1)
                    webSocket.send(subscribe.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (socket === webSocket) socket = null
                    if (running) {
                        onConnectionState("Connexion Moonraker interrompue — reconnexion…")
                        scheduleReconnect()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (socket === webSocket) socket = null
                    if (running) {
                        onConnectionState("Erreur Moonraker : ${t.message ?: "connexion impossible"} — reconnexion…")
                        scheduleReconnect()
                    }
                }
            })
            socket = newSocket
        } catch (error: Exception) {
            onConnectionState("Erreur Moonraker : ${error.message ?: "connexion impossible"}")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!running || reconnectScheduled) return
        reconnectScheduled = true
        handler.postDelayed(reconnectRunnable, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
    }

    fun disconnect() {
        running = false
        reconnectScheduled = false
        handler.removeCallbacks(reconnectRunnable)
        armedForCompletion = false
        currentFilename = ""
        socket?.close(1000, "Miracle Klipper disconnect")
        socket = null
        onConnectionState("Déconnecté de Moonraker")
    }

    fun shutdown() {
        disconnect()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private fun handleMessage(raw: String) {
        val root = try { JSONObject(raw) } catch (_: Exception) { return }
        val status = when (root.optString("method")) {
            "notify_status_update" -> root.optJSONArray("params")?.optJSONObject(0)
            else -> root.optJSONObject("result")?.optJSONObject("status")
        } ?: return
        val printStats = status.optJSONObject("print_stats") ?: return
        val state = printStats.optString("state").lowercase()
        val filename = printStats.optString("filename", currentFilename)
        if (filename.isNotBlank()) currentFilename = filename

        when (state) {
            "printing" -> armedForCompletion = true
            "complete" -> {
                val completed = currentFilename
                if (armedForCompletion && completed.isNotBlank()) {
                    armedForCompletion = false
                    onPrintCompleted(completed)
                }
            }
            "error", "cancelled" -> armedForCompletion = false
        }
    }

    private fun toWebSocketUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "Moonraker URL is empty" }
        val ws = when {
            trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring(7)
            trimmed.startsWith("wss://", ignoreCase = true) -> trimmed
            trimmed.startsWith("ws://", ignoreCase = true) -> trimmed
            else -> throw IllegalArgumentException("URL must start with http://, https://, ws:// or wss://")
        }
        return if (ws.endsWith("/websocket")) ws else "$ws/websocket"
    }
}
