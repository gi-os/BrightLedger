package com.gios.brightledger

import com.gios.brightledger.demo.Demo
import com.gios.brightledger.ledger.Recurring
import com.gios.brightledger.ledger.Summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The demo data has to survive the real pipeline, because that is the entire point of it.
 *
 * Every screen in the app derives from the same rows through its ordinary code path: the
 * header runs [Summary], the Repeats screen runs [Recurring]. So a synthetic set that is
 * merely plausible-looking is not enough — if the planted subscriptions do not clear the
 * detector's own three-occurrences-at-a-steady-interval bar, the Repeats screenshot is of an
 * empty screen, and nobody notices until the screenshot is taken.
 *
 * These are cheap tests for a feature that is scheduled for deletion, which is the right
 * trade: they are the difference between finding out now and finding out with a camera.
 */
class DemoTest {

    private val zone = ZoneId.systemDefault()
    private val now = Instant.now()

    @Test
    fun `there are accounts, including a negative card balance`() {
        val accounts = Demo.accounts()
        assertTrue(accounts.size >= 3)
        // A liability reading negative is the convention the whole app assumes, so the demo
        // set has to contain one or the screenshot never shows that case.
        assertTrue(accounts.any { it.balanceCents < 0 })
        assertTrue(accounts.any { it.balanceCents > 0 })
    }

    @Test
    fun `every demo key is namespaced to the demo connection`() {
        assertTrue(Demo.accounts().all { Demo.isDemoKey(it.id) })
        assertTrue(Demo.feed().all { Demo.isDemoKey(it.accountId) })
        // And a real key is never mistaken for one.
        assertFalse(Demo.isDemoKey("a1b2c3d4|2930002"))
    }

    @Test
    fun `the header has something to show for today and this month`() {
        val totals = Summary.of(
            Demo.feed().map { Summary.Row(it.at, it.amountCents, it.pending) },
            now,
            zone,
        )
        assertTrue("spent today should not be zero", totals.todayCents > 0)
        assertTrue("spent this month should not be zero", totals.monthCents > 0)
        assertTrue("a credit should land in the month", totals.monthInCents > 0)
        assertTrue("one pending row keeps the pending count visible", totals.pendingCount >= 1)
    }

    /**
     * The one that actually matters. The detector needs three occurrences at a steady
     * cadence, and the planted rows have to satisfy it rather than merely look like they do.
     */
    @Test
    fun `the recurring detector finds the planted subscriptions`() {
        val found = Recurring.detect(
            Demo.feed()
                .filterNot { it.pending }
                .map { Recurring.Row(it.at, it.amountCents, it.description) },
        )
        assertTrue("expected several repeats, found " + found.size, found.size >= 3)
        assertTrue(found.all { it.cadence == Recurring.Cadence.Monthly })

        val names = found.map { it.name.lowercase() }
        assertTrue("Netflix should be detected, got $names", names.any { "netflix" in it })
        assertTrue("Spotify should be detected, got $names", names.any { "spotify" in it })
    }

    @Test
    fun `the feed is ordered pending first then newest`() {
        val feed = Demo.feed()
        assertEquals(feed.first().pending, true)
        val posted = feed.filterNot { it.pending }
        assertEquals(posted.sortedByDescending { it.at }, posted)
    }

    @Test
    fun `a detail lookup resolves a row that exists and nothing else`() {
        val first = Demo.feed().first()
        assertTrue(Demo.transaction(first.accountId, first.txnId) != null)
        assertTrue(Demo.transaction(first.accountId, "no-such-txn") == null)
    }

    /** Two calls a moment apart must agree, or two screenshots will not match. */
    @Test
    fun `the set is deterministic apart from its clock`() {
        val a = Demo.feed()
        val b = Demo.feed()
        assertEquals(a.map { it.description }, b.map { it.description })
        assertEquals(a.map { it.amountCents }, b.map { it.amountCents })
    }
}
