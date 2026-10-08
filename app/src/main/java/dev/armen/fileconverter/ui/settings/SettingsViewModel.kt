package dev.armen.fileconverter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.armen.fileconverter.data.AppSettings
import dev.armen.fileconverter.data.SettingsRepository
import dev.armen.fileconverter.data.history.HistoryDao
import dev.armen.fileconverter.domain.ErrorMapper
import dev.armen.fileconverter.domain.VideoQuality
import dev.armen.fileconverter.engine.cloud.CloudConvertClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

sealed interface KeyTestState {
    data object Idle : KeyTestState
    data object Testing : KeyTestState
    data class Ok(val username: String?, val credits: Int?) : KeyTestState
    data class Failed(val message: String) : KeyTestState
}

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val clientFactory: (apiKey: String, sandbox: Boolean) -> CloudConvertClient,
    private val history: HistoryDao,
) : ViewModel() {

    val state: StateFlow<AppSettings> =
        settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _keyTest = MutableStateFlow<KeyTestState>(KeyTestState.Idle)
    val keyTest: StateFlow<KeyTestState> = _keyTest.asStateFlow()

    fun saveKey(key: String) = launch {
        settings.setApiKey(key)
        _keyTest.value = KeyTestState.Idle
    }

    fun removeKey() = launch {
        settings.setApiKey(null)
        _keyTest.value = KeyTestState.Idle
    }

    fun testKey() = launch {
        _keyTest.value = KeyTestState.Testing
        val key = settings.apiKey()
        _keyTest.value = if (key == null) {
            KeyTestState.Failed("Save a key first.")
        } else {
            try {
                val user = clientFactory(key, settings.settings.first().sandbox).me()
                KeyTestState.Ok(user.username, user.credits)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KeyTestState.Failed(ErrorMapper.map(e).message ?: "The test failed.")
            }
        }
    }

    fun setSandbox(enabled: Boolean) = launch { settings.setSandbox(enabled) }

    fun setImageQuality(quality: Int) = launch { settings.setDefaultImageQuality(quality) }

    fun setAudioBitrate(kbps: Int) = launch { settings.setDefaultAudioBitrate(kbps) }

    fun setVideoQuality(quality: VideoQuality?) = launch { settings.setDefaultVideoQuality(quality) }

    fun clearHistory() = launch { history.clear() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
