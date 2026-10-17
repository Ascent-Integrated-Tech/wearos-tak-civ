package com.aitech.weartak_civ.service

internal class PhoneManagementLease {
    companion object {
        const val TIMEOUT_MS = 30_000L
        private const val PROBE_INTERVAL_MS = 10_000L
    }

    private var address: String? = null
    private var generation = -1L
    private var deadline = 0L
    private var nextProbe = 0L
    private var challenge: String? = null

    @Synchronized
    fun start(address: String, generation: Long, now: Long) {
        this.address = address
        this.generation = generation
        deadline = now + TIMEOUT_MS
        nextProbe = now
        challenge = null
    }

    @Synchronized
    fun stop() {
        address = null
        challenge = null
    }

    @Synchronized
    fun expire(now: Long): Boolean {
        if (address == null || now < deadline) return false
        stop()
        return true
    }

    @Synchronized
    fun probe(address: String, generation: Long, now: Long): String? {
        if (!matches(address, generation) || now >= deadline || now < nextProbe) return null
        nextProbe = now + PROBE_INTERVAL_MS
        return java.util.UUID.randomUUID().toString().also { challenge = it }
    }

    @Synchronized
    fun acknowledge(address: String, generation: Long, token: String, now: Long): Boolean {
        if (!matches(address, generation) || now >= deadline || token != challenge) return false
        challenge = null
        deadline = now + TIMEOUT_MS
        return true
    }

    @Synchronized
    fun ownsSession(address: String?, generation: Long): Boolean =
        this.address != null && address != null && matches(address, generation)

    private fun matches(address: String, generation: Long) =
        this.address.equals(address, ignoreCase = true) && this.generation == generation
}
