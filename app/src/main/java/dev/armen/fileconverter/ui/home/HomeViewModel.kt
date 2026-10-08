package dev.armen.fileconverter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.armen.fileconverter.data.history.HistoryDao
import dev.armen.fileconverter.data.history.HistoryEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val history: HistoryDao) : ViewModel() {
    val recent: StateFlow<List<HistoryEntry>> =
        history.observeRecent(50).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(id: Long) {
        viewModelScope.launch { history.delete(id) }
    }
}
