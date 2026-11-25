package com.graphite.torrent.core.engine

import com.graphite.torrent.core.model.ErrorKind
import java.io.FileNotFoundException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorMapperTest {
    @Test
    fun mapsCommonFailures() {
        assertEquals(ErrorKind.NO_SPACE, ErrorMapper.fromCode(28, "No space left on device").kind)
        assertEquals(ErrorKind.NO_SPACE, ErrorMapper.fromCode(112, "There is not enough space on the disk.").kind)
        assertEquals(ErrorKind.PERMISSION_DENIED, ErrorMapper.fromCode(13, "Permission denied").kind)
        assertEquals(ErrorKind.FOLDER_UNAVAILABLE, ErrorMapper.fromCode(2, "No such file or directory").kind)
        assertEquals(ErrorKind.NO_NETWORK, ErrorMapper.fromCode(101, "Network is unreachable").kind)
        assertEquals(ErrorKind.PORT_IN_USE, ErrorMapper.fromCode(98, "Address already in use").kind)
        assertEquals(ErrorKind.NO_SPACE, ErrorMapper.fromCode(28, "").kind)
    }

    @Test
    fun mapsExceptions() {
        assertEquals(ErrorKind.NO_NETWORK, ErrorMapper.fromException(UnknownHostException("x")).kind)
        assertEquals(ErrorKind.FOLDER_UNAVAILABLE, ErrorMapper.fromException(FileNotFoundException("x")).kind)
        assertEquals(ErrorKind.UNKNOWN, ErrorMapper.fromException(IllegalStateException("weird")).kind)
    }

    @Test
    fun detectsAddressInUse() {
        assertTrue(ErrorMapper.isAddressInUse(10048, ""))
        assertTrue(ErrorMapper.isAddressInUse(0, "Address already in use"))
    }
}
