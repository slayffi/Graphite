package com.graphite.torrent.android

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What [MainActivity] offers to code that lives outside the activity (pickers, permission prompts). */
interface BridgeHost {
    suspend fun openDocuments(mimeTypes: Array<String>, single: Boolean): List<Uri>
    suspend fun openTree(): Uri?
    suspend fun createDocument(name: String, mimeType: String): Uri?
    suspend fun scanQr(): String?
    fun requestPermission(permission: String)
}

/** Connects platform services to the activity that is currently on screen. */
class ActivityBridge {
    @Volatile
    var host: BridgeHost? = null

    suspend fun openDocuments(mimeTypes: Array<String>, single: Boolean = false): List<Uri>? =
        withContext(Dispatchers.Main) { host?.openDocuments(mimeTypes, single) }

    suspend fun openTree(): Uri? = withContext(Dispatchers.Main) { host?.openTree() }

    suspend fun createDocument(name: String, mimeType: String): Uri? =
        withContext(Dispatchers.Main) { host?.createDocument(name, mimeType) }

    suspend fun scanQr(): String? = withContext(Dispatchers.Main) { host?.scanQr() }

    fun requestPermission(permission: String) {
        host?.requestPermission(permission)
    }
}
