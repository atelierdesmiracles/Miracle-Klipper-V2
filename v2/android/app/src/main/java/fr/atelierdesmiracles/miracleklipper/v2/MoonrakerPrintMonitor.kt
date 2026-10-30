package fr.atelierdesmiracles.miracleklipper.v2

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal Moonraker WebSocket monitor for successful print completion.
 *
 * Connect with the Moonraker base URL (http://printer:7125 or https://...).
 * A completion is emitted only after this connection observed "printing"
 * followed by "complete"; reconnecting while a job is already complete will
 * not trigger music.
 */
class MoonrakerPrintMonitor(
    private val onPrintCompleted: (filename: String) -> Unit,
    private val onConnectionState: (String) -> Unit = {}
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var armedForCompletion = false
    @Volatile private var lastCompletedFilename: String? = null
    @Volatile private var currentFilename: String = ""

    fun connect(baseUrl: String, apiKey: String? = null) {
        disconnect()
        val endpoint = toWebSocketUrl(baseUrl)
        val requestBuilder = Request.Builder().url(endpoint)
        if (!apiKey.isNullOrBlank()) requestBuilder.header("X-Api-Key", apiKey.trim())
        onConnectionState("Connexion à Moonraker…")
        socket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
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
                onConnectionState("Connexion Moonraker fermée")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onConnectionState("Erreur Moonraker : ${t.message ?: "connexion impossible"}")
            }
        })
    }

    fun disconnect() {
        armedForCompletion = false
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
        if (root.optString("method") != "notify_status_update") return
        val params = root.optJSONArray("params") ?: return
        val status = params.optJSONObject(0) ?: return
        val printStats = status.optJSONObject("print_stats") ?: return
        val state = printStats.optString("state").lowercase()
        val filename = printStats.optString("filename", currentFilename)
        if (filename.isNotBlank()) currentFilename = filename

        when (state) {
            "printing" -> armedForCompletion = true
            "complete" -> {
                val completed = currentFilename
                if (armedForCompletion && completed.isNotBlank() && completed != lastCompletedFilename) {
                    armedForCompletion = false
                    lastCompletedFilename = completed
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
