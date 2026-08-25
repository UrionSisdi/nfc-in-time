package io.github.urionsisdi.nfcintime.game

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TICK = 50
// Back to back: the phone above is the one lying screen up.
private const val FACE_DOWN = -9.81
private const val FACE_UP = 9.81

class ContactEngineTest {

    @Test
    fun `both sides reach the same number`() {
        val top = ContactEngine(1000, 1000)
        val bottom = ContactEngine(1000, 1000)
        repeat(10) {
            top.tick(TICK, FACE_UP, FACE_DOWN)
            bottom.tick(TICK, FACE_DOWN, FACE_UP)
        }
        assertEquals(top.state().net, -bottom.state().net)
    }

    @Test
    fun `neutral moves nothing and freezes the multiplier`() {
        val engine = ContactEngine(1000, 1000)
        repeat(20) { engine.tick(TICK, 0.0, 0.0) }
        assertEquals(0L, engine.state().net)
        assertEquals(0.0, engine.state().contactSeconds, 1e-9)
    }

    @Test
    fun `a drained donor gives up what is left and no more`() {
        val engine = ContactEngine(1_000_000, 120)
        var state = engine.state()
        repeat(100) { if (!state.drained) state = engine.tick(TICK, FACE_UP, FACE_DOWN) }
        assertTrue(state.drained)
        assertEquals(0L, state.peerBalance)
        // Short of 120: the donor is also spending a second per second on being alive.
        assertTrue(state.net in 110..120)
    }

    @Test
    fun `taking the top back keeps the multiplier`() {
        val engine = ContactEngine(1_000_000_000, 1_000_000_000)
        repeat(40) { engine.tick(TICK, FACE_DOWN, FACE_UP) }
        val lost = engine.state().net
        val heldAfterLosing = engine.state().contactSeconds
        repeat(40) { engine.tick(TICK, FACE_UP, FACE_DOWN) }
        assertTrue(lost < 0)
        assertTrue(engine.state().contactSeconds > heldAfterLosing)
        // The same two seconds of contact, back the other way, come back richer.
        assertTrue(engine.state().net > lost)
    }

    @Test
    fun `a settlement is accepted within one tick and refused beyond it`() {
        val engine = ContactEngine(1_000_000, 1_000_000)
        repeat(10) { engine.tick(TICK, FACE_UP, FACE_DOWN) }
        val net = engine.pending()
        assertTrue(engine.agreesWith(net))
        assertFalse(engine.agreesWith(net + abs(net)))
    }

    @Test
    fun `committing starts the next settlement from zero`() {
        val engine = ContactEngine(1_000_000, 1_000_000)
        repeat(10) { engine.tick(TICK, FACE_UP, FACE_DOWN) }
        engine.commit()
        assertEquals(0L, engine.pending())
        assertTrue(engine.state().net > 0)
    }
}
