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
    private val repository: SelfDevelopmentProposalRepository
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
    private val repository: SelfDevelopmentProposalRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SelfDevelopmentReviewViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return SelfDevelopmentReviewViewModel(repository) as T
    }
}
