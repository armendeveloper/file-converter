package dev.armen.fileconverter.ui

import android.net.Uri

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val CONVERT = "convert"
    const val BATCH_ARG = "batch"
    const val PROGRESS = "progress/{$BATCH_ARG}"
    const val RESULT = "result/{$BATCH_ARG}"

    fun progress(batchTag: String) = "progress/${Uri.encode(batchTag)}"

    fun result(batchTag: String) = "result/${Uri.encode(batchTag)}"
}
