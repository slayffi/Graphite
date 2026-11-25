package com.graphite.torrent.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.graphite.torrent.data.db.GraphiteDatabase
import java.io.File
import java.util.Properties

object DatabaseFactory {
    fun createDriver(file: File): SqlDriver {
        file.parentFile?.mkdirs()
        val properties = Properties().apply { setProperty("journal_mode", "WAL") }
        return JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", properties, GraphiteDatabase.Schema)
    }

    fun createInMemory(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), GraphiteDatabase.Schema)
}
