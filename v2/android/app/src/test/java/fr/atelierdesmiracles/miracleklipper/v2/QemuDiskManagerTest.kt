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
    @Test fun seedsPersistentDiskOnlyOnce() {
        val root = Files.createTempDirectory("mk-disk-seed").toFile()
        val seed = File(root, "seed.qcow2").apply { writeText("initial-image") }
        val target = File(root, "persistent/guest.qcow2")

        val first = QemuDiskManager.ensureSeededDisk(target, seed)
        assertTrue(first is DiskProvisionResult.Ready)
        assertTrue((first as DiskProvisionResult.Ready).created)
        assertEquals("initial-image", target.readText())

        target.writeText("user-data")
        seed.writeText("new-runtime-image")
        val second = QemuDiskManager.ensureSeededDisk(target, seed)
        assertTrue(second is DiskProvisionResult.Ready)
        assertFalse((second as DiskProvisionResult.Ready).created)
        assertEquals("user-data", target.readText())
    }

    @Test fun rejectsMissingSeedImage() {
        val root = Files.createTempDirectory("mk-disk-missing").toFile()
        val result = QemuDiskManager.ensureSeededDisk(File(root, "guest.qcow2"), File(root, "missing.qcow2"))
        assertTrue(result is DiskProvisionResult.Invalid)
    }

}
