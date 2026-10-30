package fr.atelierdesmiracles.miracleklipper.v2

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var report: TextView
    private lateinit var musicStatus: TextView
    private lateinit var completionMusic: PrintCompletionMusic
    private lateinit var moonrakerUrl: EditText
    private lateinit var moonrakerApiKey: EditText
    private lateinit var moonrakerStatus: TextView
    private lateinit var printMonitor: MoonrakerPrintMonitor
    private lateinit var runtimeStatus: TextView
    private lateinit var usbStatus: TextView
    private lateinit var usbDeviceSpinner: Spinner
    private lateinit var usbBaudRate: EditText
    private var usbDevices: List<UsbSerialDeviceInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        completionMusic = PrintCompletionMusic(this)
        printMonitor = MoonrakerPrintMonitor(
            onPrintCompleted = { filename ->
                runOnUiThread {
                    val played = completionMusic.playForCompletedPrint()
                    moonrakerStatus.text = if (played) "Impression terminée : $filename — musique lancée."
                    else "Impression terminée : $filename — musique automatique désactivée ou aucun morceau choisi."
                }
            },
            onConnectionState = { state -> runOnUiThread { moonrakerStatus.text = state } }
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(24))
            setBackgroundColor(0xFFF3F5F8.toInt())
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(label("MIRACLE KLIPPER", 13, 0xFF536579.toInt(), true))
        content.addView(label("Version 2 — banc de test", 27, 0xFF142338.toInt(), true))
        content.addView(label(
            "Application Android ARM64 : import du runtime vérifié, gestion de la VM QEMU et connexion Moonraker. Alpine et les services invités doivent encore être validés sur appareil.",
            15, 0xFF405168.toInt(), false
        ))
        content.addView(label("MUSIQUE DE FIN D’IMPRESSION", 12, 0xFF536579.toInt(), true))
        musicStatus = label("", 14, 0xFF17283B.toInt(), false)
        content.addView(musicStatus)
        content.addView(Button(this).apply {
            text = "Choisir un morceau"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(intent, REQUEST_AUDIO_TRACK)
            }
        })
        content.addView(Button(this).apply {
            text = if (completionMusic.enabled) "Désactiver la musique automatique" else "Activer la musique automatique"
            setOnClickListener {
                completionMusic.setEnabled(!completionMusic.enabled)
                refreshMusicStatus()
                (it as Button).text = if (completionMusic.enabled) "Désactiver la musique automatique" else "Activer la musique automatique"
            }
        })
        content.addView(Button(this).apply {
            text = "Tester le morceau sélectionné"
            setOnClickListener {
                val started = completionMusic.playSelectedTrack()
                musicStatus.text = if (started) "Lecture du morceau en cours." else "Lecture impossible : choisissez un fichier audio accessible."
            }
        })
        content.addView(label("CONNEXION USB — CARTE ÉLECTRONIQUE", 12, 0xFF536579.toInt(), true))
        usbStatus = label("Pont USB série prêt sur loopback. Scannez les périphériques OTG.", 14, 0xFF17283B.toInt(), false)
        UsbSerialBridge.initialize(applicationContext) { message -> runOnUiThread { if (::usbStatus.isInitialized) usbStatus.text = message } }
        content.addView(usbStatus)
        usbDeviceSpinner = Spinner(this)
        content.addView(usbDeviceSpinner)
        usbBaudRate = EditText(this).apply {
            hint = "Débit série (baud)"
            setText("250000")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
        }
        content.addView(usbBaudRate)
        content.addView(Button(this).apply {
            text = "Scanner les périphériques USB"
            setOnClickListener {
                usbDevices = UsbSerialBridge.scan()
                val labels = if (usbDevices.isEmpty()) listOf("Aucun périphérique série compatible") else usbDevices.map { it.label }
                usbDeviceSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, labels).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                usbStatus.text = if (usbDevices.isEmpty()) "Aucun adaptateur CDC/ACM, FTDI, CP210x ou CH340 détecté." else usbDevices.size.toString() + " périphérique(s) série détecté(s)."
            }
        })
        content.addView(Button(this).apply {
            text = "Connecter la carte sélectionnée"
            setOnClickListener {
                val selected = usbDevices.getOrNull(usbDeviceSpinner.selectedItemPosition)
                if (selected == null) {
                    usbStatus.text = "Scannez et sélectionnez d'abord un périphérique USB."
                } else {
                    try {
                        val baud = usbBaudRate.text.toString().toIntOrNull() ?: 250000
                        UsbSerialBridge.connect(selected.deviceName, baud)
                    } catch (error: Exception) {
                        usbStatus.text = error.message ?: "Connexion USB impossible."
                    }
                }
            }
        })
        content.addView(Button(this).apply {
            text = "Déconnecter la carte USB"
            setOnClickListener { UsbSerialBridge.disconnect() }
        })
        content.addView(label("MACHINE VIRTUELLE LINUX", 12, 0xFF536579.toInt(), true))
        runtimeStatus = label("", 14, 0xFF17283B.toInt(), false)
        content.addView(runtimeStatus)
        content.addView(Button(this).apply {
            text = "Importer le runtime QEMU + Alpine (ZIP)"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivityForResult(intent, REQUEST_RUNTIME_BUNDLE)
            }
        })
        content.addView(Button(this).apply {
            text = "Démarrer la machine virtuelle"
            setOnClickListener {
                if (!RuntimeBundleInstaller.isInstalled(this@MainActivity)) {
                    runtimeStatus.text = "Importez d'abord un runtime vérifié."
                } else {
                    val intent = Intent(this@MainActivity, VmRuntimeService::class.java).setAction(VmRuntimeService.ACTION_START)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                    runtimeStatus.text = "Demande de démarrage envoyée au service Android."
                }
            }
        })
        content.addView(Button(this).apply {
            text = "Arrêter la machine virtuelle"
            setOnClickListener {
                startService(Intent(this@MainActivity, VmRuntimeService::class.java).setAction(VmRuntimeService.ACTION_STOP))
                runtimeStatus.text = "Demande d'arrêt envoyée."
            }
        })
        content.addView(Button(this).apply {
            text = "Ouvrir Mainsail"
            setOnClickListener { startActivity(Intent(this@MainActivity, MainsailActivity::class.java)) }
        })
        content.addView(label("Interface Mainsail : http://127.0.0.1:8080", 13, 0xFF405168.toInt(), false))
        content.addView(label("CONNEXION MOONRAKER", 12, 0xFF536579.toInt(), true))
        moonrakerUrl = EditText(this).apply {
            hint = "http://adresse-imprimante:7125"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        content.addView(moonrakerUrl)
        moonrakerApiKey = EditText(this).apply {
            hint = "Clé API (facultative)"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        content.addView(moonrakerApiKey)
        moonrakerStatus = label("Non connecté à Moonraker", 14, 0xFF17283B.toInt(), false)
        content.addView(moonrakerStatus)
        content.addView(Button(this).apply {
            text = "Connecter à Moonraker"
            setOnClickListener {
                try {
                    printMonitor.connect(moonrakerUrl.text.toString(), moonrakerApiKey.text.toString())
                } catch (error: IllegalArgumentException) {
                    moonrakerStatus.text = error.message ?: "Adresse Moonraker invalide"
                }
            }
        })
        content.addView(Button(this).apply {
            text = "Déconnecter Moonraker"
            setOnClickListener { printMonitor.disconnect() }
        })
        content.addView(label("ÉTAT DU PROTOTYPE", 12, 0xFF536579.toInt(), true))
        report = label("", 15, 0xFF17283B.toInt(), false).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        content.addView(report)
        content.addView(Button(this).apply {
            text = "Relancer le diagnostic"
            setOnClickListener { updateReport() }
        })
        content.addView(label(
            "Le runtime doit être importé avant de démarrer la VM. Le statut de la VM ne certifie pas à lui seul que les services invités sont opérationnels.",
            13, 0xFF64748B.toInt(), false
        ))
        root.addView(ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        updateReport()
        refreshMusicStatus()
        refreshRuntimeStatus()
    }

    @Deprecated("Deprecated in Android; retained for compatibility with the current minimal Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_RUNTIME_BUNDLE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runtimeStatus.text = "Import et vérification de l'archive en cours…"
            Thread {
                val result = RuntimeBundleInstaller.install(this, uri)
                runOnUiThread {
                    runtimeStatus.text = when (result) {
                        is RuntimeInstallResult.Installed -> "Runtime installé et vérifié. QEMU : ${result.manifest.qemuBinary}. Noyau : ${result.manifest.kernel}."
                        is RuntimeInstallResult.Failed -> "Import refusé : ${result.reason}"
                    }
                }
            }.apply { name = "MiracleKlipper-RuntimeImport"; isDaemon = true; start() }
            return
        }
        if (requestCode == REQUEST_AUDIO_TRACK && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                completionMusic.setSelectedTrack(uri)
                refreshMusicStatus()
            } catch (_: SecurityException) {
                musicStatus.text = "Impossible de conserver l’accès à ce fichier audio."
            }
        }
    }

    private fun refreshRuntimeStatus() {
        runtimeStatus.text = buildString {
            append(if (RuntimeBundleInstaller.isInstalled(this@MainActivity)) "Runtime installé — SHA-256 contrôlé au démarrage" else "Runtime non installé")
            appendLine()
            append("VM : ")
            append(if (VmRuntimeService.running) "en cours d'exécution" else "arrêtée")
            appendLine()
            append("L'installation du runtime ne certifie pas encore le démarrage d'Alpine ni les services Klipper.")
        }
    }

    private fun refreshMusicStatus() {
        val track = completionMusic.selectedTrack
        musicStatus.text = buildString {
            append(if (completionMusic.enabled) "Déclenchement automatique activé" else "Déclenchement automatique désactivé")
            appendLine()
            append(if (track == null) "Aucun morceau sélectionné" else "Morceau sélectionné : " + (track.lastPathSegment ?: track.toString()))
            appendLine()
            append("La détection de fin d’impression utilise la connexion Moonraker ci-dessous.")
        }
    }

    private fun updateReport() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        val totalMb = memory.totalMem / (1024L * 1024L)
        val stat = StatFs(filesDir.absolutePath)
        val freeMb = stat.availableBytes / (1024L * 1024L)
        val usb = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        val readiness = DeviceReadiness.evaluate(DeviceProfile(
            Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT, totalMb, freeMb, usb
        ))
        report.text = buildString {
            appendLine(if (readiness.canRunV2Shell) "✓ Socle Android compatible" else "✕ Appareil non pris en charge")
            appendLine()
            appendLine("ABI : " + Build.SUPPORTED_ABIS.joinToString())
            appendLine("Android : API " + Build.VERSION.SDK_INT)
            appendLine("Mémoire totale : " + formatMb(totalMb))
            appendLine("Stockage libre privé : " + formatMb(freeMb))
            appendLine("USB Host : " + if (usb) "disponible" else "non détecté")
            appendLine()
            appendLine("RAM recommandée (4 Go) : " + if (readiness.memoryRecommended) "OK" else "insuffisante")
            appendLine("Stockage recommandé (4 Go) : " + if (readiness.storageRecommended) "OK" else "insuffisant")
            appendLine("Pré-requis VM : " + if (readiness.readyForVmPrototype) "de base OK" else "incomplets")
            appendLine()
            appendLine("Runtime QEMU/Alpine : " + if (RuntimeBundleInstaller.isInstalled(this@MainActivity)) "installé" else "non installé")
            appendLine("VM QEMU : " + if (VmRuntimeService.running) "en cours" else "arrêtée")
            appendLine("Docker / Klipper / Moonraker / Mainsail dans la VM : à valider")
        }
    }

    companion object { private const val REQUEST_AUDIO_TRACK = 4102; private const val REQUEST_RUNTIME_BUNDLE = 4103 }

    override fun onDestroy() {
        if (::printMonitor.isInitialized) printMonitor.shutdown()
        if (::completionMusic.isInitialized) completionMusic.stop()
        UsbSerialBridge.clearStatusListener()
        super.onDestroy()
    }

    private fun formatMb(value: Long) =
        String.format(Locale.getDefault(), "%.1f Mo", value.toDouble())

    private fun label(value: String, size: Int, color: Int, bold: Boolean) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START
            setPadding(0, dp(8), 0, dp(8))
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
