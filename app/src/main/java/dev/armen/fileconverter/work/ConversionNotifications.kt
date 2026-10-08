package dev.armen.fileconverter.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import dev.armen.fileconverter.MainActivity
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

    /** All workers of a batch use the batch's notification id, so a batch shows one ongoing notification. */
    fun foregroundInfo(batchTag: String, workId: UUID, title: String, stage: String, progress: Float): ForegroundInfo {
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
            .setContentIntent(openBatchIntent(batchTag))
            .addAction(0, "Cancel", WorkManager.getInstance(context).createCancelPendingIntent(workId))
            .build()
        val id = notificationId(batchTag)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    /** Tells the user about a failure that happened while they may be outside the app (spec §1.5). */
    fun notifyFailed(batchTag: String, title: String, message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_convert)
            .setContentTitle("Conversion failed: $title")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openBatchIntent(batchTag))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(failureId(batchTag), notification)
    }

    private fun openBatchIntent(batchTag: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_BATCH_TAG, batchTag)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            batchTag.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val CHANNEL_ID = "conversions"

        fun notificationId(batchTag: String): Int = batchTag.hashCode()

        fun failureId(batchTag: String): Int = batchTag.hashCode() + 1
    }
}
