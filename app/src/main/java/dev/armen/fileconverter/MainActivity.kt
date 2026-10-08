package dev.armen.fileconverter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import dev.armen.fileconverter.ui.AppNavHost
import dev.armen.fileconverter.ui.OpenBatchRequest
import dev.armen.fileconverter.ui.theme.FileConverterTheme

class MainActivity : ComponentActivity() {
    private var shareSignal by mutableIntStateOf(0)
    private var openBatch by mutableStateOf<OpenBatchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FileConverterApp).container
        if (savedInstanceState == null) handleIntent(intent)
        setContent { FileConverterTheme { AppNavHost(container, shareSignal, openBatch) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val batchTag = intent?.getStringExtra(EXTRA_BATCH_TAG)
        if (batchTag != null) {
            // Tapped a conversion notification: show that batch (Progress moves on to Result when finished).
            openBatch = OpenBatchRequest(batchTag, (openBatch?.id ?: 0) + 1)
        } else {
            handleShare(intent)
        }
    }

    /** Files shared from other apps become the current selection; the URI grant lasts while we hold the intent. */
    private fun handleShare(intent: Intent?) {
        val uris = intent?.let(::sharedUris).orEmpty()
        if (uris.isEmpty()) return
        val container = (application as FileConverterApp).container
        container.selection.set(uris.mapNotNull { runCatching { container.inputResolver.resolve(it) }.getOrNull() })
        shareSignal++
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

    companion object {
        const val EXTRA_BATCH_TAG = "dev.armen.fileconverter.BATCH_TAG"
    }
}
