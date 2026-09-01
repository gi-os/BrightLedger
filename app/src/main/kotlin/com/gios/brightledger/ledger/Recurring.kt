package com.gios.brightledger.ledger

import kotlin.math.abs

/**
 * Finding the charges that come back.
 *
 * There is no category service behind this and no model — just the observation that a
 * subscription looks like the same merchant taking about the same amount at about the same
 * interval. Three signals, all of them in data the app already has, none of them requiring
 * anything to leave the phone.
 *
 * The hard part is not the cadence maths, it is the merchant name. Banks decorate a
 * descriptor differently on every single charge: a store number, a city, a terminal id, an
 * order reference, a date. Left alone, twelve months of Spotify is twelve distinct
 * merchants and nothing recurs. [normalize] is therefore doing most of the work here.
 */
object Recurring {

    /** How often it comes back. */
    enum class Cadence(val label: String, val approxDays: Int) {
        Weekly("Weekly", 7),
        Biweekly("Every 2 weeks", 14),
        Monthly("Monthly", 30),
        Quarterly("Quarterly", 91),
        Yearly("Yearly", 365),
    }

    data class Charge(
        /** The tidiest real descriptor seen, not the normalized key. */
        val name: String,
        /** Median amount, negative, in cents. */
        val amountCents: Long,
        val cadence: Cadence,
        val lastAt: Long,
        /** Projected, from the last occurrence plus the observed median interval. */
        val nextAt: Long,
        val occurrences: Int,
    )

    data class Row(
        val at: Long,
        val amountCents: Long,
        val description: String,
    )

    private const val DAY_SECONDS = 86_400L

    /** Below three charges there is one interval, and one interval is not a pattern. */
    private const val MIN_OCCURRENCES = 3

    /**
     * How far an amount may drift and still count as the same charge.
     *
     * Both a percentage and a floor, because subscriptions move in two different ways: a
     * streaming service raises its price by a dollar, and a utility bill swings by ten
     * percent every month. Either alone misses half of them.
     */
    private const val AMOUNT_TOLERANCE_RATIO = 0.15
    private const val AMOUNT_TOLERANCE_FLOOR_CENTS = 100L

    /** How far an interval may drift from the cadence it is being matched against. */
    private const val INTERVAL_TOLERANCE_RATIO = 0.30

    fun detect(rows: List<Row>, minOccurrences: Int = MIN_OCCURRENCES): List<Charge> {
        val debits = rows.filter { it.amountCents < 0 && it.at > 0L }
        val byMerchant = debits.groupBy { normalize(it.description) }

        val found = ArrayList<Charge>()
        for ((key, group) in byMerchant) {
            if (key.isBlank() || group.size < minOccurrences) continue
            for (cluster in clusterByAmount(group)) {
                if (cluster.size < minOccurrences) continue
                charge(cluster, minOccurrences)?.let(found::add)
            }
        }
        // Biggest commitment first. On a screen this size the useful ordering is by what it
        // costs you, not by when it next lands.
        return found.sortedWith(
            compareByDescending<Charge> { monthlyCostCents(it) }.thenBy { it.name },
        )
    }

    /** What the charge works out to per month, for ordering across cadences. */
    fun monthlyCostCents(charge: Charge): Long =
        (abs(charge.amountCents) * 30.0 / charge.cadence.approxDays).toLong()

    /**
     * Strips a bank descriptor down to the merchant.
     *
     * The order matters: prefixes go before the digit strip, because "POS DEBIT 0412 NETFLIX"
     * loses its date to the digit strip and would otherwise keep "POS DEBIT" as part of the
     * name for every card transaction in the account, collapsing them all into one merchant.
     */
    fun normalize(description: String): String {
        var text = description.uppercase().trim()

        for (prefix in NOISE_PREFIXES) {
            if (text.startsWith(prefix)) text = text.removePrefix(prefix).trim()
        }
        for (token in NOISE_TOKENS) {
            text = text.replace(token, " ")
        }

        // Everything that is not a letter or a space: store numbers, terminal ids, order
        // references, the asterisk in "SQ *COFFEE", the dot in "AMAZON.COM".
        text = buildString(text.length) {
            for (character in text) {
                append(if (character.isLetter()) character else ' ')
            }
        }

        val words = text.split(' ').filter { it.isNotBlank() && it.length > 1 }
        // Two words is plenty to identify a merchant and is short enough that a trailing
        // city name does not split one merchant into several.
        return words.take(2).joinToString(" ")
    }

    /**
     * Splits one merchant's charges into groups that are the same price.
     *
     * A merchant can hold more than one subscription — the same descriptor covers a monthly
     * plan and a yearly add-on — and averaging them produces a number matching neither.
     * Greedy clustering over sorted amounts is enough here: real subscription prices sit far
     * apart relative to the tolerance.
     */
    private fun clusterByAmount(group: List<Row>): List<List<Row>> {
        val sorted = group.sortedBy { it.amountCents }
        val clusters = ArrayList<MutableList<Row>>()
        for (row in sorted) {
            val target = clusters.lastOrNull()
            if (target != null && withinAmountTolerance(target.first().amountCents, row.amountCents)) {
                target.add(row)
            } else {
                clusters.add(mutableListOf(row))
            }
        }
        return clusters
    }

    private fun withinAmountTolerance(a: Long, b: Long): Boolean {
        val allowed = maxOf(
            AMOUNT_TOLERANCE_FLOOR_CENTS,
            (abs(a) * AMOUNT_TOLERANCE_RATIO).toLong(),
        )
        return abs(a - b) <= allowed
    }

    private fun charge(cluster: List<Row>, minOccurrences: Int): Charge? {
        val ordered = cluster.sortedBy { it.at }
        val intervals = ordered.zipWithNext { a, b -> (b.at - a.at) / DAY_SECONDS }
            .filter { it > 0 }
        if (intervals.size < minOccurrences - 1) return null

        val medianInterval = median(intervals)
        val cadence = cadenceFor(medianInterval) ?: return null

        // Every gap has to look like the cadence. Without this a merchant you happen to use
        // three times at similar prices — a coffee shop, a pharmacy — reads as a subscription.
        val tolerance = maxOf(2.0, cadence.approxDays * INTERVAL_TOLERANCE_RATIO)
        if (intervals.any { abs(it - cadence.approxDays.toLong()) > tolerance }) return null

        val last = ordered.last()
        return Charge(
            name = prettiestName(ordered),
            amountCents = median(ordered.map { it.amountCents }),
            cadence = cadence,
            lastAt = last.at,
            nextAt = last.at + medianInterval * DAY_SECONDS,
            occurrences = ordered.size,
        )
    }

    private fun cadenceFor(days: Long): Cadence? = Cadence.entries.firstOrNull { cadence ->
        abs(days - cadence.approxDays.toLong()) <= maxOf(
            2.0,
            cadence.approxDays * INTERVAL_TOLERANCE_RATIO,
        )
    }

    /**
     * The shortest descriptor in the group, cleaned up.
     *
     * Shortest, because the decoration banks add is almost always a suffix — the plainest
     * string in the set is the one with the least of it attached.
     */
    private fun prettiestName(rows: List<Row>): String {
        val raw = rows.minByOrNull { it.description.length }?.description.orEmpty().trim()
        if (raw.isEmpty()) return "Unknown"
        return raw.split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                if (word.length <= 3 && word.all { it.isUpperCase() }) {
                    word
                } else {
                    word.lowercase().replaceFirstChar { it.uppercase() }
                }
            }
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2
        }
    }

    private val NOISE_PREFIXES = listOf(
        "POS DEBIT", "POS PURCHASE", "DEBIT CARD PURCHASE", "CARD PURCHASE",
        "RECURRING PAYMENT", "PREAUTHORIZED DEBIT", "ACH DEBIT", "ACH WEB",
        "ELECTRONIC PAYMENT", "PURCHASE AUTHORIZED ON", "CHECKCARD", "VISA DDA PUR",
        "WITHDRAWAL", "PAYMENT TO", "DIRECT DEBIT", "AUTOPAY",
    )

    private val NOISE_TOKENS = listOf(
        " RECURRING", " AUTOPAY", " PAYMENT", " PMT", " INC", " LLC", " LTD", " CO ",
        " USA", " US", " COM",
    )
}
