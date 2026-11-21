package ai.noid.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.StatFs
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * On-device text inference using Google's LiteRT-LM Android Kotlin API.
 * All native runtime operations are serialized on one dedicated dispatcher.
 */
class LiteRtLmAssistantEngine(
    private val appContext: Context
) : AssistantEngine {
    private val runtimeDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "noid-litert-runtime").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val mutableStatus =
        MutableStateFlow<AssistantEngineStatus>(AssistantEngineStatus.NotConfigured)
    override val status: StateFlow<AssistantEngineStatus> = mutableStatus.asStateFlow()

    // Accessed only from runtimeDispatcher.
    private var runtime: Engine? = null

    suspend fun importAndLoadModel(uri: Uri): String = withContext(runtimeDispatcher) {
        check(uri.scheme == "content") { "اختر ملف نموذج من منتقي ملفات Android." }
        val metadata = queryModelMetadata(uri)
        val displayName = metadata.first
        require(ModelFileImportPolicy.isSupportedName(displayName)) {
            "صيغة النموذج المطلوبة هي .litertlm فقط."
        }

        val modelsDirectory = File(appContext.filesDir, "models")
        if (!modelsDirectory.exists() && !modelsDirectory.mkdirs()) {
            throw IOException("تعذر إنشاء مجلد النماذج الخاص بالتطبيق.")
        }
        val availableBytes = StatFs(modelsDirectory.absolutePath).availableBytes
        require(
            ModelFileImportPolicy.hasSufficientSpace(
                modelSizeBytes = metadata.second,
                availableBytes = availableBytes,
                reserveBytes = MIN_FREE_SPACE_BYTES
            )
        ) {
            "المساحة الحرة لا تكفي لحجم النموذج مع الاحتفاظ بهامش أمان إضافي."
        }

        val temporary = File(modelsDirectory, "model-import.tmp")
        if (temporary.exists()) temporary.delete()
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("تعذر فتح ملف النموذج المحدد.")
            input.use { source ->
                temporary.outputStream().buffered().use { destination ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        require(
                            ModelFileImportPolicy.canCopyWithinBudget(
                                copiedBytes = total,
                                nextChunkBytes = count,
                                availableBytes = availableBytes,
                                reserveBytes = MIN_FREE_SPACE_BYTES
                            )
                        ) {
                            "حجم النموذج أو المساحة المتاحة لا يسمحان بإكمال النسخ مع هامش الأمان."
                        }
                        total += count
                        destination.write(buffer, 0, count)
                    }
                    require(total > 0L) { "ملف النموذج فارغ." }
                    metadata.second?.let { reportedSize ->
                        require(total == reportedSize) {
                            "حجم الملف الفعلي لا يطابق الحجم الذي أعلنه مزود الملفات."
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            temporary.delete()
            throw cancelled
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        }

        val modelFile = File(
            modelsDirectory,
            "model-${System.currentTimeMillis()}.litertlm"
        )
        if (!temporary.renameTo(modelFile)) {
            temporary.delete()
            throw IOException("تعذر تثبيت نسخة النموذج داخل مساحة التطبيق الخاصة.")
        }

        try {
            val modelSha256 = ModelFileIntegrity.sha256(modelFile)
            initializeModelOnDispatcher(modelFile, displayName)
            appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACTIVE_MODEL_FILE, modelFile.name)
                .putString(KEY_ACTIVE_MODEL_NAME, displayName)
                .putString(KEY_ACTIVE_MODEL_SHA256, modelSha256)
                .apply()
            modelsDirectory.listFiles()
                ?.filter { it.name.startsWith("model-") && it != modelFile }
                ?.forEach { it.delete() }
            displayName
        } catch (cancelled: CancellationException) {
            modelFile.delete()
            if (runtime == null) {
                mutableStatus.value = AssistantEngineStatus.Unavailable("أُلغي تحميل النموذج.")
            }
            throw cancelled
        } catch (failure: Exception) {
            modelFile.delete()
            if (runtime == null) {
                mutableStatus.value = AssistantEngineStatus.Unavailable(
                    failure.message?.take(240) ?: "تعذر تهيئة محرك LiteRT-LM."
                )
            }
            throw failure
        }
    }

    /** Restores the last user-imported model from app-private storage on process start. */
    suspend fun restoreLastModel() = withContext(runtimeDispatcher) {
        if (runtime != null) return@withContext
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val fileName = preferences.getString(KEY_ACTIVE_MODEL_FILE, null) ?: return@withContext
        if (!ModelFileImportPolicy.isSafeStoredFileName(fileName)) {
            mutableStatus.value = AssistantEngineStatus.Unavailable(
                "اسم ملف النموذج المحفوظ غير صالح."
            )
            return@withContext
        }
        val displayName = preferences.getString(KEY_ACTIVE_MODEL_NAME, fileName) ?: fileName
        val modelFile = File(File(appContext.filesDir, "models"), fileName)
        if (!modelFile.isFile || modelFile.length() <= 0L ||
            modelFile.length() > ModelFileImportPolicy.MAX_MODEL_BYTES
        ) {
            mutableStatus.value = AssistantEngineStatus.Unavailable(
                "ملف النموذج المحفوظ غير موجود أو غير صالح."
            )
            return@withContext
        }
        try {
            val actualSha256 = ModelFileIntegrity.sha256(modelFile)
            val storedSha256 = preferences.getString(KEY_ACTIVE_MODEL_SHA256, null)
            if (storedSha256 != null &&
                (!ModelFileIntegrity.isValidSha256(storedSha256) ||
                    !actualSha256.equals(storedSha256, ignoreCase = true))
            ) {
                mutableStatus.value = AssistantEngineStatus.Unavailable(
                    "تغيرت بصمة ملف النموذج المحفوظ؛ أعد استيراده قبل الاستخدام."
                )
                return@withContext
            }
            // One-time migration for models imported by versions that predate persisted digests.
            if (storedSha256 == null) {
                preferences.edit().putString(KEY_ACTIVE_MODEL_SHA256, actualSha256).apply()
            }
            initializeModelOnDispatcher(modelFile, displayName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableStatus.value = AssistantEngineStatus.Unavailable(
                failure.message?.take(240) ?: "تعذر استعادة النموذج المحلي."
            )
        }
    }

    private fun initializeModelOnDispatcher(modelFile: File, displayName: String) {
        val candidate = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                cacheDir = File(appContext.cacheDir, "litert-cache").apply { mkdirs() }.absolutePath
            )
        )
        // Store the engine only after successful initialization. LiteRT-LM documents
        // that an Engine whose initialize() failed must not be closed.
        try {
            candidate.initialize()
        } catch (failure: LinkageError) {
            throw IllegalStateException(
                "تعذر تحميل مكتبات LiteRT-LM الأصلية على هذا الجهاز.",
                failure
            )
        }
        val previous = runtime
        runtime = candidate
        mutableStatus.value = AssistantEngineStatus.Ready(displayName)
        // Keep the old model alive until its replacement has initialized successfully.
        try {
            previous?.close()
        } catch (_: Exception) {
            // The newly initialized runtime remains usable if old native cleanup fails.
        }
    }

    override fun generate(request: AssistantTurnRequest): Flow<AssistantStreamEvent> = flow {
        val loadedEngine = runtime
            ?: throw IllegalStateException("لم يتم تحميل نموذج LiteRT-LM بعد.")
        val lastUserMessage = request.conversation.lastOrNull { it.fromUser }
            ?: throw IllegalArgumentException("لا توجد رسالة مستخدم لإرسالها إلى النموذج.")

        val history = request.conversation
            .dropLastWhile { !it.fromUser }
            .dropLast(1)
            .takeLast(MAX_HISTORY_MESSAGES)
            .map { message ->
                if (message.fromUser) Message.user(message.text) else Message.model(message.text)
            }

        val memoryContext = request.approvedMemories
            .takeLast(MAX_MEMORY_ITEMS)
            .joinToString("\n") { memory ->
                "- ${memory.title}: ${memory.content.take(MAX_MEMORY_ITEM_CHARS)}"
            }
        val systemInstruction = buildString {
            append("أنت NOID AI، مساعد محلي مفيد ودقيق. أجب باللغة التي يستخدمها المستخدم. ")
            append("لا تدّع تنفيذ إجراء لم تنفذه. في الأمن السيبراني، اقتصر على التحليل الدفاعي والاختبارات المصرح بها. ")
            if (memoryContext.isNotBlank()) {
                append("\nذكريات وافق المستخدم على استخدامها:\n")
                append(memoryContext)
            }
        }

        loadedEngine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemInstruction),
                initialMessages = history,
                tools = request.toolProviders,
                automaticToolCalling = false
            )
        ).use { conversation ->
            var message = conversation.sendMessage(lastUserMessage.text)
            var toolRounds = 0
            while (true) {
                val delta = message.toString()
                if (delta.isNotEmpty()) emit(AssistantStreamEvent.TextDelta(delta))
                if (message.toolCalls.isEmpty()) break
                check(++toolRounds <= MAX_MANUAL_TOOL_ROUNDS) {
                    "تجاوز النموذج الحد المسموح لاستدعاءات الأدوات في هذه الجولة."
                }
                if (message.toolCalls.size > 1) {
                    val rejected = message.toolCalls.map { call ->
                        com.google.ai.edge.litertlm.Content.ToolResponse(
                            call.name, ToolCallResponseJson.failure("يسمح بطلب أداة واحد فقط في كل مرة.")
                        )
                    }
                    message = conversation.sendMessage(Message.tool(Contents.of(rejected)))
                    continue
                }
                val modelCall = message.toolCalls.single()
                val pendingCall = AssistantToolCall(modelCall.name, modelCall.arguments)
                emit(AssistantStreamEvent.ToolCall(pendingCall))
                val toolResponse = kotlinx.coroutines.withTimeout(MODEL_TOOL_APPROVAL_TIMEOUT_MS) {
                    pendingCall.response.await()
                }
                message = conversation.sendMessage(
                    Message.tool(
                        Contents.of(
                            com.google.ai.edge.litertlm.Content.ToolResponse(modelCall.name, toolResponse)
                        )
                    )
                )
            }
        }
        emit(AssistantStreamEvent.Completed)
    }.flowOn(runtimeDispatcher)

    override suspend fun close() {
        withContext(runtimeDispatcher) {
            closeRuntimeOnDispatcher()
        }
        runtimeDispatcher.close()
    }

    private fun closeRuntimeOnDispatcher() {
        runtime?.close()
        runtime = null
        mutableStatus.value = AssistantEngineStatus.NotConfigured
    }

    private fun queryModelMetadata(uri: Uri): Pair<String, Long?> {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else {
                        null
                    }
                    return cursor.getString(nameIndex).orEmpty() to size
                }
            }
        }
        throw IOException("تعذر قراءة بيانات ملف النموذج.")
    }

    companion object {
        const val MAX_HISTORY_MESSAGES = 30
        const val MAX_MEMORY_ITEMS = 20
        const val MAX_MEMORY_ITEM_CHARS = 1_000
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MIN_FREE_SPACE_BYTES = 256L * 1024L * 1024L
        const val MAX_MANUAL_TOOL_ROUNDS = 4
        const val MODEL_TOOL_APPROVAL_TIMEOUT_MS = 115_000L
        private const val PREFERENCES_NAME = "noid_model_runtime"
        private const val KEY_ACTIVE_MODEL_FILE = "active_model_file"
        private const val KEY_ACTIVE_MODEL_NAME = "active_model_name"
        private const val KEY_ACTIVE_MODEL_SHA256 = "active_model_sha256"
    }
}


/** Keeps the native engine alive across Activity recreation within one app process. */
object NoidEngineHolder {
    @Volatile
    private var instance: LiteRtLmAssistantEngine? = null

    fun get(context: Context): LiteRtLmAssistantEngine =
        instance ?: synchronized(this) {
            instance ?: LiteRtLmAssistantEngine(context.applicationContext).also {
                instance = it
            }
        }
}
