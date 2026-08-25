package io.github.urionsisdi.nfcintime.time

import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerTest {

    @Test
    fun `living costs a second per second`() {
        val ledger = Ledger(100, anchorMillis = 1_000)
        assertEquals(90L, ledger.secondsAt(11_000))
    }

    @Test
    fun `the balance stops at zero`() {
        val ledger = Ledger(5, anchorMillis = 0)
        assertEquals(0L, ledger.secondsAt(60_000))
    }

    @Test
    fun `a transfer applies to what is left, not to what was stored`() {
        val ledger = Ledger(100, anchorMillis = 0).withDelta(50, elapsedMillis = 10_000)
        assertEquals(140L, ledger.secondsAt(10_000))
    }
}
