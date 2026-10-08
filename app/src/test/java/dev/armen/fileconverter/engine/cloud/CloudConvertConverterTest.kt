package dev.armen.fileconverter.engine.cloud

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.engine.ConversionTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

class CloudConvertConverterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val deleted = CopyOnWriteArrayList<String>()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.shutdown()

    private fun createJson() = """{"data":{"id":"job-1","status":"waiting","tasks":[
        {"id":"t1","name":"upload-file","operation":"import/upload","status":"waiting","result":{"form":{"url":"${server.url("/upload")}","parameters":{"expires":1700000000,"signature":"abc"}}}},
        {"id":"t2","name":"convert-file","operation":"convert","status":"waiting"},
        {"id":"t3","name":"export-file","operation":"export/url","status":"waiting"}]}}"""

    private val processingJson = """{"data":{"id":"job-1","status":"processing","tasks":[]}}"""

    private fun finishedJson() = """{"data":{"id":"job-1","status":"finished","tasks":[
        {"id":"t3","name":"export-file","operation":"export/url","status":"finished","result":{"files":[{"filename":"report.pdf","url":"${server.url("/files/report.pdf")}"}]}}]}}"""

    private val errorJson = """{"data":{"id":"job-1","status":"error","tasks":[
        {"id":"t2","name":"convert-file","operation":"convert","status":"error","code":"INVALID_CONVERSION_TYPE","message":"This conversion is not supported"}]}}"""

    private fun dispatcher(statuses: List<String>, defaultStatus: String = "finished", createCode: Int = 201) = object : Dispatcher() {
        private val queue = ConcurrentLinkedQueue(statuses)

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                request.method == "POST" && path == "/v2/jobs" ->
                    if (createCode == 201) MockResponse().setResponseCode(201).setBody(createJson())
                    else MockResponse().setResponseCode(createCode).setBody("""{"message":"nope","code":"X"}""")
                request.method == "POST" && path == "/upload" -> MockResponse().setResponseCode(201)
                request.method == "GET" && path == "/v2/jobs/job-1" -> when (queue.poll() ?: defaultStatus) {
                    "processing" -> MockResponse().setBody(processingJson)
                    "error" -> MockResponse().setBody(errorJson)
                    else -> MockResponse().setBody(finishedJson())
                }
                request.method == "GET" && path == "/files/report.pdf" -> MockResponse().setBody("PDFDATA")
                request.method == "DELETE" && path == "/v2/jobs/job-1" -> {
                    deleted += path
                    MockResponse().setResponseCode(204)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun converter() =
        CloudConvertConverter(CloudConvertClient(OkHttpClient(), "secret", server.url("/v2/")), pollIntervalMs = 5, timeoutMs = 5_000)

    private fun task(): ConversionTask {
        val input = tmp.newFile("report.docx").apply { writeText("DOCX") }
        return ConversionTask(listOf(input), FileFormat.DOCX, FileFormat.PDF, ConversionOptions(), tmp.newFolder("out"), "report")
    }

    @Test fun `uploads, polls, downloads and cleans up`() = runBlocking {
        server.dispatcher = dispatcher(listOf("processing", "finished"))
        val progress = mutableListOf<Float>()
        val outputs = converter().convert(task()) { progress += it }

        assertEquals(listOf("report.pdf"), outputs.map { it.name })
        assertEquals("PDFDATA", outputs.single().readText())
        val create = server.takeRequest()
        assertEquals("Bearer secret", create.getHeader("Authorization"))
        val body = create.body.readUtf8()
        assertTrue(body, body.contains("\"input_format\":\"docx\""))
        assertTrue(body, body.contains("\"output_format\":\"pdf\""))
        val upload = server.takeRequest()
        assertEquals("/upload", upload.path)
        assertNull(upload.getHeader("Authorization"))
        val uploadBody = upload.body.readUtf8()
        assertTrue(uploadBody.contains("name=\"signature\""))
        assertTrue(uploadBody.contains("1700000000"))
        assertTrue(uploadBody.contains("DOCX"))
        assertEquals(listOf("/v2/jobs/job-1"), deleted)
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun `rejected key`() {
        server.dispatcher = dispatcher(emptyList(), createCode = 401)
        assertThrows(ConversionError.InvalidApiKey::class.java) { runBlocking { converter().convert(task()) {} } }
    }

    @Test fun `out of credits`() {
        server.dispatcher = dispatcher(emptyList(), createCode = 402)
        assertThrows(ConversionError.QuotaExceeded::class.java) { runBlocking { converter().convert(task()) {} } }
    }

    @Test fun `failed job reports the task message and still cleans up`() {
        server.dispatcher = dispatcher(listOf("error"))
        val error = assertThrows(ConversionError.EngineFailed::class.java) { runBlocking { converter().convert(task()) {} } }
        assertEquals("CloudConvert: This conversion is not supported", error.details)
        assertEquals(listOf("/v2/jobs/job-1"), deleted)
    }

    @Test fun `cancelling deletes the remote job`() = runBlocking {
        server.dispatcher = dispatcher(emptyList(), defaultStatus = "processing")
        val conversion = task()
        val job = launch(Dispatchers.IO) { converter().convert(conversion) {} }
        withTimeout(5_000) { while (server.requestCount < 3) delay(10) } // create, upload, first poll
        job.cancelAndJoin()
        assertEquals(listOf("/v2/jobs/job-1"), deleted)
    }

    @Test fun `files over the size limit are refused before uploading`() {
        val small = CloudConvertConverter(CloudConvertClient(OkHttpClient(), "secret", server.url("/v2/")), maxInputBytes = 3)
        assertThrows(ConversionError.EngineFailed::class.java) { runBlocking { small.convert(task()) {} } } // input is 4 bytes
        assertEquals(0, server.requestCount)
    }
}
