package ai.noid.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private data class ProjectScanResult(
    val findings: List<ApkSecurityFinding>,
    val scannedFiles: Int,
    val skippedFiles: Int,
    val sourceFiles: List<ProjectSourceFile>
)

@Composable
fun ProjectScanScreen(
    toolRegistry: ToolRegistry
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<ProjectScanResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingScopeUri by remember { mutableStateOf<Uri?>(null) }
    var selectedPreview by remember { mutableStateOf<ProjectRemediationPreview?>(null) }

    fun beginAuthorizedScan(uri: Uri) {
        pendingScopeUri = null
        error = null
        result = null
        selectedPreview = null
        busy = true
        val auditAction = NoidUserToolActions.PROJECT_SOURCE_STATIC_SCAN
        scope.launch {
            try {
                val execution = toolRegistry.executeUserAction(
                    action = auditAction,
                    grantedCapabilities = {
                        val hasPersistedReadGrant = context.contentResolver.persistedUriPermissions.any {
                            it.uri == uri && it.isReadPermission
                        }
                        if (hasPersistedReadGrant) setOf(ToolCapability.READ_FILES) else emptySet()
                    },
                    userConfirmed = true,
                ) {
                    withContext(Dispatchers.IO) {
                        scanSelectedProject(
                            context = context,
                            rootUri = uri,
                            userConfirmedAuthorizedScope = true
                        )
                    }
                }
                when (execution) {
                    is ToolExecutionResult.Completed -> {
                        result = execution.value
                        if (!execution.completionAudited) {
                            error = "اكتمل الفحص، لكن تعذر حفظ حدث إتمامه في سجل التدقيق."
                        }
                    }
                    is ToolExecutionResult.Rejected -> {
                        error = execution.reason
                        if (!execution.auditRecorded) {
                            error = "رُفض الفحص: ${execution.reason} وتعذر حفظ قرار الرفض في سجل التدقيق."
                        }
                    }
                    is ToolExecutionResult.AuditUnavailable -> {
                        error = "توقف الفحص لأن سجل التدقيق غير متاح (مرحلة: ${execution.phase})."
                    }
                    is ToolExecutionResult.Failed -> {
                        error = "فشل الفحص (${execution.exceptionType}). " +
                            if (execution.failureAudited) "سُجل الفشل." else "تعذر تسجيل الفشل."
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "تعذر فحص ملفات المشروع."
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            error = null
            result = null
            selectedPreview = null
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                pendingScopeUri = uri
            } catch (failure: SecurityException) {
                error = "تعذر الاحتفاظ بإذن قراءة المجلد. أعد اختيار مجلد تسمح بمشاركته."
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("فحص أمان ملفات المشروع", style = MaterialTheme.typography.titleLarge)
        Text(
            "اختر مجلد مشروع تملكه أو لديك تصريح بفحصه. سيقرأ NOID ملفات النص المدعومة فقط، ولن يعدّلها.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
        Button(
            onClick = { picker.launch(null) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("اختيار مجلد المشروع")
        }
        if (busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text("جارٍ فحص ملفات المشروع…")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        result?.let { scan ->
            Text("الملفات المفحوصة: ${scan.scannedFiles}")
            Text("الملفات المتجاوزة: ${scan.skippedFiles}")
            Text("المؤشرات المكتشفة: ${scan.findings.size}")
            Text(
                "الفحص ساكن أولي؛ راجع السياق يدوياً قبل اعتبار أي نتيجة ثغرة مؤكدة.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scan.findings, key = { it.id }) { finding ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text("${finding.severity} · الثقة ${finding.confidence} · ${finding.title}", style = MaterialTheme.typography.titleSmall)
                            Text(finding.description, fontSize = 13.sp)
                            Text("الدليل: ${finding.evidence}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("التوصية: ${finding.recommendation}", fontSize = 12.sp)
                            val sourcePath = finding.id.substringAfter(':').substringBeforeLast(':')
                            val sourceFile = scan.sourceFiles.firstOrNull { it.relativePath == sourcePath }
                            Button(onClick = { selectedPreview = sourceFile?.let { ProjectRemediationPreviewer.preview(it, finding) } }, enabled = sourceFile != null) {
                                Text("معاينة الإصلاح وإعادة الفحص")
                            }
                        }
                    }
                }
            }
        }
    }

    selectedPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { selectedPreview = null },
            title = { Text("معاينة إصلاح — دون حفظ") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(preview.relativePath, style = MaterialTheme.typography.labelMedium)
                    Text(preview.reason, fontSize = 13.sp)
                    if (!preview.supported) {
                        Text("لا يمكن إنشاء إصلاح آمن تلقائي لهذا المؤشر. يلزم التعديل اليدوي.")
                    } else {
                        Text(if (preview.resolvesFindingInPreview) "نتيجة الفحص التجريبي: اختفى هذا المؤشر من النسخة المقترحة فقط." else "نتيجة الفحص التجريبي: ما زالت هناك مؤشرات مطابقة؛ لم يُحلّ الخطر بالكامل.")
                        Text("الإضافات: ${preview.diff?.additions ?: 0} · الحذف: ${preview.diff?.removals ?: 0}", style = MaterialTheme.typography.labelMedium)
                        preview.diff?.lines?.forEach { line ->
                            val marker = when (line.kind) {
                                ProjectDiffKind.ADDED -> "+ "
                                ProjectDiffKind.REMOVED -> "- "
                                ProjectDiffKind.UNCHANGED -> "  "
                            }
                            Text(marker + ProjectPreviewSecretRedactor.redact(line.text), fontSize = 11.sp, color = when (line.kind) {
                                ProjectDiffKind.ADDED -> MaterialTheme.colorScheme.primary
                                ProjectDiffKind.REMOVED -> MaterialTheme.colorScheme.error
                                ProjectDiffKind.UNCHANGED -> MaterialTheme.colorScheme.onSurfaceVariant
                            })
                        }
                        Text("هذه معاينة داخل الذاكرة فقط. لم يتم حفظ أي تغيير في ملفات المشروع.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedPreview = null }) { Text("إغلاق") } }
        )
    }

    pendingScopeUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingScopeUri = null },
            title = { Text("تأكيد نطاق الفحص") },
            text = {
                Text(
                    "أؤكد أن هذا المشروع أملكه أو لدي تصريح صريح بفحصه. " +
                        "سيقتصر الفحص على قراءة ملفات المشروع المحدد محلياً، ولن ينفذ هجمات أو يغيّر الملفات."
                )
            },
            confirmButton = {
                TextButton(onClick = { beginAuthorizedScan(uri) }) {
                    Text("أؤكد وابدأ الفحص")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingScopeUri = null }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

private suspend fun scanSelectedProject(
    context: android.content.Context,
    rootUri: Uri,
    userConfirmedAuthorizedScope: Boolean
): ProjectScanResult {
    val hasPersistedReadGrant = context.contentResolver.persistedUriPermissions.any { permission ->
        permission.uri == rootUri && permission.isReadPermission
    }
    if (!ProjectScanAuthorizationPolicy.allowsReadOnlyScan(
            selectedTreeUri = rootUri.toString(),
            hasPersistedReadGrant = hasPersistedReadGrant,
            userConfirmedAuthorizedScope = userConfirmedAuthorizedScope
        )
    ) {
        throw SecurityException(
            "يلزم اختيار مجلد عبر منتقي Android، والاحتفاظ بإذن القراءة، وتأكيد امتلاك المشروع أو التصريح بفحصه."
        )
    }

    val checkAuthorization = AssessmentCheckAuthorizationPolicy.evaluate(
        SecurityAssessmentCheck.PROJECT_SOURCE_STATIC_READ,
        AssessmentScope(targetAuthorized = userConfirmedAuthorizedScope)
    )
    if (checkAuthorization.decision != AssessmentDecision.ALLOW) {
        throw SecurityException(checkAuthorization.reason)
    }

    val repository = ProjectFileRepository(
        context,
        java.io.File(context.filesDir, "project-backups")
    )
    val sourceFiles = mutableListOf<ProjectSourceFile>()
    var skipped = 0
    var visitedFiles = 0
    var visitedEntries = 0
    var totalBytesRead = 0L

    suspend fun visit(directory: String, depth: Int) {
        currentCoroutineContext().ensureActive()
        if (depth > MAX_SCAN_DEPTH || visitedEntries >= MAX_SCAN_ENTRIES ||
            visitedFiles >= MAX_SCAN_FILES
        ) {
            skipped++
            return
        }
        val entries = try {
            repository.list(rootUri, directory)
        } catch (_: IOException) {
            skipped++
            return
        }
        for ((index, entry) in entries.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (visitedEntries >= MAX_SCAN_ENTRIES || visitedFiles >= MAX_SCAN_FILES) {
                skipped += entries.size - index
                break
            }
            visitedEntries++
            if (entry.isDirectory) {
                visit(entry.relativePath, depth + 1)
            } else {
                visitedFiles++
                if (!ProjectFilePolicy.isEditableTextFile(entry.name) ||
                    entry.sizeBytes !in 0..ProjectFilePolicy.MAX_TEXT_FILE_BYTES ||
                    totalBytesRead + entry.sizeBytes > MAX_TOTAL_SCAN_BYTES
                ) {
                    skipped++
                    continue
                }
                try {
                    val content = repository.readText(rootUri, entry.relativePath)
                    val actualBytes = content.toByteArray(Charsets.UTF_8).size.toLong()
                    if (actualBytes > ProjectFilePolicy.MAX_TEXT_FILE_BYTES ||
                        totalBytesRead + actualBytes > MAX_TOTAL_SCAN_BYTES
                    ) {
                        skipped++
                        continue
                    }
                    totalBytesRead += actualBytes
                    sourceFiles += ProjectSourceFile(entry.relativePath, content)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    skipped++
                }
            }
        }
    }

    visit("", 0)
    currentCoroutineContext().ensureActive()

    return ProjectScanResult(
        findings = ProjectSecurityScanner.scan(sourceFiles),
        scannedFiles = sourceFiles.size,
        skippedFiles = skipped,
        sourceFiles = sourceFiles.toList()
    )
}

private const val MAX_SCAN_DEPTH = 12
private const val MAX_SCAN_FILES = 500
private const val MAX_SCAN_ENTRIES = 2_000
private const val MAX_TOTAL_SCAN_BYTES = 20L * 1024L * 1024L

