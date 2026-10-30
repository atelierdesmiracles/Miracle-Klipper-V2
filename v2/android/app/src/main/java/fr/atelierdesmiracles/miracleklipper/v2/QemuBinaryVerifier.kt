package fr.atelierdesmiracles.miracleklipper.v2

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

sealed class QemuBinaryVerification {
    data class Verified(val sha256: String) : QemuBinaryVerification()
    data class Rejected(val reason: String) : QemuBinaryVerification()
}

/**
 * Validates the exact native emulator artifact before it is executed.
 * Android ARM64 requires a 64-bit little-endian ELF for AArch64 (e_machine=183).
 */
object QemuBinaryVerifier {
    fun verify(binary: File, expectedSha256: String?): QemuBinaryVerification {
        if (expectedSha256 == null || !expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            return QemuBinaryVerification.Rejected("Empreinte SHA-256 QEMU absente ou invalide.")
        }
        if (!binary.isFile || !binary.canExecute()) {
            return QemuBinaryVerification.Rejected("Le binaire QEMU est absent ou non exécutable.")
        }
        if (binary.length() < 64L) {
            return QemuBinaryVerification.Rejected("Le fichier QEMU est trop court pour être un ELF valide.")
        }

        return try {
            val header = ByteArray(64)
            FileInputStream(binary).use { input ->
                var offset = 0
                while (offset < header.size) {
                    val count = input.read(header, offset, header.size - offset)
                    if (count < 0) break
                    offset += count
                }
                if (offset != header.size) {
                    return QemuBinaryVerification.Rejected("En-tête ELF QEMU incomplet.")
                }
            }
            if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
            ) {
                return QemuBinaryVerification.Rejected("Le binaire QEMU n'est pas un fichier ELF.")
            }
            if (header[4] != 2.toByte() || header[5] != 1.toByte()) {
                return QemuBinaryVerification.Rejected("QEMU doit être un ELF 64 bits little-endian.")
            }
            val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
            if (machine != 183) {
                return QemuBinaryVerification.Rejected("Le binaire QEMU n'est pas compilé pour AArch64.")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(binary).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                QemuBinaryVerification.Rejected("L'empreinte SHA-256 du binaire QEMU ne correspond pas.")
            } else {
                QemuBinaryVerification.Verified(actual)
            }
        } catch (error: Exception) {
            QemuBinaryVerification.Rejected(error.message ?: "Impossible de vérifier le binaire QEMU.")
        }
    }
}
