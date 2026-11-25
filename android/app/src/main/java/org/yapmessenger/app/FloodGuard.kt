package org.yapmessenger.app

/**
 * Dynamic flood throttling for envelopes arriving over nearby links (owner request,
 * 25 Nov 2026). No sender identity is used or kept: a "link" is one live connection
 * with a rotating random ID, so this never makes anyone trackable.
 *
 * Per link: more than [linkBurst] envelopes within [windowMs] (far more than a person
 * types) puts that link in slow mode: one envelope per [linkSlowIntervalMs] for
 * [linkSlowMs]. A spammer can escape by reconnecting under a new ID, so there is also
 * a phone-wide guard: more than [totalBurst] in [windowMs] across all links puts the
 * whole phone in slow mode for [totalSlowMs].
 *
 * Refused envelopes are dropped without ACK, so honest senders keep their copy and
 * retry later: slow mode delays, it does not lose messages. Honest carriers arriving
 * with a large backlog are slowed too; that is the accepted trade-off.
 * Thresholds are starting values, to be tuned with real-world tests.
 */
internal class FloodGuard(
    private val elapsed: () -> Long,
    private val windowMs: Long = 60_000,
    private val linkBurst: Int = 40,
    private val linkSlowIntervalMs: Long = 3_000,
    private val linkSlowMs: Long = 5 * 60_000,
    private val totalBurst: Int = 100,
    private val totalSlowIntervalMs: Long = 1_000,
    private val totalSlowMs: Long = 2 * 60_000,
    private val maxLinks: Int = 64
) {
    private class Gate { var start = 0L; var count = 0; var slowUntil = 0L; var lastAllowed = Long.MIN_VALUE / 2 }
    private val links = LinkedHashMap<String, Gate>()
    private val total = Gate()

    @Synchronized fun allow(link: String): Boolean {
        val now = elapsed()
        val gate = links.remove(link) ?: Gate().also { it.start = now }
        links[link] = gate // most recently used last
        while (links.size > maxLinks) links.remove(links.keys.first())
        val ok = check(gate, now, linkBurst, linkSlowIntervalMs, linkSlowMs) &&
            check(total, now, totalBurst, totalSlowIntervalMs, totalSlowMs)
        if (ok) { gate.lastAllowed = now; total.lastAllowed = now }
        return ok
    }

    @Synchronized fun slowed(link: String): Boolean = (links[link]?.slowUntil ?: 0) > elapsed()
    @Synchronized fun phoneSlowed(): Boolean = total.slowUntil > elapsed()

    private fun check(gate: Gate, now: Long, burst: Int, interval: Long, slow: Long): Boolean {
        if (now < gate.slowUntil) return now - gate.lastAllowed >= interval
        if (now - gate.start >= windowMs) { gate.start = now; gate.count = 0 }
        gate.count++
        if (gate.count > burst) { gate.slowUntil = now + slow; return false }
        return true
    }
}
