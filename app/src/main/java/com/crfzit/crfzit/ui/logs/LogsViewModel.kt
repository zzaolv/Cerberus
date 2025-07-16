package com.crfzit.crfzit.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crfzit.crfzit.data.model.LogEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

class LogsViewModel : ViewModel() {

    // TODO: 未来从真实仓库获取日志流
    val logs: StateFlow<List<LogEntry>> =
        // <-- 关键修正：明确指定泛型类型
        flowOf<List<LogEntry>>(emptyList())
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )
}