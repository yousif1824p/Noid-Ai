package ai.noid.app

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ToolAuditScreen(repository: ToolAuditRepository) {
    val events by repository.recentEvents.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    val filteredEvents = events.filter { event ->
        query.isBlank() ||
            event.actionId.contains(query, ignoreCase = true) ||
            event.actionType.contains(query, ignoreCase = true) ||
            event.decision.contains(query, ignoreCase = true) ||
            event.details.orEmpty().contains(query, ignoreCase = true)
    }
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Text("سجل تدقيق إجراءات NOID", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "يعرض الأحداث التي سُجلت فعلياً في قاعدة البيانات: فحص APK الثابت وتصدير تقريره، وفحص مصدر المشروع، وعمليات تصفح وقراءة وإنشاء وتعديل واستعادة وإعادة تسمية ملفات المشروع الموصولة. لا يمثل هذا السجل نشاطاً شاملاً لكل أدوات NOID أو لكل نشاط الهاتف.",
            color = Color(0xFF9AA0AA),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AuditCount(label = "كل الأحداث", count = events.size.toString(), modifier = Modifier.weight(1f))
            AuditCount(
                label = "مسموح",
                count = events.count { it.decision == ToolDecision.ALLOW.name }.toString(),
                modifier = Modifier.weight(1f)
            )
            AuditCount(
                label = "مرفوض",
                count = events.count { it.decision == ToolDecision.DENY.name }.toString(),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ابحث في السجل") },
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        if (filteredEvents.isEmpty()) {
            Text(
                if (events.isEmpty()) "لا توجد أحداث مسجلة بعد." else "لا توجد أحداث تطابق البحث.",
                modifier = Modifier.padding(top = 20.dp),
                color = Color(0xFF9AA0AA)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredEvents, key = { it.id }) { event ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(event.actionType, fontWeight = FontWeight.Bold)
                                Text(
                                    decisionLabel(event.decision),
                                    color = if (event.decision == ToolDecision.ALLOW.name) Color(0xFF8BD5A5) else Color(0xFFFFB4AB),
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("المعرّف: ${event.actionId}", color = Color(0xFFB9C0CB), fontSize = 12.sp)
                            event.details?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, color = Color(0xFFE8EAED), fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                dateFormatter.format(Date(event.createdAt)),
                                color = Color(0xFF858B96),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditCount(label: String, count: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(count, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(label, color = Color(0xFF9AA0AA), fontSize = 11.sp)
        }
    }
}

private fun decisionLabel(decision: String): String = when (decision) {
    ToolDecision.ALLOW.name -> "مسموح"
    ToolDecision.REQUIRE_USER_CONFIRMATION.name -> "بانتظار موافقة"
    ToolDecision.DENY.name -> "مرفوض"
    else -> "غير معروف: $decision"
}
