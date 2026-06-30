package com.aitech.weartak_civ.cep.util

class DoubleRing(private val cap: Int) {
    private val buf = DoubleArray(cap)
    private var size = 0
    private var head = 0
    fun add(v: Double) {
        buf[head] = v
        head = (head + 1) % cap
        if (size < cap) size++
    }
    fun lastOrNull(): Double? = if (size == 0) null else buf[(head - 1 + cap) % cap]
    fun getBack(k: Int): Double? {
        if (k <= 0 || k > size) return null
        val idx = Math.floorMod(head - 1 - k, cap)
        return buf[idx]
    }
    fun tail(n: Int): List<Double> {
        val take = n.coerceAtMost(size)
        val out = ArrayList<Double>(take)
        var idx = (head - take + cap) % cap
        repeat(take) {
            out.add(buf[idx])
            idx = (idx + 1) % cap
        }
        return out
    }
}

class ArrayRing(private val cap: Int) {
    private val buf = Array(cap) { emptyList<Double>() }
    private var size = 0
    private var head = 0
    fun add(v: List<Double>) {
        buf[head] = v
        head = (head + 1) % cap
        if (size < cap) size++
    }
    fun lastOrNull(): List<Double>? = if (size == 0) null else buf[(head - 1 + cap) % cap]
    fun tail(n: Int): List<List<Double>> {
        val take = n.coerceAtMost(size)
        val out = ArrayList<List<Double>>(take)
        var idx = (head - take + cap) % cap
        repeat(take) {
            out.add(buf[idx])
            idx = (idx + 1) % cap
        }
        return out
    }
}

// ── Phase 2: timestamp-aware ring variants ────────────────────────────────────
// Samples are stored with their wall-clock measurement timestamps so that
// windows can be computed by real elapsed time rather than tick count.

/**
 * Ring buffer for scalar (Double) samples with per-sample timestamps.
 *
 * Phase 2 replacement for [DoubleRing]: switching from count-based to
 * time-based window extraction makes CEP feature windows rate-independent.
 */
class TimedDoubleRing(private val cap: Int) {
    private val values = DoubleArray(cap)
    private val times  = LongArray(cap)
    var size = 0
        private set
    private var head = 0

    /**
     * Copies buffer state from [other] into this ring. Both rings must have the same capacity
     * (same [RingConfig]-derived sizing); used when swapping engines to retain sensor history.
     */
    fun copyStateFrom(other: TimedDoubleRing) {
        check(cap == other.cap) { "TimedDoubleRing capacity mismatch: $cap vs ${other.cap}" }
        System.arraycopy(other.values, 0, values, 0, cap)
        System.arraycopy(other.times, 0, times, 0, cap)
        size = other.size
        head = other.head
    }

    fun add(v: Double, tsMs: Long) {
        values[head] = v
        times[head]  = tsMs
        head = (head + 1) % cap
        if (size < cap) size++
    }

    fun lastOrNull(): Double? = if (size == 0) null else values[(head - 1 + cap) % cap]
    fun lastTsOrNull(): Long?  = if (size == 0) null else  times[(head - 1 + cap) % cap]

    /**
     * Values whose timestamp falls in the half-open interval
     * (nowMs - windowMs, nowMs].  Walks backwards (newest-first) and stops
     * at the first sample older than the cutoff — valid because samples are
     * ingested in monotonically non-decreasing timestamp order.
     */
    fun tailByTime(windowMs: Long, nowMs: Long): List<Double> {
        val cutoff = nowMs - windowMs
        val out = ArrayList<Double>()
        for (i in 0 until size) {
            val idx = Math.floorMod(head - 1 - i, cap)
            if (times[idx] <= cutoff) break
            out.add(values[idx])
        }
        out.reverse()
        return out
    }

    /** Same as [tailByTime] but returns (tsMs, value) pairs. */
    fun tailByTimePairs(windowMs: Long, nowMs: Long): List<Pair<Long, Double>> {
        val cutoff = nowMs - windowMs
        val out = ArrayList<Pair<Long, Double>>()
        for (i in 0 until size) {
            val idx = Math.floorMod(head - 1 - i, cap)
            if (times[idx] <= cutoff) break
            out.add(Pair(times[idx], values[idx]))
        }
        out.reverse()
        return out
    }

    /**
     * Returns the value of the most-recent sample whose timestamp is
     * at-or-before (nowMs - horizonMs).  Used for Delta features:
     * delta = current − value_at(nowMs − horizonSec).
     */
    fun getByTime(horizonMs: Long, nowMs: Long): Double? {
        val target = nowMs - horizonMs
        for (i in 0 until size) {
            val idx = Math.floorMod(head - 1 - i, cap)
            if (times[idx] <= target) return values[idx]
        }
        return null
    }

    // ── Sample-count accessors (for SampleWindow / SampleDelta / SampleSlope) ──

    /**
     * Returns the [n] most-recently inserted samples in chronological order
     * (oldest-first).  If fewer than [n] samples exist, returns all of them.
     */
    fun tailByCount(n: Int): List<Double> {
        val take = n.coerceAtMost(size)
        val out = ArrayList<Double>(take)
        for (i in 0 until take) {
            val idx = Math.floorMod(head - 1 - i, cap)
            out.add(values[idx])
        }
        out.reverse()
        return out
    }

    /**
     * Returns (tsMs, value) pairs for the [n] most-recently inserted samples
     * in chronological order (oldest-first).
     */
    fun tailByCountPairs(n: Int): List<Pair<Long, Double>> {
        val take = n.coerceAtMost(size)
        val out = ArrayList<Pair<Long, Double>>(take)
        for (i in 0 until take) {
            val idx = Math.floorMod(head - 1 - i, cap)
            out.add(Pair(times[idx], values[idx]))
        }
        out.reverse()
        return out
    }

    /**
     * Returns the value of the sample [k] positions back from the most-recently
     * inserted sample (0 = newest, 1 = second-newest, …).
     * Returns null if [k] is out of range.
     */
    fun getByCount(k: Int): Double? {
        if (k < 0 || k >= size) return null
        val idx = Math.floorMod(head - 1 - k, cap)
        return values[idx]
    }
}

/**
 * Ring buffer for multi-channel (List<Double>) samples with per-sample timestamps.
 * Phase 2 replacement for [ArrayRing].
 */
class TimedArrayRing(private val cap: Int) {
    private val buf   = Array(cap) { emptyList<Double>() }
    private val times = LongArray(cap)
    private var size = 0
    private var head = 0

    fun copyStateFrom(other: TimedArrayRing) {
        check(cap == other.cap) { "TimedArrayRing capacity mismatch: $cap vs ${other.cap}" }
        for (i in buf.indices) buf[i] = other.buf[i].toList()
        System.arraycopy(other.times, 0, times, 0, cap)
        size = other.size
        head = other.head
    }

    fun add(v: List<Double>, tsMs: Long) {
        buf[head]   = v
        times[head] = tsMs
        head = (head + 1) % cap
        if (size < cap) size++
    }

    fun lastOrNull(): List<Double>? = if (size == 0) null else buf[(head - 1 + cap) % cap]
    fun lastTsOrNull(): Long?       = if (size == 0) null else times[(head - 1 + cap) % cap]

    /** Channel-lists whose timestamp falls in (nowMs - windowMs, nowMs]. */
    fun tailByTime(windowMs: Long, nowMs: Long): List<List<Double>> {
        val cutoff = nowMs - windowMs
        val out = ArrayList<List<Double>>()
        for (i in 0 until size) {
            val idx = Math.floorMod(head - 1 - i, cap)
            if (times[idx] <= cutoff) break
            out.add(buf[idx])
        }
        out.reverse()
        return out
    }

    /** (tsMs, channels) pairs within the window. */
    fun tailByTimePairs(windowMs: Long, nowMs: Long): List<Pair<Long, List<Double>>> {
        val cutoff = nowMs - windowMs
        val out = ArrayList<Pair<Long, List<Double>>>()
        for (i in 0 until size) {
            val idx = Math.floorMod(head - 1 - i, cap)
            if (times[idx] <= cutoff) break
            out.add(Pair(times[idx], buf[idx]))
        }
        out.reverse()
        return out
    }
}


