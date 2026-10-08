package dev.armen.fileconverter.engine.cloud

import dev.armen.fileconverter.domain.ConversionError
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudConvertClientTest {
    private val server = MockWebServer()

    @After fun tearDown() = server.shutdown()

    @Test fun `me returns username and credits`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"id":1,"username":"armen","email":"x@y.z","credits":25}}"""))
        val user = CloudConvertClient(OkHttpClient(), "k", server.url("/v2/")).me()
        assertEquals("armen", user.username)
        assertEquals(25, user.credits)
        val request = server.takeRequest()
        assertEquals("/v2/users/me", request.path)
        assertEquals("Bearer k", request.getHeader("Authorization"))
    }

    @Test fun `maps http errors`() {
        assertTrue(CloudConvertClient.mapHttpError(401, null) is ConversionError.InvalidApiKey)
        assertTrue(CloudConvertClient.mapHttpError(403, null) is ConversionError.InvalidApiKey)
        assertTrue(CloudConvertClient.mapHttpError(402, null) is ConversionError.QuotaExceeded)
        assertTrue(CloudConvertClient.mapHttpError(429, null) is ConversionError.QuotaExceeded)
        assertEquals("CloudConvert: boom", CloudConvertClient.mapHttpError(500, """{"message":"boom"}""").details)
        assertEquals("CloudConvert: HTTP 404", CloudConvertClient.mapHttpError(404, "").details)
    }

    @Test fun `unreachable server is no network`() {
        val client = CloudConvertClient(OkHttpClient(), "k", "http://127.0.0.1:1/v2/".toHttpUrl())
        assertThrows(ConversionError.NoNetwork::class.java) { runBlocking { client.me() } }
    }
}
