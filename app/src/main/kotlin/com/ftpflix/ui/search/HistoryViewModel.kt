package com.ftpflix.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftpflix.data.local.HistoryEntity
import com.ftpflix.data.repository.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HistoryFilter { All, InProgress, Completed }

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val history: HistoryRepository,
) : ViewModel() {

    private val _filter = MutableStateFlow(HistoryFilter.All)
    val filter: StateFlow<HistoryFilter> = _filter.asStateFlow()

    val items: StateFlow<List<HistoryEntity>> = combine(
        history.recent(),
        _filter,
    ) { recent, filter ->
        when (filter) {
            HistoryFilter.All -> recent
            HistoryFilter.InProgress -> recent.filter { !it.isFinished }
            HistoryFilter.Completed -> recent.filter { it.isFinished }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(value: HistoryFilter) {
        _filter.value = value
    }

    fun remove(item: HistoryEntity) {
        viewModelScope.launch { history.remove(item.mediaKey) }
    }

    fun clearAll() {
        viewModelScope.launch { history.clear() }
    }
}
