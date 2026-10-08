package dev.armen.fileconverter.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.work.WorkManager
import dev.armen.fileconverter.data.InputResolver
import dev.armen.fileconverter.data.KeystoreSecretCipher
import dev.armen.fileconverter.data.OutputStore
import dev.armen.fileconverter.data.SelectionHolder
import dev.armen.fileconverter.data.SettingsRepository
import dev.armen.fileconverter.data.history.HistoryDao
import dev.armen.fileconverter.data.history.HistoryDatabase
import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.Engine
import dev.armen.fileconverter.engine.Converter
import dev.armen.fileconverter.engine.FfmpegConverter
import dev.armen.fileconverter.engine.ImageConverter
import dev.armen.fileconverter.engine.LocalPdfConverter
import dev.armen.fileconverter.engine.TextPdfWriter
import dev.armen.fileconverter.engine.cloud.CloudConvertClient
import dev.armen.fileconverter.engine.cloud.CloudConvertConverter
import dev.armen.fileconverter.engine.office.AndroidXml
import dev.armen.fileconverter.engine.office.DocxReader
import dev.armen.fileconverter.engine.office.DocxWriter
import dev.armen.fileconverter.engine.office.OfficeLiteConverter
import dev.armen.fileconverter.engine.office.XlsxReader
import dev.armen.fileconverter.engine.office.XlsxWriter
import dev.armen.fileconverter.work.CloudStatus
import dev.armen.fileconverter.work.ConversionNotifications
import dev.armen.fileconverter.work.ConversionRunner
import dev.armen.fileconverter.work.ConversionScheduler
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Manual dependency injection: one instance per process, owned by FileConverterApp. */
class AppContainer(private val context: Context) {

    val settings = SettingsRepository(
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("settings") }),
        KeystoreSecretCipher(),
    )
    private val database by lazy { HistoryDatabase.create(context) }
    val history: HistoryDao get() = database.historyDao()
    val inputResolver = InputResolver(context.contentResolver)
    val outputStore = OutputStore(context)
    val notifications = ConversionNotifications(context)
    val selection = SelectionHolder()
    val scheduler by lazy { ConversionScheduler(WorkManager.getInstance(context)) }
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    /** Inputs copied from the user's Uris wait here until their worker runs. */
    fun queueDir(): File = File(context.filesDir, "queue")

    fun cloudClient(apiKey: String, sandbox: Boolean): CloudConvertClient =
        CloudConvertClient(http, apiKey, if (sandbox) CloudConvertClient.SANDBOX_URL else CloudConvertClient.PRODUCTION_URL)

    fun runner(): ConversionRunner =
        ConversionRunner(Build.VERSION.SDK_INT, cloudStatus = ::cloudStatus, converterFor = ::converterFor)

    /** Deletes queued inputs older than two days (left behind by cancelled or killed work). */
    fun pruneStaleQueue(now: Long = System.currentTimeMillis()) {
        queueDir().listFiles()
            ?.filter { now - it.lastModified() > STALE_QUEUE_MS }
            ?.forEach { it.deleteRecursively() }
    }

    private suspend fun cloudStatus(): CloudStatus = when {
        settings.apiKey() == null -> CloudStatus.NO_KEY
        !isOnline() -> CloudStatus.OFFLINE
        else -> CloudStatus.AVAILABLE
    }

    private fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun converterFor(engine: Engine): Converter = when (engine) {
        Engine.IMAGE_NATIVE -> ImageConverter()
        Engine.FFMPEG -> FfmpegConverter()
        Engine.LOCAL_PDF -> LocalPdfConverter()
        Engine.OFFICE_LITE -> OfficeLiteConverter(
            XlsxReader(AndroidXml.factory), XlsxWriter(), DocxReader(AndroidXml.factory), DocxWriter(), TextPdfWriter::write,
        )
        Engine.CLOUD -> {
            val key = settings.apiKey() ?: throw ConversionError.MissingApiKey()
            CloudConvertConverter(cloudClient(key, settings.settings.first().sandbox))
        }
    }

    private companion object {
        const val STALE_QUEUE_MS = 48L * 60 * 60 * 1000
    }
}
