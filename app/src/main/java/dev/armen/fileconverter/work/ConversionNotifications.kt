package dev.armen.fileconverter.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import dev.armen.fileconverter.R
import java.util.UUID
import kotlin.math.roundToInt

class ConversionNotifications(private val context: Context) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Conversions", NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun foregroundInfo(workId: UUID, title: String, stage: String, progress: Float): ForegroundInfo {
        val percent = (progress * 100).roundToInt()
        val text = when (stage) {
            ConversionWorker.STAGE_WAITING -> "Waiting…"
            ConversionWorker.STAGE_SAVING -> "Saving…"
            else -> "$percent%"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_convert)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent, stage != ConversionWorker.STAGE_CONVERTING || progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "Cancel", WorkManager.getInstance(context).createCancelPendingIntent(workId))
            .build()
        val id = workId.hashCode()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private companion object {
        const val CHANNEL_ID = "conversions"
    }
}
