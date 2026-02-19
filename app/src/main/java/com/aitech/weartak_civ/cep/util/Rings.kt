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


