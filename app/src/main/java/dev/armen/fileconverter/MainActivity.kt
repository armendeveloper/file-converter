package dev.armen.fileconverter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import dev.armen.fileconverter.ui.AppNavHost
import dev.armen.fileconverter.ui.theme.FileConverterTheme

class MainActivity : ComponentActivity() {
    private var shareSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FileConverterApp).container
        if (savedInstanceState == null) handleShare(intent)
        setContent { FileConverterTheme { AppNavHost(container, shareSignal) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
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
}
