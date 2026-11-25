package com.graphite.torrent.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.graphite.torrent.core.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath

/** JSON format shared by the settings store, export/import and the remote protocol. */
val GraphiteJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}

internal object AppSettingsSerializer : OkioSerializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(source: BufferedSource): AppSettings {
        val text = source.readUtf8()
        if (text.isBlank()) return defaultValue
        return try {
            GraphiteJson.decodeFromString(AppSettings.serializer(), text)
        } catch (e: SerializationException) {
            throw CorruptionException("Settings file is not valid JSON", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Settings file is not valid", e)
        }
    }

    override suspend fun writeTo(t: AppSettings, sink: BufferedSink) {
        sink.writeUtf8(GraphiteJson.encodeToString(AppSettings.serializer(), t))
    }
}

/** Typed DataStore holding [AppSettings] as JSON. */
class SettingsRepository(private val store: DataStore<AppSettings>, scope: CoroutineScope) {

    val settings: StateFlow<AppSettings> = store.data.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = store.data.first()

    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = store.updateData { transform(it) }

    suspend fun replace(settings: AppSettings): AppSettings = store.updateData { settings }

    companion object {
        fun create(filePath: String, scope: CoroutineScope): SettingsRepository {
            val store = DataStoreFactory.create(
                storage = OkioStorage(FileSystem.SYSTEM, AppSettingsSerializer) { filePath.toPath() },
                corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
                scope = scope,
            )
            return SettingsRepository(store, scope)
        }
    }
}
