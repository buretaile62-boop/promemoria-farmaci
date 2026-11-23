package it.promemoriafarmaci.anziano.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Archivio locale: l'app deve funzionare anche senza rete. */
class Store private constructor(private val prefs: SharedPreferences) {

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        @Volatile private var instance: Store? = null

        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(context.applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE))
                .also { instance = it }
        }
    }

    private inline fun <reified T> read(key: String): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    private inline fun <reified T> write(key: String, value: T?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, json.encodeToString(value))
        }.commit()
    }

    var session: Session?
        get() = read("session")
        set(value) = write("session", value)

    var displayName: String?
        get() = prefs.getString("displayName", null)
        set(value) { prefs.edit().putString("displayName", value).commit() }

    var registered: Boolean
        get() = prefs.getBoolean("registered", false)
        set(value) { prefs.edit().putBoolean("registered", value).commit() }

    var schedule: Schedule
        get() = read("schedule") ?: Schedule()
        set(value) = write("schedule", value)

    var pairingCode: String?
        get() = prefs.getString("pairingCode", null)?.takeIf { pairingCodeExpiresAt > System.currentTimeMillis() }
        set(value) { prefs.edit().putString("pairingCode", value).commit() }

    var pairingCodeExpiresAt: Long
        get() = prefs.getLong("pairingCodeExpiresAt", 0)
        set(value) { prefs.edit().putLong("pairingCodeExpiresAt", value).commit() }

    var lastSyncMillis: Long
        get() = prefs.getLong("lastSync", 0)
        set(value) { prefs.edit().putLong("lastSync", value).commit() }

    /** Dopo lo sblocco di emergenza i richiami sono sospesi fino a questo istante. */
    var nagSuspendedUntil: Long
        get() = prefs.getLong("nagSuspendedUntil", 0)
        set(value) { prefs.edit().putLong("nagSuspendedUntil", value).commit() }

    @get:Synchronized @set:Synchronized
    var activeAlarm: ActiveAlarm
        get() = read("activeAlarm") ?: ActiveAlarm()
        set(value) = write("activeAlarm", value.takeUnless { it.isEmpty })

    @Synchronized
    fun updateActiveAlarm(transform: (ActiveAlarm) -> ActiveAlarm): ActiveAlarm {
        val updated = transform(activeAlarm)
        activeAlarm = updated
        return updated
    }

    @Synchronized
    fun enqueue(action: PendingAction) {
        write("pending", pendingActions() + action)
    }

    @Synchronized
    fun pendingActions(): List<PendingAction> = read("pending") ?: emptyList()

    @Synchronized
    fun removePending(done: PendingAction) {
        val list = pendingActions().toMutableList()
        list.remove(done)
        write("pending", list)
    }
}
