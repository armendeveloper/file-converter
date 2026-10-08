package dev.armen.fileconverter.work

import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.armen.fileconverter.domain.ConversionRequest
import kotlinx.coroutines.flow.Flow
import java.io.File

class ConversionScheduler(private val workManager: WorkManager) {

    /**
     * Writes each request to [batchDir] (WorkManager input data is limited to 10 KB) and enqueues one
     * worker per request. Returns the batch tag used to observe or cancel the batch.
     */
    fun enqueue(batchId: String, batchDir: File, requests: List<ConversionRequest>): String {
        val tag = batchTag(batchId)
        batchDir.mkdirs()
        requests.forEachIndexed { index, request ->
            val file = File(batchDir, "request-$index.json").apply { writeText(request.toJson()) }
            val label = if (request.inputs.size > 1) {
                "${request.inputs.size} images → ${request.target.label}"
            } else {
                "${request.inputs.first().displayName} → ${request.target.label}"
            }
            val work = OneTimeWorkRequestBuilder<ConversionWorker>()
                .setInputData(workDataOf(ConversionWorker.KEY_REQUEST_FILE to file.absolutePath))
                .addTag(TAG_ALL)
                .addTag(tag)
                .addTag(LABEL_PREFIX + label)
                .addTag(INDEX_PREFIX + index)
                .build()
            workManager.enqueue(work)
        }
        return tag
    }

    fun observe(tag: String): Flow<List<WorkInfo>> = workManager.getWorkInfosByTagFlow(tag)

    fun cancel(tag: String) {
        workManager.cancelAllWorkByTag(tag)
    }

    companion object {
        const val TAG_ALL = "conversion"
        const val LABEL_PREFIX = "label:"
        const val INDEX_PREFIX = "index:"
        private const val BATCH_PREFIX = "batch:"

        fun batchTag(batchId: String) = BATCH_PREFIX + batchId

        fun batchId(tag: String) = tag.removePrefix(BATCH_PREFIX)
    }
}
