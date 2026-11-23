package it.promemoriafarmaci.anziano.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import it.promemoriafarmaci.anziano.data.SyncWorker

/** Dopo riavvio, aggiornamento o cambio d'ora gli allarmi vanno ripianificati. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.scheduleNext(context)
        Reminder.present(context)
        SyncWorker.schedulePeriodic(context)
    }
}
