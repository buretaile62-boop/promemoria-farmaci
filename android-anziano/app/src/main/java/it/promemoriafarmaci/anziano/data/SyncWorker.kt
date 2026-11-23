package it.promemoriafarmaci.anziano.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.promemoriafarmaci.anziano.alarm.AlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

/** Invia conferme in coda, segnala che il telefono è attivo e scarica la terapia aggiornata. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = Store.get(applicationContext)
        val api = SupabaseApi(store)
        if (!api.isConfigured || !store.registered) return@withContext Result.success()
        try {
            flush(store, api)
            api.rpc("heartbeat")
            val schedule = api.rpc("get_my_schedule")?.let {
                Store.json.decodeFromJsonElement(Schedule.serializer(), it)
            }
            if (schedule != null) store.schedule = schedule
            store.lastSyncMillis = System.currentTimeMillis()
            AlarmScheduler.scheduleNext(applicationContext)
            Result.success()
        } catch (e: ApiException) {
            if (e.code in 400..499 && e.code != 401 && e.code != 408 && e.code != 429) Result.failure() else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun flush(store: Store, api: SupabaseApi) {
        for (action in store.pendingActions()) {
            try {
                when (action.type) {
                    "dose" -> api.rpc("confirm_dose", buildJsonObject {
                        put("p_medication_time_id", action.medicationTimeId)
                        put("p_scheduled_for", action.atIso)
                    })
                    "wellness" -> api.rpc("answer_wellness", buildJsonObject { put("p_slot_at", action.atIso) })
                    "emergency" -> api.rpc("report_emergency_unlock")
                }
                store.removePending(action)
            } catch (e: ApiException) {
                // Rifiutata in modo definitivo (es. farmaco eliminato, orario troppo vecchio): scarta.
                if (e.code == 400 || e.code == 404) store.removePending(action) else throw e
            }
        }
    }

    companion object {
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
