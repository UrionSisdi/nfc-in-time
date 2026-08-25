package io.github.urionsisdi.nfcintime.game

import io.github.urionsisdi.nfcintime.time.YEAR_SECONDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateTest {

    @Test
    fun `a minute a second at the touch`() {
        assertEquals(60.0, rate(0.0), 1e-9)
    }

    @Test
    fun `an hour a second at about nine seconds`() {
        assertEquals(3600.0, rate(8.86), 10.0)
    }

    @Test
    fun `past half a minute a second of contact is worth years`() {
        assertTrue(rate(30.0) > YEAR_SECONDS)
    }
}
