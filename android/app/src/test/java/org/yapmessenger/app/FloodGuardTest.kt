package org.yapmessenger.app

import org.junit.Assert.*
import org.junit.Test

class FloodGuardTest {
    private var now = 0L
    private fun guard() = FloodGuard({ now }, linkBurst = 5, linkSlowIntervalMs = 3_000, linkSlowMs = 60_000, totalBurst = 12, totalSlowIntervalMs = 1_000, totalSlowMs = 30_000)

    @Test fun normalUseIsNeverSlowed() {
        val g = guard()
        repeat(20) { assertTrue(g.allow("a")); now += 15_000 } // one message every 15 s
        assertFalse(g.slowed("a"))
    }

    @Test fun floodingLinkIsSlowedThenRecovers() {
        val g = guard()
        repeat(5) { assertTrue(g.allow("a")) }
        assertFalse(g.allow("a"))            // sixth in the window trips slow mode
        assertTrue(g.slowed("a"))
        assertFalse(g.allow("a"))            // too soon
        now += 3_000; assertTrue(g.allow("a"))  // one per interval
        assertFalse(g.allow("a"))
        assertTrue(g.allow("b"))             // other links unaffected
        now += 61_000
        assertFalse(g.slowed("a")); assertTrue(g.allow("a"))
    }

    @Test fun reconnectingUnderNewIdsTripsThePhoneWideGuard() {
        val g = guard()
        var allowed = 0
        for (i in 0 until 40) if (g.allow("id$i")) allowed++  // new ID per message
        assertEquals(12, allowed)
        assertTrue(g.phoneSlowed())
        now += 1_000; assertTrue(g.allow("fresh")); assertFalse(g.allow("fresh2"))
        now += 31_000; assertFalse(g.phoneSlowed())
    }
}
