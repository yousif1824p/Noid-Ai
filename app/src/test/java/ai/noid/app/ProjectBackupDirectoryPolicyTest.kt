package ai.noid.app

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectBackupDirectoryPolicyTest {
    @Test fun createsMissingDirectoryOnlyWhenRequested() {
        val parent = Files.createTempDirectory("noid-backup-policy").toFile()
        try {
            val directory = File(parent, "backups")
            assertFalse(ProjectBackupDirectoryPolicy.ensure(directory, createIfMissing = false))
            assertFalse(directory.exists())
            assertTrue(ProjectBackupDirectoryPolicy.ensure(directory, createIfMissing = true))
            assertTrue(directory.isDirectory)
        } finally { parent.deleteRecursively() }
    }

    @Test fun rejectsBackupDirectoryThatIsSymbolicLink() {
        val parent = Files.createTempDirectory("noid-backup-symlink").toFile()
        try {
            val target = File(parent, "outside").apply { mkdir() }
            val link = File(parent, "backups")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            try { ProjectBackupDirectoryPolicy.ensure(link, createIfMissing = true); fail("Expected symlink rejection") }
            catch (_: java.io.IOException) { }
        } finally { parent.deleteRecursively() }
    }

    @Test fun rejectsSymbolicLinkInBackupDirectoryParent() {
        val parent = Files.createTempDirectory("noid-backup-parent").toFile()
        try {
            val target = File(parent, "target").apply { mkdir() }
            val link = File(parent, "linked-parent")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            try { ProjectBackupDirectoryPolicy.ensure(File(link, "backups"), createIfMissing = true); fail("Expected parent symlink rejection") }
            catch (_: java.io.IOException) { assertFalse(File(target, "backups").exists()) }
        } finally { parent.deleteRecursively() }
    }

    @Test fun acceptsOnlyRegularFilesDirectlyInsideBackupDirectory() {
        val parent = Files.createTempDirectory("noid-backup-owned").toFile()
        try {
            val directory = File(parent, "backups").apply { mkdir() }
            val backup = File(directory, "one.bak").apply { writeText("backup") }
            val outside = File(parent, "outside.bak").apply { writeText("outside") }
            assertTrue(ProjectBackupDirectoryPolicy.isOwnedRegularFile(directory, backup, "bak"))
            assertFalse(ProjectBackupDirectoryPolicy.isOwnedRegularFile(directory, outside, "bak"))
            assertFalse(ProjectBackupDirectoryPolicy.isOwnedRegularFile(directory, backup, "meta"))
        } finally { parent.deleteRecursively() }
    }

    @Test fun rejectsSymbolicLinkBackupFile() {
        val parent = Files.createTempDirectory("noid-backup-file-link").toFile()
        try {
            val directory = File(parent, "backups").apply { mkdir() }
            val outside = File(parent, "outside.bak").apply { writeText("outside") }
            val link = File(directory, "linked.bak")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertFalse(ProjectBackupDirectoryPolicy.isOwnedRegularFile(directory, link, "bak"))
        } finally { parent.deleteRecursively() }
    }

    @Test fun rejectsSymbolicLinkMetadataFile() {
        val parent = Files.createTempDirectory("noid-backup-meta-link").toFile()
        try {
            val directory = File(parent, "backups").apply { mkdir() }
            val outside = File(parent, "outside.meta").apply { writeText("relativePath=secret.txt") }
            val link = File(directory, "one.bak.meta")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertFalse(ProjectBackupDirectoryPolicy.isOwnedRegularFile(directory, link, "meta"))
        } finally { parent.deleteRecursively() }
    }

    @Test fun rejectsNestedChildPathEvenWhenTargetIsRegular() {
        val parent = Files.createTempDirectory("noid-backup-nested").toFile()
        try {
            val directory = File(parent, "backups").apply { mkdir() }
            val nested = File(directory, "nested").apply { mkdir() }
            val backup = File(nested, "one.bak").apply { writeText("backup") }
            assertFalse(ProjectBackupDirectoryPolicy.isOwnedRegularFile(directory, backup, "bak"))
        } finally { parent.deleteRecursively() }
    }
}
