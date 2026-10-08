package dev.armen.fileconverter.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.VideoQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val hasApiKey: Boolean = false,
    val sandbox: Boolean = false,
    val defaultImageQuality: Int = 90,
    val defaultAudioBitrateKbps: Int = 192,
    val defaultVideoQuality: VideoQuality? = null,
)

class SettingsRepository(private val store: DataStore<Preferences>, private val cipher: SecretCipher) {

    private object Keys {
        val API_KEY = stringPreferencesKey("api_key_enc")
        val SANDBOX = booleanPreferencesKey("sandbox")
        val IMAGE_QUALITY = intPreferencesKey("image_quality")
        val AUDIO_BITRATE = intPreferencesKey("audio_bitrate")
        val VIDEO_QUALITY = stringPreferencesKey("video_quality")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            hasApiKey = p[Keys.API_KEY] != null,
            sandbox = p[Keys.SANDBOX] ?: false,
            defaultImageQuality = p[Keys.IMAGE_QUALITY] ?: 90,
            defaultAudioBitrateKbps = p[Keys.AUDIO_BITRATE] ?: 192,
            defaultVideoQuality = p[Keys.VIDEO_QUALITY]?.let { name -> VideoQuality.entries.firstOrNull { it.name == name } },
        )
    }

    /** The decrypted key; null when none is saved or it can no longer be decrypted (e.g. Keystore reset). */
    suspend fun apiKey(): String? =
        store.data.first()[Keys.API_KEY]?.let { runCatching { cipher.decrypt(it) }.getOrNull() }

    suspend fun setApiKey(key: String?) {
        val trimmed = key?.trim()
        store.edit { p ->
            if (trimmed.isNullOrEmpty()) {
                p.remove(Keys.API_KEY)
            } else {
                p[Keys.API_KEY] = cipher.encrypt(trimmed)
            }
        }
    }

    suspend fun setSandbox(enabled: Boolean) {
        store.edit { it[Keys.SANDBOX] = enabled }
    }

    suspend fun setDefaultImageQuality(quality: Int) {
        store.edit { it[Keys.IMAGE_QUALITY] = quality.coerceIn(1, 100) }
    }

    suspend fun setDefaultAudioBitrate(kbps: Int) {
        store.edit { it[Keys.AUDIO_BITRATE] = kbps }
    }

    suspend fun setDefaultVideoQuality(quality: VideoQuality?) {
        store.edit { p ->
            if (quality == null) {
                p.remove(Keys.VIDEO_QUALITY)
            } else {
                p[Keys.VIDEO_QUALITY] = quality.name
            }
        }
    }

    suspend fun defaultOptions(): ConversionOptions = settings.first().let {
        ConversionOptions(
            imageQuality = it.defaultImageQuality,
            audioBitrateKbps = it.defaultAudioBitrateKbps,
            videoQuality = it.defaultVideoQuality,
        )
    }
}
