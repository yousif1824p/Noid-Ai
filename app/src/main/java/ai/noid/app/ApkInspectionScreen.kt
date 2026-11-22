package ai.noid.app

import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@Composable
fun ApkInspectionScreen(
    toolRegistry: ToolRegistry
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<ApkInspectionReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var pendingApkUri by remember { mutableStateOf<Uri?>(null) }

    val reportExporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { destination: Uri? ->
        val currentReport = report
        if (destination != null && currentReport != null) {
            scope.launch {
                val exportAction = NoidUserToolActions.APK_REPORT_EXPORT
                try {
                    val execution = toolRegistry.executeUserAction(
                        action = exportAction,
                        grantedCapabilities = {
                            val hasDestinationWriteGrant = context.checkUriPermission(
                                destination,
                                android.os.Process.myPid(),
                                android.os.Process.myUid(),
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasDestinationWriteGrant) setOf(ToolCapability.WRITE_FILES) else emptySet()
                        },
                        userConfirmed = true,
                    ) {
                        withContext(Dispatchers.IO) {
                            val output = context.contentResolver.openOutputStream(destination)
                                ?: throw IOException("تعذر فتح وجهة حفظ التقرير.")
                            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                                writer.write(ApkReportFormatter.format(currentReport, selectedName))
                            }
                        }
                    }
                    error = when (execution) {
                        is ToolExecutionResult.Completed -> if (execution.completionAudited) null
                            else "تم تصدير التقرير، لكن تعذر حفظ حدث إتمامه في سجل التدقيق."
                        is ToolExecutionResult.Rejected -> execution.reason
                        is ToolExecutionResult.AuditUnavailable ->
                            "توقف التصدير لأن سجل التدقيق غير متاح (مرحلة: ${execution.phase})."
                        is ToolExecutionResult.Failed ->
                            "فشل تصدير التقرير (${execution.exceptionType}). " +
                                if (execution.failureAudited) "سُجل الفشل." else "تعذر تسجيل الفشل."
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "تعذر حفظ التقرير."
                }
            }
        }
    }

    fun inspectAuthorizedApk(uri: Uri, userConfirmedAuthorizedScope: Boolean) {
        pendingApkUri = null
        val authorization = AssessmentCheckAuthorizationPolicy.evaluate(
            SecurityAssessmentCheck.APK_MANIFEST_READ,
            AssessmentScope(targetAuthorized = userConfirmedAuthorizedScope)
        )
        val auditAction = NoidUserToolActions.APK_MANIFEST_INSPECTION
        if (authorization.decision != AssessmentDecision.ALLOW) {
            error = authorization.reason
            return
        }
        busy = true
        error = null
        report = null
        selectedName = uri.lastPathSegment
        scope.launch {
            try {
                val execution = toolRegistry.executeUserAction(
                    action = auditAction,
                    grantedCapabilities = {
                        val hasSelectedApkReadGrant = context.checkUriPermission(
                            uri,
                            android.os.Process.myPid(),
                            android.os.Process.myUid(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasSelectedApkReadGrant) setOf(ToolCapability.READ_FILES) else emptySet()
                    },
                    userConfirmed = userConfirmedAuthorizedScope,
                ) {
                    withContext(Dispatchers.IO) {
                        val apk = copyApkToCache(context, uri)
                        try {
                            ApkManifestInspector(context.packageManager).inspect(apk)
                        } finally {
                            apk.delete()
                        }
                    }
                }
                when (execution) {
                    is ToolExecutionResult.Completed -> {
                        report = execution.value
                        if (!execution.completionAudited) {
                            error = "اكتمل الفحص، لكن تعذر حفظ حدث إتمامه في سجل التدقيق."
                        }
                    }
                    is ToolExecutionResult.Rejected -> {
                        error = if (execution.auditRecorded) execution.reason
                            else "${execution.reason} وتعذر حفظ قرار الرفض في سجل التدقيق."
                    }
                    is ToolExecutionResult.AuditUnavailable -> {
                        error = "توقف الفحص لأن سجل التدقيق غير متاح (مرحلة: ${execution.phase})."
                    }
                    is ToolExecutionResult.Failed -> {
                        error = "فشل تحليل APK (${execution.exceptionType}). " +
                            if (execution.failureAudited) "سُجل الفشل." else "تعذر تسجيل الفشل."
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "تعذر تحليل ملف APK."
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            error = null
            report = null
            selectedName = uri.lastPathSegment
            pendingApkUri = uri
        }
    }

    pendingApkUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingApkUri = null },
            title = { Text("تأكيد تصريح فحص APK") },
            text = {
                Text(
                    "أؤكد أن ملف APK أملكه أو لدي تصريح صريح بفحصه. " +
                        "سيقتصر الإجراء على قراءة بيانات Manifest محلياً؛ لن يُثبّت التطبيق أو يُشغّل أو يُعدّل."
                )
            },
            confirmButton = {
                TextButton(onClick = { inspectAuthorizedApk(uri, userConfirmedAuthorizedScope = true) }) {
                    Text("أؤكد وابدأ الفحص")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingApkUri = null }) {
                    Text("إلغاء")
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("فاحص حماية التطبيقات", style = MaterialTheme.typography.titleLarge)
        Text(
            "اختر ملف APK لتحليل بيانات Manifest والصلاحيات والمكونات. الفحص ثابت ولا يثبت التطبيق أو يشغّله.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
        Button(
            onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("اختيار ملف APK")
        }
        if (busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text("جارٍ تحليل بيانات الحزمة…")
            }
        }
        selectedName?.let { Text("الملف المحدد: $it", fontSize = 12.sp) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        report?.let { result ->
            Text("الحزمة: ${result.packageName}", style = MaterialTheme.typography.titleMedium)
            Text("الإصدار: ${result.versionName ?: "غير محدد"}")
            Text("SDK الأدنى: ${result.minSdkVersion ?: "غير معروف"} · SDK الهدف: ${result.targetSdkVersion ?: "غير معروف"}")
            Text("الصلاحيات المطلوبة: ${result.requestedPermissions.size}")
            Text("المكونات: ${result.components.size}")
            Text("حالة تقييم الأدلة: ${result.assessment.status}")
            Text("الأدلة الحاسمة: ${result.assessment.authoritativeEvidence.size} · التعارضات: ${result.assessment.conflicts.size} · استشارات الذكاء الاصطناعي: ${result.assessment.aiAdvisories.size}")
            when (result.assessment.status) {
                ApkAssessmentStatus.INSUFFICIENT_EVIDENCE -> Text(
                    "الأدلة غير كافية لإصدار تقييم أمني؛ لم تُثبت سلامة الملف.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
                ApkAssessmentStatus.NO_FLAGS_WITHIN_SCOPE -> Text(
                    "لم تظهر مؤشرات ضمن الفحوص المحددة فقط؛ هذه ليست شهادة أمان.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                ApkAssessmentStatus.FINDINGS_PRESENT -> Text(
                    "توجد مؤشرات تستلزم المراجعة والتحقق.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
                ApkAssessmentStatus.CONFLICTING_EVIDENCE -> Text(
                    "توجد أدلة متعارضة؛ لا يمكن حسم النتيجة تلقائياً.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
            result.assessment.conflicts.forEach { conflict ->
                Text(
                    "تعارض في ${conflict.checkId}: ${conflict.flagged.size} دليل إيجابي مقابل ${conflict.notFlagged.size} دليل سلبي.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
            Text(
                "هذه مؤشرات مراجعة أولية وليست إثباتاً على إمكانية استغلال الثغرة.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            OutlinedButton(
                onClick = { reportExporter.launch("noid-apk-review.txt") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("تصدير التقرير النصي") }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(result.findings, key = { it.id }) { finding ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text("${finding.severity} · الثقة ${finding.confidence} · ${finding.title}", style = MaterialTheme.typography.titleSmall)
                            Text(finding.description, fontSize = 13.sp)
                            Text("الدليل: ${finding.evidence}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("المصدر: ${finding.source}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("التوصية: ${finding.recommendation}", fontSize = 12.sp)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { report = null; selectedName = null }, modifier = Modifier.fillMaxWidth()) {
                Text("مسح النتيجة")
            }
        }
        if (report == null && !busy && error == null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "حد الملف 1.5 غيغابايت. يتطلب الملف الكبير مساحة مؤقتة كافية؛ لا يُرسل الملف إلى أي خادم.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

private fun copyApkToCache(context: android.content.Context, uri: Uri): File {
    val declaredSize = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    } catch (_: Exception) {
        -1L
    }
    if (declaredSize > ApkManifestInspector.MAX_APK_BYTES) {
        throw IOException("حجم APK يتجاوز الحد المسموح (1.5 غيغابايت).")
    }
    // Unknown provider sizes are handled incrementally instead of reserving the full 1.5 GiB.
    val bytesToReserveForCopy = declaredSize.coerceAtLeast(0L)
    val safetyReserveBytes = 128L * 1024L * 1024L
    if (context.cacheDir.usableSpace < bytesToReserveForCopy + safetyReserveBytes) {
        throw IOException(
            "المساحة المؤقتة غير كافية لفحص هذا الملف بأمان. وفر مساحة لا تقل عن حجم الملف مع 128 ميغابايت احتياطية."
        )
    }
    val temp = File.createTempFile("noid-apk-", ".apk", context.cacheDir)
    try {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("تعذر فتح الملف المحدد.")
        input.use { source ->
            FileOutputStream(temp).use { target ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > ApkManifestInspector.MAX_APK_BYTES) {
                        throw IOException("حجم APK يتجاوز الحد المسموح (1.5 غيغابايت).")
                    }
                    if (context.cacheDir.usableSpace < count.toLong() + safetyReserveBytes) {
                        throw IOException("توقّف النسخ قبل نفاد مساحة التخزين؛ وفّر مساحة إضافية ثم أعد الفحص.")
                    }
                    target.write(buffer, 0, count)
                }
            }
        }
        if (temp.length() == 0L) throw IOException("ملف APK فارغ.")
        return temp
    } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
        temp.delete()
        throw failure
    }
}
