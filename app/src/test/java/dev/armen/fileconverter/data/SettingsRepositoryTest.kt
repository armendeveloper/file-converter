package dev.armen.fileconverter.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.VideoQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsRepositoryTest {
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: SettingsRepository

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(encoded: String): String {
            check(encoded.startsWith("enc:")) { "cannot decrypt" }
            return encoded.removePrefix("enc:").reversed()
        }
    }

    @Before fun setUp() {
        store = InMemoryPreferencesStore()
        repo = SettingsRepository(store, cipher)
    }

    @Test fun `starts with defaults`() = runBlocking {
        assertEquals(AppSettings(), repo.settings.first())
        assertNull(repo.apiKey())
    }

    @Test fun `api key is trimmed and stored encrypted`() = runBlocking {
        repo.setApiKey("  key-123 ")
        assertEquals("enc:321-yek", store.data.first()[stringPreferencesKey("api_key_enc")])
        assertEquals("key-123", repo.apiKey())
        assertTrue(repo.settings.first().hasApiKey)
    }

    @Test fun `blank key removes it`() = runBlocking {
        repo.setApiKey("k")
        repo.setApiKey("   ")
        assertNull(repo.apiKey())
        assertFalse(repo.settings.first().hasApiKey)
    }

    @Test fun `undecryptable key reads as missing`() = runBlocking {
        store.edit { it[stringPreferencesKey("api_key_enc")] = "garbage" }
        assertNull(repo.apiKey())
    }

    @Test fun `defaults feed conversion options`() = runBlocking {
        repo.setDefaultImageQuality(150)
        repo.setDefaultAudioBitrate(320)
        repo.setDefaultVideoQuality(VideoQuality.SMALL)
        assertEquals(
            ConversionOptions(imageQuality = 100, audioBitrateKbps = 320, videoQuality = VideoQuality.SMALL),
            repo.defaultOptions(),
        )
        repo.setDefaultVideoQuality(null)
        assertNull(repo.settings.first().defaultVideoQuality)
    }
}

/**
 * In-memory DataStore. The file-backed store cannot be used in JVM tests on Windows: its
 * File.renameTo over an existing file fails there (it works on Android).
 */
private class InMemoryPreferencesStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock { transform(state.value).also { state.value = it } }
}
