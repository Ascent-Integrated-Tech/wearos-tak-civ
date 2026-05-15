package com.aitech.weartak_civ.cep.engine

class StateTracker {
    private val lastTrueAt = mutableMapOf<String, Long>()
    private val currentState = mutableMapOf<String, Boolean>()
    fun mark(key: String, isTrue: Boolean, now: Long) {
        currentState[key] = isTrue
        if (isTrue) lastTrueAt[key] = now
    }
    fun recentlyIn(key: String, seconds: Long, now: Long): Boolean {
        val last = lastTrueAt[key] ?: return false
        return (now - last) <= seconds * 1000
    }
    fun ageSecondsIfTrue(key: String, now: Long): Double? {
        if (currentState[key] != true) return 0.0
        val last = lastTrueAt[key] ?: return 0.0
        return (now - last) / 1000.0
    }
}

