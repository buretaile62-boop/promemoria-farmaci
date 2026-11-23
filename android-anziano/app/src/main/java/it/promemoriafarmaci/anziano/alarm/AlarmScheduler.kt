package it.promemoriafarmaci.anziano.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import it.promemoriafarmaci.anziano.data.Store
import it.promemoriafarmaci.anziano.ui.MainActivity
import java.time.Instant

/** Pianifica gli allarmi con AlarmManager.setAlarmClock: affidabile anche in risparmio energetico. */
object AlarmScheduler {
    const val ACTION_FIRE = "it.promemoriafarmaci.anziano.FIRE"
    const val ACTION_NAG = "it.promemoriafarmaci.anziano.NAG"
    const val EXTRA_AT = "at"

    /** Ogni quanto si ripresenta l'allarme finché non viene confermato. */
    const val NAG_INTERVAL_MS = 3 * 60_000L

    private const val REQ_FIRE = 1
    private const val REQ_NAG = 2

    fun scheduleNext(context: Context) {
        val next = Planner.next(Store.get(context).schedule, Instant.now())
        if (next == null) {
            cancel(context, REQ_FIRE, ACTION_FIRE)
            return
        }
        set(context, REQ_FIRE, ACTION_FIRE, next.at.toEpochMilli())
    }

    fun scheduleNag(context: Context, atMillis: Long = System.currentTimeMillis() + NAG_INTERVAL_MS) {
        set(context, REQ_NAG, ACTION_NAG, atMillis)
    }

    fun cancelNag(context: Context) = cancel(context, REQ_NAG, ACTION_NAG)

    private fun pending(context: Context, requestCode: Int, action: String, atMillis: Long = 0): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_AT, atMillis)
        return PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun set(context: Context, requestCode: Int, action: String, atMillis: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val operation = pending(context, requestCode, action, atMillis)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            // Senza permesso di allarme esatto: meglio un leggero ritardo che nessun promemoria.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
            return
        }
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, show), operation)
    }

    private fun cancel(context: Context, requestCode: Int, action: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, requestCode, action))
    }
}
