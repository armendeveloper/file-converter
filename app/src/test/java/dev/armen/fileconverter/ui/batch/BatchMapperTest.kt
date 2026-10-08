package dev.armen.fileconverter.ui.batch

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import dev.armen.fileconverter.domain.ErrorKind
import dev.armen.fileconverter.work.ConversionScheduler
import dev.armen.fileconverter.work.ConversionWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BatchMapperTest {
    private fun info(state: WorkInfo.State, index: Int, output: Data = Data.EMPTY, progress: Data = Data.EMPTY) = WorkInfo(
        id = UUID.randomUUID(),
        state = state,
        tags = setOf(
            ConversionScheduler.TAG_ALL,
            "${ConversionScheduler.INDEX_PREFIX}$index",
            "${ConversionScheduler.LABEL_PREFIX}file$index.docx → PDF",
        ),
        outputData = output,
        progress = progress,
    )

    @Test fun `sorts by queue index and reads the label`() {
        val items = BatchMapper.map(listOf(info(WorkInfo.State.ENQUEUED, 2), info(WorkInfo.State.RUNNING, 0), info(WorkInfo.State.BLOCKED, 1)))
        assertEquals(listOf(0, 1, 2), items.map { it.index })
        assertEquals("file0.docx → PDF", items[0].label)
        assertEquals(listOf(ItemState.RUNNING, ItemState.QUEUED, ItemState.QUEUED), items.map { it.state })
    }

    @Test fun `succeeded work carrying an error is a failure`() {
        val output = workDataOf(
            ConversionWorker.KEY_ERROR_KIND to "MISSING_API_KEY",
            ConversionWorker.KEY_ERROR_MESSAGE to "Add your CloudConvert API key in Settings.",
        )
        val item = BatchMapper.toItem(info(WorkInfo.State.SUCCEEDED, 0, output = output))
        assertEquals(ItemState.FAILED, item.state)
        assertEquals(ErrorInfo(ErrorKind.MISSING_API_KEY, "Add your CloudConvert API key in Settings.", null), item.error)
        assertTrue(item.outputs.isEmpty())
    }

    @Test fun `done work lists its outputs and total`() {
        val output = Data.Builder()
            .putStringArray(ConversionWorker.KEY_OUTPUT_URIS, arrayOf("content://a/1", "content://a/2"))
            .putStringArray(ConversionWorker.KEY_OUTPUT_NAMES, arrayOf("doc-1.png", "doc-2.png"))
            .putLongArray(ConversionWorker.KEY_OUTPUT_SIZES, longArrayOf(10, 20))
            .putInt(ConversionWorker.KEY_OUTPUT_TOTAL, 45)
            .putBoolean(ConversionWorker.KEY_BASIC, true)
            .build()
        val item = BatchMapper.toItem(info(WorkInfo.State.SUCCEEDED, 0, output = output))
        assertEquals(ItemState.DONE, item.state)
        assertEquals(listOf(SavedOutput("content://a/1", "doc-1.png", 10), SavedOutput("content://a/2", "doc-2.png", 20)), item.outputs)
        assertEquals(45, item.totalOutputs)
        assertTrue(item.basic)
        assertEquals(1f, item.progress)
    }

    @Test fun `running work reports progress and stage`() {
        val progress = workDataOf(ConversionWorker.KEY_PROGRESS to 0.25f, ConversionWorker.KEY_STAGE to ConversionWorker.STAGE_CONVERTING)
        val item = BatchMapper.toItem(info(WorkInfo.State.RUNNING, 0, progress = progress))
        assertEquals(0.25f, item.progress)
        assertEquals(ConversionWorker.STAGE_CONVERTING, item.stage)
    }

    @Test fun `failed work without details still has an error`() =
        assertEquals(ErrorKind.ENGINE_FAILED, BatchMapper.toItem(info(WorkInfo.State.FAILED, 0)).error?.kind)

    @Test fun `overall progress counts finished items as complete`() {
        val done = BatchMapper.toItem(info(WorkInfo.State.SUCCEEDED, 0))
        val half = BatchMapper.toItem(info(WorkInfo.State.RUNNING, 1, progress = workDataOf(ConversionWorker.KEY_PROGRESS to 0.5f)))
        assertEquals(0.75f, BatchMapper.overallProgress(listOf(done, half)), 0.001f)
        assertFalse(BatchMapper.allFinished(listOf(done, half)))
        assertTrue(BatchMapper.allFinished(listOf(done, BatchMapper.toItem(info(WorkInfo.State.CANCELLED, 1)))))
        assertFalse(BatchMapper.allFinished(emptyList()))
    }
}
