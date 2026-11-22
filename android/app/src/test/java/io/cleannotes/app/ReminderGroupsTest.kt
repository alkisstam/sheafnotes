package io.cleannotes.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class ReminderGroupsTest {
    private fun at(day: String) = ReminderItem(day, day, null, day, LocalDate.parse(day).atTime(9, 0), null, false, false, "reminder")
    private fun sections(today: String, weekStart: DayOfWeek, vararg days: String) =
        scheduledSections(days.map(::at), LocalDate.parse(today), weekStart).map { (k, v) -> k to v.map { it.key } }

    @Test fun everyBucket() = assertEquals(
        listOf("Tomorrow" to listOf("2026-11-03"), "This week" to listOf("2026-11-06"), "Next week" to listOf("2026-11-12"), "This month" to listOf("2026-11-20"), "Later" to listOf("2026-12-01")),
        sections("2026-11-02", DayOfWeek.MONDAY, "2026-11-03", "2026-11-06", "2026-11-12", "2026-11-20", "2026-12-01"),
    )

    // Saturday with Sunday-start weeks: tomorrow is already next week, and next week runs past the month's end.
    @Test fun weekendAndMonthEnd() = assertEquals(
        listOf("Tomorrow" to listOf("2026-11-29"), "Next week" to listOf("2026-12-03"), "Later" to listOf("2026-12-10")),
        sections("2026-11-28", DayOfWeek.SUNDAY, "2026-11-29", "2026-12-03", "2026-12-10"),
    )
}
