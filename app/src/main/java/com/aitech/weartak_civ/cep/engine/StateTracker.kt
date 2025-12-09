package com.aitech.weartak_civ.cep.engine

class StateTracker {
    private val lastTrueAt = mutableMapOf<String, Long>()
    fun mark(key: String, isTrue: Boolean, now: Long) {
        if (isTrue) lastTrueAt[key] = now
    }
    fun recentlyIn(key: String, seconds: Long, now: Long): Boolean {
        val last = lastTrueAt[key] ?: return false
        return (now - last) <= seconds * 1000
    }
}


