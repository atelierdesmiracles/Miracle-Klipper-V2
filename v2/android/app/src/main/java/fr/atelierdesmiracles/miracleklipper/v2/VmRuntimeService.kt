package fr.atelierdesmiracles.miracleklipper.v2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the QEMU process for the lifetime of the Android foreground service.
 * The VM is never started implicitly: the user must press Start.
 */
class VmRuntimeService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val controller = QemuProcessController()
    private val stopping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopping.set(true)
                worker.execute {
                    controller.stop()
                    running = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            ACTION_START -> {
                if (controller.isRunning()) return START_NOT_STICKY
                stopping.set(false)
                startForeground(NOTIFICATION_ID, notification("Préparation du runtime Miracle Klipper…"))
                worker.execute { startVm(startId) }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startVm(startId: Int) {
        try {
            val root = RuntimeBundleInstaller.runtimeDirectory(this)
            val manifest = RuntimeBundleManifest.parse(File(root, "manifest.json"))
            val disk = File(filesDir, "miracle-vm/guest.raw")
            when (val provision = QemuDiskManager.ensureRawDisk(disk, manifest.diskSizeMb)) {
                is DiskProvisionResult.Invalid -> error(provision.reason)
                is DiskProvisionResult.Failed -> error(provision.reason)
                is DiskProvisionResult.Ready -> Unit
            }
            val qemu = File(root, manifest.qemuBinary)
            val kernel = File(root, manifest.kernel)
            val initrd = File(root, manifest.initrd)
            val config = QemuGuestConfig(
                qemuBinary = qemu,
                kernelImage = kernel,
                initrdImage = initrd,
                diskImage = disk,
                qmpSocket = File(filesDir, "miracle-vm/run/qmp.sock"),
                serialLog = File(filesDir, "miracle-vm/logs/serial.log"),
                processLog = File(filesDir, "miracle-vm/logs/qemu.log"),
                expectedQemuSha256 = manifest.sha256[manifest.qemuBinary],
                memoryMb = manifest.memoryMb,
                cpuCount = manifest.cpuCount,
                append = manifest.kernelAppend,
                libraryDirectory = File(root, "lib")
            )
            when (val result = controller.start(config, File(root, "work"))) {
                is QemuStartResult.Started -> {
                    running = true
                    updateNotification("VM démarrée — surveillance du système invité.")
                    Thread {
                        try {
                            result.process.waitFor()
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } finally {
                            running = false
                            if (!stopping.get()) {
                                updateNotification("La VM s’est arrêtée. Consultez les journaux.")
                                stopSelf(startId)
                            }
                        }
                    }.apply { name = "MiracleKlipper-QemuMonitor"; isDaemon = true; start() }
                }
                QemuStartResult.AlreadyRunning -> updateNotification("VM déjà en cours d’exécution.")
                is QemuStartResult.MissingFiles -> error("Fichiers manquants : " + result.items.joinToString())
                is QemuStartResult.Invalid -> error(result.reason)
                is QemuStartResult.Failed -> error(result.reason)
            }
        } catch (error: Exception) {
            running = false
            updateNotification("Démarrage impossible : " + (error.message ?: "erreur inconnue"))
            stopSelf(startId)
        }
    }

    override fun onDestroy() {
        stopping.set(true)
        controller.stop()
        running = false
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Miracle Klipper VM", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setContentTitle("Miracle Klipper V2")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    companion object {
        const val ACTION_START = "fr.atelierdesmiracles.miracleklipper.v2.START_VM"
        const val ACTION_STOP = "fr.atelierdesmiracles.miracleklipper.v2.STOP_VM"
        private const val CHANNEL_ID = "miracle-klipper-vm"
        private const val NOTIFICATION_ID = 2042
        @Volatile var running: Boolean = false
            private set
    }
}
