package fr.atelierdesmiracles.miracleklipper.v2

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

data class UsbSerialDeviceInfo(
    val deviceName: String,
    val label: String
)

/**
 * Bridges Android USB Host serial ports to QEMU's private virtio-serial chardev.
 * QEMU connects to 127.0.0.1:8023; no USB device is exposed to the LAN.
 */
object UsbSerialBridge {
    @Volatile var localPort: Int = 0
        private set
    private const val ACTION_PERMISSION_SUFFIX = ".MIRACLE_USB_SERIAL_PERMISSION"

    @Volatile private var appContext: Context? = null
    @Volatile private var usbManager: UsbManager? = null
    @Volatile private var listener: ((String) -> Unit)? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var serialPort: UsbSerialPort? = null
    @Volatile private var usbConnection: android.hardware.usb.UsbDeviceConnection? = null
    @Volatile private var pendingDriver: UsbSerialDriver? = null
    @Volatile private var pendingBaudRate: Int = 250000
    @Volatile private var currentDeviceName: String? = null
    @Volatile private var receiverRegistered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                permissionAction() -> {
                    val driver = pendingDriver
                    pendingDriver = null
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && driver != null) {
                        openDriver(driver, pendingBaudRate)
                    } else {
                        report("Permission USB refusée.")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device?.deviceName == currentDeviceName) disconnect()
                }
            }
        }
    }

    @Synchronized
    fun initialize(context: Context, onStatus: (String) -> Unit) {
        listener = onStatus
        val app = context.applicationContext
        appContext = app
        usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(permissionAction())
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                app.registerReceiver(permissionReceiver, filter)
            }
            receiverRegistered = true
        }
        startServer()
    }

    fun clearStatusListener() { listener = null }

    fun scan(): List<UsbSerialDeviceInfo> {
        val manager = usbManager ?: return emptyList()
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager).map { driver ->
            val device = driver.device
            val product = device.productName?.takeIf { it.isNotBlank() } ?: "Adaptateur série USB"
            UsbSerialDeviceInfo(
                deviceName = device.deviceName,
                label = product + " — VID:%04X PID:%04X".format(device.vendorId, device.productId)
            )
        }
    }

    fun connect(deviceName: String, baudRate: Int = 250000) {
        require(baudRate in 1200..2000000) { "Débit série invalide." }
        val manager = usbManager ?: error("Gestionnaire USB non initialisé.")
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            .firstOrNull { it.device.deviceName == deviceName }
            ?: error("Périphérique USB absent ou pilote série non reconnu.")
        pendingBaudRate = baudRate
        if (manager.hasPermission(driver.device)) {
            openDriver(driver, baudRate)
            return
        }
        pendingDriver = driver
        val context = appContext ?: error("Contexte Android indisponible.")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            8023,
            Intent(permissionAction()).setPackage(context.packageName),
            flags
        )
        manager.requestPermission(driver.device, permissionIntent)
        report("Autorisation Android demandée pour " + driver.device.deviceName + ".")
    }

    @Synchronized
    private fun openDriver(driver: UsbSerialDriver, baudRate: Int) {
        disconnect()
        try {
            val manager = usbManager ?: error("Gestionnaire USB indisponible.")
            val connection = manager.openDevice(driver.device)
                ?: error("Android n'a pas pu ouvrir le périphérique USB.")
            val port = driver.ports.firstOrNull() ?: error("Aucun port série fourni par ce pilote.")
            try {
                port.open(connection)
                port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            } catch (error: Exception) {
                try { port.close() } catch (_: Exception) { }
                connection.close()
                throw error
            }
            usbConnection = connection
            serialPort = port
            currentDeviceName = driver.device.deviceName
            report("USB connecté : " + driver.device.deviceName + " à " + baudRate + " bauds. En attente de QEMU.")
        } catch (error: Exception) {
            report("Ouverture USB impossible : " + (error.message ?: "erreur inconnue"))
        }
    }

    @Synchronized
    fun disconnect() {
        activeSocket?.let { try { it.close() } catch (_: Exception) { } }
        activeSocket = null
        serialPort?.let { try { it.close() } catch (_: Exception) { } }
        serialPort = null
        usbConnection?.close()
        usbConnection = null
        currentDeviceName = null
        report("Périphérique USB série déconnecté.")
    }

    private fun startServer() {
        if (server?.isClosed == false) return
        try {
            val socketServer = ServerSocket()
            socketServer.reuseAddress = true
            socketServer.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            server = socketServer
            localPort = socketServer.localPort
            thread(name = "MiracleKlipper-USBSerialServer", isDaemon = true) {
                while (!socketServer.isClosed) {
                    try {
                        val client = socketServer.accept()
                        client.tcpNoDelay = true
                        client.soTimeout = 500
                        activeSocket?.close()
                        activeSocket = client
                        report("Canal série virtio connecté à QEMU.")
                        bridgeClient(client)
                    } catch (error: IOException) {
                        if (!socketServer.isClosed) report("Serveur série interrompu : " + error.message)
                    }
                }
            }
            report("Pont USB série prêt sur loopback 127.0.0.1:" + localPort + ".")
        } catch (error: Exception) {
            report("Impossible d'ouvrir le pont USB série : " + (error.message ?: "erreur inconnue"))
        }
    }

    private fun bridgeClient(socket: Socket) {
        val output = socket.getOutputStream()
        val usbReader = thread(name = "MiracleKlipper-USBRead", isDaemon = true) {
            val buffer = ByteArray(4096)
            while (!socket.isClosed) {
                val port = serialPort
                if (port == null) {
                    try { Thread.sleep(100) } catch (_: InterruptedException) { return@thread }
                    continue
                }
                try {
                    val count = port.read(buffer, 100)
                    if (count > 0) synchronized(output) {
                        output.write(buffer, 0, count)
                        output.flush()
                    }
                } catch (error: Exception) {
                    if (serialPort === port) report("Lecture USB interrompue : " + error.message)
                    break
                }
            }
        }

        val input = socket.getInputStream()
        val buffer = ByteArray(4096)
        try {
            while (!socket.isClosed) {
                val count = try {
                    input.read(buffer)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (count < 0) break
                while (serialPort == null && !socket.isClosed) {
                    try { Thread.sleep(50) } catch (_: InterruptedException) { return }
                }
                val port = serialPort ?: break
                port.write(buffer.copyOf(count), 1000)
            }
        } catch (error: Exception) {
            report("Canal série fermé : " + (error.message ?: "connexion interrompue"))
        } finally {
            try { socket.close() } catch (_: Exception) { }
            if (activeSocket === socket) activeSocket = null
            usbReader.interrupt()
            report("Canal série QEMU déconnecté.")
        }
    }

    private fun permissionAction(): String =
        (appContext?.packageName ?: "fr.atelierdesmiracles.miracleklipper.v2") + ACTION_PERMISSION_SUFFIX

    private fun report(message: String) {
        listener?.invoke(message)
    }
}
