package ai.noid.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatRepository(
    private val chatMessageDao: ChatMessageDao
) {
    val messages: Flow<List<ChatMessage>> = chatMessageDao.observeAll()

    suspend fun sendMessage(text: String) {
        val normalized = text.trim()
        if (normalized.isNotEmpty()) {
            chatMessageDao.insert(ChatMessage(text = normalized, fromUser = true))
        }
    }

    suspend fun appendAssistantResponse(text: String) {
        val normalized = text.trim()
        if (normalized.isNotEmpty()) {
            chatMessageDao.insert(ChatMessage(text = normalized, fromUser = false))
        }
    }

    suspend fun searchMessages(query: String, limit: Int = 100): List<ChatMessage> {
        val normalized = query.trim().take(120)
        if (normalized.isBlank()) return emptyList()
        require(limit in 1..100) { "Search result limit must be between 1 and 100." }
        return chatMessageDao.search(normalized, limit)
    }

    suspend fun clearConversation() {
        chatMessageDao.deleteAll()
    }

    suspend fun currentConversation(limit: Int = 40): List<ChatMessage> {
        require(limit > 0) { "Conversation context limit must be positive." }
        return chatMessageDao.getRecent(limit).asReversed()
    }
}

class ChatViewModel(
    private val repository: ChatRepository,
    private val memoryRepository: MemoryRepository,
    private val learningRepository: LearningRepository,
    private val engine: AssistantEngine,
    private val toolRegistry: ToolRegistry,
    private val remoteChatAdapter: RemoteChatTurnAdapter = RemoteChatTurnAdapter()
) : ViewModel() {
    val messages: Flow<List<ChatMessage>> = repository.messages
    val engineStatus: StateFlow<AssistantEngineStatus> = engine.status

    private val mutableIsGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = mutableIsGenerating.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    private val mutableSearchResults = MutableStateFlow<List<ChatMessage>>(emptyList())
    val searchResults: StateFlow<List<ChatMessage>> = mutableSearchResults.asStateFlow()
    private val mutableSearchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = mutableSearchError.asStateFlow()
    private val mutableIsSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = mutableIsSearching.asStateFlow()
    private var searchJob: Job? = null
    private var searchRequestId = 0L
    private var generationJob: Job? = null
    private val pendingModelToolCalls = PendingModelToolCallHandoff()
    val error: StateFlow<String?> = mutableError.asStateFlow()

    fun dismissError() {
        mutableError.value = null
    }

    fun searchConversation(query: String) {
        searchJob?.cancel()
        val requestId = ++searchRequestId
        val normalized = query.trim().take(120)
        if (normalized.isBlank()) {
            mutableSearchResults.value = emptyList()
            mutableSearchError.value = null
            mutableIsSearching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            mutableIsSearching.value = true
            mutableSearchError.value = null
            try {
                mutableSearchResults.value = repository.searchMessages(normalized)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableSearchError.value = failure.message?.takeIf { it.isNotBlank() }
                    ?: "تعذر البحث في سجل المحادثات."
            } finally {
                if (requestId == searchRequestId) mutableIsSearching.value = false
            }
        }
    }

    fun clearSearch() {
        searchRequestId++
        searchJob?.cancel()
        mutableSearchResults.value = emptyList()
        mutableSearchError.value = null
        mutableIsSearching.value = false
    }

    /** Starts a separate, explicit opt-in remote turn; local sendMessage never uses the network. */
    fun sendRemoteMessage(text: String, endpoint: String, enabled: Boolean) {
        val normalized = text.trim()
        if (normalized.isEmpty() || !enabled || generationJob?.isActive == true) return

        generationJob = viewModelScope.launch {
            mutableError.value = null
            mutableIsGenerating.value = true
            try {
                repository.sendMessage(normalized)
                val request = repository.currentConversation(
                    limit = RemoteChatRequestPolicy.MAX_MESSAGES
                )
                val response = remoteChatAdapter.execute(
                    conversation = request,
                    endpoint = endpoint,
                    enabled = enabled,
                    userInitiated = true
                )
                repository.appendAssistantResponse(response)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableError.value = failure.message?.takeIf { it.isNotBlank() }
                    ?: "تعذر إكمال طلب المحادثة البعيدة."
            } finally {
                remoteChatAdapter.cancel()
                mutableIsGenerating.value = false
            }
        }
    }

    /** Cancels the active local or remote generation without deleting the conversation. */
    fun cancelGeneration() {
        generationJob?.cancel()
        remoteChatAdapter.cancel()
        pendingModelToolCalls.cancelAll { id -> toolRegistry.cancelApproval(id) }
        mutableIsGenerating.value = false
    }

    fun sendMessage(text: String) {
        val normalized = text.trim()
        if (normalized.isEmpty() || generationJob?.isActive == true) return

        generationJob = viewModelScope.launch {
            mutableError.value = null
            try {
                repository.sendMessage(normalized)

                // Never fabricate an answer when no inference runtime is configured.
                if (engine.status.value !is AssistantEngineStatus.Ready) return@launch

                mutableIsGenerating.value = true
                val request = AssistantTurnRequest(
                    conversation = repository.currentConversation(),
                    approvedMemories = memoryRepository.recentContext(),
                    approvedLessons = learningRepository.approvedContext(),
                    toolProviders = toolRegistry.modelToolProviders()
                )
                val response = AssistantResponseCollector()
                    .collectCompleteResponse(engine, request) { call ->
                        val pending = toolRegistry.requestApprovalFromModel(
                            call.name, call.arguments, "طلبه النموذج: ${call.name}"
                        )
                        if (pending == null) {
                            call.response.complete(ToolCallResponseJson.failure(
                                "الأداة غير معروفة أو الطلب غير صالح أو لم يتم اختيار مشروع."
                            ))
                        } else {
                            pendingModelToolCalls.register(pending.id, call.response)
                        }
                    }
                repository.appendAssistantResponse(response)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableError.value = failure.message?.takeIf { it.isNotBlank() }
                    ?: "تعذر حفظ الرسالة أو إكمال رد محرك الذكاء الاصطناعي."
            } finally {
                pendingModelToolCalls.cancelAll { id -> toolRegistry.cancelApproval(id) }
                mutableIsGenerating.value = false
            }
        }
    }

    fun approveToolApproval(pendingId: String) {
        viewModelScope.launch {
            // Claim the model handoff before execution. If the generation times out
            // while the approved tool is running, its result must not become a
            // separate unsolicited chat message.
            val waiting = pendingModelToolCalls.claim(pendingId)
            val result = toolRegistry.approveAndInvoke(pendingId)
            if (waiting != null) {
                waiting.complete(ToolCallResponseJson.fromRegistryResult(result))
                return@launch
            }
            when (result) {
                is ToolRegistryResult.Executed -> when (val execution = result.result) {
                    is ToolExecutionResult.Completed -> repository.appendAssistantResponse(
                        (if (execution.completionAudited) "نتيجة قراءة الملف بموافقتك:" else "تحذير: تمت القراءة لكن تعذر تسجيل اكتمالها. النتيجة:") + "\n" + execution.value
                    )
                    is ToolExecutionResult.Rejected -> repository.appendAssistantResponse("رُفض طلب الأداة: ${execution.reason}")
                    is ToolExecutionResult.AuditUnavailable -> repository.appendAssistantResponse("توقفت الأداة لأن سجل التدقيق غير متاح.")
                    is ToolExecutionResult.Failed -> repository.appendAssistantResponse("فشلت أداة القراءة (${execution.exceptionType}).")
                }
                ToolRegistryResult.UnknownTool -> repository.appendAssistantResponse("الأداة المطلوبة غير مسجلة.")
                is ToolRegistryResult.InvalidRequest -> repository.appendAssistantResponse("طلب الأداة غير صالح: ${result.reason}")
                ToolRegistryResult.ApprovalRequired -> repository.appendAssistantResponse("انتهت صلاحية طلب الموافقة أو لم يعد صالحاً.")
            }
        }
    }

    fun rejectToolApproval(pendingId: String) {
        toolRegistry.cancelApproval(pendingId)
        pendingModelToolCalls.reject(
            pendingId,
            ToolCallResponseJson.failure("رفض المستخدم تنفيذ الأداة.")
        )
    }

    fun clearConversation() {
        val pendingGeneration = generationJob
        pendingGeneration?.cancel()
        remoteChatAdapter.cancel()
        mutableIsGenerating.value = false
        viewModelScope.launch {
            try {
                // Wait until a cancelled generation has stopped writing before
                // deleting the conversation, avoiding a late assistant insert.
                pendingGeneration?.join()
                repository.clearConversation()
                mutableError.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableError.value = failure.message?.takeIf { it.isNotBlank() }
                    ?: "تعذر مسح سجل المحادثة."
            }
        }
    }
}

class ChatViewModelFactory(
    private val repository: ChatRepository,
    private val memoryRepository: MemoryRepository,
    private val learningRepository: LearningRepository,
    private val engine: AssistantEngine,
    private val toolRegistry: ToolRegistry,
    private val remoteChatAdapter: RemoteChatTurnAdapter = RemoteChatTurnAdapter()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return ChatViewModel(repository, memoryRepository, learningRepository, engine, toolRegistry, remoteChatAdapter) as T
    }
}
