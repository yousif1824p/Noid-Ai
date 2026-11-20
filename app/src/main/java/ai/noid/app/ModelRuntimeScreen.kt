package ai.noid.app

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ModelRuntimeScreen(
    status: AssistantEngineStatus,
    toolRegistry: ToolRegistry,
    onLoadModel: suspend (Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            loading = true
            error = null
            scope.launch {
                try {
                    val hadPersistedReadGrant = context.contentResolver.persistedUriPermissions.any {
                        it.uri == uri && it.isReadPermission
                    }
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (failure: SecurityException) {
                        throw IllegalStateException("تعذر تثبيت إذن قراءة ملف النموذج المحدد.", failure)
                    }
                    try {
                        val result = toolRegistry.executeUserAction(
                            action = NoidUserToolActions.TEXT_MODEL_IMPORT,
                            grantedCapabilities = {
                                val hasPersistedReadGrant =
                                    context.contentResolver.persistedUriPermissions.any {
                                        it.uri == uri && it.isReadPermission
                                    }
                                if (hasPersistedReadGrant) {
                                    setOf(ToolCapability.READ_FILES)
                                } else {
                                    emptySet()
                                }
                            },
                            userConfirmed = false
                        ) {
                            onLoadModel(uri)
                        }
                        when (result) {
                            is ToolExecutionResult.Completed -> Unit
                            is ToolExecutionResult.Rejected ->
                                throw IllegalStateException(result.reason)
                            is ToolExecutionResult.AuditUnavailable ->
                                throw IllegalStateException("تعذر تسجيل بدء الاستيراد؛ لم يُنفذ الاستيراد.")
                            is ToolExecutionResult.Failed ->
                                throw IllegalStateException("فشل استيراد النموذج (${result.exceptionType}).")
                        }
                    } finally {
                        if (PersistedUriReadGrantPolicy.shouldReleaseAfterAttempt(hadPersistedReadGrant)) {
                            runCatching {
                                context.contentResolver.releasePersistableUriPermission(
                                    uri,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message?.take(240) ?: "تعذر تحميل النموذج."
                } finally {
                    loading = false
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "محرك الذكاء الاصطناعي المحلي",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                when (status) {
                    AssistantEngineStatus.NotConfigured -> "الحالة: لم يتم تحميل نموذج"
                    is AssistantEngineStatus.Ready -> "الحالة: جاهز — ${status.modelId}"
                    is AssistantEngineStatus.Unavailable -> "الحالة: غير متاح — ${status.reason}"
                },
                color = if (status is AssistantEngineStatus.Ready) Color(0xFF81C995) else Color(0xFFFFC66D),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "يستخدم NOID محرك LiteRT-LM الرسمي من Google AI Edge لتشغيل نموذج نصي محلياً على الهاتف. لا تُرسل المحادثات إلى خادم."
            )
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (status is AssistantEngineStatus.Ready) "استبدال النموذج" else "اختيار ملف نموذج .litertlm")
            }
            if (loading) {
                CircularProgressIndicator()
                Text("جارٍ نسخ النموذج والتحقق من تحميله. قد يستغرق ذلك وقتاً ومساحة كبيرين.")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Spacer(Modifier.height(4.dp))
            Text("طريقة الإعداد", fontWeight = FontWeight.Bold)
            Text("• اختر ملفاً بامتداد .litertlm من مساحة التخزين عبر منتقي Android.")
            Text("• ينسخ التطبيق الملف إلى مساحة خاصة به؛ لا يحتاج إلى صلاحية وصول شاملة للملفات.")
            Text("• الحد الأقصى للاستيراد 8 GiB، مع اشتراط مساحة حرة إضافية قبل النسخ.")
            Text("• يبدأ التحميل على خيط خلفي مخصص وباستخدام CPU لتجنب افتراض دعم GPU/NPU.")
            Text("• يجب أن يكون النموذج متوافقاً فعلياً مع LiteRT-LM؛ الامتداد وحده لا يثبت صحة الملف.")
            Text("• لا يُحمّل أي نموذج تلقائياً ولا يُنزّل من الإنترنت.")

            Spacer(Modifier.height(4.dp))
            Text(
                "دعم النموذج وسرعته واستهلاك الذاكرة تعتمد على الملف والجهاز. لم يتم اختبار الأداء على هاتفك بعد.",
                color = Color(0xFF9AA0AA)
            )
        }
    }
}
