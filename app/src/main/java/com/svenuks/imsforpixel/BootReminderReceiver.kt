package com.svenuks.imsforpixel

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.io.File

class BootReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(SimConfigStore.PREFS_NAME, Context.MODE_PRIVATE)

        // Android clears in-memory CarrierConfig overrides on reboot — sync local flags immediately
        SimConfigStore.setNeedsReapplyAfterBoot(prefs, SimConfigStore.hasAnyOverrideEnabled(prefs), commit = true)
        for (slot in 0..1) {
            try {
                File(context.filesDir, "config_applied_$slot.txt").writeText("false")
                val existing = SimConfigStore.loadLiveStatus(context, slot)
                if (existing.configApplied) {
                    SimConfigStore.saveLiveStatus(context, existing.copy(configApplied = false, overrideCount = 0))
                }
            } catch (_: Exception) {
            }
        }
        LocalAdbManager.requestTileUpdate(context)

        if (!SimConfigStore.isBootReminderEnabled(prefs)) return
        if (!SimConfigStore.hasAnyOverrideEnabled(prefs)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val channelId = "boot_reminder_channel"
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                context.getString(R.string.notification_channel_boot),
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_AUTO_APPLY, true)
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(context, 301, launchIntent, pendingFlags)

        val watcherIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_START_WATCHER_AND_OPEN_DEBUG, true)
        }
        val watcherPendingIntent = PendingIntent.getActivity(context, 302, watcherIntent, pendingFlags)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_tile_ims)
            .setContentTitle(context.getString(R.string.notification_boot_title))
            .setContentText(context.getString(R.string.notification_boot_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .addAction(0, context.getString(R.string.notification_action_quick_reapply), pendingIntent)
            .addAction(0, context.getString(R.string.notification_action_enable_debug), watcherPendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(204, notification)
    }
}
