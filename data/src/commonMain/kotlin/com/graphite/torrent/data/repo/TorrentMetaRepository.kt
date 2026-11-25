package com.graphite.torrent.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.graphite.torrent.core.model.Category
import com.graphite.torrent.data.db.GraphiteDatabase
import com.graphite.torrent.data.db.Torrent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Category, tags, timestamps and final location of torrents. */
class TorrentMetaRepository(
    private val db: GraphiteDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val q get() = db.torrentQueries

    val rows: Flow<Map<String, Torrent>> =
        q.selectAll().asFlow().mapToList(io).map { list -> list.associateBy { it.id } }

    val tags: Flow<Map<String, List<String>>> =
        q.selectAllTags().asFlow().mapToList(io).map { list ->
            list.groupBy({ it.torrent_id }, { it.tag }).mapValues { (_, v) -> v.sorted() }
        }

    val allTags: Flow<List<String>> = q.distinctTags().asFlow().mapToList(io)

    val categories: Flow<List<Category>> =
        db.categoryQueries.selectAll().asFlow().mapToList(io).map { list -> list.map { Category(it.name, it.save_path) } }

    suspend fun get(id: String): Torrent? = withContext(io) { q.selectById(id).executeAsOneOrNull() }

    suspend fun all(): List<Torrent> = withContext(io) { q.selectAll().executeAsList() }

    suspend fun tagsOf(id: String): List<String> = withContext(io) { q.selectTagsFor(id).executeAsList() }

    suspend fun insert(
        id: String,
        name: String,
        category: String?,
        addedAt: Long,
        savePath: String,
        totalSize: Long,
        source: String,
        tags: List<String>,
    ) = withContext(io) {
        db.transaction {
            q.insertIfMissing(id, name, category, addedAt, 0, savePath, null, totalSize, source)
            q.updateInfo(name, savePath, totalSize, id)
            if (category != null) q.setCategory(category, id)
            tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { q.insertTag(id, it) }
        }
    }

    suspend fun ensureExists(id: String, name: String, addedAt: Long, savePath: String) = withContext(io) {
        q.insertIfMissing(id, name, null, addedAt, 0, savePath, null, 0, "")
    }

    suspend fun restore(row: Torrent, tags: List<String>) = withContext(io) {
        db.transaction {
            q.insertOrReplace(
                row.id, row.name, row.category, row.added_at, row.completed_at, row.save_path,
                row.final_location, row.total_size, row.source,
            )
            q.deleteTagsFor(row.id)
            tags.forEach { q.insertTag(row.id, it) }
        }
    }

    suspend fun updateInfo(id: String, name: String, savePath: String, totalSize: Long) = withContext(io) {
        q.updateInfo(name, savePath, totalSize, id)
    }

    /** Returns true when the row was not marked completed before (first completion). */
    suspend fun markCompleted(id: String, at: Long): Boolean = withContext(io) {
        q.markCompleted(at, id).value > 0
    }

    suspend fun setFinalLocation(id: String, location: String?) = withContext(io) { q.setFinalLocation(location, id) }

    suspend fun setSavePath(id: String, path: String) = withContext(io) { q.setSavePath(path, id) }

    suspend fun setCategory(ids: Collection<String>, category: String?) = withContext(io) {
        db.transaction { ids.forEach { q.setCategory(category, it) } }
    }

    suspend fun setTags(id: String, tags: List<String>) = withContext(io) {
        db.transaction {
            q.deleteTagsFor(id)
            tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { q.insertTag(id, it) }
        }
    }

    suspend fun delete(id: String) = withContext(io) {
        db.transaction {
            q.deleteTagsFor(id)
            q.deleteById(id)
        }
    }

    suspend fun saveCategory(category: Category) = withContext(io) {
        db.categoryQueries.upsert(category.name.trim(), category.savePath)
    }

    suspend fun deleteCategory(name: String) = withContext(io) {
        db.transaction {
            db.categoryQueries.deleteByName(name)
            q.clearCategory(name)
        }
    }
}
