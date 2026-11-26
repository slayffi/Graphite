package com.graphite.torrent.ui.app.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.graphite.torrent.core.model.DailyStat
import com.graphite.torrent.core.model.HistoryEntry
import com.graphite.torrent.core.model.StatsPeriod
import com.graphite.torrent.core.model.StatsTotals
import com.graphite.torrent.ui.app.AppEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(env: AppEnvironment) : ViewModel() {
    private val _period = MutableStateFlow(StatsPeriod.WEEK)
    val period: StateFlow<StatsPeriod> = _period.asStateFlow()

    val totals: StateFlow<StatsTotals> = _period.flatMapLatest { env.stats.totals(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsTotals())

    val days: StateFlow<List<DailyStat>> = _period.flatMapLatest { env.stats.days(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history: StateFlow<List<HistoryEntry>> = env.history.recent(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val speedHistory = env.controller.speedHistory
    val session = env.controller.session

    fun setPeriod(p: StatsPeriod) {
        _period.value = p
    }
}
