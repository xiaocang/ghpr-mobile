package com.ghpr.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ghpr.app.R
import com.ghpr.app.data.NotificationEventMapper

object NotificationChannelManager {

    const val CHANNEL_ID = "pr_updates"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PR Updates",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Pull request update notifications"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun showPrNotification(
        context: Context,
        deliveryId: String?,
        repo: String,
        prNumber: Int,
        action: String,
        prTitle: String?,
        prUrl: String?,
    ) {
        if (!hasNotificationPermission(context)) return

        val intent = if (prUrl != null) {
            Intent(Intent.ACTION_VIEW, prUrl.toUri())
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
        }
        if (intent == null) return

        val notificationId = deliveryId?.hashCode()
            ?: "$repo#$prNumber#${NotificationEventMapper.normalizeAction(action)}".hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = "$repo #$prNumber"
        val body = "${NotificationEventMapper.labelFor(action)}: ${prTitle?.takeIf { it.isNotBlank() } ?: "Pull request updated"}"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ghpr)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Notification permission not granted
        }
    }

}
