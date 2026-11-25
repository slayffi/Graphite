package com.graphite.torrent.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.graphite.torrent.data.db.GraphiteDatabase

object DatabaseFactory {
    fun createDriver(context: Context, name: String = "graphite.db"): SqlDriver =
        AndroidSqliteDriver(GraphiteDatabase.Schema, context, name)
}
