package io.github.urionsisdi.nfcintime.time

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `a hundred years split the way the landing splits them`() {
        val left = span(100 * YEAR_SECONDS)
        assertEquals(100L, left.years)
        assertEquals(0L, left.days)
    }

    @Test
    fun `groups of three, as on the landing`() {
        assertEquals("999", group(999))
        assertEquals("1,000", group(1000))
        assertEquals("31,557,600", group(31_557_600))
    }

    @Test
    fun `coarse drops to the two units that matter`() {
        val units = Units("y", "d", "h", "m", "s")
        assertEquals("73 y 119 d", coarse(73 * YEAR_SECONDS + 119 * DAY_SECONDS, units))
        assertEquals("4 h 09 m", coarse(4 * 3600 + 9 * 60, units))
    }
}
