package ai.noid.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** User-facing review only. Approval never applies a change to the selected project. */
class SelfDevelopmentReviewViewModel(
    private val repository: SelfDevelopmentProposalRepository,
    private val applyCoordinator: SelfDevelopmentApplyCoordinator? = null,
    private val selectedTree: SelectedProjectTreeSession? = null
) : ViewModel() {
    val pending = repository.pending

    private val _recent = MutableStateFlow<List<SelfDevelopmentProposal>>(emptyList())
    val recent: StateFlow<List<SelfDevelopmentProposal>> = _recent.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var operation: Job? = null

    init {
        refreshRecent()
    }

    fun refreshRecent() {
        viewModelScope.launch {
            try {
                _recent.value = repository.recent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _message.value = "تعذر تحميل سجل المقترحات."
            }
        }
    }

    fun approve(id: Long) = runReview(
        success = "تمت الموافقة على المقترح للمراجعة اللاحقة فقط؛ لم يتغير أي ملف.",
        failure = "تعذرت الموافقة؛ ربما عولج المقترح مسبقاً."
    ) { check(repository.approve(id)) }

    fun reject(id: Long) = runReview(
        success = "رُفض المقترح ولم يُطبّق أي تغيير.",
        failure = "تعذر رفض المقترح؛ ربما عولج مسبقاً."
    ) { check(repository.reject(id)) }

    fun apply(id: Long) {
        if (operation?.isActive == true) return
        val coordinator = applyCoordinator
        val selection = selectedTree?.current()
        if (coordinator == null || selection == null) {
            _message.value = "اختر مجلد المشروع أولاً؛ لا يمكن تطبيق المقترح دون تحديد مصدره."
            return
        }
        operation = viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try {
                _message.value = when (val result = coordinator.apply(id, selection.selectionId)) {
                    is SelfDevelopmentApplyResult.Applied -> {
                        if (result.completionAudited) {
                            "تم تطبيق المقترح. النسخة السابقة محفوظة: ${result.backup.backupFile.name}"
                        } else {
                            "تم تطبيق المقترح وحُفظت نسخته السابقة، لكن تعذر تسجيل اكتمال العملية في سجل التدقيق."
                        }
                    }
                    SelfDevelopmentApplyResult.ProposalNotFound -> "المقترح غير موجود."
                    SelfDevelopmentApplyResult.NotApproved -> "لا يمكن التطبيق؛ يجب اعتماد المقترح أولاً."
                    SelfDevelopmentApplyResult.AlreadyProcessing -> "هذا المقترح قيد المعالجة أو عولج سابقاً؛ لن نكرر الكتابة."
                    SelfDevelopmentApplyResult.ProjectNotSelected -> "اختر مجلد المشروع الذي يحتوي على الملف."
                    SelfDevelopmentApplyResult.ProjectSelectionChanged -> "تغير اختيار المشروع؛ أعد مراجعة المقترح."
                    SelfDevelopmentApplyResult.PermissionUnavailable -> "إذن قراءة وكتابة مجلد المشروع غير متاح."
                    SelfDevelopmentApplyResult.SourceStale -> "تغير الملف الأصلي منذ إنشاء المقترح؛ رُفض التطبيق ولم تُكتب التغييرات."
                    SelfDevelopmentApplyResult.SourceUnavailable -> "تعذرت إعادة قراءة الملف؛ لم يُطبق المقترح."
                    SelfDevelopmentApplyResult.InvalidProposalSnapshot -> "بيانات المقترح أو بصماته غير متطابقة؛ رُفض التطبيق."
                    SelfDevelopmentApplyResult.AuditUnavailable -> "تعذر تسجيل بدء العملية؛ لم يبدأ التطبيق."
                    is SelfDevelopmentApplyResult.Rejected -> result.reason
                    is SelfDevelopmentApplyResult.Failed ->
                        "فشل التطبيق (${result.exceptionType}). لا تعاود المحاولة قبل فحص الملف والنسخة الاحتياطية."
                    SelfDevelopmentApplyResult.AppliedStateNotRecorded ->
                        "قد يكون الملف قد تغير، لكن تعذر تسجيل حالة التطبيق. لا تعاود التطبيق؛ افحص الملف وسجل التدقيق."
                }
                _recent.value = repository.recent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _message.value = "تعذر إكمال عملية التطبيق أو تحديث سجل المقترحات."
            } finally {
                _busy.value = false
            }
        }
    }

    private fun runReview(success: String, failure: String, block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try {
                block()
                _message.value = success
                _recent.value = repository.recent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _message.value = failure
            } finally {
                _busy.value = false
            }
        }
    }
}

class SelfDevelopmentReviewViewModelFactory(
    private val repository: SelfDevelopmentProposalRepository,
    private val applyCoordinator: SelfDevelopmentApplyCoordinator? = null,
    private val selectedTree: SelectedProjectTreeSession? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SelfDevelopmentReviewViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return SelfDevelopmentReviewViewModel(repository, applyCoordinator, selectedTree) as T
    }
}
