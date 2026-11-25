package com.graphite.torrent.core.engine

import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.TorrentError
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException

/** Turns libtorrent error codes and JVM exceptions into user-facing error categories. */
object ErrorMapper {
    // POSIX errno (Linux/Android) and Win32 codes that matter to users.
    private val noSpaceCodes = setOf(28, 112, 39)
    private val accessCodes = setOf(1, 13, 5)
    private val notFoundCodes = setOf(2, 3, 20, 30)
    private val networkCodes = setOf(101, 113, 10050, 10051, 10065)
    private val addressInUseCodes = setOf(98, 48, 10048)

    fun fromCode(value: Int, message: String): TorrentError {
        val m = message.lowercase()
        val kind = when {
            "no space" in m || "not enough space" in m || "disk full" in m || "disk is full" in m -> ErrorKind.NO_SPACE
            "permission denied" in m || "access is denied" in m || "operation not permitted" in m ||
                "read-only file system" in m -> ErrorKind.PERMISSION_DENIED
            "no such file" in m || "cannot find the path" in m || "cannot find the file" in m ||
                "not a directory" in m -> ErrorKind.FOLDER_UNAVAILABLE
            "address already in use" in m || "only one usage of each socket address" in m -> ErrorKind.PORT_IN_USE
            "network is unreachable" in m || "no route to host" in m || "host is unreachable" in m ||
                "name or service not known" in m || "host not found" in m -> ErrorKind.NO_NETWORK
            "invalid torrent" in m || "bdecode" in m || "invalid bencoding" in m || "missing info" in m ||
                "invalid info" in m || "parse" in m && "torrent" in m -> ErrorKind.INVALID_TORRENT
            "duplicate" in m -> ErrorKind.DUPLICATE
            value in noSpaceCodes -> ErrorKind.NO_SPACE
            value in accessCodes -> ErrorKind.PERMISSION_DENIED
            value in notFoundCodes -> ErrorKind.FOLDER_UNAVAILABLE
            value in networkCodes -> ErrorKind.NO_NETWORK
            value in addressInUseCodes -> ErrorKind.PORT_IN_USE
            else -> ErrorKind.FILE_IO
        }
        return TorrentError(kind, message.ifBlank { "error $value" })
    }

    fun fromException(t: Throwable): TorrentError {
        if (t is com.graphite.torrent.core.model.GraphiteException) return t.error
        val detail = t.message ?: t::class.simpleName.orEmpty()
        val kind = when (t) {
            is UnknownHostException, is NoRouteToHostException, is ConnectException -> ErrorKind.NO_NETWORK
            is SocketTimeoutException -> ErrorKind.NO_NETWORK
            is AccessDeniedException, is SecurityException -> ErrorKind.PERMISSION_DENIED
            is NoSuchFileException, is FileNotFoundException -> ErrorKind.FOLDER_UNAVAILABLE
            is IllegalArgumentException -> ErrorKind.INVALID_TORRENT
            else -> return fromCode(0, detail).let { if (it.kind == ErrorKind.FILE_IO) TorrentError(ErrorKind.UNKNOWN, detail) else it }
        }
        return TorrentError(kind, detail)
    }

    fun isAddressInUse(value: Int, message: String): Boolean =
        value in addressInUseCodes || message.lowercase().let { "in use" in it || "only one usage" in it }
}
