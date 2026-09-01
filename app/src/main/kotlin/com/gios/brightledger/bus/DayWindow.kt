package com.gios.brightledger.bus

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The date arithmetic behind [DaysProvider], kept free of Android so it can be unit-tested.
 *
 * The one rule everything here follows: **a day is a local calendar day, not 24 hours.**
 * Twice a year a DST transition makes a day 23 or 25 hours long, so a window computed as
 * `start + 86_400` puts the last hour of one day into the next — or drops it entirely. Every
 * window is therefore local-midnight-to-next-local-midnight via [ZoneId], and the length is
 * whatever the zone says it is.
 */
object DayWindow {

    /** How far ahead `bills/upcoming` looks, in days. Part of the provider contract. */
    const val HORIZON_DAYS = 35L

    /** Cheap sanity bound on projection loops; no real cadence needs more rows than this. */
    private const val MAX_OCCURRENCES = 64

    /**
     * A strict `yyyy-MM-dd`, or null. Null rather than a throw, because the segment arrives
     * from another app over Binder and a bad date must come back as an empty cursor.
     */
    fun parse(segment: String): LocalDate? =
        runCatching { LocalDate.parse(segment, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()

    /** First instant of the local day, in epoch **seconds** — the database's unit. */
    fun startSeconds(day: LocalDate, zone: ZoneId): Long =
        day.atStartOfDay(zone).toEpochSecond()

    /** First instant of the *next* local day, so windows are `[start, end)`. */
    fun endSeconds(day: LocalDate, zone: ZoneId): Long =
        day.plusDays(1).atStartOfDay(zone).toEpochSecond()

    /**
     * The days one recurring charge is expected to land on, within the horizon.
     *
     * [nextAtSeconds] is the detector's projection — last occurrence plus the observed
     * median interval — which can already be in the past when the projection outran the
     * last sync. A late charge is treated as still coming: the due date is advanced by
     * whole cadences until it reaches today, mirroring what the in-app Recurring screen
     * shows. Occurrences are then emitted every [cadenceDays] until `today + horizonDays`,
     * inclusive, as epoch days (what `LocalDate.toEpochDay` returns — zone-free by design,
     * since "the 3rd" is the same journal day in every timezone).
     */
    fun dueEpochDays(
        nextAtSeconds: Long,
        cadenceDays: Int,
        today: LocalDate,
        zone: ZoneId,
        horizonDays: Long = HORIZON_DAYS,
    ): List<Long> {
        if (cadenceDays <= 0 || nextAtSeconds <= 0L || horizonDays < 0) return emptyList()

        var due = Instant.ofEpochSecond(nextAtSeconds).atZone(zone).toLocalDate()
        val todayEpoch = today.toEpochDay()
        val lastEpoch = todayEpoch + horizonDays

        if (due.toEpochDay() < todayEpoch) {
            val behind = todayEpoch - due.toEpochDay()
            val steps = (behind + cadenceDays - 1) / cadenceDays
            due = due.plusDays(steps * cadenceDays)
        }

        val out = ArrayList<Long>()
        var epochDay = due.toEpochDay()
        while (epochDay <= lastEpoch && out.size < MAX_OCCURRENCES) {
            out.add(epochDay)
            epochDay += cadenceDays
        }
        return out
    }
}
