package fr.atelierdesmiracles.miracleklipper.v2

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class QemuBinaryVerifierTest {
    private fun fakeAarch64Elf(file: File): ByteArray {
        val header = ByteArray(64)
        header[0] = 0x7f
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = 2
        header[5] = 1
        header[18] = 0xB7.toByte()
        header[19] = 0
        file.writeBytes(header)
        file.setExecutable(true)
        return header
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun acceptsMatchingAarch64ElfAndDigest() {
        val root = Files.createTempDirectory("mk-qemu-elf").toFile()
        val binary = File(root, "qemu-system-aarch64")
        val bytes = fakeAarch64Elf(binary)
        val result = QemuBinaryVerifier.verify(binary, sha256(bytes))
        assertTrue(result is QemuBinaryVerification.Verified)
    }

    @Test fun rejectsDigestMismatch() {
        val root = Files.createTempDirectory("mk-qemu-hash").toFile()
        val binary = File(root, "qemu-system-aarch64")
        fakeAarch64Elf(binary)
        val result = QemuBinaryVerifier.verify(binary, "0".repeat(64))
        assertTrue(result is QemuBinaryVerification.Rejected)
    }

    @Test fun rejectsNonAarch64Elf() {
        val root = Files.createTempDirectory("mk-qemu-arch").toFile()
        val binary = File(root, "qemu-system-aarch64")
        val bytes = fakeAarch64Elf(binary).also { it[18] = 0x3e; binary.writeBytes(it) }
        val result = QemuBinaryVerifier.verify(binary, sha256(bytes))
        assertTrue(result is QemuBinaryVerification.Rejected)
    }

    @Test fun rejectsMissingExpectedDigest() {
        val root = Files.createTempDirectory("mk-qemu-no-hash").toFile()
        val binary = File(root, "qemu-system-aarch64")
        fakeAarch64Elf(binary)
        val result = QemuBinaryVerifier.verify(binary, null)
        assertTrue(result is QemuBinaryVerification.Rejected)
    }
}
