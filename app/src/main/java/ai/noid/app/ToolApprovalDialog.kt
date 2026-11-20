package ai.noid.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

/** Explicit, one-request-at-a-time approval surface. It never executes the tool itself. */
@Composable
fun ToolApprovalDialog(
    pending: PendingToolApproval,
    onApprove: () -> Unit,
    onCancel: () -> Unit
) {
    val argumentSummary = ToolApprovalArgumentFormatter.summarize(
        actionId = pending.actionId,
        arguments = pending.arguments
    )

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("طلب موافقة على إجراء") },
        text = {
            Column {
                Text(pending.description, fontWeight = FontWeight.SemiBold)
                Text("المعرّف: ${pending.actionId}")
                Text(argumentSummary)
                Text("تنتهي الموافقة تلقائياً بعد دقيقتين. الموافقة تخص هذا الطلب فقط.")
            }
        },
        confirmButton = {
            TextButton(onClick = onApprove) { Text("موافقة") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("رفض") }
        }
    )
}
