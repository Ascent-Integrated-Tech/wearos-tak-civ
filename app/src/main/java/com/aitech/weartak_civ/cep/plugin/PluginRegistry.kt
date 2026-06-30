package com.aitech.weartak_civ.cep.plugin

import com.aitech.weartak_civ.cep.engine.config.CepConfigMerger
import com.aitech.weartak_civ.cep.engine.config.ParserException
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Holds active plugin overlays and persists raw/cert/lease state. */
class PluginRegistry(
    private val repo: DataRepository,
    private val trust: PluginTrust,
    private val onBootstrapParseFailure: (raw: String, reasonCode: String, message: String) -> Unit = { _, _, _ -> }
) {
    private val mutex = Mutex()
    private val byId = LinkedHashMap<String, ParsedPluginOverlay>()
    /** Time source — overridable in tests for deterministic lease-expiry assertions. */
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    suspend fun bootstrap() = mutex.withLock {
        val raws = repo.getCepPluginOverlaysOnce()
        val leases = repo.getCepPluginLeaseUntilMsOnce()
        val now = clock()
        val survivors = LinkedHashMap<String, String>()
        val survivorLeases = LinkedHashMap<String, Long>()
        for ((id, raw) in raws.entries.sortedBy { it.key }) {
            val leaseUntil = leases[id] ?: 0L
            if (leaseUntil > 0L && leaseUntil < now) continue
            val parsed = try {
                PluginOverlayParser.parse(raw)
            } catch (t: ParserException) {
                onBootstrapParseFailure(
                    raw,
                    t.reasonCode,
                    t.message ?: "plugin overlay rejected during bootstrap"
                )
                continue
            }
            byId[id] = parsed
            survivors[id] = raw
            survivorLeases[id] = leaseUntil
        }
        repo.storeCepPluginOverlays(survivors)
        repo.storeCepPluginLeaseUntilMs(survivorLeases)
    }

    /**
     * Parse + trust-gate + upsert. The receiver MUST resolve [senderUid] and [senderPackages]
     * from the calling broadcast (via `Binder.getCallingUid()` /
     * `PackageManager.getPackagesForUid`) and pass them through; the trust gate refuses to
     * register a [pluginId] not owned by the caller's UID.
     */
    suspend fun upsertFromRaw(
        raw: String,
        senderUid: Int,
        senderPackages: Set<String>,
    ): ParsedPluginOverlay = mutex.withLock {
        val parsed = PluginOverlayParser.parse(raw)
        trust.enforce(parsed.pluginId, senderUid, senderPackages)
        val leaseUntil = if (parsed.leaseSec <= 0) 0L else clock() + parsed.leaseSec * 1000L
        byId[parsed.pluginId] = parsed
        val raws = repo.getCepPluginOverlaysOnce().toMutableMap()
        raws[parsed.pluginId] = raw
        repo.storeCepPluginOverlays(raws)
        val leases = repo.getCepPluginLeaseUntilMsOnce().toMutableMap()
        leases[parsed.pluginId] = leaseUntil
        repo.storeCepPluginLeaseUntilMs(leases)
        parsed
    }

    /**
     * Remove a plugin's overlay + lease state. The caller (CepProvider) is responsible for
     * triggering a recompose so the engine swap clears any alerts owned by removed plugin
     * rules (handled by the same `clearAlertsForRemovedRules` path used by managed apply).
     * Trust gating for the unregister broadcast happens in the receiver before invocation.
     */
    suspend fun unregister(pluginId: String) = mutex.withLock {
        byId.remove(pluginId)
        val raws = repo.getCepPluginOverlaysOnce().toMutableMap()
        raws.remove(pluginId)
        repo.storeCepPluginOverlays(raws)
        val leases = repo.getCepPluginLeaseUntilMsOnce().toMutableMap()
        leases.remove(pluginId)
        repo.storeCepPluginLeaseUntilMs(leases)
    }

    /**
     * Lazy lease-expiry evict: drop any plugin whose lease elapsed since boot/last apply.
     * Returns the set of removed plugin ids so the caller can decide whether a recompose +
     * alert clear is warranted. v1 cadence: runs each time a recompose is about to happen
     * (no separate background timer — design §539–550 lists lease expiry as an eviction
     * trigger but does not mandate millisecond-precise mid-tick eviction).
     */
    suspend fun evictExpired(): Set<String> = mutex.withLock {
        val now = clock()
        val leases = repo.getCepPluginLeaseUntilMsOnce()
        val expired = byId.keys.filter { id ->
            val until = leases[id] ?: 0L
            until in 1 until now
        }.toSet()
        if (expired.isEmpty()) return@withLock expired
        for (id in expired) byId.remove(id)
        val raws = repo.getCepPluginOverlaysOnce().toMutableMap()
        for (id in expired) raws.remove(id)
        repo.storeCepPluginOverlays(raws)
        val survivorLeases = leases.toMutableMap()
        for (id in expired) survivorLeases.remove(id)
        repo.storeCepPluginLeaseUntilMs(survivorLeases)
        expired
    }

    suspend fun contributions(): List<CepConfigMerger.PluginContribution> = mutex.withLock {
        byId.values.sortedBy { it.pluginId }.map { it.contribution }
    }

    /** Snapshot of currently known plugin ids — used by package-removed receiver fan-out. */
    suspend fun knownPluginIds(): Set<String> = mutex.withLock { byId.keys.toSet() }

    /**
     * Rule IDs contributed by [pluginId]. Used by the apply lane after an explicit unregister
     * or package-removed eviction so the same `clearAlertsForRemovedRules` orchestration the
     * managed path uses can be invoked for the plugin path too.
     */
    suspend fun ruleIdsForPlugin(pluginId: String): Set<String> = mutex.withLock {
        byId[pluginId]?.contribution?.rules?.map { it.id }?.toSet().orEmpty()
    }
}
