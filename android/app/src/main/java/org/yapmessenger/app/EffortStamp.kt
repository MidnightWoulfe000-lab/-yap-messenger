package org.yapmessenger.app

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Effort stamp (proof of work) for nearby envelopes. The sender searches for an
 * 8-byte value so that SHA-256("Yap stamp v1\0" || relay key || stamp) starts with
 * [BITS] zero bits. That takes about a quarter of a million hashes on average
 * (roughly 0.3–1 s on a mid-range phone); checking it takes one hash.
 *
 * The relay key covers every immutable envelope byte, so a stamp cannot be reused
 * for another envelope. Carriers pass the stamp along unchanged and every phone
 * checks it before storing or carrying anything.
 *
 * This makes mass junk slow and battery-hungry for phones. A laptop or server can
 * still mint thousands per minute, so flood slow mode and limited copies remain
 * the main defences. [BITS] is a starting value; tune on low-end phones.
 */
internal object EffortStamp {
    const val BITS = 18
    const val BYTES = 8
    private val domain = "Yap stamp v1\u0000".toByteArray()
    private val random = SecureRandom()

    fun make(relayKey: ByteArray, bits: Int = BITS): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val stamp = ByteArray(BYTES).also(random::nextBytes)
        while (true) {
            if (meets(digest, relayKey, stamp, bits)) return stamp
            increment(stamp)
        }
    }

    fun valid(relayKey: ByteArray, stamp: ByteArray, bits: Int = BITS): Boolean =
        stamp.size == BYTES && meets(MessageDigest.getInstance("SHA-256"), relayKey, stamp, bits)

    private fun meets(digest: MessageDigest, key: ByteArray, stamp: ByteArray, bits: Int): Boolean {
        digest.reset(); digest.update(domain); digest.update(key); digest.update(stamp)
        val hash = digest.digest()
        var remaining = bits
        for (byte in hash) {
            val value = byte.toInt() and 255
            if (remaining >= 8) { if (value != 0) return false; remaining -= 8 }
            else return remaining == 0 || value ushr (8 - remaining) == 0
        }
        return true
    }

    private fun increment(stamp: ByteArray) {
        for (i in stamp.indices.reversed()) { stamp[i] = (stamp[i] + 1).toByte(); if (stamp[i] != 0.toByte()) return }
    }
}

/**
 * Spray and wait (owner decision, 25 Nov 2026: 12 copies). Every nearby ENVELOPE
 * frame is `copies (1) + effort stamp (8) + envelope`. A holder with more than one
 * copy hands half to the next phone; with one copy left it only delivers straight
 * to the recipient. See docs/proposals/local-mesh-v2.md.
 */
internal object Spray {
    const val MAX_COPIES = 12
    private const val PREFIX = 1 + EffortStamp.BYTES
    private const val MIN_ENVELOPE = 74

    class Frame(val copies: Int, val stamp: ByteArray, val envelope: ByteArray)

    /** Copies to give the next phone. With one copy left, it goes only to the recipient. */
    fun give(copies: Int): Int = if (copies > 1) copies / 2 else 1

    fun encode(copies: Int, stamp: ByteArray, envelope: ByteArray): ByteArray {
        require(copies in 1..MAX_COPIES && stamp.size == EffortStamp.BYTES)
        return byteArrayOf(copies.toByte()) + stamp + envelope
    }

    fun decode(payload: ByteArray): Frame? {
        if (payload.size < PREFIX + MIN_ENVELOPE) return null
        val copies = payload[0].toInt() and 255
        if (copies !in 1..MAX_COPIES) return null
        return Frame(copies, payload.copyOfRange(1, PREFIX), payload.copyOfRange(PREFIX, payload.size))
    }
}
