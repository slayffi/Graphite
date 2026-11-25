package com.graphite.torrent.core.engine

/**
 * Makes sure the libtorrent4j JNI library can be loaded on the current platform.
 *
 * On Android the `.so` files are packaged into the APK by the Android Gradle plugin and
 * `System.loadLibrary` finds them. On desktop the library lives inside a jar and has to be
 * extracted to a real file first; its path is then passed through the `libtorrent4j.jni.path`
 * system property, which libtorrent4j checks before calling `System.loadLibrary`.
 */
expect object NativeLibraries {
    /** @param extractDir writable directory for extracted libraries (desktop only). */
    fun ensureLoaded(extractDir: String?)
}
