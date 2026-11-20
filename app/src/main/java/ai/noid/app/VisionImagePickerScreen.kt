package ai.noid.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun VisionImagePickerScreen(
    toolRegistry: ToolRegistry,
    visionEngine: LiteRtLmVisionEngine
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val visionStatus by visionEngine.status.collectAsState()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedGrantOwned by remember { mutableStateOf(false) }
    var mimeType by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf("صف ما يظهر في الصورة.") }
    var status by remember { mutableStateOf("استورد نموذج رؤية متعدد الوسائط أولاً.") }
    var busy by remember { mutableStateOf(false) }

    fun readGrant(uri: Uri): Set<ToolCapability> {
        val grant = context.contentResolver.persistedUriPermissions
            .firstOrNull { it.uri == uri && it.isReadPermission }
            ?: return emptySet()
        return setOf(ToolCapability.READ_FILES)
    }

    fun persistReadGrant(uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    }.getOrDefault(false)

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val hadPersistedReadGrant = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
            if (!persistReadGrant(uri)) {
                status = "تعذر تثبيت إذن قراءة ملف النموذج؛ لم يبدأ الاستيراد."
            } else scope.launch {
                busy = true
                status = "جارٍ استيراد نموذج الرؤية وتهيئته محلياً…"
                try {
                    when (val execution = toolRegistry.executeUserAction(
                        action = NoidUserToolActions.VISION_MODEL_IMPORT,
                        grantedCapabilities = { readGrant(uri) },
                        userConfirmed = false
                    ) { visionEngine.importAndLoadModel(uri) }) {
                        is ToolExecutionResult.Completed -> {
                            status = "تم تحميل النموذج: ${execution.value}." +
                                if (execution.completionAudited) "" else
                                    " تعذر تسجيل اكتمال الاستيراد في سجل التدقيق."
                        }
                        is ToolExecutionResult.Rejected -> status = execution.reason
                        is ToolExecutionResult.AuditUnavailable ->
                            status = "تعذر تسجيل بدء الاستيراد؛ لم يُستورد النموذج."
                        is ToolExecutionResult.Failed ->
                            status = "تعذر تحميل نموذج الرؤية (${execution.exceptionType}). اختر ملف LiteRT-LM متوافقاً."
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } finally {
                    if (PersistedUriReadGrantPolicy.shouldReleaseAfterAttempt(hadPersistedReadGrant)) {
                        runCatching {
                            context.contentResolver.releasePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                    }
                    busy = false
                }
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            selectedUri = null
            selectedGrantOwned = false
            mimeType = null
            status = "لم يتم اختيار صورة."
        } else {
            val hadPersistedReadGrant = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
            if (!persistReadGrant(uri)) {
                selectedUri = null
                selectedGrantOwned = false
                mimeType = null
                status = "تعذر تثبيت إذن قراءة الصورة؛ اخترها مجدداً."
            } else {
                val alreadyOwnedByScreen = selectedUri == uri && selectedGrantOwned
                selectedUri = uri
                selectedGrantOwned = PersistedUriReadGrantPolicy.shouldReleaseAfterScreen(
                    hadPersistedReadGrantBeforeSelection = hadPersistedReadGrant,
                    alreadyOwnedByScreen = alreadyOwnedByScreen
                )
                mimeType = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT)
                status = "تم اختيار الصورة. يمكنك بدء التحليل."
            }
        }
    }

    // Release only the persisted read grant created by this screen. Keep it
    // throughout analysis, then release it on replacement or screen disposal.
    DisposableEffect(selectedUri, selectedGrantOwned) {
        val uri = selectedUri
        val releaseGrant = selectedGrantOwned
        onDispose {
            if (uri != null && releaseGrant) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("تحليل الصور محلياً", style = MaterialTheme.typography.titleLarge)
            Text(
                when (visionStatus) {
                    VisionEngineStatus.NotConfigured -> "حالة نموذج الرؤية: غير مهيأ"
                    is VisionEngineStatus.Ready ->
                        "تم تحميل النموذج: ${(visionStatus as VisionEngineStatus.Ready).modelId} — يلزم تحليل ناجح لتأكيد التوافق."
                    is VisionEngineStatus.Unavailable ->
                        "نموذج الرؤية غير متاح: ${(visionStatus as VisionEngineStatus.Unavailable).reason}"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { modelPicker.launch(arrayOf("*/*")) },
                enabled = !busy, modifier = Modifier.fillMaxWidth()
            ) { Text("استيراد / استبدال نموذج الرؤية .litertlm") }
            Text("اختر نموذجاً متعدد الوسائط يدعم الصور، مثل نسخة متوافقة من Gemma 3n. الامتداد وحده لا يثبت دعمه للرؤية.")
            Button(
                onClick = { imagePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
                enabled = !busy, modifier = Modifier.fillMaxWidth()
            ) { Text("اختيار صورة") }
            if (selectedUri != null) {
                Text("تم اختيار صورة من منتقي Android. لا يُعرض اسم الملف أو مساره.")
                Text("نوع الصورة: ${mimeType ?: "غير معروف"}")
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it.take(VisionInputPolicy.MAX_PROMPT_LENGTH) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ما الذي تريد معرفته عن الصورة؟") },
                minLines = 2, maxLines = 4
            )
            Button(
                onClick = {
                    val uri = selectedUri ?: return@Button
                    val mime = mimeType ?: return@Button
                    scope.launch {
                        busy = true
                        status = "جارٍ تحليل الصورة على الجهاز…"
                        val request = VisionAnalysisRequest(
                            prompt,
                            VisionImageInput(uri.toString(), mime, null)
                        )
                        try {
                            when (val execution = toolRegistry.executeUserAction(
                                action = NoidUserToolActions.VISION_IMAGE_ANALYSIS,
                                grantedCapabilities = { readGrant(uri) },
                                userConfirmed = false
                            ) {
                                VisionResponseCollector().collectCompleteResponse(visionEngine, request)
                            }) {
                                is ToolExecutionResult.Completed -> {
                                    status = execution.value + if (execution.completionAudited) "" else
                                        "\\nتعذر تسجيل اكتمال التحليل في سجل التدقيق."
                                }
                                is ToolExecutionResult.Rejected -> status = execution.reason
                                is ToolExecutionResult.AuditUnavailable ->
                                    status = "تعذر تسجيل بدء التحليل؛ لم تُقرأ الصورة."
                                is ToolExecutionResult.Failed ->
                                    status = "تعذر تحليل الصورة (${execution.exceptionType}). تحقق من توافق النموذج وحجم الصورة."
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = selectedUri != null && !busy &&
                    visionStatus is VisionEngineStatus.Ready && prompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("تحليل الصورة") }
            if (busy) CircularProgressIndicator()
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                "تُقرأ الصورة عبر ContentResolver من content:// مع حد 12 MiB وفحص MIME والتوقيع والأبعاد. لا تُرفع الصور ولا تُحفظ في سجل المحادثة.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "التوافق والأداء والذاكرة والحرارة لم تُختبر بعد على هاتفك. عند فشل التحميل أو التحليل يبقى المحرك غير متاح.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
