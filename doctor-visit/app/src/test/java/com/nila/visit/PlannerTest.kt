package com.nila.visit

import com.nila.visit.extract.Appointment
import com.nila.visit.extract.Medicine
import com.nila.visit.extract.TimeOfDay
import com.nila.visit.extract.VisitNotes
import com.nila.visit.remind.Planner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class PlannerTest {
    private val visit = LocalDate.of(2026, 10, 10)

    @Test
    fun `a three-day night course is three nights, starting tonight`() {
        val notes = VisitNotes(medicines = listOf(Medicine("Cough syrup", "2.5 ml", "at night", listOf(TimeOfDay.NIGHT), durationDays = 3)))
        val plan = Planner.plan(notes, visit, now = LocalDateTime.of(visit, LocalTime.of(11, 0)))
        assertEquals(listOf(10, 11, 12), plan.map { it.at.dayOfMonth })
        assertTrue(plan.all { it.at.toLocalTime() == LocalTime.of(21, 0) })
    }

    @Test
    fun `when today's time has passed the course starts tomorrow and keeps its length`() {
        val notes = VisitNotes(medicines = listOf(Medicine("Zinc syrup", times = listOf(TimeOfDay.AFTERNOON), durationDays = 14)))
        val plan = Planner.plan(notes, visit, now = LocalDateTime.of(visit, LocalTime.of(18, 0)))
        assertEquals(14, plan.size)
        assertEquals(visit.plusDays(1), plan.first().at.toLocalDate())
    }

    @Test
    fun `three times a day for five days is fifteen alarms`() {
        val notes = VisitNotes(medicines = listOf(Medicine("Saline drops", timesPerDay = 3, durationDays = 5)))
        val plan = Planner.plan(notes, visit, now = LocalDateTime.of(visit, LocalTime.of(7, 0)))
        assertEquals(15, plan.size)
    }

    @Test
    fun `only-if medicines never ring, open courses stop at thirty days`() {
        val notes = VisitNotes(medicines = listOf(
            Medicine("Paracetamol", asNeeded = true),
            Medicine("Vitamin D", times = listOf(TimeOfDay.MORNING)),
        ))
        val plan = Planner.plan(notes, visit, now = LocalDateTime.of(visit, LocalTime.of(6, 0)))
        assertTrue(plan.none { it.title == "Paracetamol" })
        assertEquals(Planner.OPEN_ENDED_DAYS, plan.count { it.title == "Vitamin D" })
    }

    @Test
    fun `a visit rings the evening before and an hour ahead of its time`() {
        val notes = VisitNotes(appointments = listOf(Appointment("Bilirubin test", "day after tomorrow at 9", visit.plusDays(2), LocalTime.of(9, 0))))
        val plan = Planner.plan(notes, visit, now = LocalDateTime.of(visit, LocalTime.of(12, 0)))
        assertEquals(listOf(LocalDateTime.of(visit.plusDays(1), LocalTime.of(19, 0)), LocalDateTime.of(visit.plusDays(2), LocalTime.of(8, 0))), plan.map { it.at })
    }
}
