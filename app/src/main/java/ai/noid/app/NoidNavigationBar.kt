package ai.noid.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class NoidDestination(val label: String, val icon: ImageVector) {
    CHAT("المحادثة", Icons.Filled.Chat),
    MEMORY("الذاكرة", Icons.Filled.Memory),
    LEARNING("التعلّم", Icons.Filled.School),
    SELF_DEVELOPMENT("تطوير NOID", Icons.Filled.Build),
    APK_INSPECTION("فحص APK", Icons.Filled.BugReport),
    PROJECT_SCAN("فحص مشروع", Icons.Filled.FolderOpen),
    PROJECT_FILES("ملفات المشروع", Icons.Filled.Folder),
    AUDIT("سجل الأمان", Icons.Filled.Security),
    MODEL("محرك AI", Icons.Filled.SmartToy),
    CHAT_SEARCH("بحث", Icons.Filled.Search),
    VISION("تحليل الصور", Icons.Filled.Image),
    REMOTE("الاتصال البعيد", Icons.Filled.Cloud)
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
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(imageVector = destination.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(destination.label, maxLines = 1)
                }
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
