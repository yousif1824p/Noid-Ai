package ai.noid.app

import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.RuntimeEnvironment

/**
 * Exercises ProjectFileRepository through Android's DocumentsProvider/ContentResolver
 * boundary. This is a provider integration test, not a physical-device SAF test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectFileRepositorySafTest {
    private val root = Files.createTempDirectory("noid-saf-provider").toFile()
    private val backups = File(root.parentFile, "noid-saf-backups-${System.nanoTime()}")
    private val context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        root.deleteRecursively()
        backups.deleteRecursively()
    }

    @Test
    fun readsWritesBacksUpAndRestoresThroughDocumentsProvider() {
        File(root, "notes.txt").writeText("original", Charsets.UTF_8)
        TestDocumentsProvider.backingRoot = root
        ShadowContentResolver.registerProviderInternal(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider())

        val treeUri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root")
        val repository = ProjectFileRepository(context, backups)

        assertEquals("original", repository.readText(treeUri, "notes.txt"))

        val backup = repository.writeText(treeUri, "notes.txt", "updated")
        assertEquals("updated", repository.readText(treeUri, "notes.txt"))
        assertEquals("original", backup.backupFile.readText(Charsets.UTF_8))
        assertEquals(1, repository.listBackups("notes.txt").size)

        val restoreBackup = repository.restoreBackup(treeUri, backup)
        assertEquals("original", repository.readText(treeUri, "notes.txt"))
        assertEquals("updated", restoreBackup.backupFile.readText(Charsets.UTF_8))
        assertTrue(repository.deleteBackup(backup))
        assertEquals(1, repository.listBackups("notes.txt").size)
    }
}

/** Minimal in-memory-tree DocumentsProvider backed by a temporary host directory. */
class TestDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: ROOT_COLUMNS
        return MatrixCursor(columns).apply {
            newRow().apply {
                add(DocumentsContract.Root.COLUMN_ROOT_ID, "root")
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root")
                add(DocumentsContract.Root.COLUMN_TITLE, "NOID test tree")
                add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE)
                add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
                add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, 1024L * 1024L)
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        documentCursor(documentId, projection)

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val parent = fileFor(parentDocumentId)
        val columns = projection ?: DOCUMENT_COLUMNS
        return MatrixCursor(columns).apply {
            parent.listFiles().orEmpty().forEach { child ->
                val id = idFor(child)
                addDocumentRow(this, columns, id, child)
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val flags = when {
            mode.contains('w') -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            else -> ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(fileFor(documentId), flags)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val parent = fileFor(parentDocumentId)
        val created = File(parent, displayName)
        check(created.createNewFile()) { "Document already exists" }
        return idFor(created)
    }

    override fun deleteDocument(documentId: String) {
        check(fileFor(documentId).delete())
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val source = fileFor(documentId)
        val destination = File(source.parentFile, displayName)
        check(source.renameTo(destination))
        return idFor(destination)
    }

    private fun documentCursor(documentId: String, projection: Array<out String>?): Cursor {
        val columns = projection ?: DOCUMENT_COLUMNS
        val file = fileFor(documentId)
        return MatrixCursor(columns).apply { addDocumentRow(this, columns, documentId, file) }
    }

    private fun addDocumentRow(
        cursor: MatrixCursor,
        columns: Array<out String>,
        documentId: String,
        file: File
    ) {
        val values = mapOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to documentId,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to file.name.ifBlank { "NOID test tree" },
            DocumentsContract.Document.COLUMN_MIME_TYPE to if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "text/plain",
            DocumentsContract.Document.COLUMN_FLAGS to (
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                ),
            DocumentsContract.Document.COLUMN_SIZE to file.length(),
            DocumentsContract.Document.COLUMN_LAST_MODIFIED to file.lastModified()
        )
        cursor.newRow().apply { columns.forEach { add(values[it]) } }
    }

    private fun fileFor(documentId: String): File {
        val base = backingRoot
        if (documentId == "root") return base
        val relative = documentId.removePrefix("root/")
        require(!relative.startsWith("/") && relative.split('/').none { it == ".." })
        return File(base, relative)
    }

    private fun idFor(file: File): String =
        if (file.canonicalFile == backingRoot.canonicalFile) "root"
        else "root/" + file.relativeTo(backingRoot).invariantSeparatorsPath

    companion object {
        const val AUTHORITY = "ai.noid.test.documents"
        lateinit var backingRoot: File

        private val ROOT_COLUMNS = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }
}
