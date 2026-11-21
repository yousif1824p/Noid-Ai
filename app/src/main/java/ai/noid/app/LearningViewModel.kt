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

class LearningViewModel(private val repository: LearningRepository) : ViewModel() {
    val pending = repository.pending

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var operation: Job? = null

    fun propose(title: String, content: String, source: String) = runOperation(
        success = "أُضيفت المعرفة إلى قائمة المراجعة، ولم تدخل سياق المساعد بعد.",
        failure = "تعذر حفظ الاقتراح. تحقق من الحقول وحاول مجدداً."
    ) { repository.propose(title, content, source) }

    fun approve(id: Long) = runOperation(
        success = "تمت الموافقة؛ ستظهر المعرفة في سياق المحادثات المحلية التالية.",
        failure = "تعذرت الموافقة؛ ربما عولج الاقتراح مسبقاً."
    ) { check(repository.approve(id)) }

    fun reject(id: Long) = runOperation(
        success = "رُفض الاقتراح ولن يستخدمه المساعد.",
        failure = "تعذر رفض الاقتراح؛ ربما عولج مسبقاً."
    ) { check(repository.reject(id)) }

    private fun runOperation(success: String, failure: String, block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try {
                block()
                _message.value = success
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

class LearningViewModelFactory(
    private val repository: LearningRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LearningViewModel::class.java))
        return LearningViewModel(repository) as T
    }
}
