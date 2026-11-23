package it.promemoriafarmaci.anziano.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_FIRE ->
                Reminder.onAlarmFired(context, intent.getLongExtra(AlarmScheduler.EXTRA_AT, System.currentTimeMillis()))
            AlarmScheduler.ACTION_NAG -> Reminder.present(context)
        }
    }
}
