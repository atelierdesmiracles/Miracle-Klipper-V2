package fr.atelierdesmiracles.miracleklipper.v2

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

sealed class DiskProvisionResult {
    data class Ready(val file: File, val created: Boolean) : DiskProvisionResult()
    data class Invalid(val reason: String) : DiskProvisionResult()
    data class Failed(val reason: String) : DiskProvisionResult()
}

/**
 * Guest disk provisioning. The seed is copied only on first install;
 * existing writable guest data is never silently replaced or truncated.
 */
object QemuDiskManager {
    fun ensureSeededDisk(destination: File, seedImage: File): DiskProvisionResult {
        if (!seedImage.isFile || seedImage.length() <= 0L) {
            return DiskProvisionResult.Invalid("L'image disque initiale est absente ou vide.")
        }
        return try {
            val parent = destination.parentFile
                ?: return DiskProvisionResult.Invalid("Le disque doit avoir un répertoire parent.")
            if (!parent.exists() && !parent.mkdirs()) {
                return DiskProvisionResult.Failed("Impossible de créer le répertoire du disque.")
            }
            if (destination.exists()) {
                if (!destination.isFile || destination.length() <= 0L) {
                    return DiskProvisionResult.Invalid("Le disque persistant existant est invalide.")
                }
                return DiskProvisionResult.Ready(destination, false)
            }
            val requiredBytes = seedImage.length() + 128L * 1024L * 1024L
            val availableBytes = parent.usableSpace
            if (availableBytes < requiredBytes) {
                return DiskProvisionResult.Failed("Espace libre insuffisant pour initialiser le disque invité.")
            }
            val temporary = File(parent, destination.name + ".installing")
            temporary.delete()
            FileInputStream(seedImage).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, 1024 * 1024)
                    output.fd.sync()
                }
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete()
                return DiskProvisionResult.Failed("Impossible d'activer le disque invité initial.")
            }
            DiskProvisionResult.Ready(destination, true)
        } catch (error: Exception) {
            DiskProvisionResult.Failed(error.message ?: "Impossible de préparer le disque invité.")
        }
    }

    fun ensureRawDisk(file: File, sizeMb: Int): DiskProvisionResult {
        if (sizeMb !in 512..131072) {
            return DiskProvisionResult.Invalid("La taille du disque doit être comprise entre 512 et 131072 Mo.")
        }
        return try {
            val parent = file.parentFile
                ?: return DiskProvisionResult.Invalid("Le disque doit avoir un répertoire parent.")
            if (!parent.exists() && !parent.mkdirs()) {
                return DiskProvisionResult.Failed("Impossible de créer le répertoire du disque.")
            }
            if (file.exists()) {
                if (!file.isFile || file.length() < sizeMb.toLong() * 1024L * 1024L) {
                    return DiskProvisionResult.Invalid("Le disque existant est invalide ou plus petit que la taille demandée.")
                }
                return DiskProvisionResult.Ready(file, false)
            }
            RandomAccessFile(file, "rw").use { it.setLength(sizeMb.toLong() * 1024L * 1024L) }
            DiskProvisionResult.Ready(file, true)
        } catch (error: Exception) {
            DiskProvisionResult.Failed(error.message ?: "Impossible de préparer le disque virtuel.")
        }
    }
}
