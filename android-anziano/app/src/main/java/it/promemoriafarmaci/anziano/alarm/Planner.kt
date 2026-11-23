package it.promemoriafarmaci.anziano.alarm

import it.promemoriafarmaci.anziano.data.Schedule
import it.promemoriafarmaci.anziano.data.ScheduleItem
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

data class PlannedAlarm(
    val at: Instant,
    val doses: List<ScheduleItem>,
    val wellness: Boolean,
)

/** Calcolo puro (senza Android) degli orari dei promemoria, nel fuso orario dell'anziano. */
object Planner {

    private fun parseTime(value: String): LocalTime? = runCatching { LocalTime.parse(value) }.getOrNull()

    /** Tutte le occorrenze dei prossimi [days] giorni a partire dal giorno di [from]. */
    fun occurrences(schedule: Schedule, from: Instant, days: Int = 3): List<PlannedAlarm> {
        val zone = schedule.zone()
        val startDate = from.atZone(zone).toLocalDate()
        val doses = mutableMapOf<Instant, MutableList<ScheduleItem>>()
        val wellness = mutableSetOf<Instant>()
        for (offset in 0 until days) {
            val date = startDate.plusDays(offset.toLong())
            for (item in schedule.items) {
                val time = parseTime(item.time) ?: continue
                // In caso di ora legale (orario inesistente) ZonedDateTime sposta in avanti.
                val at = ZonedDateTime.of(date, time, zone).toInstant()
                doses.getOrPut(at) { mutableListOf() }.add(item)
            }
            for (hour in schedule.wellnessHours) {
                if (hour !in 0..23) continue
                wellness += ZonedDateTime.of(date, LocalTime.of(hour, 0), zone).toInstant()
            }
        }
        return (doses.keys + wellness).toSortedSet().map { at ->
            PlannedAlarm(at, doses[at].orEmpty().sortedBy { it.name }, at in wellness)
        }
    }

    /** Il primo promemoria strettamente successivo a [after]. */
    fun next(schedule: Schedule, after: Instant): PlannedAlarm? =
        occurrences(schedule, after).firstOrNull { it.at.isAfter(after) }

    /** Il promemoria previsto esattamente all'istante [at] (al minuto). */
    fun at(schedule: Schedule, at: Instant): PlannedAlarm? {
        val minute = Instant.ofEpochSecond(at.epochSecond / 60 * 60)
        return occurrences(schedule, minute.minusSeconds(86_400)).firstOrNull { it.at == minute }
    }
}
