package io.github.urionsisdi.nfcintime.game

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DominanceTest {

    @Test
    fun `hand on top takes`() {
        // Back to back: the hand on top holds a phone screen up, the one under it
        // screen down. Time goes to the phone above.
        assertEquals(1.0, dominance(gzSelf = 9.81, gzPeer = -9.81), 1e-6)
        assertEquals(-1.0, dominance(gzSelf = -9.81, gzPeer = 9.81), 1e-6)
    }

    @Test
    fun `both on edge is neutral`() {
        assertEquals(0.0, dominance(gzSelf = 0.0, gzPeer = 0.0), 1e-9)
    }

    @Test
    fun `dead zone holds until the threshold and releases later`() {
        val zone = DeadZone()
        assertEquals(0.0, zone.shape(0.29), 1e-9)
        assertTrue(zone.shape(0.31) > 0.0)
        // Hysteresis: what opened at 0.30 keeps flowing at 0.24.
        assertTrue(zone.shape(0.24) > 0.0)
        assertEquals(0.0, zone.shape(0.21), 1e-9)
    }

    @Test
    fun `shape is odd`() {
        val up = DeadZone().shape(0.8)
        val down = DeadZone().shape(-0.8)
        assertEquals(up, -down, 1e-9)
        assertTrue(abs(up) <= 1.0)
    }
}
