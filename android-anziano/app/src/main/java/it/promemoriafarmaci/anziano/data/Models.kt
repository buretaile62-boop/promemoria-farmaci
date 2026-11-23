package it.promemoriafarmaci.anziano.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.ZoneId

/** Orari del controllo "STO BENE": devono coincidere con wellness_slot_hours() nel database. */
val DEFAULT_WELLNESS_HOURS = listOf(8, 12, 16, 20)

@Serializable
data class ScheduleItem(
    @SerialName("medication_time_id") val medicationTimeId: String,
    val name: String,
    val dosage: String = "",
    val instructions: String = "",
    /** "HH:mm" nell'ora locale dell'anziano. */
    val time: String,
)

@Serializable
data class Tutor(val name: String, val phone: String? = null)

@Serializable
data class Schedule(
    val timezone: String = "Europe/Rome",
    @SerialName("wellness_hours") val wellnessHours: List<Int> = DEFAULT_WELLNESS_HOURS,
    val items: List<ScheduleItem> = emptyList(),
    val tutors: List<Tutor> = emptyList(),
) {
    fun zone(): ZoneId = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
}

@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val userId: String,
)

/** Operazione da inviare al server; resta in coda se manca la rete. */
@Serializable
data class PendingAction(
    val type: String, // "dose" | "wellness" | "emergency"
    val medicationTimeId: String? = null,
    val atIso: String? = null,
)

@Serializable
data class ActiveDose(
    val medicationTimeId: String,
    val scheduledForMillis: Long,
    val name: String,
    val dosage: String,
    val instructions: String,
)

/** Promemoria in corso, in attesa di conferma da parte dell'anziano. */
@Serializable
data class ActiveAlarm(
    val doses: List<ActiveDose> = emptyList(),
    val wellnessAtMillis: Long? = null,
) {
    val isEmpty get() = doses.isEmpty() && wellnessAtMillis == null
}
