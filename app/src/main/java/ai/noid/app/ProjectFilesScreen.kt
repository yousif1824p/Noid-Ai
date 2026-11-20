package ai.noid.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ProjectFilesScreen(
    toolRegistry: ToolRegistry,
    onProjectTreeSelected: (Uri) -> Unit,
    onRequestProjectFileRead: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) {
        ProjectFileRepository(context, File(context.filesDir, "project-backups"))
    }

    var rootUri by remember { mutableStateOf<Uri?>(null) }
    var currentDirectory by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<ProjectFileEntry>>(emptyList()) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var originalText by remember { mutableStateOf("") }
    var editedText by remember { mutableStateOf("") }
    var lastBackup by remember { mutableStateOf<ProjectFileBackup?>(null) }
    var backupHistory by remember { mutableStateOf<List<ProjectFileBackup>>(emptyList()) }
    var selectedBackupForRestore by remember { mutableStateOf<ProjectFileBackup?>(null) }
    var newFileName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    val diffPreview = remember(originalText, editedText) {
        ProjectTextDiff.compare(originalText, editedText)
    }

    // Never manufacture capabilities: derive them from Android's persisted SAF grant
    // for this exact selected tree URI immediately before authorization.
    fun grantedCapabilities(uri: Uri): Set<ToolCapability> {
        val grant = context.contentResolver.persistedUriPermissions
            .firstOrNull { it.uri == uri }
            ?: return emptySet()
        return buildSet {
            if (grant.isReadPermission) add(ToolCapability.READ_FILES)
            if (grant.isWritePermission) add(ToolCapability.WRITE_FILES)
        }
    }

    fun loadDirectory(uri: Uri, path: String, selectedAfterLoad: String? = null) {
        busy = true
        error = null
        scope.launch {

            try {
            val action = NoidUserToolActions.PROJECT_DIRECTORY_LIST
            when (val result = toolRegistry.executeUserAction(
                action,
                { grantedCapabilities(uri) },
                userConfirmed = false,
            ) {
                withContext(Dispatchers.IO) { repository.list(uri, path) }
            }) {
                is ToolExecutionResult.Completed -> {
                    entries = result.value
                    currentDirectory = path
                    selectedPath = selectedAfterLoad
                    if (!result.completionAudited) error = "تم العرض، لكن تعذر تسجيل اكتمال العملية في سجل التدقيق."
                }
                is ToolExecutionResult.Rejected -> error = result.reason
                is ToolExecutionResult.AuditUnavailable -> error = "تعذر تسجيل بدء العملية؛ لم يُفتح المجلد."
                is ToolExecutionResult.Failed -> error = "تعذر فتح المجلد (${result.exceptionType})."
            }
            } finally {
                busy = false
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                rootUri = uri
                onProjectTreeSelected(uri)
                lastBackup = null
                loadDirectory(uri, "")
            } catch (failure: SecurityException) {
                error = "تعذر الحصول على إذن قراءة وكتابة المجلد المختار."
            }
        }
    }

    if (confirmSave) {
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text("مراجعة التعديل قبل الحفظ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("الملف: ${selectedPath.orEmpty()}")
                    if (diffPreview.truncated) {
                        Text("الملف يتجاوز حد المقارنة التفصيلية (250 سطراً)؛ عدد الأسطر المتغيرة غير محسوب.")
                    } else {
                        Text("الأسطر المضافة: ${diffPreview.additions} · المحذوفة: ${diffPreview.removals}")
                        diffPreview.lines.take(12).forEach { line ->
                            val prefix = when (line.kind) {
                                ProjectDiffKind.ADDED -> "+ "
                                ProjectDiffKind.REMOVED -> "- "
                                ProjectDiffKind.UNCHANGED -> "  "
                            }
                            Text(prefix + line.text, fontSize = 10.sp, maxLines = 2)
                        }
                        if (diffPreview.lines.size > 12) Text("… معاينة أول 12 سطراً فقط.")
                    }
                    Text(
                        "سيُنشئ NOID نسخة احتياطية خاصة قبل الكتابة. راجع المعاينة والنص في المحرر؛ الحفظ سيستبدل محتوى الملف.",
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSave = false
                    val uri = rootUri
                    val path = selectedPath
                    if (uri != null && path != null) {
                        busy = true
                        scope.launch {

                            try {
                            val saveAction = NoidUserToolActions.PROJECT_FILE_SAVE
                            val result = toolRegistry.executeUserAction(
                                saveAction,
                                { grantedCapabilities(uri) },
                                userConfirmed = true,
                            ) {
                                withContext(Dispatchers.IO) {
                                    repository.writeText(uri, path, editedText)
                                }
                            }
                            when (result) {
                                is ToolExecutionResult.Completed -> {
                                    lastBackup = result.value
                                    backupHistory = withContext(Dispatchers.IO) {
                                        repository.listBackups(path)
                                    }
                                    originalText = editedText
                                    if (!result.completionAudited) {
                                        error = "حُفظ الملف، لكن تعذر تسجيل اكتمال العملية."
                                    }
                                }
                                is ToolExecutionResult.Rejected -> error = result.reason
                                is ToolExecutionResult.AuditUnavailable -> error = "تعذر تسجيل بدء الحفظ؛ لم يتغير الملف."
                                is ToolExecutionResult.Failed -> error = "تعذر حفظ الملف (" + result.exceptionType + ")."
                            }
                            } finally {
                                busy = false
                            }
                        }
                    }
                }) { Text("تأكيد الحفظ") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSave = false }) { Text("رجوع للمحرر") }
            }
        )
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("استعادة النسخة السابقة؟") },
            text = { Text("سيُستبدل محتوى الملف الحالي بالنسخة الاحتياطية المحفوظة داخل مساحة NOID.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    val uri = rootUri
                    val backup = selectedBackupForRestore ?: lastBackup
                    selectedBackupForRestore = null
                    if (uri != null && backup != null) {
                        busy = true
                        scope.launch {

                            try {
                            val restoreAction = NoidUserToolActions.PROJECT_FILE_RESTORE
                            val result = toolRegistry.executeUserAction(
                                restoreAction,
                                { grantedCapabilities(uri) },
                                userConfirmed = true,
                            ) {
                                val rollbackBackup = withContext(Dispatchers.IO) {
                                    repository.restoreBackup(uri, backup)
                                }
                                val restored = withContext(Dispatchers.IO) {
                                    repository.readText(uri, backup.relativePath)
                                }
                                rollbackBackup to restored
                            }
                            when (result) {
                                is ToolExecutionResult.Completed -> {
                                    lastBackup = result.value.first
                                    backupHistory = withContext(Dispatchers.IO) {
                                        repository.listBackups(backup.relativePath)
                                    }
                                    originalText = result.value.second
                                    editedText = result.value.second
                                    if (!result.completionAudited) {
                                        error = "استُعيد الملف، لكن تعذر تسجيل اكتمال العملية."
                                    }
                                }
                                is ToolExecutionResult.Rejected -> error = result.reason
                                is ToolExecutionResult.AuditUnavailable -> error = "تعذر تسجيل بدء الاستعادة؛ لم يتغير الملف."
                                is ToolExecutionResult.Failed -> error = "تعذرت الاستعادة (" + result.exceptionType + ")."
                            }
                            } finally {
                                busy = false
                            }
                        }
                    }
                }) { Text("استعادة") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("إلغاء") } }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("إعادة تسمية الملف") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text("الاسم الجديد مع الامتداد") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = rootUri
                    val oldPath = selectedPath
                    val newName = renameValue.trim()
                    if (uri != null && oldPath != null) {
                        val action = NoidUserToolActions.PROJECT_FILE_RENAME
                        showRenameDialog = false
                        busy = true
                        scope.launch {

                            try {
                            val result = toolRegistry.executeUserAction(
                                action,
                                { grantedCapabilities(uri) },
                                userConfirmed = true,
                            ) {
                                val renamed = withContext(Dispatchers.IO) {
                                    repository.rename(uri, oldPath, newName)
                                }
                                if (!renamed) throw IllegalStateException("تعذر إعادة تسمية الملف لدى موفر التخزين.")
                                renamed
                            }
                            when (result) {
                                is ToolExecutionResult.Completed -> {
                                    val parent = oldPath.substringBeforeLast('/', "")
                                    val newPath = listOf(parent, newName).filter { it.isNotBlank() }.joinToString("/")
                                    if (!result.completionAudited) {
                                        error = "أُعيدت التسمية، لكن تعذر تسجيل اكتمال العملية."
                                    }
                                    loadDirectory(uri, parent, newPath)
                                }
                                is ToolExecutionResult.Rejected -> error = result.reason
                                is ToolExecutionResult.AuditUnavailable -> error = "تعذر تسجيل بدء إعادة التسمية؛ لم يتغير الملف."
                                is ToolExecutionResult.Failed -> error = "تعذرت إعادة التسمية (" + result.exceptionType + ")."
                            }
                            } finally {
                                busy = false
                            }
                        }
                    }
                }) { Text("تأكيد إعادة التسمية") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("إلغاء") }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text("مدير ملفات المشاريع", style = MaterialTheme.typography.titleLarge)
        Text(
            "يعمل فقط داخل المجلد الذي تختاره بنفسك. لا يصل إلى المساحة الخاصة لتطبيق آخر ولا يتجاوز أذونات Android.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Button(
            onClick = { folderPicker.launch(null) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (rootUri == null) "اختيار مجلد مشروع" else "تغيير مجلد المشروع") }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (busy) Text("جارٍ تنفيذ العملية…", color = MaterialTheme.colorScheme.primary)

        if (rootUri != null) {
            if (selectedPath == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "المجلد: /${currentDirectory}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (currentDirectory.isNotEmpty()) {
                        OutlinedButton(onClick = {
                            val parent = currentDirectory.substringBeforeLast('/', "")
                            rootUri?.let { loadDirectory(it, parent) }
                        }) { Text("للخلف") }
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(entries, key = { it.relativePath }) { entry ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            TextButton(
                                onClick = {
                                    val uri = rootUri ?: return@TextButton
                                    if (entry.isDirectory) {
                                        loadDirectory(uri, entry.relativePath)
                                    } else {
                                        busy = true
                                        scope.launch {

                                            try {
                                            val readAction = NoidUserToolActions.PROJECT_FILE_READ
                                            when (val result = toolRegistry.executeUserAction(
                                                readAction,
                                                { grantedCapabilities(uri) },
                                                userConfirmed = false,
                                            ) {
                                                withContext(Dispatchers.IO) {
                                                    repository.readText(uri, entry.relativePath)
                                                }
                                            }) {
                                                is ToolExecutionResult.Completed -> {
                                                    selectedPath = entry.relativePath
                                                    backupHistory = withContext(Dispatchers.IO) {
                                                        repository.listBackups(entry.relativePath)
                                                    }
                                                    originalText = result.value
                                                    editedText = result.value
                                                    error = if (result.completionAudited) null
                                                        else "قُرئ الملف، لكن تعذر تسجيل اكتمال القراءة."
                                                }
                                                is ToolExecutionResult.Rejected -> error = result.reason
                                                is ToolExecutionResult.AuditUnavailable -> error = "تعذر تسجيل بدء القراءة؛ لم يُفتح الملف."
                                                is ToolExecutionResult.Failed -> error = "تعذرت قراءة الملف (${result.exceptionType})."
                                            }
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    (if (entry.isDirectory) "📁 " else "📄 ") + entry.name +
                                        if (!entry.isDirectory) " · ${entry.sizeBytes} B" else "",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text("اسم ملف نصي جديد") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = {
                        val uri = rootUri ?: return@OutlinedButton
                        val name = newFileName.trim()
                        if (name.isBlank() || !ProjectFilePolicy.isEditableTextFile(name)) {
                            error = "أدخل اسماً بامتداد نصي مدعوم مثل .kt أو .xml."
                        } else {
                            val action = NoidUserToolActions.PROJECT_FILE_CREATE
                            busy = true
                            scope.launch {

                                try {
                                val result = toolRegistry.executeUserAction(
                                    action,
                                    { grantedCapabilities(uri) },
                                    userConfirmed = true,
                                ) {
                                    withContext(Dispatchers.IO) {
                                        repository.createTextFile(
                                            uri,
                                            listOf(currentDirectory, name).filter { it.isNotBlank() }.joinToString("/"),
                                            ""
                                        )
                                    }
                                }
                                when (result) {
                                    is ToolExecutionResult.Completed -> {
                                        newFileName = ""
                                        if (!result.completionAudited) {
                                            error = "أُنشئ الملف، لكن تعذر تسجيل اكتمال العملية."
                                        }
                                        loadDirectory(uri, currentDirectory)
                                    }
                                    is ToolExecutionResult.Rejected -> error = result.reason
                                    is ToolExecutionResult.AuditUnavailable -> error = "تعذر تسجيل بدء الإنشاء؛ لم يُنشأ الملف."
                                    is ToolExecutionResult.Failed -> error = "تعذر إنشاء الملف (" + result.exceptionType + ")."
                                }
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("إنشاء ملف فارغ") }
            } else {
                Text(selectedPath.orEmpty(), style = MaterialTheme.typography.titleSmall)
                OutlinedButton(
                    onClick = { selectedPath?.let(onRequestProjectFileRead) },
                    enabled = !busy && selectedPath != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("طلب قراءة الملف بواسطة NOID") }
                OutlinedButton(
                    onClick = {
                        renameValue = selectedPath?.substringAfterLast('/') ?: ""
                        showRenameDialog = true
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("إعادة تسمية الملف") }
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    label = { Text("محتوى الملف") },
                    minLines = 8
                )
                Text(
                    if (editedText == originalText) "لا توجد تغييرات غير محفوظة"
                    else "هناك تغييرات غير محفوظة · ${originalText.length} ← ${editedText.length} حرف",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Button(
                    onClick = { confirmSave = true },
                    enabled = !busy && editedText != originalText,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("مراجعة وحفظ مع نسخة احتياطية") }
                if (backupHistory.isNotEmpty()) {
                    Text("سجل النسخ الاحتياطية (${backupHistory.size})", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(
                        modifier = Modifier.weight(0.7f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(backupHistory, key = { it.backupFile.name }) { backup ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "نسخة · ${backup.createdAt}",
                                        modifier = Modifier.weight(1f),
                                        fontSize = 11.sp
                                    )
                                    TextButton(
                                        onClick = {
                                            selectedBackupForRestore = backup
                                            confirmRestore = true
                                        },
                                        enabled = !busy
                                    ) { Text("استعادة") }
                                }
                            }
                        }
                    }
                } else {
                    Text("لا توجد نسخ احتياطية محفوظة لهذا الملف بعد.", fontSize = 11.sp)
                }
                TextButton(onClick = {
                    selectedPath = null
                    rootUri?.let { loadDirectory(it, currentDirectory) }
                }) { Text("العودة إلى قائمة الملفات") }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "يدعم تحرير الملفات النصية المحددة فقط، بحد أقصى 2 ميغابايت للملف.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}
