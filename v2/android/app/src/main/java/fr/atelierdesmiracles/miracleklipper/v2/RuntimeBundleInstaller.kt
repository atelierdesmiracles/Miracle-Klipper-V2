package fr.atelierdesmiracles.miracleklipper.v2

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class RuntimeBundleManifest(
    val qemuBinary: String,
    val kernel: String,
    val initrd: String,
    val diskImage: String,
    val diskFormat: String,
    val diskSizeMb: Int,
    val memoryMb: Int,
    val cpuCount: Int,
    val kernelAppend: String,
    val sha256: Map<String, String>
) {
    companion object {
        fun parse(file: File): RuntimeBundleManifest {
            val json = JSONObject(file.readText())
            require(json.optInt("schemaVersion") == 1) { "Version de manifeste runtime non prise en charge." }
            require(json.optString("architecture") == "aarch64") { "Le runtime doit être compilé pour ARM64." }
            val hashesJson = json.getJSONObject("files")
            val hashes = buildMap {
                val keys = hashesJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(safeRelativePath(key), hashesJson.getString(key))
                }
            }
            require(hashes.isNotEmpty()) { "Le manifeste ne contient aucune empreinte de fichier." }
            hashes.forEach { (path, hash) ->
                require(hash.matches(Regex("[a-fA-F0-9]{64}"))) { "Empreinte SHA-256 invalide pour $path." }
            }
            val manifest = RuntimeBundleManifest(
                qemuBinary = safeRelativePath(json.getString("qemuBinary")),
                kernel = safeRelativePath(json.getString("kernel")),
                initrd = safeRelativePath(json.getString("initrd")),
                diskImage = safeRelativePath(json.getString("diskImage")),
                diskFormat = json.optString("diskFormat", "raw"),
                diskSizeMb = json.optInt("diskSizeMb", 8192),
                memoryMb = json.optInt("memoryMb", 1024),
                cpuCount = json.optInt("cpuCount", 2),
                kernelAppend = json.optString("kernelAppend", "console=ttyAMA0,115200 earlycon=pl011,0x09000000 rdinit=/init"),
                sha256 = hashes
            )
            require(manifest.qemuBinary in hashes && manifest.kernel in hashes && manifest.initrd in hashes && manifest.diskImage in hashes) {
                "Le manifeste doit authentifier QEMU, le noyau, l'initramfs et le disque invité."
            }
            require(manifest.diskFormat in setOf("raw", "qcow2")) { "Format de disque invité non pris en charge." }
            require(manifest.diskSizeMb in 512..131072) { "Taille du disque runtime invalide." }
            require(manifest.memoryMb in 256..16384) { "Mémoire runtime invalide." }
            require(manifest.cpuCount in 1..8) { "Nombre de processeurs runtime invalide." }
            require(manifest.kernelAppend.isNotBlank() && !manifest.kernelAppend.contains('\n') && !manifest.kernelAppend.contains('\r')) {
                "Arguments du noyau invalides."
            }
            return manifest
        }

        private fun safeRelativePath(value: String): String {
            require(value.isNotBlank() && !value.startsWith("/") && !value.contains('\\')) { "Chemin runtime invalide." }
            val parts = value.split("/")
            require(parts.none { it.isBlank() || it == "." || it == ".." }) { "Chemin runtime dangereux." }
            return value
        }
    }
}

sealed class RuntimeInstallResult {
    data class Installed(val manifest: RuntimeBundleManifest) : RuntimeInstallResult()
    data class Failed(val reason: String) : RuntimeInstallResult()
}

/**
 * Imports a self-contained runtime ZIP selected by the user.
 * Every executable and guest image must be listed in manifest.json with SHA-256.
 * Extraction is staged and atomically swapped into app-private storage.
 */
object RuntimeBundleInstaller {
    private const val MAX_ENTRIES = 4096
    private const val MAX_UNPACKED_BYTES = 3L * 1024L * 1024L * 1024L

    fun runtimeDirectory(context: Context) = File(context.filesDir, "miracle-runtime")

    fun isInstalled(context: Context): Boolean {
        val root = runtimeDirectory(context)
        return try {
            val manifest = RuntimeBundleManifest.parse(File(root, "manifest.json"))
            manifest.sha256.all { (path, expected) ->
                val file = File(root, path)
                file.isFile && sha256(file).equals(expected, ignoreCase = true)
            } && File(root, manifest.qemuBinary).canExecute()
        } catch (_: Exception) {
            false
        }
    }

    fun install(context: Context, source: Uri): RuntimeInstallResult {
        val staging = File(context.filesDir, "miracle-runtime-staging")
        val target = runtimeDirectory(context)
        val backup = File(context.filesDir, "miracle-runtime-backup")
        try {
            staging.deleteRecursively()
            if (!staging.mkdirs()) return RuntimeInstallResult.Failed("Impossible de créer le répertoire temporaire.")
            var entries = 0
            var totalBytes = 0L
            context.contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "Impossible d'ouvrir l'archive sélectionnée." }
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        require(entries <= MAX_ENTRIES) { "L'archive contient trop de fichiers." }
                        val relative = validateArchivePath(entry.name)
                        val output = File(staging, relative)
                        require(output.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            "L'archive tente de sortir du répertoire d'installation."
                        }
                        if (entry.isDirectory) {
                            require(output.mkdirs() || output.isDirectory) { "Impossible de créer $relative." }
                        } else {
                            output.parentFile?.let { require(it.mkdirs() || it.isDirectory) }
                            FileOutputStream(output).use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    totalBytes += count
                                    require(totalBytes <= MAX_UNPACKED_BYTES) { "L'archive runtime dépasse la limite de taille." }
                                    out.write(buffer, 0, count)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            val manifestFile = File(staging, "manifest.json")
            require(manifestFile.isFile) { "manifest.json absent de l'archive runtime." }
            val manifest = RuntimeBundleManifest.parse(manifestFile)
            manifest.sha256.forEach { (path, expected) ->
                val file = File(staging, path)
                require(file.isFile) { "Fichier runtime absent : $path" }
                require(sha256(file).equals(expected, ignoreCase = true)) { "Empreinte SHA-256 incorrecte : $path" }
            }
            val qemu = File(staging, manifest.qemuBinary)
            require(qemu.setExecutable(true, true) || qemu.canExecute()) { "Impossible de rendre QEMU exécutable." }
            require(File(staging, manifest.kernel).length() > 0L && File(staging, manifest.initrd).length() > 0L && File(staging, manifest.diskImage).length() > 0L) {
                "Le noyau ou l'initramfs est vide."
            }
            backup.deleteRecursively()
            if (target.exists()) require(target.renameTo(backup)) { "Impossible de sauvegarder le runtime précédent." }
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("Impossible d'activer le nouveau runtime.")
            }
            backup.deleteRecursively()
            return RuntimeInstallResult.Installed(manifest)
        } catch (error: Exception) {
            staging.deleteRecursively()
            return RuntimeInstallResult.Failed(error.message ?: "Échec de l'installation du runtime.")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateArchivePath(value: String): String {
        require(value.isNotBlank() && !value.startsWith("/") && !value.contains('\\')) { "Entrée ZIP invalide." }
        val normalized = value.removeSuffix("/")
        require(normalized.isNotBlank() && normalized.split("/").none { it.isBlank() || it == "." || it == ".." }) {
            "Entrée ZIP dangereuse."
        }
        return normalized
    }
}
