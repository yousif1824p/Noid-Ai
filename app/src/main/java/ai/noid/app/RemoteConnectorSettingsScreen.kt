package ai.noid.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun RemoteConnectorSettingsScreen(
    settings: RemoteConnectorSettings,
    credentialStore: AndroidKeystoreRemoteCredentialStore,
    onSettingsSaved: (RemoteConnectorSettings) -> Unit
) {
    var enabled by remember(settings) { mutableStateOf(settings.enabled) }
    var endpoint by remember(settings) { mutableStateOf(settings.endpoint) }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var hasCredential by remember {
        mutableStateOf(runCatching { credentialStore.readBearerToken() != null }.getOrDefault(false))
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("الاتصال الاختياري بخادم خارجي", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text(
            "يبقى NOID محلياً افتراضياً. لا يُرسل أي شيء إلى الخادم إلا عند تفعيل الاتصال ثم اختيار زر «بعيد» عند إرسال الرسالة.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("السماح بالاتصال البعيد", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Text(
                    "التفعيل لا يرسل البيانات وحده؛ كل طلب بعيد يحتاج إلى ضغط زر الإرسال البعيد.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it.take(RemoteConnectionPolicy.MAX_ENDPOINT_CHARS) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("عنوان HTTPS للخادم") },
            placeholder = { Text("https://server.example/v1/chat") },
            singleLine = true
        )
        Text(
            "يُرفض HTTP والعناوين التي تحتوي بيانات دخول أو معاملات URL. يجب أن يتوافق الخادم مع بروتوكول NOID SSE.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = {
                try {
                    val validated = RemoteConnectorSettingsPolicy.validate(
                        RemoteConnectorSettings(enabled, endpoint)
                    )
                    onSettingsSaved(validated)
                    status = "تم حفظ إعدادات الاتصال."
                } catch (failure: IllegalArgumentException) {
                    status = failure.message ?: "الإعدادات غير صالحة."
                } catch (failure: IllegalStateException) {
                    status = failure.message ?: "تعذر حفظ الإعدادات."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("حفظ إعدادات الاتصال") }

        Spacer(Modifier.height(8.dp))
        Text("رمز الدخول الاختياري", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(
            if (hasCredential) "يوجد رمز محفوظ ومشفّر في Android Keystore." else "لا يوجد رمز دخول محفوظ.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.take(RemoteBearerTokenPolicy.MAX_TOKEN_CHARS) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Bearer token") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )
        Button(
            onClick = {
                try {
                    credentialStore.saveBearerToken(token)
                    token = ""
                    hasCredential = true
                    status = "حُفظ رمز الدخول مشفّراً."
                } catch (failure: IllegalArgumentException) {
                    status = failure.message ?: "رمز الدخول غير صالح."
                } catch (failure: Exception) {
                    status = failure.message ?: "تعذر حفظ رمز الدخول."
                }
            },
            enabled = token.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("حفظ الرمز المشفّر") }
        TextButton(
            onClick = {
                try {
                    credentialStore.clearBearerToken()
                    token = ""
                    hasCredential = false
                    status = "تم مسح رمز الدخول المحفوظ."
                } catch (failure: Exception) {
                    status = failure.message ?: "تعذر مسح رمز الدخول."
                }
            },
            enabled = hasCredential,
            modifier = Modifier.fillMaxWidth()
        ) { Text("مسح رمز الدخول") }
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text(
            "تنبيه: عند استخدام الاتصال البعيد، تصل رسائلك الأخيرة إلى الخادم الذي تحدده. لا ترسل كلمات مرور أو بيانات حساسة. محتوى الخادم يُعامل كنص غير موثوق ولا يمنحه صلاحية تشغيل أدوات الجهاز.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
