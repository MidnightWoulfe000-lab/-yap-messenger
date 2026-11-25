package org.yapmessenger.app

import org.junit.Assert.*
import org.junit.Test

class SprayAndStampTest {
    private val key = ByteArray(48) { it.toByte() }

    @Test fun stampVerifiesAndIsBoundToTheEnvelope() {
        val stamp = EffortStamp.make(key, bits = 12)
        assertTrue(EffortStamp.valid(key, stamp, bits = 12))
        val other = key.clone().also { it[0] = 99 }
        // A stamp for one envelope almost never fits another (1 in 4096 at 12 bits).
        assertFalse(EffortStamp.valid(other, stamp, bits = 12) && EffortStamp.valid(other, stamp, bits = 16))
        assertFalse(EffortStamp.valid(key, ByteArray(7), bits = 12))
    }

    @Test fun realDifficultyStampIsValid() {
        val stamp = EffortStamp.make(key)
        assertTrue(EffortStamp.valid(key, stamp))
    }

    @Test fun randomStampsAreRejected() {
        var accepted = 0
        repeat(2000) { if (EffortStamp.valid(key, ByteArray(8) { _ -> (it * 31 + 7).toByte() })) accepted++ }
        assertTrue(accepted < 3)
    }

    @Test fun halvesCopiesThenWaits() {
        var copies = Spray.MAX_COPIES
        val given = mutableListOf<Int>()
        while (copies > 1) { val g = Spray.give(copies); given += g; copies -= g }
        assertEquals(listOf(6, 3, 1, 1), given)
        assertEquals(1, Spray.give(1)) // last copy: recipient only
    }

    @Test fun frameRoundTripsAndRejectsBadCopies() {
        val env = ByteArray(80) { 3 }
        val frame = Spray.decode(Spray.encode(5, ByteArray(8) { 1 }, env))!!
        assertEquals(5, frame.copies); assertArrayEquals(env, frame.envelope)
        assertNull(Spray.decode(byteArrayOf(0) + ByteArray(8) + env))
        assertNull(Spray.decode(byteArrayOf(13) + ByteArray(8) + env))
        assertNull(Spray.decode(ByteArray(20)))
    }
}
