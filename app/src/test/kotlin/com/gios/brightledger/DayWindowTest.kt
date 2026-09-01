package com.gios.brightledger

import com.gios.brightledger.bus.DayWindow
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The provider's date arithmetic, with the DST cases spelled out.
 *
 * The whole reason [DayWindow] exists is that a local day is not 24 hours: New York's
 * clocks skip an hour on 2026-03-08 and repeat one on 2026-11-01, and a window computed
 * as `start + 86_400` misfiles the transactions around those midnights.
 */
class DayWindowTest {

    private val newYork = ZoneId.of("America/New_York")
    private val utc = ZoneId.of("UTC")

    // ------------------------------------------------------------ parsing

    @Test
    fun `parses a plain iso date`() {
        assertEquals(LocalDate.of(2026, 9, 1), DayWindow.parse("2026-09-01"))
    }

    @Test
    fun `rejects everything that is not yyyy-MM-dd`() {
        assertNull(DayWindow.parse(""))
        assertNull(DayWindow.parse("yesterday"))
        assertNull(DayWindow.parse("2026-13-01"))
        assertNull(DayWindow.parse("2026-02-30"))
        assertNull(DayWindow.parse("09/01/2026"))
        assertNull(DayWindow.parse("2026-9-1"))
    }

    // ------------------------------------------------------------ day windows

    @Test
    fun `an ordinary day is 24 hours`() {
        val day = LocalDate.of(2026, 9, 1)
        val length = DayWindow.endSeconds(day, newYork) - DayWindow.startSeconds(day, newYork)
        assertEquals(24 * 3600L, length)
    }

    @Test
    fun `the spring-forward day is 23 hours`() {
        // US DST begins 2026-03-08; 02:00 EST jumps to 03:00 EDT.
        val day = LocalDate.of(2026, 3, 8)
        val length = DayWindow.endSeconds(day, newYork) - DayWindow.startSeconds(day, newYork)
        assertEquals(23 * 3600L, length)
    }

    @Test
    fun `the fall-back day is 25 hours`() {
        // US DST ends 2026-11-01; 02:00 EDT falls back to 01:00 EST.
        val day = LocalDate.of(2026, 11, 1)
        val length = DayWindow.endSeconds(day, newYork) - DayWindow.startSeconds(day, newYork)
        assertEquals(25 * 3600L, length)
    }

    @Test
    fun `windows are half-open and adjacent days share a boundary`() {
        val day = LocalDate.of(2026, 3, 8)
        assertEquals(
            DayWindow.endSeconds(day, newYork),
            DayWindow.startSeconds(day.plusDays(1), newYork),
        )
    }

    @Test
    fun `a transaction at local 23-30 on the fall-back day stays inside it`() {
        val day = LocalDate.of(2026, 11, 1)
        val lateEvening = day.atTime(23, 30).atZone(newYork).toEpochSecond()
        assertTrue(lateEvening >= DayWindow.startSeconds(day, newYork))
        assertTrue(lateEvening < DayWindow.endSeconds(day, newYork))
        // A naive start+86400 window would have already rolled into November 2nd here.
        assertTrue(lateEvening >= DayWindow.startSeconds(day, newYork) + 86_400L)
    }

    // ------------------------------------------------------------ bill projection

    private fun secondsOf(date: LocalDate, zone: ZoneId = utc): Long =
        date.atStartOfDay(zone).plusHours(10).toEpochSecond()

    @Test
    fun `a monthly bill due mid-horizon appears once, maybe twice`() {
        val today = LocalDate.of(2026, 9, 1)
        val due = today.plusDays(10)
        val days = DayWindow.dueEpochDays(secondsOf(due), 30, today, utc)
        assertEquals(listOf(due.toEpochDay()), days)
    }

    @Test
    fun `a weekly bill fills the horizon at its cadence`() {
        val today = LocalDate.of(2026, 9, 1)
        val due = today.plusDays(1)
        val days = DayWindow.dueEpochDays(secondsOf(due), 7, today, utc)
        val expected = generateSequence(due) { it.plusDays(7) }
            .takeWhile { it.toEpochDay() <= today.toEpochDay() + DayWindow.HORIZON_DAYS }
            .map { it.toEpochDay() }
            .toList()
        assertEquals(expected, days)
        assertEquals(5, days.size)
    }

    @Test
    fun `a projection already in the past is advanced by whole cadences, not clamped to today`() {
        val today = LocalDate.of(2026, 9, 1)
        val missed = today.minusDays(3)
        val days = DayWindow.dueEpochDays(secondsOf(missed), 30, today, utc)
        assertEquals(listOf(missed.plusDays(30).toEpochDay()), days)
    }

    @Test
    fun `a projection due exactly today is emitted today`() {
        val today = LocalDate.of(2026, 9, 1)
        val days = DayWindow.dueEpochDays(secondsOf(today), 30, today, utc)
        assertEquals(today.toEpochDay(), days.first())
    }

    @Test
    fun `the horizon boundary is inclusive`() {
        val today = LocalDate.of(2026, 9, 1)
        val atEdge = today.plusDays(DayWindow.HORIZON_DAYS)
        assertEquals(
            listOf(atEdge.toEpochDay()),
            DayWindow.dueEpochDays(secondsOf(atEdge), 365, today, utc),
        )
        val pastEdge = today.plusDays(DayWindow.HORIZON_DAYS + 1)
        assertEquals(
            emptyList(),
            DayWindow.dueEpochDays(secondsOf(pastEdge), 365, today, utc),
        )
    }

    @Test
    fun `garbage inputs produce no rows rather than a loop`() {
        val today = LocalDate.of(2026, 9, 1)
        assertEquals(emptyList(), DayWindow.dueEpochDays(0L, 30, today, utc))
        assertEquals(emptyList(), DayWindow.dueEpochDays(secondsOf(today), 0, today, utc))
        assertEquals(emptyList(), DayWindow.dueEpochDays(secondsOf(today), -7, today, utc))
    }

    @Test
    fun `the due day is the local date in the given zone`() {
        // 03:00 UTC on Sep 2nd is still Sep 1st in New York.
        val instant = LocalDate.of(2026, 9, 2).atStartOfDay(utc).plusHours(3).toEpochSecond()
        val today = LocalDate.of(2026, 8, 31)
        // Two occurrences each: the horizon from Aug 31 runs to Oct 5, so the +30 repeat
        // still fits. The point under test is the first day, shifted by the zone.
        assertEquals(
            listOf(LocalDate.of(2026, 9, 1).toEpochDay(), LocalDate.of(2026, 10, 1).toEpochDay()),
            DayWindow.dueEpochDays(instant, 30, today, newYork),
        )
        assertEquals(
            listOf(LocalDate.of(2026, 9, 2).toEpochDay(), LocalDate.of(2026, 10, 2).toEpochDay()),
            DayWindow.dueEpochDays(instant, 30, today, utc),
        )
    }
}
