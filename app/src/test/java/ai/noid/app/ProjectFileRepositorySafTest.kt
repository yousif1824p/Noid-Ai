package ai.noid.app

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.IOException
import java.util.Base64
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

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
        TestDocumentsProvider.failNextWrite = false
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
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, "root")
        val childCursor = context.contentResolver.query(
            childUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )
        assertTrue("Provider child query must return the seeded file", childCursor?.use { it.moveToFirst() } == true)

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

    @Test
    fun providerWriteFailureRestoresOriginalAndRetainsRecoveryBackup() {
        File(root, "notes.txt").writeText("original", Charsets.UTF_8)
        TestDocumentsProvider.backingRoot = root
        ShadowContentResolver.registerProviderInternal(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider())
        val treeUri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root")
        val repository = ProjectFileRepository(context, backups)

        TestDocumentsProvider.failNextWrite = true
        try {
            repository.writeText(treeUri, "notes.txt", "must-not-remain")
            throw AssertionError("Expected provider write failure")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("احتُفظ بالنسخة الاحتياطية"))
        }

        assertEquals("original", repository.readText(treeUri, "notes.txt"))
        assertEquals(1, repository.listBackups("notes.txt").size)
    }
}

/** Minimal DocumentsProvider backed by a temporary host directory. */
class TestDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    // Robolectric's legacy ContentResolver path reaches this overload. Route it to the
    // same DocumentsProvider callbacks used by the framework's document contract.
    override fun query(
        uri: android.net.Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val segments = uri.pathSegments
        return when {
            segments.lastOrNull() == "children" ->
                queryChildDocuments(DocumentsContract.getDocumentId(uri), projection, sortOrder)
            segments.contains("document") ->
                queryDocument(DocumentsContract.getDocumentId(uri), projection)
            segments.contains("root") -> queryRoots(projection)
            else -> throw IOException("Unsupported test DocumentsProvider URI: $uri")
        }
    }

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
                addDocumentRow(this, columns, idFor(child), child)
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (mode.contains('w') && failNextWrite) {
            failNextWrite = false
            throw IOException("Injected provider write failure")
        }
        val flags = if (mode.contains('w')) {
            ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(fileFor(documentId), flags)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val created = File(fileFor(parentDocumentId), displayName)
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
        return MatrixCursor(columns).apply {
            addDocumentRow(this, columns, documentId, fileFor(documentId))
        }
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
        val base = backingRoot.canonicalFile
        if (documentId == "root") return base
        require(documentId.startsWith("doc-")) { "Unknown document ID" }
        val encodedPath = documentId.removePrefix("doc-")
        val relative = String(Base64.getUrlDecoder().decode(encodedPath), Charsets.UTF_8)
        require(relative.isNotBlank() && !relative.startsWith("/") &&
            relative.split('/').none { it.isBlank() || it == "." || it == ".." })
        val target = File(base, relative).canonicalFile
        require(target.toPath().startsWith(base.toPath())) { "Document escaped provider root" }
        return target
    }

    private fun idFor(file: File): String =
        if (file.canonicalFile == backingRoot.canonicalFile) "root"
        else "doc-" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(file.relativeTo(backingRoot).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))

    companion object {
        const val AUTHORITY = "ai.noid.test.documents"
        lateinit var backingRoot: File
        @Volatile var failNextWrite: Boolean = false

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
