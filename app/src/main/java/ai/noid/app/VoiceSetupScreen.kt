package ai.noid.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun VoiceSetupScreen(onRecognizedText: (String) -> Unit) {
    var status by remember { mutableStateOf("الإملاء الصوتي متوقف حتى تضغط زر البدء.") }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            val accepted = VoiceRecognitionTextPolicy.accept(spoken)
            if (accepted == null) {
                status = "لم يتم التقاط نص واضح. حاول مرة أخرى."
            } else {
                status = "تم تحويل الكلام إلى نص؛ راجعه قبل الإرسال."
                onRecognizedText(accepted)
            }
        } else {
            status = "أُلغيت جلسة الإملاء أو لم تُرجع نتيجة."
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("الصوت والتنشيط", style = MaterialTheme.typography.headlineSmall)
        Text(
            "الإملاء الصوتي متاح عبر واجهة التعرف التي يوفرها Android عند طلبك.",
            color = Color(0xFF9AA0AA), fontSize = 13.sp
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("إدخال صوتي يدوي", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text("«يا NOID»: لم يُدمج نموذج اكتشاف كلمة التنبيه المحلي بعد.")
                Text("صوت NOID الصادر: العربية الفصحى فقط؛ محرك النطق والتشغيل غير مهيأ بعد.")
                Text("الهوية الصوتية المختارة: اصطناعية إلكترونية ومحايدة جندرياً؛ لم تُعتمد عينة صوت نهائية.")
                Text("خدمة استماع دائمة: غير مفعّلة.")
                Text("اختصار الصوت: متاح من قائمة NOID في مشغل التطبيقات؛ يفتح هذه الشاشة دون تشغيل الميكروفون.")
            }
        }
        Button(
            onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-IQ")
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث إلى NOID")
                }
                try {
                    status = "بانتظار واجهة التعرف الصوتي من النظام…"
                    launcher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    status = "لا يوجد تطبيق تعرّف صوتي متاح على هذا الجهاز."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("بدء الإملاء الصوتي") }
        Text(status, color = Color(0xFFB9D4FF), fontSize = 13.sp)
        Text("سيعود النص إلى خانة المحادثة لتراجعه بنفسك قبل الإرسال.")
        Text("تنبيه الخصوصية", style = MaterialTheme.typography.titleMedium)
        Text("• يبدأ التعرف فقط بعد ضغطك الزر، ولا يوجد تسجيل في الخلفية.")
        Text("• نطلب من مزود النظام تفضيل المعالجة دون اتصال، لكن هذا الطلب ليس ضماناً؛ قد يستخدم المزود خدمته الشبكية.")
        Text("• لا تستخدم الإملاء لإدخال معلومات حساسة ما لم تكن تثق بمزود التعرف المثبت.")
        Text("• زر الإملاء لا يفعّل «يا NOID» ولا يمثل محرك KWS محلياً.")
        Spacer(Modifier.height(4.dp))
        Text(
            "التنشيط المستمر يحتاج لاحقاً إلى نموذج KWS محلي موثوق وخدمة ميكروفون أمامية بإشعار ظاهر، " +
                "مع صلاحيات Android 14+ واختبار استهلاك البطارية.",
            color = Color(0xFF9AA0AA), fontSize = 12.sp
        )
    }
}
