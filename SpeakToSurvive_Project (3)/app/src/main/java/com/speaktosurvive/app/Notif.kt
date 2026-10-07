package com.speaktosurvive.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import androidx.core.app.NotificationCompat

object Notif {
    const val CH_GUARD = "guard"
    const val CH_NEARBY = "nearby"
    const val ID_GUARD = 1

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_GUARD) == null) {
            val ch = NotificationChannel(
                CH_GUARD, "Protection status", NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "Shown while Speak to Survive is watching for your emergency triggers"
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(CH_NEARBY) == null) {
            val ch = NotificationChannel(
                CH_NEARBY, "Nearby SOS alerts", NotificationManager.IMPORTANCE_HIGH
            )
            ch.description = "Shown when your phone helps pass on an SOS from someone nearby"
            nm.createNotificationChannel(ch)
        }
    }

    fun build(ctx: Context, text: String, sosActive: Boolean): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), flags
        )
        val b = NotificationCompat.Builder(ctx, CH_GUARD)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(if (sosActive) "SOS ACTIVE" else "Speak to Survive")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        val hasPw = Prefs.hasPassword(ctx)
        val activityFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP

        if (sosActive) {
            // With a password set, the button opens the app's password box instead of stopping directly.
            val safe = if (hasPw) {
                PendingIntent.getActivity(
                    ctx, 2,
                    Intent(ctx, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_ASK_CANCEL)
                        .addFlags(activityFlags),
                    flags
                )
            } else {
                PendingIntent.getService(
                    ctx, 2,
                    Intent(ctx, GuardService::class.java).setAction(GuardService.ACTION_CANCEL_SOS),
                    flags
                )
            }
            b.addAction(0, "I'M SAFE", safe)
            b.setColor(Color.parseColor("#DC2626"))
        } else {
            val stop = if (hasPw) {
                PendingIntent.getActivity(
                    ctx, 1,
                    Intent(ctx, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_ASK_STOP)
                        .addFlags(activityFlags),
                    flags
                )
            } else {
                PendingIntent.getService(
                    ctx, 1,
                    Intent(ctx, GuardService::class.java).setAction(GuardService.ACTION_STOP),
                    flags
                )
            }
            b.addAction(0, "Stop protection", stop)
        }
        return b.build()
    }

    fun nearbyAlert(ctx: Context, id: Int, title: String, text: String, link: String?) {
        ensureChannels(ctx)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val tapIntent = if (link != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(link))
        } else {
            Intent(ctx, MainActivity::class.java)
        }
        val tap = PendingIntent.getActivity(ctx, 1000 + (id and 0xFFFF), tapIntent, flags)
        val n = NotificationCompat.Builder(ctx, CH_NEARBY)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(1000 + (id and 0xFFFF), n)
        } catch (e: SecurityException) {
            // notification permission missing
        }
    }
}
