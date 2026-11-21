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

class MemoryViewModel(
    private val repository: MemoryRepository
) : ViewModel() {
    val memories: Flow<List<UserMemory>> = repository.memories

    private val _searchResults = MutableStateFlow<List<UserMemory>>(emptyList())
    val searchResults: StateFlow<List<UserMemory>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    private var searchJob: Job? = null
    private var searchRequestId = 0L

    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = _operationMessage.asStateFlow()

    private val _isOperating = MutableStateFlow(false)
    val isOperating: StateFlow<Boolean> = _isOperating.asStateFlow()

    private var operationJob: Job? = null

    fun clearOperationMessage() {
        _operationMessage.value = null
    }

    private fun runMemoryOperation(
        successMessage: String,
        errorMessage: String,
        operation: suspend () -> Unit
    ) {
        if (operationJob?.isActive == true) return

        operationJob = viewModelScope.launch {
            _isOperating.value = true
            _operationMessage.value = null
            try {
                operation()
                _operationMessage.value = successMessage
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Do not expose database paths, SQL text, or provider details in the UI.
                _operationMessage.value = errorMessage
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun searchMemories(query: String) {
        val normalized = query.trim().take(MemoryRepository.MAX_SEARCH_QUERY_LENGTH)
        val requestId = ++searchRequestId
        searchJob?.cancel()

        if (normalized.isEmpty()) {
            _searchResults.value = emptyList()
            _searchError.value = null
            _isSearching.value = false
            return
        }

        _isSearching.value = true
        _searchError.value = null
        searchJob = viewModelScope.launch {
            try {
                val results = repository.searchMemories(normalized)
                if (requestId == searchRequestId) {
                    _searchResults.value = results
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (requestId == searchRequestId) {
                    _searchError.value = failure.message ?: "تعذر البحث في الذاكرة."
                    _searchResults.value = emptyList()
                }
            } finally {
                if (requestId == searchRequestId) {
                    _isSearching.value = false
                }
            }
        }
    }

    fun saveApprovedMemory(title: String, content: String, source: String) {
        runMemoryOperation(
            successMessage = "تم حفظ المعلومة في الذاكرة المحلية.",
            errorMessage = "تعذر حفظ المعلومة. لم يتم تأكيد نجاح العملية."
        ) {
            repository.saveApprovedMemory(title, content, source)
        }
    }

    fun updateMemory(id: Long, title: String, content: String, source: String) {
        runMemoryOperation(
            successMessage = "تم تحديث الذاكرة المحلية.",
            errorMessage = "تعذر تحديث الذاكرة. لم يتم تأكيد نجاح العملية."
        ) {
            repository.updateMemory(id, title, content, source)
        }
    }

    fun deleteMemory(id: Long) {
        runMemoryOperation(
            successMessage = "تم حذف المعلومة من الذاكرة المحلية.",
            errorMessage = "تعذر حذف المعلومة. لم يتم تأكيد نجاح العملية."
        ) {
            repository.deleteMemory(id)
        }
    }
}

class MemoryViewModelFactory(
    private val repository: MemoryRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MemoryViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return MemoryViewModel(repository) as T
    }
}
