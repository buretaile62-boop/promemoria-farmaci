package it.promemoriafarmaci.anziano.alarm

import it.promemoriafarmaci.anziano.data.Schedule
import it.promemoriafarmaci.anziano.data.ScheduleItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PlannerTest {
    private val aspirina = ScheduleItem("mt1", "Cardioaspirina", "100 mg", "", "08:00")
    private val pressione = ScheduleItem("mt2", "Ramipril", "5 mg", "", "08:00")
    private val sera = ScheduleItem("mt3", "Statina", "", "", "21:30")
    private val schedule = Schedule("Europe/Rome", listOf(8, 12, 16, 20), listOf(aspirina, pressione, sera))

    @Test
    fun `raggruppa farmaci e STO BENE nello stesso minuto`() {
        // 23/11/2026 07:00 ora italiana = 06:00 UTC
        val next = Planner.next(schedule, Instant.parse("2026-11-23T06:00:00Z"))!!
        assertEquals(Instant.parse("2026-11-23T07:00:00Z"), next.at)
        assertEquals(listOf("Cardioaspirina", "Ramipril"), next.doses.map { it.name })
        assertTrue(next.wellness)
    }

    @Test
    fun `dopo le 8 il prossimo è il controllo delle 12 senza farmaci`() {
        val next = Planner.next(schedule, Instant.parse("2026-11-23T07:00:00Z"))!!
        assertEquals(Instant.parse("2026-11-23T11:00:00Z"), next.at)
        assertTrue(next.doses.isEmpty())
        assertTrue(next.wellness)
    }

    @Test
    fun `di sera passa al giorno dopo`() {
        // 22:00 ora italiana: la statina delle 21:30 è passata, prossimo è domani alle 8.
        val next = Planner.next(schedule, Instant.parse("2026-11-23T21:00:00Z"))!!
        assertEquals(Instant.parse("2026-11-24T07:00:00Z"), next.at)
    }

    @Test
    fun `farmaco serale senza controllo STO BENE`() {
        val next = Planner.next(schedule, Instant.parse("2026-11-23T19:05:00Z"))!!
        assertEquals(Instant.parse("2026-11-23T20:30:00Z"), next.at)
        assertEquals(listOf("Statina"), next.doses.map { it.name })
        assertFalse(next.wellness)
    }

    @Test
    fun `ora legale - le 8 restano le 8 locali`() {
        // 29/03/2026: dopo il cambio, le 08:00 italiane sono le 06:00 UTC.
        val next = Planner.next(schedule, Instant.parse("2026-03-29T03:00:00Z"))!!
        assertEquals(Instant.parse("2026-03-29T06:00:00Z"), next.at)
    }

    @Test
    fun `at ritrova il promemoria all'istante esatto`() {
        val found = Planner.at(schedule, Instant.parse("2026-11-23T07:00:20Z"))!!
        assertEquals(2, found.doses.size)
        assertNull(Planner.at(schedule, Instant.parse("2026-11-23T07:01:00Z")))
    }

    @Test
    fun `orari non validi vengono ignorati`() {
        val broken = schedule.copy(items = listOf(aspirina.copy(time = "25:99")), wellnessHours = emptyList())
        assertNull(Planner.next(broken, Instant.parse("2026-11-23T06:00:00Z")))
    }
}
