package dev.armen.fileconverter.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.armen.fileconverter.data.history.HistoryDatabase
import dev.armen.fileconverter.data.history.HistoryEntry
import dev.armen.fileconverter.domain.FileFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DataLayerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun keystoreCipherRoundTrips() {
        val cipher = KeystoreSecretCipher("test_alias")
        val encrypted = cipher.encrypt("secret-key")
        assertNotEquals("secret-key", encrypted)
        assertEquals("secret-key", cipher.decrypt(encrypted))
    }

    @Test fun outputStoreSavesToDownloadsAndInputResolverReadsIt() {
        val file = File(context.cacheDir, "store-test.txt").apply { writeText("hello") }
        val saved = OutputStore(context).save(file, "text/plain", subfolder = "test-${System.nanoTime()}")
        try {
            val resolver = InputResolver(context.contentResolver)
            val info = resolver.resolve(saved.uri)
            assertEquals(saved.displayName, info.displayName)
            assertEquals(5L, info.sizeBytes)
            assertEquals(FileFormat.TXT, info.format)
            val copy = File(context.cacheDir, "copy.txt")
            resolver.copyToFile(saved.uri, copy)
            assertEquals("hello", copy.readText())
        } finally {
            context.contentResolver.delete(saved.uri, null, null)
        }
    }

    @Test fun historyDaoReturnsNewestFirst() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        val dao = db.historyDao()
        listOf(1L, 3L, 2L).forEach { t ->
            dao.insert(
                HistoryEntry(
                    createdAt = t, sourceName = "f$t.docx", sourceFormat = FileFormat.DOCX, targetFormat = FileFormat.PDF,
                    outputUri = "content://x/$t", outputName = "f$t.pdf", outputSizeBytes = t,
                ),
            )
        }
        assertEquals(listOf(3L, 2L), dao.observeRecent(2).first().map { it.createdAt })
        dao.delete(dao.observeRecent(1).first().single().id)
        assertEquals(listOf(2L, 1L), dao.observeRecent(10).first().map { it.createdAt })
        dao.clear()
        assertTrue(dao.observeRecent(10).first().isEmpty())
        db.close()
    }
}
