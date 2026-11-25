package com.graphite.torrent.core.engine

import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.FilePriority
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.MetaFile
import com.graphite.torrent.core.model.TorrentMetadata
import com.graphite.torrent.core.util.Base64Util
import org.libtorrent4j.Entry
import org.libtorrent4j.InfoHash
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentInfo

/** Conversions between libtorrent4j types and Graphite models. */
object TorrentMapping {

    /** Stable torrent id: the v1 info hash when present, otherwise the truncated v2 hash. */
    fun idOf(hashes: InfoHash): String =
        if (hashes.hasV1()) hashes.getV1().toHex() else hashes.getBest().toHex()

    fun toLibtorrent(priority: FilePriority): Priority = when (priority) {
        FilePriority.SKIP -> Priority.IGNORE
        FilePriority.LOW -> Priority.LOW
        FilePriority.NORMAL -> Priority.DEFAULT
        FilePriority.HIGH -> Priority.TOP_PRIORITY
    }

    fun fromLibtorrent(priority: Priority): FilePriority = when (priority) {
        Priority.IGNORE -> FilePriority.SKIP
        Priority.LOW, Priority.TWO, Priority.THREE -> FilePriority.LOW
        Priority.DEFAULT, Priority.FIVE -> FilePriority.NORMAL
        Priority.SIX, Priority.TOP_PRIORITY -> FilePriority.HIGH
        else -> FilePriority.NORMAL
    }

    /** Parses .torrent bytes, mapping every failure to [ErrorKind.INVALID_TORRENT]. */
    fun parseTorrentInfo(data: ByteArray): TorrentInfo {
        if (data.isEmpty()) throw GraphiteException(ErrorKind.INVALID_TORRENT, "empty file")
        val info = try {
            TorrentInfo(data)
        } catch (e: Throwable) {
            throw GraphiteException(ErrorKind.INVALID_TORRENT, e.message ?: "cannot parse torrent", e)
        }
        if (!info.isValid || info.numFiles() <= 0) {
            throw GraphiteException(ErrorKind.INVALID_TORRENT, "torrent has no files")
        }
        return info
    }

    fun metadataOf(info: TorrentInfo, data: ByteArray, magnetUri: String = "", extraTrackers: List<String> = emptyList()): TorrentMetadata {
        val fs = info.files()
        val files = ArrayList<MetaFile>(fs.numFiles())
        for (i in 0 until fs.numFiles()) {
            if (fs.padFileAt(i)) continue
            files += MetaFile(index = i, path = fs.filePath(i).replace('\\', '/'), size = fs.fileSize(i))
        }
        val dict = runCatching { Entry.bdecode(data).dictionary() }.getOrNull().orEmpty()
        val trackers = LinkedHashSet<String>()
        runCatching { dict["announce"]?.string() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { trackers += it }
        runCatching {
            dict["announce-list"]?.list()?.forEach { tier ->
                tier.list().forEach { url -> url.string().takeIf { it.isNotBlank() }?.let(trackers::add) }
            }
        }
        trackers += extraTrackers
        return TorrentMetadata(
            id = idOf(info.infoHashes()),
            name = info.name().ifBlank { files.firstOrNull()?.path ?: "torrent" },
            totalSize = info.totalSize(),
            pieceLength = info.pieceLength(),
            pieceCount = info.numPieces(),
            isPrivate = info.isPrivate,
            comment = runCatching { dict["comment"]?.string() }.getOrNull().orEmpty(),
            createdBy = runCatching { dict["created by"]?.string() }.getOrNull().orEmpty(),
            creationDate = runCatching { dict["creation date"]?.integer() }.getOrNull() ?: 0L,
            trackers = trackers.toList(),
            files = files,
            torrentDataBase64 = Base64Util.encode(data),
            magnetUri = magnetUri,
        )
    }
}
