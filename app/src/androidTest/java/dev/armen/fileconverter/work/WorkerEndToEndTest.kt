package dev.armen.fileconverter.work

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import dev.armen.fileconverter.FileConverterApp
import dev.armen.fileconverter.domain.ConversionInput
import dev.armen.fileconverter.domain.ConversionRequest
import dev.armen.fileconverter.domain.ErrorKind
import dev.armen.fileconverter.domain.FileFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkerEndToEndTest {
    private val app = ApplicationProvider.getApplicationContext<FileConverterApp>()
    private val container get() = app.container

    private fun newBatch(): Pair<String, File> {
        val id = UUID.randomUUID().toString()
        return id to File(container.queueDir(), id).apply { mkdirs() }
    }

    private fun awaitFinished(tag: String): WorkInfo = runBlocking {
        withTimeout(60_000) {
            container.scheduler.observe(tag).first { infos -> infos.isNotEmpty() && infos.all { it.state.isFinished } }.single()
        }
    }

    @Test fun txtToPdfIsSavedToDownloads() {
        val (id, dir) = newBatch()
        val input = File(dir, "0-notes.txt").apply { writeText("hello\nworld") }
        val request = ConversionRequest(listOf(ConversionInput(input.path, "notes.txt")), FileFormat.TXT, FileFormat.PDF)
        val info = awaitFinished(container.scheduler.enqueue(id, dir, listOf(request)))

        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertNull(info.outputData.getString(ConversionWorker.KEY_ERROR_KIND))
        val uri = Uri.parse(info.outputData.getStringArray(ConversionWorker.KEY_OUTPUT_URIS)!!.single())
        try {
            val head = ByteArray(4)
            app.contentResolver.openInputStream(uri)!!.use { it.read(head) }
            assertEquals("%PDF", String(head, Charsets.US_ASCII))
            assertFalse("input should be deleted after conversion", input.exists())
        } finally {
            app.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun unsupportedPairReportsAnErrorWithoutFailingTheQueue() {
        val (id, dir) = newBatch()
        val input = File(dir, "0-a.mp3").apply { writeText("x") }
        val request = ConversionRequest(listOf(ConversionInput(input.path, "a.mp3")), FileFormat.MP3, FileFormat.PDF)
        val info = awaitFinished(container.scheduler.enqueue(id, dir, listOf(request)))

        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertEquals(ErrorKind.UNSUPPORTED_PAIR.name, info.outputData.getString(ConversionWorker.KEY_ERROR_KIND))
    }

    @Test fun largeMergeRequestCanBeEnqueued() {
        val (id, dir) = newBatch()
        val inputs = List(150) { ConversionInput(File(dir, "$it-" + "x".repeat(120) + ".jpg").path, "photo-$it.jpg") }
        val tag = container.scheduler.enqueue(id, dir, listOf(ConversionRequest(inputs, FileFormat.JPG, FileFormat.PDF)))
        assertTrue(tag.startsWith("batch:"))
        container.scheduler.cancel(tag)
        dir.deleteRecursively()
    }
}
