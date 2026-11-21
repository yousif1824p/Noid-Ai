package ai.noid.app

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectBackupDirectoryPolicyTest {
    @Test
    fun createsMissingDirectoryOnlyWhenRequested() {
        val parent = Files.createTempDirectory("noid-backup-policy").toFile()
        try {
            val directory = File(parent, "backups")
            assertFalse(ProjectBackupDirectoryPolicy.ensure(directory, createIfMissing = false))
            assertFalse(directory.exists())
            assertTrue(ProjectBackupDirectoryPolicy.ensure(directory, createIfMissing = true))
            assertTrue(directory.isDirectory)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun rejectsBackupDirectoryThatIsSymbolicLink() {
        val parent = Files.createTempDirectory("noid-backup-symlink").toFile()
        try {
            val target = File(parent, "outside").apply { mkdir() }
            val link = File(parent, "backups")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            try {
                ProjectBackupDirectoryPolicy.ensure(link, createIfMissing = true)
                fail("Expected symbolic-link backup directory to be rejected")
            } catch (_: java.io.IOException) {
                // Expected: never follow a symbolic link for backup storage.
            }
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun rejectsSymbolicLinkInBackupDirectoryParent() {
        val parent = Files.createTempDirectory("noid-backup-parent").toFile()
        try {
            val target = File(parent, "target").apply { mkdir() }
            val link = File(parent, "linked-parent")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            val nested = File(link, "backups")
            try {
                ProjectBackupDirectoryPolicy.ensure(nested, createIfMissing = true)
                fail("Expected symbolic-link parent to be rejected")
            } catch (_: java.io.IOException) {
                assertFalse(File(target, "backups").exists())
            }
        } finally {
            parent.deleteRecursively()
        }
    }
}
