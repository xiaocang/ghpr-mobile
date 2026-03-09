package com.ghpr.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ghpr.app.R

object NotificationChannelManager {

    const val CHANNEL_ID = "pr_updates"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PR Updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Pull request update notifications"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun showPrNotification(context: Context, title: String, body: String, prUrl: String?) {
        val intent = if (prUrl != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(prUrl))
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            prUrl.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify(prUrl.hashCode(), notification)
        } catch (_: SecurityException) {
            // Notification permission not granted
        }
    }
}
