package fr.atelierdesmiracles.miracleklipper.v2

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class QemuDiskManagerTest {
    @Test fun createsSparseRawDiskAtRequestedSize() {
        val root = Files.createTempDirectory("mk-disk").toFile()
        val disk = File(root, "guest.raw")
        val result = QemuDiskManager.ensureRawDisk(disk, 512)
        assertTrue(result is DiskProvisionResult.Ready)
        assertEquals(512L * 1024L * 1024L, disk.length())
        assertTrue((result as DiskProvisionResult.Ready).created)
    }

    @Test fun preservesExistingDiskWithoutResizing() {
        val root = Files.createTempDirectory("mk-disk-existing").toFile()
        val disk = File(root, "guest.raw").apply { writeBytes(ByteArray(1024)) }
        val result = QemuDiskManager.ensureRawDisk(disk, 512)
        assertTrue(result is DiskProvisionResult.Invalid)
        assertEquals(1024L, disk.length())
    }

    @Test fun rejectsTooSmallRequestedDisk() {
        val root = Files.createTempDirectory("mk-disk-invalid").toFile()
        val result = QemuDiskManager.ensureRawDisk(File(root, "guest.raw"), 128)
        assertTrue(result is DiskProvisionResult.Invalid)
    }
}
