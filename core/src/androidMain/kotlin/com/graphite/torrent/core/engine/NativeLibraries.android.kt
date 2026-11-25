package com.graphite.torrent.core.engine

import org.libtorrent4j.LibTorrent

actual object NativeLibraries {
    @Volatile
    private var loaded = false

    actual fun ensureLoaded(extractDir: String?) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            // Touching any native method triggers System.loadLibrary("torrent4j") inside libtorrent4j.
            LibTorrent.version()
            loaded = true
        }
    }
}
