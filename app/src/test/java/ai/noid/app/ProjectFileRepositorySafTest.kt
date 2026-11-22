package ai.noid.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
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
        TestSafContentProvider.failNextWrite = false
        TestSafContentProvider.failNextRead = false
        root.deleteRecursively()
        backups.deleteRecursively()
    }

    @Test
    fun documentsProviderChildQueryExposesOpaqueDocumentIds() {
        File(root, "notes.txt").writeText("original", Charsets.UTF_8)
        TestSafContentProvider.backingRoot = root
        ShadowContentResolver.registerProviderInternal(TestSafContentProvider.AUTHORITY, TestSafContentProvider())
        val treeUri = DocumentsContract.buildTreeDocumentUri(TestSafContentProvider.AUTHORITY, "root")
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, "root")
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null,
            null,
            null
        ) ?: throw AssertionError("DocumentsProvider returned a null child cursor")
        cursor.use {
            assertTrue("Expected notes.txt in the provider child listing", it.moveToFirst())
            assertEquals("notes.txt", it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)))
            val documentId = it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
            assertTrue("Document IDs must remain opaque", documentId.startsWith("doc-"))
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            val documentCursor = context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            ) ?: throw AssertionError("DocumentsProvider returned a null document cursor")
            documentCursor.use { document ->
                assertTrue("Expected queryDocument to resolve the child ID", document.moveToFirst())
                assertEquals(documentId, document.getString(0))
            }
        }
    }

    @Test
    fun readsWritesBacksUpAndRestoresThroughDocumentsProvider() {
        File(root, "notes.txt").writeText("original", Charsets.UTF_8)
        TestSafContentProvider.backingRoot = root
        ShadowContentResolver.registerProviderInternal(TestSafContentProvider.AUTHORITY, TestSafContentProvider())

        val treeUri = DocumentsContract.buildTreeDocumentUri(TestSafContentProvider.AUTHORITY, "root")
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

    @Test
    fun sourceFreshnessReadsProviderContentAndDetectsConcurrentChanges() {
        val original = "class Worker { val count = 1 }"
        File(root, "Worker.kt").writeText(original, Charsets.UTF_8)
        TestSafContentProvider.backingRoot = root
        ShadowContentResolver.registerProviderInternal(TestSafContentProvider.AUTHORITY, TestSafContentProvider())
        val treeUri = DocumentsContract.buildTreeDocumentUri(TestSafContentProvider.AUTHORITY, "root")
        val repository = ProjectFileRepository(context, backups)

        assertEquals(
            SelfDevelopmentSourceFreshnessStatus.MATCH,
            repository.verifySourceUnchanged(
                treeUri,
                "Worker.kt",
                SelfDevelopmentProposalRepository.sha256(original)
            ).status
        )

        File(root, "Worker.kt").writeText("class Worker { val count = 2 }", Charsets.UTF_8)
        assertEquals(
            SelfDevelopmentSourceFreshnessStatus.STALE,
            repository.verifySourceUnchanged(
                treeUri,
                "Worker.kt",
                SelfDevelopmentProposalRepository.sha256(original)
            ).status
        )
    }

    @Test
    fun providerReadFailureFailsClosedAsUnavailableSource() {
        val original = "original"
        File(root, "notes.txt").writeText(original, Charsets.UTF_8)
        TestSafContentProvider.backingRoot = root
        ShadowContentResolver.registerProviderInternal(TestSafContentProvider.AUTHORITY, TestSafContentProvider())
        val treeUri = DocumentsContract.buildTreeDocumentUri(TestSafContentProvider.AUTHORITY, "root")
        val repository = ProjectFileRepository(context, backups)

        TestSafContentProvider.failNextRead = true
        assertEquals(
            SelfDevelopmentSourceFreshnessStatus.SOURCE_UNAVAILABLE,
            repository.verifySourceUnchanged(
                treeUri,
                "notes.txt",
                SelfDevelopmentProposalRepository.sha256(original)
            ).status
        )
    }

    @Test
    fun providerWriteFailureRestoresOriginalAndRetainsRecoveryBackup() {
        File(root, "notes.txt").writeText("original", Charsets.UTF_8)
        TestSafContentProvider.backingRoot = root
        ShadowContentResolver.registerProviderInternal(TestSafContentProvider.AUTHORITY, TestSafContentProvider())
        val treeUri = DocumentsContract.buildTreeDocumentUri(TestSafContentProvider.AUTHORITY, "root")
        val repository = ProjectFileRepository(context, backups)

        TestSafContentProvider.failNextWrite = true
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

/**
 * ContentProvider test double implementing the URI/call surface consumed by
 * androidx DocumentFile. Robolectric's DocumentsProvider legacy query path is
 * final and deliberately unsupported, so this exercises DocumentFile without
 * pretending to be a physical Android SAF provider.
 */
class TestSafContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val columns = projection ?: DOCUMENT_COLUMNS
        return when {
            uri.lastPathSegment == "children" -> {
                val parent = fileFor(DocumentsContract.getDocumentId(uri))
                MatrixCursor(columns).apply {
                    parent.listFiles().orEmpty().forEach { child ->
                        addDocumentRow(this, columns, idFor(child), child)
                    }
                }
            }
            uri.pathSegments.contains("document") -> {
                val id = DocumentsContract.getDocumentId(uri)
                MatrixCursor(columns).apply { addDocumentRow(this, columns, id, fileFor(id)) }
            }
            else -> MatrixCursor(columns)
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = DocumentsContract.getDocumentId(uri)
        if (!mode.contains('w') && failNextRead) {
            failNextRead = false
            throw IOException("Injected provider read failure")
        }
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
        return ParcelFileDescriptor.open(fileFor(id), flags)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle()
        when (method) {
            DocumentsContract.METHOD_CREATE_DOCUMENT -> {
                val parent = fileFor(arg ?: throw IOException("Missing parent document ID"))
                val name = extras?.getString(DocumentsContract.EXTRA_DISPLAY_NAME)
                    ?: throw IOException("Missing display name")
                val created = File(parent, name)
                check(created.createNewFile()) { "Document already exists" }
                result.putParcelable(
                    DocumentsContract.EXTRA_URI,
                    DocumentsContract.buildDocumentUri(AUTHORITY, idFor(created))
                )
            }
            DocumentsContract.METHOD_DELETE_DOCUMENT -> {
                check(fileFor(arg ?: throw IOException("Missing document ID")).delete())
            }
            DocumentsContract.METHOD_RENAME_DOCUMENT -> {
                val source = fileFor(arg ?: throw IOException("Missing document ID"))
                val name = extras?.getString(DocumentsContract.EXTRA_DISPLAY_NAME)
                    ?: throw IOException("Missing display name")
                val destination = File(source.parentFile, name)
                check(source.renameTo(destination))
                result.putParcelable(
                    DocumentsContract.EXTRA_URI,
                    DocumentsContract.buildDocumentUri(AUTHORITY, idFor(destination))
                )
            }
            else -> throw UnsupportedOperationException("Unsupported test provider call: $method")
        }
        return result
    }

    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment == "children") "vnd.android.cursor.dir/vnd.android.document"
        else "text/plain"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Insert is not part of the DocumentFile contract")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        if (fileFor(DocumentsContract.getDocumentId(uri)).delete()) 1 else 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("Update is not part of the DocumentFile contract")

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
        @Volatile var failNextRead: Boolean = false

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
