package it.promemoriafarmaci.anziano

import android.app.Application
import it.promemoriafarmaci.anziano.alarm.AlarmScheduler
import it.promemoriafarmaci.anziano.alarm.Notifications
import it.promemoriafarmaci.anziano.data.SyncWorker
import it.promemoriafarmaci.anziano.lock.LockManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        LockManager.configure(this)
        SyncWorker.schedulePeriodic(this)
        AlarmScheduler.scheduleNext(this)
    }
}
