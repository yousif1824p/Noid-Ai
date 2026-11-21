package ai.noid.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class NoidDestination(val label: String) {
    CHAT("المحادثة"),
    MEMORY("الذاكرة"),
    LEARNING("التعلّم"),
    SELF_DEVELOPMENT("تطوير NOID"),
    APK_INSPECTION("فحص APK"),
    PROJECT_SCAN("فحص مشروع"),
    PROJECT_FILES("ملفات المشروع"),
    AUDIT("سجل الأمان"),
    MODEL("محرك AI"),
    CHAT_SEARCH("بحث"),
    VISION("تحليل الصور"),
    VOICE("الصوت"),
    REMOTE("الاتصال البعيد")
}

@Composable
fun NoidNavigationBar(
    selectedDestination: NoidDestination,
    showClearAction: Boolean,
    onDestinationSelected: (NoidDestination) -> Unit,
    onClearConversation: () -> Unit
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = "التنقل الرئيسي" },
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        NoidDestination.entries.forEach { destination ->
            TextButton(
                onClick = { onDestinationSelected(destination) },
                modifier = Modifier.semantics {
                    selected = destination == selectedDestination
                }
            ) {
                Text(destination.label, maxLines = 1)
            }
        }
        if (showClearAction) {
            TextButton(
                onClick = onClearConversation,
                modifier = Modifier.semantics {
                    contentDescription = "مسح سجل المحادثة"
                }
            ) {
                Text("مسح المحادثة", maxLines = 1)
            }
        }
    }
}
