package fr.atelierdesmiracles.miracleklipper.v2

import java.io.File
import java.io.IOException

/**
 * Minimal process adapter. The caller must own Android foreground-service
 * lifecycle and user consent before starting a guest.
 */
class QemuProcessController {
    @Volatile private var process: Process? = null
    @Volatile private var qmpSocket: File? = null

    @Synchronized
    fun start(config: QemuGuestConfig, workingDirectory: File): QemuStartResult {
        if (process?.isAlive == true) return QemuStartResult.AlreadyRunning
        when (val preflight = QemuCommandBuilder.build(config)) {
            is QemuPreflightResult.Missing -> return QemuStartResult.MissingFiles(preflight.items)
            is QemuPreflightResult.Invalid -> return QemuStartResult.Invalid(preflight.reason)
            is QemuPreflightResult.Ready -> {
                val binaryCheck = QemuBinaryVerifier.verify(config.qemuBinary, config.expectedQemuSha256)
                if (binaryCheck is QemuBinaryVerification.Rejected) {
                    return QemuStartResult.Invalid(binaryCheck.reason)
                }
                return try {
                    if (!workingDirectory.exists() && !workingDirectory.mkdirs()) {
                        return QemuStartResult.Failed("Impossible de créer le répertoire de travail.")
                    }
                    config.qmpSocket.parentFile?.mkdirs()
                    if (config.qmpSocket.exists() && !config.qmpSocket.delete()) {
                        return QemuStartResult.Failed("Impossible de supprimer l'ancien socket QMP.")
                    }
                    config.serialLog.parentFile?.mkdirs()
                    config.processLog.parentFile?.mkdirs()
                    val builder = ProcessBuilder(preflight.command)
                        .directory(workingDirectory)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(config.processLog))
                    config.libraryDirectory?.let { libraryDir ->
                        if (libraryDir.isDirectory) {
                            val previous = builder.environment()["LD_LIBRARY_PATH"]
                            builder.environment()["LD_LIBRARY_PATH"] =
                                if (previous.isNullOrBlank()) libraryDir.absolutePath
                                else libraryDir.absolutePath + File.pathSeparator + previous
                        }
                    }
                    process = builder.start()
                    qmpSocket = config.qmpSocket
                    QemuStartResult.Started(process!!)
                } catch (error: IOException) {
                    QemuStartResult.Failed(error.message ?: "Échec du lancement de QEMU.")
                } catch (error: SecurityException) {
                    QemuStartResult.Failed(error.message ?: "Android a refusé le lancement de QEMU.")
                }
            }
        }
    }

    /**
     * Ask Alpine to power down through QMP/ACPI before terminating QEMU.
     * Returns true only when the guest exits within the requested grace period.
     * Call from a worker thread; QMP and process waits are blocking.
     */
    @Synchronized
    fun stop(gracefulTimeoutMs: Long = 30_000L): Boolean {
        require(gracefulTimeoutMs in 0L..120_000L) { "Délai d'arrêt invité invalide." }
        val running = process ?: return true
        val socketFile = qmpSocket
        if (!running.isAlive) {
            process = null
            qmpSocket = null
            socketFile?.delete()
            return true
        }

        var powerdownRequested = false
        val socketPath = socketFile?.absolutePath
        if (!socketPath.isNullOrBlank()) {
            try {
                QmpClient(socketPath).use { qmp ->
                    qmp.connect(timeoutMs = 3000)
                    qmp.execute("system_powerdown")
                    powerdownRequested = true
                }
            } catch (_: Exception) {
                // QEMU may still be booting, or the guest may not expose ACPI.
            }
        }

        val cleanShutdown = if (powerdownRequested) {
            try {
                running.waitFor(gracefulTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        } else {
            false
        }

        if (!cleanShutdown && running.isAlive) {
            running.destroy()
            val terminated = try {
                running.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!terminated) {
                try {
                    running.destroyForcibly().waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        process = null
        qmpSocket = null
        socketFile?.delete()
        return cleanShutdown
    }

    fun isRunning(): Boolean = process?.isAlive == true
}

sealed class QemuStartResult {
    data class Started(val process: Process) : QemuStartResult()
    data class MissingFiles(val items: List<String>) : QemuStartResult()
    data class Invalid(val reason: String) : QemuStartResult()
    data class Failed(val reason: String) : QemuStartResult()
    object AlreadyRunning : QemuStartResult()
}
