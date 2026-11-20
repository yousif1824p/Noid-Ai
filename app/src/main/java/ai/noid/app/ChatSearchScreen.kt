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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import java.text.DateFormat
import java.util.Date

@Composable
fun ChatSearchScreen(viewModel: ChatViewModel) {
    var query by remember { mutableStateOf("") }
    var hasSearched by remember { mutableStateOf(false) }
    val results by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val error by viewModel.searchError.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("البحث في المحادثات", style = MaterialTheme.typography.headlineSmall)
        Text(
            "بحث محلي في الرسائل المحفوظة على هذا الجهاز. لا تُرسل كلمات البحث إلى خادم.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(120) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ابحث عن كلمة أو عبارة") },
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    hasSearched = true
                    viewModel.searchConversation(query)
                },
                enabled = query.isNotBlank() && !isSearching
            ) { Text(if (isSearching) "جارٍ البحث…" else "بحث") }
            TextButton(onClick = {
                query = ""
                hasSearched = false
                viewModel.clearSearch()
            }) { Text("مسح") }
        }
        if (error != null) {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        } else if (results.isEmpty()) {
            Text(
                if (query.isBlank()) "أدخل عبارة ثم اضغط بحث."
                else if (isSearching) "جارٍ البحث في السجل…"
                else if (hasSearched) "لم نعثر على رسائل تطابق هذه العبارة."
                else "اضغط بحث لعرض الرسائل المطابقة.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text("النتائج: ${results.size}", style = MaterialTheme.typography.labelLarge)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(results, key = { it.id }) { message ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                if (message.fromUser) "أنت" else "NOID",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(message.text, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                DateFormat.getDateTimeInstance().format(Date(message.createdAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
