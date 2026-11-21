package ai.noid.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LearningScreen(viewModel: LearningViewModel) {
    val pending by viewModel.pending.collectAsState(initial = emptyList())
    val message by viewModel.message.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("التعلّم بمراجعتك", style = MaterialTheme.typography.titleLarge)
        Text(
            "أضف درساً أو تصحيحاً كمقترح. لن يستخدمه NOID في الإجابات حتى توافق عليه. " +
                "هذه ذاكرة معرفة مسترجعة وليست تدريباً لأوزان النموذج.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(title, { title = it }, label = { Text("عنوان المعرفة") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        OutlinedTextField(content, { content = it }, label = { Text("المحتوى") }, modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = !busy)
        OutlinedTextField(source, { source = it }, label = { Text("المصدر أو سبب الاقتراح") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Button(
            onClick = {
                viewModel.propose(title, content, source)
                title = ""
                content = ""
                source = ""
            },
            enabled = !busy && title.isNotBlank() && content.isNotBlank() && source.isNotBlank()
        ) { Text("إرسال للمراجعة") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text("اقتراحات تنتظر موافقتك: ${pending.size}", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pending, key = { it.id }) { record ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(record.title, style = MaterialTheme.typography.titleMedium)
                        Text(record.content, style = MaterialTheme.typography.bodyMedium)
                        Text("المصدر: ${record.source}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.approve(record.id) }, enabled = !busy) {
                                Text("موافقة")
                            }
                            TextButton(onClick = { viewModel.reject(record.id) }, enabled = !busy) {
                                Text("رفض")
                            }
                        }
                    }
                }
            }
        }
    }
}
