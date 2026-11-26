package com.graphite.torrent.data

import com.graphite.torrent.core.model.PauseReason
import com.graphite.torrent.core.model.SessionSnapshot
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentState

/** When the app has to keep running in the background (Android foreground service). */
object BackgroundPolicy {
    val ACTIVE: Set<TorrentState> =
        setOf(TorrentState.DOWNLOADING, TorrentState.METADATA, TorrentState.CHECKING, TorrentState.QUEUED, TorrentState.MOVING)

    /**
     * - Files being moved to the user's folder: always.
     * - Nothing paused: while something downloads, checks or waits in the queue.
     * - Paused by a rule (no Wi-Fi, not charging, schedule…): yes while unfinished downloads wait for it, because
     *   only a living process can resume them when the rule clears.
     * - Paused by the user: no, nothing will happen until the user comes back.
     */
    fun needsService(torrents: List<TorrentItem>, session: SessionSnapshot): Boolean {
        if (torrents.any { it.state == TorrentState.MOVING }) return true
        if (session.pauseReasons.isEmpty()) return torrents.any { it.state in ACTIVE }
        val byRule = session.pauseReasons.any { it != PauseReason.USER }
        return byRule && torrents.any { it.state != TorrentState.COMPLETED && it.state != TorrentState.ERROR }
    }
}
