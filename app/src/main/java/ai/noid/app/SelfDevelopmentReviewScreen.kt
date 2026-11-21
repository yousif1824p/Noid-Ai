package ai.noid.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val MAX_SNAPSHOT_PREVIEW_CHARS = 6_000
private const val MAX_DIFF_PREVIEW_LINES = 80

@Composable
fun SelfDevelopmentReviewScreen(viewModel: SelfDevelopmentReviewViewModel) {
    val pending by viewModel.pending.collectAsState(initial = emptyList())
    val recent by viewModel.recent.collectAsState()
    val message by viewModel.message.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val expandedSnapshots = remember { mutableStateMapOf<Long, Boolean>() }
    var pendingApply by remember { mutableStateOf<SelfDevelopmentProposal?>(null) }

    pendingApply?.let { proposal ->
        val completeDiff = ProjectTextDiff.compare(proposal.originalContent, proposal.proposedContent)
        AlertDialog(
            onDismissRequest = { if (!busy) pendingApply = null },
            title = { Text("مراجعة التغيير الكامل قبل التطبيق") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("الملف: ${proposal.relativePath}")
                    Text("السبب: ${proposal.rationale}")
                    Text("البصمة الأصلية: ${proposal.originalSha256}")
                    Text("البصمة المقترحة: ${proposal.proposedSha256}")
                    Text("الأسطر المضافة: ${completeDiff.additions} · المحذوفة: ${completeDiff.removals}")
                    completeDiff.lines.forEach { line ->
                        val prefix = when (line.kind) {
                            ProjectDiffKind.ADDED -> "+ "
                            ProjectDiffKind.REMOVED -> "- "
                            ProjectDiffKind.UNCHANGED -> "  "
                        }
                        Text(prefix + line.text, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "لن يُكتب الملف إلا بعد تأكيدك. سيُعاد فحص المصدر، وتُحفظ نسخة احتياطية، ثم يُتحقق من المحتوى المكتوب.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        pendingApply = null
                        viewModel.apply(proposal.id)
                    }
                ) { Text("تأكيد التطبيق") }
            },
            dismissButton = {
                TextButton(onClick = { pendingApply = null }, enabled = !busy) { Text("إلغاء") }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("مراجعة تطوير NOID", style = MaterialTheme.typography.titleLarge)
        Text(
            "تُحفظ المقترحات منفصلة عن ملفات المشروع. الموافقة وحدها لا تكتب الملفات؛ " +
                "يُتاح التطبيق لاحقاً بعد عرض المقارنة الكاملة وتأكيد مستقل وإعادة فحص المصدر.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text("بانتظار المراجعة: ${pending.size}", style = MaterialTheme.typography.titleMedium)
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (recent.isEmpty()) {
            Text("لا توجد مقترحات محفوظة بعد.", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(recent, key = { it.id }) { proposal ->
                val isPending = proposal.status == SelfDevelopmentProposal.STATUS_PENDING_REVIEW
                val expanded = expandedSnapshots[proposal.id] == true
                val diff = remember(proposal.originalContent, proposal.proposedContent) {
                    ProjectTextDiff.compare(proposal.originalContent, proposal.proposedContent)
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text(proposal.title, style = MaterialTheme.typography.titleMedium)
                        Text("المسار: ${proposal.relativePath}", style = MaterialTheme.typography.bodySmall)
                        Text("السبب: ${proposal.rationale}", style = MaterialTheme.typography.bodyMedium)
                        Text("الحالة: ${statusLabel(proposal.status)}", style = MaterialTheme.typography.labelMedium)
                        Text("SHA-256 الأصلي: ${proposal.originalSha256}", style = MaterialTheme.typography.bodySmall)
                        Text("SHA-256 المقترح: ${proposal.proposedSha256}", style = MaterialTheme.typography.bodySmall)
                        if (diff.truncated) {
                            Text("المقارنة التفصيلية غير متاحة: يتجاوز الملف حد 250 سطراً.")
                        } else {
                            Text("الأسطر المضافة: ${diff.additions} · المحذوفة: ${diff.removals}")
                            diff.lines.take(MAX_DIFF_PREVIEW_LINES).forEach { line ->
                                val prefix = when (line.kind) {
                                    ProjectDiffKind.ADDED -> "+ "
                                    ProjectDiffKind.REMOVED -> "- "
                                    ProjectDiffKind.UNCHANGED -> "  "
                                }
                                Text(prefix + line.text, style = MaterialTheme.typography.bodySmall)
                            }
                            if (diff.lines.size > MAX_DIFF_PREVIEW_LINES) {
                                Text("عُرضت أول ${MAX_DIFF_PREVIEW_LINES} أسطر من المقارنة فقط.")
                            }
                        }
                        TextButton(
                            onClick = { expandedSnapshots[proposal.id] = !expanded },
                            enabled = !busy
                        ) {
                            Text(if (expanded) "إخفاء لقطتي المحتوى" else "عرض لقطتي المحتوى")
                        }
                        if (expanded) {
                            Text("المحتوى الأصلي", style = MaterialTheme.typography.titleSmall)
                            Text(proposal.originalContent.take(MAX_SNAPSHOT_PREVIEW_CHARS))
                            if (proposal.originalContent.length > MAX_SNAPSHOT_PREVIEW_CHARS) {
                                Text("اقتُطع العرض بعد 6000 محرف.")
                            }
                            Text("المحتوى المقترح", style = MaterialTheme.typography.titleSmall)
                            Text(proposal.proposedContent.take(MAX_SNAPSHOT_PREVIEW_CHARS))
                            if (proposal.proposedContent.length > MAX_SNAPSHOT_PREVIEW_CHARS) {
                                Text("اقتُطع العرض بعد 6000 محرف.")
                            }
                        }
                        if (isPending) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.approve(proposal.id) },
                                    enabled = !busy
                                ) { Text("موافقة للمراجعة اللاحقة") }
                                TextButton(
                                    onClick = { viewModel.reject(proposal.id) },
                                    enabled = !busy
                                ) { Text("رفض") }
                            }
                        } else if (proposal.status == SelfDevelopmentProposal.STATUS_APPROVED) {
                            Button(
                                onClick = { pendingApply = proposal },
                                enabled = !busy
                            ) { Text("مراجعة المقارنة الكاملة ثم التطبيق") }
                        }
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    SelfDevelopmentProposal.STATUS_PENDING_REVIEW -> "بانتظار المراجعة"
    SelfDevelopmentProposal.STATUS_APPROVED -> "موافق عليه للمراجعة اللاحقة"
    SelfDevelopmentProposal.STATUS_REJECTED -> "مرفوض"
    SelfDevelopmentProposal.STATUS_APPLYING -> "جارٍ التطبيق"
    SelfDevelopmentProposal.STATUS_APPLIED -> "طُبّق"
    SelfDevelopmentProposal.STATUS_STALE -> "تغير المصدر؛ رُفض التطبيق"
    SelfDevelopmentProposal.STATUS_APPLY_FAILED -> "فشل التطبيق"
    SelfDevelopmentProposal.STATUS_APPLY_INTERRUPTED -> "توقف التطبيق؛ يلزم فحص يدوي"
    else -> "حالة غير معروفة"
}
