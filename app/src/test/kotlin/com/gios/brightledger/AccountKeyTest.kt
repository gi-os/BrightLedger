package com.gios.brightledger

import com.gios.brightledger.data.AccountEntity
import com.gios.brightledger.data.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The invariant that multi-server support exists to protect.
 *
 * SimpleFIN guarantees an account id is unique within one server and says nothing about
 * across servers, so the moment the phone talks to two of them the server's own id stops
 * being a primary key. Two Bridges each numbering an account `1` would collapse into one
 * row, and what the user sees is a balance flickering between two banks on every sync — no
 * crash, no error, just a number that is sometimes wrong.
 */
class AccountKeyTest {

    @Test
    fun `the same remote id on two servers is two accounts`() {
        val a = AccountEntity.key("conn-a", "1")
        val b = AccountEntity.key("conn-b", "1")
        assertNotEquals(a, b)
    }

    @Test
    fun `the same account from the same server is one account`() {
        assertEquals(
            AccountEntity.key("conn-a", "2930002"),
            AccountEntity.key("conn-a", "2930002"),
        )
    }

    /**
     * The transaction key inherits the fix. Its primary key is (accountId, txnId) and the
     * protocol permits two accounts to reuse a transaction id, so both halves have to be
     * globally unique for the pair to be.
     */
    @Test
    fun `a reused transaction id across servers does not collide`() {
        val onA = TransactionEntity(
            accountId = AccountEntity.key("conn-a", "1"),
            txnId = "t1",
            at = 100,
            posted = 100,
            amountCents = -500,
            description = "A",
            pending = false,
        )
        val onB = onA.copy(accountId = AccountEntity.key("conn-b", "1"), amountCents = -900)

        assertNotEquals(onA.accountId, onB.accountId)
        // Same txnId, different rows — which is the whole point.
        assertEquals(onA.txnId, onB.txnId)
    }

    /**
     * The separator has to be one a SimpleFIN id cannot contain, or two different pairs could
     * compose to the same string. Ids in the wild are opaque alphanumerics and the spec
     * recommends they reveal nothing, so a pipe is safe — but the key is built in one place
     * precisely so this assumption has one home.
     */
    @Test
    fun `the composed key keeps its two halves distinguishable`() {
        assertEquals("conn-a|2930002", AccountEntity.key("conn-a", "2930002"))
    }
}
