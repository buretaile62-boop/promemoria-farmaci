package it.promemoriafarmaci.anziano.alarm

import android.content.Context
import android.content.Intent
import android.provider.Settings
import it.promemoriafarmaci.anziano.data.ActiveAlarm
import it.promemoriafarmaci.anziano.data.ActiveDose
import it.promemoriafarmaci.anziano.data.PendingAction
import it.promemoriafarmaci.anziano.data.Store
import it.promemoriafarmaci.anziano.data.SyncWorker
import it.promemoriafarmaci.anziano.lock.LockManager
import it.promemoriafarmaci.anziano.ui.AlarmActivity
import it.promemoriafarmaci.anziano.ui.WellnessActivity
import java.time.Instant

/** Stato dei promemoria in corso e azioni dell'anziano. */
object Reminder {
    /** Oltre questo tempo un promemoria non confermato smette di suonare (il tutore è già avvisato). */
    const val MAX_AGE_MS = 2 * 60 * 60_000L

    /** Durata della sospensione dopo lo sblocco di emergenza. */
    const val EMERGENCY_PAUSE_MS = 30 * 60_000L

    fun onAlarmFired(context: Context, atMillis: Long) {
        val store = Store.get(context)
        val planned = Planner.at(store.schedule, Instant.ofEpochMilli(atMillis))
        if (planned != null) {
            store.updateActiveAlarm { current ->
                val newDoses = planned.doses.map {
                    ActiveDose(it.medicationTimeId, planned.at.toEpochMilli(), it.name, it.dosage, it.instructions)
                }
                current.copy(
                    doses = (current.doses + newDoses).distinctBy { it.medicationTimeId to it.scheduledForMillis },
                    wellnessAtMillis = if (planned.wellness) planned.at.toEpochMilli() else current.wellnessAtMillis,
                )
            }
            store.nagSuspendedUntil = 0
        }
        AlarmScheduler.scheduleNext(context)
        SyncWorker.syncNow(context)
        present(context)
    }

    /** Mostra (o ripropone) la schermata del promemoria in corso. */
    fun present(context: Context) {
        val store = Store.get(context)
        val now = System.currentTimeMillis()
        val active = store.updateActiveAlarm { a ->
            ActiveAlarm(
                doses = a.doses.filter { now - it.scheduledForMillis < MAX_AGE_MS },
                wellnessAtMillis = a.wellnessAtMillis?.takeIf { now - it < MAX_AGE_MS },
            )
        }
        if (active.isEmpty) {
            AlarmScheduler.cancelNag(context)
            Notifications.cancel(context)
            return
        }
        if (store.nagSuspendedUntil > now) {
            AlarmScheduler.scheduleNag(context, store.nagSuspendedUntil)
            return
        }
        Notifications.showReminder(context, active)
        // Con il permesso "mostra sopra le altre app" (o come proprietario del dispositivo)
        // la schermata si apre anche mentre il telefono è in uso.
        if (Settings.canDrawOverlays(context) || LockManager.isDeviceOwner(context)) {
            runCatching { context.startActivity(screenIntent(context, active)) }
        }
        AlarmScheduler.scheduleNag(context)
    }

    fun screenIntent(context: Context, active: ActiveAlarm): Intent {
        val target = if (active.doses.isNotEmpty()) AlarmActivity::class.java else WellnessActivity::class.java
        return Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }

    /** "HO PRESO IL FARMACO": vale anche come risposta al controllo STO BENE dello stesso momento. */
    fun confirmDoses(context: Context) {
        val store = Store.get(context)
        val active = store.activeAlarm
        active.doses.forEach {
            store.enqueue(PendingAction("dose", it.medicationTimeId, Instant.ofEpochMilli(it.scheduledForMillis).toString()))
        }
        active.wellnessAtMillis?.let { store.enqueue(PendingAction("wellness", atIso = Instant.ofEpochMilli(it).toString())) }
        store.activeAlarm = ActiveAlarm()
        finishAll(context)
    }

    fun confirmWellness(context: Context) {
        val store = Store.get(context)
        val active = store.activeAlarm
        active.wellnessAtMillis?.let { store.enqueue(PendingAction("wellness", atIso = Instant.ofEpochMilli(it).toString())) }
        store.activeAlarm = active.copy(wellnessAtMillis = null)
        if (store.activeAlarm.isEmpty) finishAll(context) else SyncWorker.syncNow(context)
    }

    /** Sblocco di emergenza: libera il telefono per 30 minuti e avvisa il tutore. */
    fun emergencyUnlock(context: Context) {
        val store = Store.get(context)
        store.enqueue(PendingAction("emergency"))
        store.nagSuspendedUntil = System.currentTimeMillis() + EMERGENCY_PAUSE_MS
        AlarmScheduler.scheduleNag(context, store.nagSuspendedUntil)
        Notifications.cancel(context)
        SyncWorker.syncNow(context)
    }

    private fun finishAll(context: Context) {
        AlarmScheduler.cancelNag(context)
        Notifications.cancel(context)
        SyncWorker.syncNow(context)
    }
}
