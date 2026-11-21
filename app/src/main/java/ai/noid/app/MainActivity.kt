package ai.noid.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (VoiceShortcutPolicy.shouldOpenVoiceScreen(intent.getStringExtra(VoiceShortcutPolicy.EXTRA_OPEN_SCREEN))) {
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = NoidDatabase.getInstance(applicationContext)
        val chatRepository = ChatRepository(database.chatMessageDao())
        val memoryRepository = MemoryRepository(database.userMemoryDao())
        val learningRepository = LearningRepository(database.learningRecordDao())
        val selfDevelopmentRepository = SelfDevelopmentProposalRepository(database.selfDevelopmentProposalDao())
        val toolAuditRepository = ToolAuditRepository(database.toolAuditDao())
        val selectedProjectTree = SelectedProjectTreeSession()
        val toolExecutionCoordinator = ToolExecutionCoordinator()
        val toolRegistry = ToolRegistry(toolExecutionCoordinator, toolAuditRepository)
        val projectFileRepository =
            ProjectFileRepository(applicationContext, java.io.File(filesDir, "project-backups"))
        toolRegistry.register(
            ProjectFileReadToolAdapter(
                applicationContext,
                selectedProjectTree,
                projectFileRepository
            )
        )
        toolRegistry.register(
            ProjectDirectoryListToolAdapter(
                applicationContext,
                selectedProjectTree,
                projectFileRepository
            )
        )
        toolRegistry.register(
            ProjectFileCreateToolAdapter(
                applicationContext,
                selectedProjectTree,
                projectFileRepository
            )
        )
        toolRegistry.register(
            ProjectFileEditToolAdapter(
                applicationContext,
                selectedProjectTree,
                projectFileRepository
            )
        )
        toolRegistry.register(
            ProjectFileRenameToolAdapter(
                applicationContext,
                selectedProjectTree,
                projectFileRepository
            )
        )
        toolRegistry.register(
            ProjectFileBackupListToolAdapter(
                applicationContext,
                selectedProjectTree,
                projectFileRepository
            )
        )
        toolRegistry.register(
            ProjectFileBackupRestoreToolAdapter(
                applicationContext,
                selectedProjectTree,
                projectFileRepository
            )
        )
        val engine = NoidEngineHolder.get(applicationContext)
        val visionEngine = NoidVisionEngineHolder.get(applicationContext)
        lifecycleScope.launch {
            engine.restoreLastModel()
            visionEngine.restoreLastModel()
        }
        val remoteCredentialStore = AndroidKeystoreRemoteCredentialStore(applicationContext)
        val remoteSettingsStore = RemoteConnectorSettingsStore(applicationContext)
        val remoteChatAdapter = RemoteChatTurnAdapter(
            bearerTokenProvider = { remoteCredentialStore.readBearerToken() }
        )
        val chatViewModel = ViewModelProvider(
            this,
            ChatViewModelFactory(
                chatRepository,
                memoryRepository,
                learningRepository,
                engine,
                toolRegistry,
                remoteChatAdapter
            )
        )[ChatViewModel::class.java]
        val memoryViewModel = ViewModelProvider(
            this,
            MemoryViewModelFactory(memoryRepository)
        )[MemoryViewModel::class.java]
        val learningViewModel = ViewModelProvider(
            this,
            LearningViewModelFactory(learningRepository)
        )[LearningViewModel::class.java]
        val selfDevelopmentReviewViewModel = ViewModelProvider(
            this,
            SelfDevelopmentReviewViewModelFactory(selfDevelopmentRepository)
        )[SelfDevelopmentReviewViewModel::class.java]

        setContent {
            val colors = darkColorScheme(
                primary = Color(0xFF8AB4F8),
                background = Color(0xFF101114),
                surface = Color(0xFF191B20),
                onBackground = Color(0xFFE8EAED),
                onSurface = Color(0xFFE8EAED)
            )
            MaterialTheme(colorScheme = colors) {
                NoidChatScreen(
                    viewModel = chatViewModel,
                    memoryViewModel = memoryViewModel,
                    learningViewModel = learningViewModel,
                    selfDevelopmentReviewViewModel = selfDevelopmentReviewViewModel,
                    toolAuditRepository = toolAuditRepository,
                    toolRegistry = toolRegistry,
                    engine = engine,
                    visionEngine = visionEngine,
                    initialShowVoiceScreen = VoiceShortcutPolicy.shouldOpenVoiceScreen(
                        intent?.getStringExtra(VoiceShortcutPolicy.EXTRA_OPEN_SCREEN)
                    ),
                    onProjectTreeSelected = { uri -> selectedProjectTree.select(uri) },
                    onRequestProjectFileRead = { path ->
                        val selection = selectedProjectTree.current()
                        if (selection != null) {
                            toolRegistry.requestApproval(
                                ProjectFileReadToolAdapter.ACTION_ID,
                                ToolRequest(
                                    mapOf(
                                        ProjectFileReadToolAdapter.ARG_SELECTION_ID to selection.selectionId,
                                        ProjectFileReadToolAdapter.ARG_PATH to path
                                    )
                                )
                            )
                        }
                    },
                    chatRepository = chatRepository,
                    remoteCredentialStore = remoteCredentialStore,
                    remoteSettingsStore = remoteSettingsStore
                )
            }
        }
    }
}

@Composable
private fun NoidChatScreen(
    viewModel: ChatViewModel,
    memoryViewModel: MemoryViewModel,
    learningViewModel: LearningViewModel,
    selfDevelopmentReviewViewModel: SelfDevelopmentReviewViewModel,
    toolAuditRepository: ToolAuditRepository,
    toolRegistry: ToolRegistry,
    engine: LiteRtLmAssistantEngine,
    visionEngine: LiteRtLmVisionEngine,
    initialShowVoiceScreen: Boolean = false,
    onProjectTreeSelected: (android.net.Uri) -> Unit,
    onRequestProjectFileRead: (String) -> Unit,
    chatRepository: ChatRepository,
    remoteCredentialStore: AndroidKeystoreRemoteCredentialStore,
    remoteSettingsStore: RemoteConnectorSettingsStore
) {
    val messages by viewModel.messages.collectAsState(initial = emptyList())
    val pendingToolApprovals by toolRegistry.pendingApprovals.collectAsState()
    val engineStatus by viewModel.engineStatus.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val error by viewModel.error.collectAsState()
    var draft by remember { mutableStateOf("") }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var showMemoryScreen by remember { mutableStateOf(false) }
    var showLearningScreen by remember { mutableStateOf(false) }
    var showSelfDevelopmentScreen by remember { mutableStateOf(false) }
    var showSecurityScreen by remember { mutableStateOf(false) }
    var showProjectScanScreen by remember { mutableStateOf(false) }
    var showProjectFilesScreen by remember { mutableStateOf(false) }
    var showAuditScreen by remember { mutableStateOf(false) }
    var showModelScreen by remember { mutableStateOf(false) }
    var showChatSearch by remember { mutableStateOf(false) }
    var showVisionScreen by remember { mutableStateOf(false) }
    var showVoiceScreen by remember { mutableStateOf(initialShowVoiceScreen) }
    var showRemoteSettings by remember { mutableStateOf(false) }
    var remoteSettings by remember { mutableStateOf(remoteSettingsStore.read()) }

    pendingToolApprovals.firstOrNull()?.let { pending ->
        ToolApprovalDialog(
            pending = pending,
            onApprove = { viewModel.approveToolApproval(pending.id) },
            onCancel = { viewModel.rejectToolApproval(pending.id) }
        )
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("مسح سجل المحادثة؟") },
            text = {
                Text("سيتم حذف جميع الرسائل المحفوظة محلياً من هذا الجهاز. لا يمكن التراجع عن هذه الخطوة.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirmation = false
                    viewModel.clearConversation()
                }) { Text("حذف") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("إلغاء") }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.noid_brand_mark),
                        contentDescription = "شعار NOID AI",
                        modifier = Modifier.size(38.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("NOID AI", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            when {
                                showMemoryScreen -> "ذاكرة يتحكم بها المستخدم"
                                showLearningScreen -> "معرفة يراجعها المستخدم"
                                showSelfDevelopmentScreen -> "مراجعة مقترحات تغييرات المشروع"
                                showSecurityScreen -> "تحليل أمني ثابت لملفات APK"
                                showProjectScanScreen -> "فحص ملفات مشروع تختاره"
                                showProjectFilesScreen -> "تصفح وتعديل ملفات المشروع"
                                showAuditScreen -> "سجل تدقيق الإجراءات المحلية"
                                showModelScreen -> "حالة محرك الذكاء الاصطناعي المحلي"
                                showChatSearch -> "بحث محلي في سجل المحادثات"
                                showVisionScreen -> "اختيار صورة والتحقق منها"
                                showVoiceScreen -> "إعداد الصوت والتنشيط"
                                showRemoteSettings -> "إعداد الاتصال البعيد الاختياري"
                                else -> "مساعدك الشخصي · تخزين محلي"
                            },
                            color = Color(0xFF9AA0AA),
                            fontSize = 12.sp
                        )
                    }
                }
                val selectedDestination = when {
                    showMemoryScreen -> NoidDestination.MEMORY
                    showLearningScreen -> NoidDestination.LEARNING
                    showSelfDevelopmentScreen -> NoidDestination.SELF_DEVELOPMENT
                    showSecurityScreen -> NoidDestination.APK_INSPECTION
                    showProjectScanScreen -> NoidDestination.PROJECT_SCAN
                    showProjectFilesScreen -> NoidDestination.PROJECT_FILES
                    showAuditScreen -> NoidDestination.AUDIT
                    showModelScreen -> NoidDestination.MODEL
                    showChatSearch -> NoidDestination.CHAT_SEARCH
                    showVisionScreen -> NoidDestination.VISION
                    showVoiceScreen -> NoidDestination.VOICE
                    showRemoteSettings -> NoidDestination.REMOTE
                    else -> NoidDestination.CHAT
                }
                NoidNavigationBar(
                    selectedDestination = selectedDestination,
                    showClearAction = selectedDestination == NoidDestination.CHAT && messages.isNotEmpty(),
                    onDestinationSelected = { destination ->
                        val next = if (destination == selectedDestination) NoidDestination.CHAT else destination
                        showMemoryScreen = next == NoidDestination.MEMORY
                        showLearningScreen = next == NoidDestination.LEARNING
                        showSelfDevelopmentScreen = next == NoidDestination.SELF_DEVELOPMENT
                        showSecurityScreen = next == NoidDestination.APK_INSPECTION
                        showProjectScanScreen = next == NoidDestination.PROJECT_SCAN
                        showProjectFilesScreen = next == NoidDestination.PROJECT_FILES
                        showAuditScreen = next == NoidDestination.AUDIT
                        showModelScreen = next == NoidDestination.MODEL
                        showChatSearch = next == NoidDestination.CHAT_SEARCH
                        showVisionScreen = next == NoidDestination.VISION
                        showVoiceScreen = next == NoidDestination.VOICE
                        showRemoteSettings = next == NoidDestination.REMOTE
                    },
                    onClearConversation = { showClearConfirmation = true }
                )
            }

            if (showMemoryScreen) {
                MemoryManagementScreen(viewModel = memoryViewModel)
            } else if (showLearningScreen) {
                LearningScreen(viewModel = learningViewModel)
            } else if (showSelfDevelopmentScreen) {
                SelfDevelopmentReviewScreen(viewModel = selfDevelopmentReviewViewModel)
            } else if (showSecurityScreen) {
                ApkInspectionScreen(toolRegistry = toolRegistry)
            } else if (showProjectScanScreen) {
                ProjectScanScreen(toolRegistry = toolRegistry)
            } else if (showProjectFilesScreen) {
                ProjectFilesScreen(
                    toolRegistry = toolRegistry,
                    onProjectTreeSelected = onProjectTreeSelected,
                    onRequestProjectFileRead = onRequestProjectFileRead
                )
            } else if (showAuditScreen) {
                ToolAuditScreen(repository = toolAuditRepository)
            } else if (showModelScreen) {
                ModelRuntimeScreen(status = engineStatus, toolRegistry = toolRegistry, onLoadModel = { uri ->
                    engine.importAndLoadModel(uri)
                    Unit
                })
            } else if (showChatSearch) {
                ChatSearchScreen(viewModel = viewModel)
            } else if (showVisionScreen) {
                VisionImagePickerScreen(toolRegistry = toolRegistry, visionEngine = visionEngine)
            } else if (showVoiceScreen) {
                VoiceSetupScreen(onRecognizedText = { recognized ->
                    draft = recognized
                    showVoiceScreen = false
                })
            } else if (showRemoteSettings) {
                RemoteConnectorSettingsScreen(
                    settings = remoteSettings,
                    credentialStore = remoteCredentialStore,
                    onSettingsSaved = { updated ->
                        remoteSettingsStore.save(updated)
                        remoteSettings = updated
                        showRemoteSettings = false
                    }
                )
            } else {
                ChatContent(
                    messages = messages,
                    engineStatus = engineStatus,
                    isGenerating = isGenerating,
                    error = error,
                    onDismissError = viewModel::dismissError,
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = {
                        viewModel.sendMessage(draft)
                        draft = ""
                    },
                    remoteEnabled = remoteSettings.enabled,
                    onSendRemote = {
                        viewModel.sendRemoteMessage(draft, remoteSettings.endpoint, enabled = true)
                        draft = ""
                    }
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.ChatContent(
    messages: List<ChatMessage>,
    engineStatus: AssistantEngineStatus,
    isGenerating: Boolean,
    error: String?,
    onDismissError: () -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    remoteEnabled: Boolean,
    onSendRemote: () -> Unit
) {
    Text(
        text = when (engineStatus) {
            AssistantEngineStatus.NotConfigured -> "محرك الذكاء الاصطناعي غير مهيأ بعد — رسائلك تُحفظ محلياً."
            is AssistantEngineStatus.Ready -> "المحرك جاهز: ${engineStatus.modelId}"
            is AssistantEngineStatus.Unavailable -> "المحرك غير متاح: ${engineStatus.reason}"
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = Color(0xFF9AA0AA),
        fontSize = 12.sp
    )

    if (error != null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(error, modifier = Modifier.weight(1f), color = Color(0xFFFF9A9A), fontSize = 12.sp)
            TextButton(onClick = onDismissError) { Text("إغلاق") }
        }
    }

    if (messages.isEmpty()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("مرحباً بك في NOID AI", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "أرسل رسالتك. لاستقبال إجابات محلية، افتح «محرك AI» واستورد نموذج LiteRT-LM متوافقاً.",
                color = Color(0xFF9AA0AA)
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (message.fromUser) Color(0xFF263B59) else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(
                            text = message.text,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            color = Color(0xFFE8EAED),
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }

    Text(
        "المحادثات محفوظة محلياً على هذا الجهاز",
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = Color(0xFF858B96),
        fontSize = 11.sp
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("اكتب رسالتك إلى NOID…") },
            maxLines = 4,
            shape = RoundedCornerShape(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSend, enabled = draft.isNotBlank() && !isGenerating) {
            Text(if (isGenerating) "جارٍ التفكير…" else "محلي")
        }
        if (remoteEnabled) {
            TextButton(onClick = onSendRemote, enabled = draft.isNotBlank() && !isGenerating) {
                Text("بعيد")
            }
        }
    }
}

@Composable
private fun MemoryManagementScreen(viewModel: MemoryViewModel) {
    val memories by viewModel.memories.collectAsState(initial = emptyList())
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchError by viewModel.searchError.collectAsState()
    val operationMessage by viewModel.operationMessage.collectAsState()
    var showEditor by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<UserMemory?>(null) }
    var pendingDelete by remember { mutableStateOf<UserMemory?>(null) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("موافقة المستخدم") }
    var searchQuery by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(searchQuery) {
        viewModel.searchMemories(searchQuery)
    }
    val displayedMemories = if (searchQuery.isBlank()) memories else searchResults

    fun openNewMemory() {
        editingMemory = null
        title = ""
        content = ""
        source = "موافقة المستخدم"
        showEditor = true
    }

    fun openEditMemory(memory: UserMemory) {
        editingMemory = memory
        title = memory.title
        content = memory.content
        source = memory.source
        showEditor = true
    }

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(if (editingMemory == null) "إضافة ذاكرة بموافقتك" else "تعديل الذاكرة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "لن تُحفظ هذه المعلومة إلا بعد ضغطك على زر الموافقة.",
                        color = Color(0xFF9AA0AA),
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("العنوان") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("المعلومة") },
                        minLines = 2
                    )
                    OutlinedTextField(
                        value = source,
                        onValueChange = { source = it },
                        label = { Text("مصدر الموافقة") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank() && content.isNotBlank() && source.isNotBlank(),
                    onClick = {
                        val current = editingMemory
                        if (current == null) {
                            viewModel.saveApprovedMemory(title, content, source)
                        } else {
                            viewModel.updateMemory(current.id, title, content, source)
                        }
                        showEditor = false
                    }
                ) {
                    Text(if (editingMemory == null) "أوافق على الحفظ" else "حفظ التعديل")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false }) { Text("إلغاء") }
            }
        )
    }

    pendingDelete?.let { memory ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("حذف الذاكرة؟") },
            text = { Text("سيتم حذف «${memory.title}» نهائياً من ذاكرة NOID المحلية.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMemory(memory.id)
                    pendingDelete = null
                }) { Text("حذف") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("إلغاء") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "هذه الذاكرة منفصلة عن سجل المحادثات. لا يحفظ NOID أي معلومة هنا تلقائياً.",
            color = Color(0xFF9AA0AA),
            fontSize = 13.sp
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { openNewMemory() }, modifier = Modifier.fillMaxWidth()) {
            Text("إضافة معلومة بموافقتي")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it.take(MemoryRepository.MAX_SEARCH_QUERY_LENGTH) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("بحث في الذاكرة (حتى 120 حرفاً)") },
            singleLine = true
        )
        operationMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = if (message.startsWith("تعذر")) Color(0xFFFF9A9A) else Color(0xFF9ED7A5),
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(12.dp))

        if (memories.isEmpty() && searchQuery.isBlank()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("لا توجد ذكريات محفوظة بعد.", color = Color(0xFF9AA0AA))
            }
        } else if (isSearching) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("جارٍ البحث في الذاكرة المحلية…", color = Color(0xFF9AA0AA))
            }
        } else if (searchError != null) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(searchError.orEmpty(), color = Color(0xFFFF9A9A))
            }
        } else if (displayedMemories.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("لا توجد نتائج مطابقة.", color = Color(0xFF9AA0AA))
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(displayedMemories, key = { it.id }) { memory ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(memory.title, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(memory.content, color = Color(0xFFE8EAED))
                            Spacer(Modifier.height(4.dp))
                            Text("المصدر: ${memory.source}", color = Color(0xFF9AA0AA), fontSize = 11.sp)
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { openEditMemory(memory) }) { Text("تعديل") }
                                TextButton(onClick = { pendingDelete = memory }) { Text("حذف") }
                            }
                        }
                    }
                }
            }
        }
    }
}
