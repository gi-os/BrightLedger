package com.gios.brightledger.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dates as short as they can be and still be unambiguous.
 *
 * The panel is narrow and every transaction row already carries a description and an amount,
 * so the date gets whatever space is left. "Today" and "Yesterday" earn their length by
 * removing the need to work out what the number means; everything inside the year drops the
 * year, and only older rows pay for one.
 */
object Format {

    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.US)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
    private val weekdayFull = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.US)

    fun relativeDate(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (epochSeconds <= 0L) return "Pending"
        val date = LocalDate.ofInstant(Instant.ofEpochSecond(epochSeconds), zone)
        val today = LocalDate.now(zone)
        return when {
            date == today -> "Today"
            date == today.minusDays(1) -> "Yesterday"
            date.year == today.year -> dayMonth.format(date)
            else -> dayMonthYear.format(date)
        }
    }

    fun fullDate(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (epochSeconds <= 0L) return "Not posted yet"
        return weekdayFull.format(LocalDate.ofInstant(Instant.ofEpochSecond(epochSeconds), zone))
    }

    /** "3 minutes ago" for the staleness line, which is the only place elapsed time matters. */
    fun since(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return "never"
        val seconds = (System.currentTimeMillis() / 1000) - epochSeconds
        return when {
            seconds < 90 -> "just now"
            seconds < 3600 -> plural(seconds / 60, "minute") + " ago"
            seconds < 86_400 -> plural(seconds / 3600, "hour") + " ago"
            else -> plural(seconds / 86_400, "day") + " ago"
        }
    }

    /** Days until a projected date, phrased for the recurring list. */
    fun until(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val target = LocalDate.ofInstant(Instant.ofEpochSecond(epochSeconds), zone)
        val today = LocalDate.now(zone)
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, target)
        return when {
            days < 0 -> "overdue"
            days == 0L -> "due today"
            days == 1L -> "due tomorrow"
            days <= 45 -> "in " + plural(days, "day")
            else -> dayMonth.format(target)
        }
    }

    private fun plural(value: Long, unit: String): String =
        value.toString() + " " + unit + if (value == 1L) "" else "s"
}
