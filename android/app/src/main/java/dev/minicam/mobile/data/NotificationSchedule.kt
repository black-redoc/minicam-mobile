package dev.minicam.mobile.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * When this phone shows detection pushes (docs/adr/0015): one window of [days] from [startMinute]
 * to [endMinute] (minutes after midnight). An end before the start runs past midnight and belongs
 * to the day it starts on; start equal to end is the whole day. Disabled means always.
 */
@Serializable
data class NotificationSchedule(
    val enabled: Boolean = false,
    /** ISO day numbers, 1 = Monday … 7 = Sunday. */
    val days: Set<Int> = WEEKDAYS,
    @SerialName("start_minute") val startMinute: Int = 20 * 60,
    @SerialName("end_minute") val endMinute: Int = 6 * 60,
) {
    val overnight: Boolean get() = endMinute < startMinute

    fun allows(at: ZonedDateTime): Boolean {
        if (!enabled) return true
        val day = at.dayOfWeek.value
        val minute = at.hour * 60 + at.minute
        return when {
            startMinute == endMinute -> day in days
            !overnight -> day in days && minute >= startMinute && minute < endMinute
            // Overnight: the evening part on a chosen day, or the early part after a chosen day.
            else -> (day in days && minute >= startMinute) ||
                (at.dayOfWeek.minus(1).value in days && minute < endMinute)
        }
    }

    /** "Mon–Fri · 8:00 PM – 6:00 AM (next day)". */
    val summary: String
        get() {
            val hours = when {
                startMinute == endMinute -> "all day"
                overnight -> "${formatMinute(startMinute)} – ${formatMinute(endMinute)} (next day)"
                else -> "${formatMinute(startMinute)} – ${formatMinute(endMinute)}"
            }
            return "${formatDays(days)} · $hours"
        }

    /** The schedule as valid values: minutes clamped to the day, unknown days dropped. */
    fun sanitized(): NotificationSchedule = copy(
        days = days.filter { it in 1..7 }.toSet(),
        startMinute = startMinute.coerceIn(0, MINUTES_PER_DAY - 1),
        endMinute = endMinute.coerceIn(0, MINUTES_PER_DAY - 1),
    )

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        val WEEKDAYS: Set<Int> = (1..5).toSet()
        val EVERY_DAY: Set<Int> = (1..7).toSet()

        /** "8:00 PM", "12:30 AM". */
        fun formatMinute(minute: Int): String {
            val hour = minute / 60
            val hour12 = if (hour % 12 == 0) 12 else hour % 12
            return "%d:%02d %s".format(hour12, minute % 60, if (hour < 12) "AM" else "PM")
        }

        fun dayLabel(day: Int): String = DAY_LABELS[day - 1]

        /** "Every day", "Mon–Fri", "Mon, Wed, Sat", "No days". */
        fun formatDays(days: Set<Int>): String {
            val sorted = days.filter { it in 1..7 }.sorted()
            return when {
                sorted.isEmpty() -> "No days"
                sorted.size == 7 -> "Every day"
                sorted.size >= 3 && sorted.last() - sorted.first() == sorted.size - 1 ->
                    "${dayLabel(sorted.first())}–${dayLabel(sorted.last())}"
                else -> sorted.joinToString(", ") { dayLabel(it) }
            }
        }

        private val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        /** Every [DayOfWeek] in Monday-first order, for the day chips. */
        val ALL_DAYS: List<Int> = DayOfWeek.entries.map { it.value }
    }
}

/** Keeps this phone's [NotificationSchedule] in the credential store; kept on logout. */
class NotificationScheduleStore(private val store: CredentialStore) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): NotificationSchedule = store.get(CredentialKey.NOTIFICATION_SCHEDULE)?.let {
        runCatching { json.decodeFromString(NotificationSchedule.serializer(), it).sanitized() }.getOrNull()
    } ?: NotificationSchedule()

    fun save(schedule: NotificationSchedule) {
        store.set(
            CredentialKey.NOTIFICATION_SCHEDULE,
            json.encodeToString(NotificationSchedule.serializer(), schedule.sanitized()),
        )
    }

    /**
     * Whether a detection seen at [detectedAt] (ISO-8601, minicam's `detected_at`) should be shown,
     * in [zone]; an empty or unreadable time falls back to [now].
     */
    fun allows(
        detectedAt: String,
        zone: ZoneId = ZoneId.systemDefault(),
        now: () -> Instant = Instant::now,
    ): Boolean {
        val instant = parseInstant(detectedAt) ?: now()
        return load().allows(instant.atZone(zone))
    }

    private fun parseInstant(value: String): Instant? = value.takeIf { it.isNotBlank() }?.let {
        runCatching { Instant.parse(it) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull()
    }
}
