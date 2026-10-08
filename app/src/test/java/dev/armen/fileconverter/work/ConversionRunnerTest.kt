package dev.armen.fileconverter.work

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionInput
import dev.armen.fileconverter.domain.ConversionRequest
import dev.armen.fileconverter.domain.Engine
import dev.armen.fileconverter.domain.Fidelity
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.Route
import dev.armen.fileconverter.engine.ConversionTask
import dev.armen.fileconverter.engine.Converter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class ConversionRunnerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val usedEngines = mutableListOf<Engine>()
    private var lastTask: ConversionTask? = null

    private val writesOneFile = object : Converter {
        override suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File> {
            lastTask = task
            onProgress(1f)
            return listOf(File(task.outputDir, "${task.baseName}.${task.target.extension}").apply { writeText("x") })
        }
    }

    private fun converterThatThrows(error: Throwable) = object : Converter {
        override suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File> = throw error
    }

    private val producesNothing = object : Converter {
        override suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File> = emptyList()
    }

    private fun runner(status: CloudStatus, converter: Converter = writesOneFile) =
        ConversionRunner(34, cloudStatus = { status }, converterFor = { engine -> usedEngines += engine; converter })

    private fun request(source: FileFormat, target: FileFormat, name: String = "My file.docx", inputs: Int = 1) =
        ConversionRequest(List(inputs) { ConversionInput("/in/$it", name) }, source, target)

    private fun run(runner: ConversionRunner, request: ConversionRequest): RunResult = runBlocking {
        runner.run(request, request.inputs.map { File(it.path) }, tmp.newFolder()) {}
    }

    @Test fun `docx to pdf without a key falls back to basic offline`() {
        val result = run(runner(CloudStatus.NO_KEY), request(FileFormat.DOCX, FileFormat.PDF))
        assertEquals(Route(Engine.OFFICE_LITE, Fidelity.BASIC), result.route)
        assertEquals(listOf(Engine.OFFICE_LITE), usedEngines)
    }

    @Test fun `docx to pdf with cloud available uses cloud`() =
        assertEquals(Engine.CLOUD, run(runner(CloudStatus.AVAILABLE), request(FileFormat.DOCX, FileFormat.PDF)).route.engine)

    @Test fun `cloud-only pair without a key asks for one`() {
        assertThrows(ConversionError.MissingApiKey::class.java) { run(runner(CloudStatus.NO_KEY), request(FileFormat.DOCX, FileFormat.ODT)) }
    }

    @Test fun `cloud-only pair while offline reports no network`() {
        assertThrows(ConversionError.NoNetwork::class.java) { run(runner(CloudStatus.OFFLINE), request(FileFormat.DOCX, FileFormat.ODT)) }
    }

    @Test fun `impossible pair is unsupported`() {
        assertThrows(ConversionError.UnsupportedPair::class.java) { run(runner(CloudStatus.AVAILABLE), request(FileFormat.MP3, FileFormat.PDF)) }
    }

    @Test fun `output base name comes from the sanitised display name`() {
        run(runner(CloudStatus.AVAILABLE), request(FileFormat.MP4, FileFormat.MP3, name = "Holiday: day 1.mp4"))
        assertEquals("Holiday_ day 1", lastTask!!.baseName)
    }

    @Test fun `only images to pdf may have several inputs`() {
        assertThrows(ConversionError.EngineFailed::class.java) {
            run(runner(CloudStatus.AVAILABLE), request(FileFormat.MP4, FileFormat.MP3, inputs = 2))
        }
        assertEquals(Engine.LOCAL_PDF, run(runner(CloudStatus.AVAILABLE), request(FileFormat.JPG, FileFormat.PDF, name = "a.jpg", inputs = 3)).route.engine)
    }

    @Test fun `engine exceptions are mapped`() {
        assertThrows(ConversionError.InsufficientStorage::class.java) {
            run(runner(CloudStatus.AVAILABLE, converterThatThrows(IOException("write failed: ENOSPC"))), request(FileFormat.WAV, FileFormat.MP3))
        }
    }

    @Test fun `empty output is an engine failure`() {
        assertThrows(ConversionError.EngineFailed::class.java) {
            run(runner(CloudStatus.AVAILABLE, producesNothing), request(FileFormat.WAV, FileFormat.MP3))
        }
    }

    @Test fun `cancellation is passed through untouched`() {
        assertThrows(CancellationException::class.java) {
            run(runner(CloudStatus.AVAILABLE, converterThatThrows(CancellationException("stop"))), request(FileFormat.WAV, FileFormat.MP3))
        }
    }
}
