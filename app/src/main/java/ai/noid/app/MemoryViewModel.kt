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
        viewModelScope.launch {
            repository.saveApprovedMemory(title, content, source)
        }
    }

    fun updateMemory(id: Long, title: String, content: String, source: String) {
        viewModelScope.launch {
            repository.updateMemory(id, title, content, source)
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
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
