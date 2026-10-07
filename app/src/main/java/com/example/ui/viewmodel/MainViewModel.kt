package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.CopiedHistory
import com.example.data.model.QueueNumber
import com.example.data.repository.ImportMode
import com.example.data.repository.NumberRepository
import com.example.service.FloatingWidgetService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(private val repository: NumberRepository) : ViewModel() {

    val isFloatingServiceRunning: StateFlow<Boolean> = FloatingWidgetService.isRunning

    val queueCount: StateFlow<Int> = repository.queueCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _selectedTab = MutableStateFlow(0) // 0: Queue, 1: History, 2: Backup/Tools
    val selectedTab = _selectedTab.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()

    private val _inputNote = MutableStateFlow("")
    val inputNote = _inputNote.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage = _userMessage.asStateFlow()

    // Filtered Queue Numbers
    val queueNumbers: StateFlow<List<QueueNumber>> = combine(
        repository.allQueueNumbers,
        _searchQuery
    ) { list, query ->
        if (query.isBlank()) list
        else list.filter {
            it.number.contains(query, ignoreCase = true) ||
                    it.note.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // History Numbers
    val historyNumbers: StateFlow<List<CopiedHistory>> = repository.allHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSelectedTab(tab: Int) {
        _selectedTab.value = tab
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setInputText(text: String) {
        _inputText.value = text
    }

    fun setInputNote(note: String) {
        _inputNote.value = note
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    fun showMessage(msg: String) {
        _userMessage.value = msg
    }

    fun addNumbersFromInput() {
        val text = _inputText.value.trim()
        val note = _inputNote.value.trim()
        if (text.isBlank()) {
            _userMessage.value = "অনুগ্রহ করে অন্তত একটি নম্বর লিখুন বা পেস্ট করুন!"
            return
        }

        viewModelScope.launch {
            val count = repository.addBatchNumbers(text, note)
            if (count > 0) {
                _inputText.value = ""
                _inputNote.value = ""
                _userMessage.value = "সফলভাবে $count টি নম্বর সারিতে যোগ করা হয়েছে।"
            } else {
                _userMessage.value = "কোনো বৈধ নম্বর পাওয়া যায়নি।"
            }
        }
    }

    fun copyAndPopNext(context: Context) {
        viewModelScope.launch {
            val result = repository.copyAndPopNext(context)
            _userMessage.value = result.message
        }
    }

    fun deleteQueueItem(item: QueueNumber) {
        viewModelScope.launch {
            repository.deleteQueueNumber(item)
            _userMessage.value = "${item.number} মুছে ফেলা হয়েছে।"
        }
    }

    fun clearQueue() {
        viewModelScope.launch {
            repository.clearQueue()
            _userMessage.value = "সব নম্বর মুছে ফেলা হয়েছে।"
        }
    }

    fun restoreHistoryItem(history: CopiedHistory) {
        viewModelScope.launch {
            repository.restoreHistoryItem(history)
            _userMessage.value = "${history.number} আবার সারিতে ফিরিয়ে আনা হয়েছে।"
        }
    }

    fun deleteHistoryItem(history: CopiedHistory) {
        viewModelScope.launch {
            repository.deleteHistory(history)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            _userMessage.value = "হিস্ট্রি মুছে ফেলা হয়েছে।"
        }
    }

    suspend fun exportJson(): String {
        return repository.exportDatabaseToJson()
    }

    suspend fun exportPlainText(): String {
        return repository.exportQueueToPlainText()
    }

    fun importJsonBackup(jsonString: String, mode: ImportMode, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val summary = repository.importDatabaseFromJson(jsonString, mode)
            if (summary.success) {
                val msg = "রিস্টোর সম্পন্ন! মোট ${summary.activeImported} টি সারি ও ${summary.historyImported} টি হিস্ট্রি যোগ হয়েছে।"
                _userMessage.value = msg
                onComplete(true, msg)
            } else {
                val err = "ব্যাকআপ রিস্টোর ব্যর্থ হয়েছে: ${summary.errorMessage}"
                _userMessage.value = err
                onComplete(false, err)
            }
        }
    }

    fun importPlainText(rawText: String, replaceAll: Boolean, onComplete: (Int) -> Unit) {
        viewModelScope.launch {
            if (replaceAll) {
                repository.clearQueue()
            }
            val count = repository.addBatchNumbers(rawText)
            _userMessage.value = "$count টি নম্বর যোগ করা হয়েছে।"
            onComplete(count)
        }
    }
}

class MainViewModelFactory(private val repository: NumberRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
