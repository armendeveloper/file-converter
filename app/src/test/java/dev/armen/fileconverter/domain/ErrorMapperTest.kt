package dev.armen.fileconverter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

class ErrorMapperTest {
    @Test fun `conversion errors pass through unchanged`() {
        val error = ConversionError.MissingApiKey()
        assertSame(error, ErrorMapper.map(error))
    }

    @Test fun `dns and connect failures are no network`() {
        assertEquals(ErrorKind.NO_NETWORK, ErrorMapper.map(UnknownHostException("api.cloudconvert.com")).kind)
        assertEquals(ErrorKind.NO_NETWORK, ErrorMapper.map(ConnectException("refused")).kind)
    }

    @Test fun `ENOSPC is insufficient storage, also when nested`() {
        assertEquals(ErrorKind.INSUFFICIENT_STORAGE, ErrorMapper.map(IOException("write failed: ENOSPC (No space left on device)")).kind)
        assertEquals(ErrorKind.INSUFFICIENT_STORAGE, ErrorMapper.map(IOException("copy failed", IOException("No space left on device"))).kind)
    }

    @Test fun `other io errors are engine failures with details`() {
        val mapped = ErrorMapper.map(IOException("broken pipe"))
        assertEquals(ErrorKind.ENGINE_FAILED, mapped.kind)
        assertEquals("broken pipe", mapped.details)
    }

    @Test fun `out of memory is an engine failure`() {
        val mapped = ErrorMapper.map(OutOfMemoryError())
        assertEquals(ErrorKind.ENGINE_FAILED, mapped.kind)
        assertTrue(mapped.details!!.contains("too large"))
    }

    @Test fun `exception without message uses its class name`() =
        assertEquals("IllegalStateException", ErrorMapper.map(IllegalStateException()).details)
}
