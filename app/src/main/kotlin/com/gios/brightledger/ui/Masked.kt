package com.gios.brightledger.ui

/**
 * Standing in for an amount when totals are hidden.
 *
 * A fixed-width mask rather than one bullet per digit. Digit-for-digit masking leaks the
 * magnitude, which is most of what someone reading over your shoulder wants — the difference
 * between three figures and five is the interesting part, not the exact cents.
 *
 * The currency symbol stays. It costs nothing to leak and keeps the column from reflowing
 * when the mask goes on and off.
 */
object Masked {
    const val AMOUNT = "••••"

    fun amount(symbol: String, hidden: Boolean, real: () -> String): String =
        if (hidden) symbol + AMOUNT else symbol + real()
}
