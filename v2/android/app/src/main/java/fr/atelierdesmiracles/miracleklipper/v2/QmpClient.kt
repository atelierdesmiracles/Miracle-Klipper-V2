package fr.atelierdesmiracles.miracleklipper.v2

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.IOException

/**
 * Minimal synchronous QMP client for an app-private Unix socket.
 * Call from a worker thread; never from the Android main thread.
 */
class QmpClient(private val socketPath: String) : AutoCloseable {
    private var socket: LocalSocket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    @Synchronized
    @Throws(IOException::class)
    fun connect(timeoutMs: Int = 3000) {
        require(timeoutMs in 100..30000) { "Délai QMP invalide." }
        if (socket?.isConnected == true) return
        val candidate = LocalSocket()
        try {
            candidate.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            candidate.soTimeout = timeoutMs
            socket = candidate
            reader = BufferedReader(InputStreamReader(candidate.inputStream, Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(candidate.outputStream, Charsets.UTF_8))
            val greeting = readObject()
            if (!greeting.has("QMP")) throw IOException("La bannière QMP est absente.")
            execute("qmp_capabilities")
        } catch (error: Exception) {
            try { candidate.close() } catch (_: IOException) { }
            socket = null
            reader = null
            writer = null
            if (error is IOException) throw error
            throw IOException(error.message ?: "Connexion QMP impossible.", error)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun execute(command: String, arguments: JSONObject? = null): JSONObject {
        require(command.matches(Regex("[a-z][a-z0-9_-]*"))) { "Commande QMP invalide." }
        val out = JSONObject().put("execute", command)
        if (arguments != null) out.put("arguments", arguments)
        val output = writer ?: throw IOException("QMP n'est pas connecté.")
        output.write(out.toString())
        output.newLine()
        output.flush()

        while (true) {
            val response = readObject()
            if (response.has("event")) continue
            if (response.has("error")) throw IOException("Erreur QMP: ${response.optJSONObject("error")?.optString("desc") ?: "inconnue"}")
            if (response.has("return")) return response.optJSONObject("return") ?: JSONObject()
        }
    }

    @Synchronized
    @Throws(IOException::class)
    private fun readObject(): JSONObject {
        val line = reader?.readLine() ?: throw IOException("Connexion QMP fermée.")
        return try { JSONObject(line) } catch (error: Exception) {
            throw IOException("Réponse JSON QMP invalide.", error)
        }
    }

    @Synchronized
    override fun close() {
        try { socket?.close() } catch (_: IOException) { }
        socket = null
        reader = null
        writer = null
    }
}
