package com.graphite.torrent.ui.app.common

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.state.ToggleableState
import com.graphite.torrent.core.model.FileEntry
import com.graphite.torrent.core.model.FilePriority
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons

/** Folder/file tree built from the flat file list of a torrent. */
data class FileTreeNode(
    val name: String,
    val path: String,
    val depth: Int,
    /** File index for files, null for folders. */
    val fileIndex: Int?,
    val size: Long,
    val downloaded: Long,
    val children: List<FileTreeNode> = emptyList(),
) {
    val isFolder: Boolean get() = fileIndex == null
    val fileIndices: List<Int> get() = if (fileIndex != null) listOf(fileIndex) else children.flatMap { it.fileIndices }
    val fileCount: Int get() = if (isFolder) children.sumOf { it.fileCount } else 1
    val progress: Float get() = if (size <= 0) 1f else (downloaded.toDouble() / size).toFloat().coerceIn(0f, 1f)
}

object FileTree {
    private class MutableNode(val name: String, val path: String) {
        val folders = LinkedHashMap<String, MutableNode>()
        val files = ArrayList<FileEntry>()
    }

    fun build(files: List<FileEntry>): List<FileTreeNode> {
        val root = MutableNode("", "")
        for (f in files) {
            val parts = f.path.split('/').filter { it.isNotEmpty() }
            var node = root
            for (i in 0 until parts.size - 1) {
                val name = parts[i]
                node = node.folders.getOrPut(name) { MutableNode(name, (if (node.path.isEmpty()) "" else node.path + "/") + name) }
            }
            node.files += f
        }
        return convert(root, 0)
    }

    private fun convert(node: MutableNode, depth: Int): List<FileTreeNode> {
        val folders = node.folders.values.sortedBy { it.name.lowercase() }.map { folder ->
            val children = convert(folder, depth + 1)
            FileTreeNode(
                name = folder.name,
                path = folder.path,
                depth = depth,
                fileIndex = null,
                size = children.sumOf { it.size },
                downloaded = children.sumOf { it.downloaded },
                children = children,
            )
        }
        val files = node.files.sortedBy { it.name.lowercase() }.map { f ->
            FileTreeNode(f.name, f.path, depth, f.index, f.size, f.downloaded)
        }
        return folders + files
    }

    /** Rows visible with the given expanded folders. */
    fun flatten(nodes: List<FileTreeNode>, expanded: Set<String>): List<FileTreeNode> {
        val out = ArrayList<FileTreeNode>()
        fun walk(list: List<FileTreeNode>) {
            for (n in list) {
                out += n
                if (n.isFolder && n.path in expanded) walk(n.children)
            }
        }
        walk(nodes)
        return out
    }

    /** All folder paths, used to expand everything for small torrents. */
    fun allFolders(nodes: List<FileTreeNode>): Set<String> =
        nodes.flatMap { n -> if (n.isFolder) listOf(n.path) + allFolders(n.children) else emptyList() }.toSet()

    fun checkState(node: FileTreeNode, priorities: Map<Int, FilePriority>): ToggleableState {
        val indices = node.fileIndices
        val selected = indices.count { (priorities[it] ?: FilePriority.NORMAL) != FilePriority.SKIP }
        return when (selected) {
            0 -> ToggleableState.Off
            indices.size -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        }
    }

    /** New priorities after toggling a node: unchecked/partial → selected (NORMAL), checked → SKIP. */
    fun toggle(node: FileTreeNode, priorities: Map<Int, FilePriority>): Map<Int, FilePriority> {
        val target = if (checkState(node, priorities) == ToggleableState.On) FilePriority.SKIP else FilePriority.NORMAL
        return node.fileIndices.associateWith { idx ->
            val current = priorities[idx] ?: FilePriority.NORMAL
            if (target == FilePriority.NORMAL && current != FilePriority.SKIP) current else target
        }
    }

    fun selectedSize(nodes: List<FileTreeNode>, priorities: Map<Int, FilePriority>): Long {
        var total = 0L
        fun walk(list: List<FileTreeNode>) {
            for (n in list) {
                if (n.isFolder) walk(n.children)
                else if ((priorities[n.fileIndex!!] ?: FilePriority.NORMAL) != FilePriority.SKIP) total += n.size
            }
        }
        walk(nodes)
        return total
    }

    fun iconFor(node: FileTreeNode, expanded: Boolean): ImageVector {
        if (node.isFolder) return if (expanded) GraphiteIcons.FolderOpen else GraphiteIcons.Folder
        return when (node.name.substringAfterLast('.', "").lowercase()) {
            in videoExtensions -> GraphiteIcons.FileVideo
            in audioExtensions -> GraphiteIcons.FileAudio
            "zip", "rar", "7z", "tar", "gz", "xz", "bz2", "zst", "iso", "img", "dmg" -> GraphiteIcons.FileArchive
            "txt", "nfo", "md", "pdf", "epub", "fb2", "srt", "ass", "sub" -> GraphiteIcons.FileText
            else -> GraphiteIcons.File
        }
    }

    val videoExtensions = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg", "3gp")
    val audioExtensions = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ape", "wma", "alac")

    fun isPlayable(name: String): Boolean = name.substringAfterLast('.', "").lowercase().let { it in videoExtensions || it in audioExtensions }
}
