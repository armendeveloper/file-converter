package dev.armen.fileconverter

import android.app.Application
import dev.armen.fileconverter.di.AppContainer
import kotlin.concurrent.thread

class FileConverterApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.ensureChannel()
        thread(name = "queue-prune") { container.pruneStaleQueue() }
    }
}
