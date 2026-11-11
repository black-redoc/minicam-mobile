package dev.minicam.mobile.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** spec 018 AC1-AC6: one window of days and hours, overnight ranges (docs/adr/0015). */
class NotificationScheduleTest {
    private val zone = ZoneId.of("America/Bogota")

    // 2026-11-09 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0): ZonedDateTime =
        ZonedDateTime.of(LocalDateTime.of(2026, 11, 8 + day, hour, minute), zone)

    private fun schedule(days: Set<Int>, start: Int, end: Int) =
        NotificationSchedule(enabled = true, days = days, startMinute = start * 60, endMinute = end * 60)

    @Test
    fun schedule_sameDayWindow() {
        val s = schedule(NotificationSchedule.WEEKDAYS, 10, 17)
        assertTrue(s.allows(at(1, 10)))
        assertTrue(s.allows(at(5, 16, 59)))
        assertFalse(s.allows(at(5, 17)))
        assertFalse(s.allows(at(3, 9, 59)))
        assertFalse(s.allows(at(6, 12)))
        assertFalse(s.allows(at(7, 12)))
    }

    @Test
    fun schedule_overnightWindowBelongsToItsStartDay() {
        val s = schedule(NotificationSchedule.WEEKDAYS, 20, 6)
        assertTrue(s.allows(at(1, 20)))
        assertTrue(s.allows(at(5, 23)))
        assertTrue(s.allows(at(6, 5, 59))) // Saturday morning, after Friday night
        assertTrue(s.allows(at(2, 3))) // Tuesday morning, after Monday night
        assertFalse(s.allows(at(1, 5))) // Monday morning: Sunday was not chosen
        assertFalse(s.allows(at(5, 6, 30)))
        assertFalse(s.allows(at(6, 6)))
        assertFalse(s.allows(at(6, 21))) // Saturday night
        assertFalse(s.allows(at(3, 12)))
    }

    @Test
    fun schedule_wholeDayDisabledAndNoDays() {
        val wholeDay = schedule(setOf(6, 7), 0, 0)
        assertTrue(wholeDay.allows(at(6, 0)))
        assertTrue(wholeDay.allows(at(7, 23, 59)))
        assertFalse(wholeDay.allows(at(1, 12)))

        val disabled = schedule(emptySet(), 10, 11).copy(enabled = false)
        assertTrue(disabled.allows(at(3, 3)))

        val noDays = schedule(emptySet(), 0, 0)
        assertFalse(noDays.allows(at(3, 12)))
    }

    @Test
    fun scheduleStore_roundTripsAndToleratesCorruptData() {
        val store = InMemoryCredentialStore()
        val schedules = NotificationScheduleStore(store)
        assertEquals(NotificationSchedule(), schedules.load())

        val saved = schedule(setOf(1, 3, 7), 20, 6)
        schedules.save(saved)
        assertEquals(saved, schedules.load())

        store.set(CredentialKey.NOTIFICATION_SCHEDULE, "{not json")
        assertFalse(schedules.load().enabled)

        store.set(CredentialKey.NOTIFICATION_SCHEDULE, """{"enabled":true,"days":[1,9],"start_minute":-5,"end_minute":99999}""")
        assertEquals(NotificationSchedule(true, setOf(1), 0, NotificationSchedule.MINUTES_PER_DAY - 1), schedules.load())
    }

    @Test
    fun schedule_usesDetectedAtInPhoneZone() {
        val schedules = NotificationScheduleStore(InMemoryCredentialStore())
        schedules.save(schedule(NotificationSchedule.WEEKDAYS, 20, 6))

        // Tue 01:00 UTC is Mon 20:00 in Bogota (UTC-5): inside the window.
        assertTrue(schedules.allows("2026-11-10T01:00:00.123456+00:00", zone))
        // Mon 15:00 UTC is Mon 10:00 in Bogota: outside.
        assertFalse(schedules.allows("2026-11-09T15:00:00Z", zone))
        // No time: falls back to now.
        val night = Instant.parse("2026-11-11T02:00:00Z") // Tue 21:00 in Bogota
        assertTrue(schedules.allows("", zone) { night })
        assertTrue(schedules.allows("garbage", zone) { night })
        assertFalse(schedules.allows("", zone) { Instant.parse("2026-11-11T17:00:00Z") })
    }

    @Test
    fun schedule_summary() {
        assertEquals("Mon–Fri · 8:00 PM – 6:00 AM (next day)", schedule(NotificationSchedule.WEEKDAYS, 20, 6).summary)
        assertEquals("Every day · 10:00 AM – 5:00 PM", schedule(NotificationSchedule.EVERY_DAY, 10, 17).summary)
        assertEquals("Sat, Sun · all day", schedule(setOf(6, 7), 0, 0).summary)
        assertEquals("Mon, Wed, Fri · 12:00 AM – 12:30 PM", schedule(setOf(1, 3, 5), 0, 0).copy(endMinute = 750).summary)
    }
}
