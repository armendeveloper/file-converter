package dev.armen.fileconverter.work

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConversionNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notifications = ConversionNotifications(context).apply { ensureChannel() }

    @Test fun filesOfOneBatchShareOneNotification() {
        val first = notifications.foregroundInfo("batch:a", UUID.randomUUID(), "one.png → JPG", ConversionWorker.STAGE_WAITING, 0f)
        val second = notifications.foregroundInfo("batch:a", UUID.randomUUID(), "two.png → JPG", ConversionWorker.STAGE_CONVERTING, 0.5f)
        val otherBatch = notifications.foregroundInfo("batch:b", UUID.randomUUID(), "three.png → JPG", ConversionWorker.STAGE_WAITING, 0f)
        assertEquals(first.notificationId, second.notificationId)
        assertNotEquals(first.notificationId, otherBatch.notificationId)
    }

    @Test fun tappingTheProgressNotificationOpensTheApp() {
        val info = notifications.foregroundInfo("batch:c", UUID.randomUUID(), "clip.mp4 → GIF", ConversionWorker.STAGE_CONVERTING, 0.1f)
        assertNotNull(info.notification.contentIntent)
    }

    @Test fun failuresArePostedAsTheirOwnNotification() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val message = "Your CloudConvert key was rejected. Check it in Settings."
        notifications.notifyFailed("batch:d", "notes.docx → ODT", message)

        val manager = context.getSystemService(NotificationManager::class.java)
        var posted: StatusBarNotification? = null
        repeat(20) {
            posted = manager.activeNotifications.firstOrNull { it.id == ConversionNotifications.failureId("batch:d") }
            if (posted != null) return@repeat
            Thread.sleep(100)
        }
        val notification = requireNotNull(posted) { "failure notification was not posted" }.notification
        assertEquals(message, notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNotNull(notification.contentIntent)
        manager.cancel(ConversionNotifications.failureId("batch:d"))
    }
}
