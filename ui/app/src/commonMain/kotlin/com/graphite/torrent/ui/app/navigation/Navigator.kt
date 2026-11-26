package com.graphite.torrent.ui.app.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Top level tabs (bottom bar on phones, sidebar on desktop). */
enum class RootTab { DOWNLOADS, STATS, SETTINGS }

sealed interface Destination {
    data class Details(val id: String) : Destination
    data class Player(val id: String, val fileIndex: Int, val title: String) : Destination
    data object EventLog : Destination
    data object Rss : Destination
    data object Scheduler : Destination
    data object Remote : Destination
    data object History : Destination
}

/** Simple stack navigation; each root tab keeps its own stack. */
@Stable
class Navigator {
    var tab by mutableStateOf(RootTab.DOWNLOADS)
        private set

    private val stacks = RootTab.entries.associateWith { mutableStateListOf<Destination>() }

    val stack: List<Destination> get() = stacks.getValue(tab)
    val current: Destination? get() = stack.lastOrNull()

    fun selectTab(newTab: RootTab) {
        if (newTab == tab) stacks.getValue(tab).clear() else tab = newTab
    }

    fun navigate(destination: Destination) {
        val s = stacks.getValue(tab)
        if (s.lastOrNull() != destination) s.add(destination)
    }

    /** Replaces the top of the stack (used by the two-pane layout to swap the details pane). */
    fun replaceTop(destination: Destination?) {
        val s = stacks.getValue(tab)
        if (s.isNotEmpty() && s.last() is Destination.Details) s.removeAt(s.lastIndex)
        if (destination != null) s.add(destination)
    }

    /** Returns false when there is nothing to go back to. */
    fun back(): Boolean {
        val s = stacks.getValue(tab)
        if (s.isNotEmpty()) {
            s.removeAt(s.lastIndex)
            return true
        }
        if (tab != RootTab.DOWNLOADS) {
            tab = RootTab.DOWNLOADS
            return true
        }
        return false
    }
}
