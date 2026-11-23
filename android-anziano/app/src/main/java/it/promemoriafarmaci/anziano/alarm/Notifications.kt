package it.promemoriafarmaci.anziano.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import it.promemoriafarmaci.anziano.R
import it.promemoriafarmaci.anziano.data.ActiveAlarm

object Notifications {
    private const val CHANNEL = "promemoria"
    private const val ID = 100

    fun createChannels(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Promemoria farmaci e STO BENE", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Allarmi a tutto schermo per i farmaci e il controllo STO BENE"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun showReminder(context: Context, active: ActiveAlarm) {
        val screen = PendingIntent.getActivity(
            context, 10, Reminder.screenIntent(context, active),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, text) = if (active.doses.isNotEmpty()) {
            "È ora del farmaco" to active.doses.joinToString(", ") { "${it.name} ${it.dosage}".trim() }
        } else {
            "Come stai?" to "Premi STO BENE"
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_pill)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
