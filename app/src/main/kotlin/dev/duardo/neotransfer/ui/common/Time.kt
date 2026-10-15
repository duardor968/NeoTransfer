package dev.duardo.neotransfer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val spanish: Locale = Locale.forLanguageTag("es")
private val hourMinute = DateTimeFormatter.ofPattern("HH:mm", spanish)
private val dayMonth = DateTimeFormatter.ofPattern("d MMM", spanish)
private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", spanish)
private val weekdayLong = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", spanish)
private val dateLong = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", spanish)

/** Human time labels relative to [clock]. */
class TimeLabels(private val clock: Clock) {
    private fun zoned(at: Instant): ZonedDateTime = at.atZone(clock.zone)
    private val today: LocalDate get() = LocalDate.now(clock)

    fun date(at: Instant): LocalDate = zoned(at).toLocalDate()

    /** "ahora", "hace 5 min", "hace 2 h", then the short date-time. */
    fun ago(at: Instant): String {
        val elapsed = Duration.between(at, clock.instant())
        return when {
            elapsed.toMinutes() < 1 -> "ahora"
            elapsed.toMinutes() < 60 -> "hace ${elapsed.toMinutes()} min"
            elapsed.toHours() < 12 -> "hace ${elapsed.toHours()} h"
            else -> moment(at)
        }
    }

    fun clockTime(at: Instant): String = hourMinute.format(zoned(at))

    /** "Hoy, 14:32", "Ayer, 09:10", "12 oct, 18:20". */
    fun moment(at: Instant): String {
        val day = date(at)
        val prefix = when (day) {
            today -> "Hoy"
            today.minusDays(1) -> "Ayer"
            else -> if (day.year == today.year) dayMonth.format(day).trimEnd('.') else dayMonthYear.format(day).replace(".", "")
        }
        return "$prefix, ${clockTime(at)}"
    }

    /** Section header for a day of activity. */
    fun day(day: LocalDate): String = when (day) {
        today -> "Hoy"
        today.minusDays(1) -> "Ayer"
        else -> (if (day.year == today.year) weekdayLong.format(day) else dateLong.format(day)).replaceFirstChar { it.uppercase() }
    }

    /** Full stamp for receipts: "13 de octubre de 2026, 14:32". */
    fun stamp(at: Instant): String = "${dateLong.format(zoned(at))}, ${clockTime(at)}"
}

val timeLabels: TimeLabels
    @Composable @ReadOnlyComposable get() = TimeLabels(LocalClock.current)
