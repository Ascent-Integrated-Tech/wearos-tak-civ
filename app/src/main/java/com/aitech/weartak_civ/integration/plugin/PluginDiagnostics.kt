package com.aitech.weartak_civ.integration.plugin

import android.util.Log
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight plugin integration diagnostics (discover / bind / reject / death).
 * In-memory counters + [integrationState] for tests and host-side debugging (no UI, no persistence).
 */
object PluginDiagnostics {
    private const val TAG = "CEP-PluginDiag"

    /** Stable category keys for counters, [snapshot], and [integrationState]. */
    object AdmissionCategory {
        const val PLUGIN_INFO_MISMATCH = "plugin_info_mismatch"
        const val TRUST_FAILURE = "trust_failure"
        const val UID_PACKAGE_MISMATCH = "uid_package_mismatch"
        const val BINDER_PROTOCOL = "binder_protocol"
        const val LIFECYCLE_DISCONNECT = "lifecycle_disconnect"
        const val BINDER_DEATH = "binder_death"
        /** [PluginManager.unbindPackage] / shutdown-driven unbind. */
        const val EXPLICIT_UNBIND = "explicit_unbind"
        /** [PluginIngressPort.onPackageFullyRemoved] (package uninstall). */
        const val PACKAGE_REMOVED = "package_removed"
        /** [markRejected] without structured admission (legacy / tests). */
        const val LEGACY_REJECTION = "legacy_rejection"
    }

    private val lock = Any()
    private val discovered = Collections.synchronizedSet(mutableSetOf<String>())
    private val bound = Collections.synchronizedSet(mutableSetOf<String>())
    private val connected = Collections.synchronizedSet(mutableSetOf<String>())
    private val rejected = Collections.synchronizedSet(mutableSetOf<String>())

    private val categoryCounts = ConcurrentHashMap<String, AtomicInteger>()

    val bindFailures = AtomicInteger(0)
    val deniedAdmission = AtomicInteger(0)
    val binderDeaths = AtomicInteger(0)
    val unbindEvents = AtomicInteger(0)
    private val discoveryRefreshes = AtomicInteger(0)
    private val packageRemovals = AtomicInteger(0)

    /** All [AdmissionCategory] values plus any extra keys that ever appear in [categoryCounts]. */
    fun stableCategoryKeys(): List<String> = listOf(
        AdmissionCategory.PLUGIN_INFO_MISMATCH,
        AdmissionCategory.TRUST_FAILURE,
        AdmissionCategory.UID_PACKAGE_MISMATCH,
        AdmissionCategory.BINDER_PROTOCOL,
        AdmissionCategory.LIFECYCLE_DISCONNECT,
        AdmissionCategory.BINDER_DEATH,
        AdmissionCategory.EXPLICIT_UNBIND,
        AdmissionCategory.PACKAGE_REMOVED,
        AdmissionCategory.LEGACY_REJECTION,
    )

    /**
     * Maps [PluginConnection.failBind] / [PluginManager.onConnectionFailed] reason strings
     * to a stable admission category for counters and logs.
     */
    fun admissionCategoryForAidlBindFailure(reason: String): String {
        if (reason.startsWith("trust_failure:")) {
            val code = reason.removePrefix("trust_failure:")
            return if (code == ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH) {
                AdmissionCategory.UID_PACKAGE_MISMATCH
            } else {
                AdmissionCategory.TRUST_FAILURE
            }
        }
        return when (reason) {
            "plugin_info_mismatch" -> AdmissionCategory.PLUGIN_INFO_MISMATCH
            "uid_package_mismatch" -> AdmissionCategory.UID_PACKAGE_MISMATCH
            "null_binder", "asInterface", "get_plugin_info", "remote_exception" -> AdmissionCategory.BINDER_PROTOCOL
            else -> AdmissionCategory.BINDER_PROTOCOL
        }
    }

    /**
     * Queryable view of current plugin integration diagnostics (same data as [snapshot], plus
     * stable per-category map entries and a debug string). Does not persist; suitable for logging
     * or dev settings hooks later.
     */
    fun integrationState(): PluginIntegrationState {
        synchronized(lock) {
            val snap = snapshotUnlocked()
            val cats = stableCategoryKeys().associateWith { snap.categoryCounts[it] ?: 0 }
            return PluginIntegrationState(snapshot = snap, stableCategories = cats)
        }
    }

    fun snapshot(): Snapshot {
        synchronized(lock) {
            return snapshotUnlocked()
        }
    }

    private fun snapshotUnlocked(): Snapshot {
        return Snapshot(
            discovered = discovered.toSet(),
            bound = bound.toSet(),
            connected = connected.toSet(),
            rejected = rejected.toSet(),
            bindFailures = bindFailures.get(),
            deniedAdmission = deniedAdmission.get(),
            binderDeaths = binderDeaths.get(),
            unbindEvents = unbindEvents.get(),
            discoveryRefreshCount = discoveryRefreshes.get(),
            packageRemovals = packageRemovals.get(),
            categoryCounts = categoryCounts.mapValues { it.value.get() },
        )
    }

    fun markDiscovered(packageNames: Collection<String>) {
        synchronized(lock) {
            discovered.clear()
            discovered.addAll(packageNames)
            discoveryRefreshes.incrementAndGet()
        }
        Log.i(TAG, "discovered plugins: ${packageNames.joinToString()}")
    }

    fun markBound(pkg: String) {
        bound.add(pkg)
        Log.i(TAG, "bound: $pkg")
    }

    fun markUnbound(pkg: String) {
        bound.remove(pkg)
        unbindEvents.incrementAndGet()
        bumpCategory(AdmissionCategory.EXPLICIT_UNBIND)
        Log.i(TAG, "unbound: $pkg")
    }

    fun markConnected(pkg: String) {
        connected.add(pkg)
        Log.i(TAG, "connected: $pkg")
    }

    fun markDisconnected(pkg: String) {
        connected.remove(pkg)
        bumpCategory(AdmissionCategory.LIFECYCLE_DISCONNECT)
        Log.i(TAG, "disconnected: $pkg")
    }

    fun markRejected(pkg: String, reason: String) {
        rejected.add(pkg)
        deniedAdmission.incrementAndGet()
        bumpCategory(AdmissionCategory.LEGACY_REJECTION)
        Log.w(TAG, "rejected $pkg: $reason")
    }

    /**
     * Structured admission denial (AIDL bind path and connection teardown).
     * Increments [deniedAdmission], records [pkg] in [rejected], bumps per-category counters.
     */
    fun recordAdmissionRejected(pkg: String, category: String, detail: String?) {
        synchronized(lock) {
            rejected.add(pkg)
        }
        deniedAdmission.incrementAndGet()
        bumpCategory(category)
        val suffix = if (detail.isNullOrBlank()) "" else " detail=$detail"
        Log.w(TAG, "admission rejected pkg=$pkg category=$category$suffix")
    }

    private fun bumpCategory(category: String) {
        categoryCounts.getOrPut(category) { AtomicInteger(0) }.incrementAndGet()
    }

    /**
     * Transport / discovery bind failure (cannot connect to service).
     * @param includeBinderProtocolCategory when false, only [bindFailures] increments — use when
     *   [PluginManager.onConnectionFailed] will record a structured admission row for the same
     *   failure (avoids double-counting [AdmissionCategory.BINDER_PROTOCOL]).
     */
    fun recordBindFailure(component: String, t: Throwable?, includeBinderProtocolCategory: Boolean = true) {
        bindFailures.incrementAndGet()
        if (includeBinderProtocolCategory) {
            bumpCategory(AdmissionCategory.BINDER_PROTOCOL)
        }
        Log.w(TAG, "bind failure for $component", t)
    }

    fun recordBinderDeath(pkg: String) {
        binderDeaths.incrementAndGet()
        bumpCategory(AdmissionCategory.BINDER_DEATH)
        Log.w(TAG, "binder death: $pkg")
    }

    /** Host received package-removed; CEP eviction is async — this counts the integration event only. */
    fun recordPackageRemoved(packageName: String) {
        packageRemovals.incrementAndGet()
        bumpCategory(AdmissionCategory.PACKAGE_REMOVED)
        Log.i(TAG, "package removed: $packageName")
    }

    /** Clears counters and sets for deterministic unit/Robolectric tests. */
    fun resetForTest() {
        synchronized(lock) {
            discovered.clear()
            bound.clear()
            connected.clear()
            rejected.clear()
        }
        categoryCounts.clear()
        bindFailures.set(0)
        deniedAdmission.set(0)
        binderDeaths.set(0)
        unbindEvents.set(0)
        discoveryRefreshes.set(0)
        packageRemovals.set(0)
    }

    data class Snapshot(
        val discovered: Set<String>,
        val bound: Set<String>,
        val connected: Set<String>,
        val rejected: Set<String>,
        val bindFailures: Int,
        val deniedAdmission: Int,
        val binderDeaths: Int,
        val unbindEvents: Int,
        /** Number of times [markDiscovered] ran (discovery refresh cycles). */
        val discoveryRefreshCount: Int = 0,
        /** Number of package-removal integration events recorded. */
        val packageRemovals: Int = 0,
        /** Per-category event counts (admission, bind protocol, lifecycle, package removal, …). */
        val categoryCounts: Map<String, Int> = emptyMap(),
    )

    /**
     * Stable, queryable view built from [snapshot] with every [stableCategoryKeys] entry present
     * in [stableCategories] (value 0 if no events yet).
     */
    data class PluginIntegrationState(
        val snapshot: Snapshot,
        val stableCategories: Map<String, Int>,
    ) {
        fun toDebugString(): String = buildString {
            val s = snapshot
            appendLine("PluginIntegrationState")
            appendLine("  discovered: count=${s.discovered.size} pkgs=${s.discovered}")
            appendLine("  bound: count=${s.bound.size} pkgs=${s.bound}")
            appendLine("  connected: count=${s.connected.size} pkgs=${s.connected}")
            appendLine("  rejected: count=${s.rejected.size} pkgs=${s.rejected}")
            appendLine("  bindFailures=${s.bindFailures} deniedAdmission=${s.deniedAdmission}")
            appendLine("  binderDeaths=${s.binderDeaths} unbindEvents=${s.unbindEvents}")
            appendLine("  discoveryRefreshCount=${s.discoveryRefreshCount} packageRemovals=${s.packageRemovals}")
            appendLine("  categories:")
            stableCategories.keys.sorted().forEach { k ->
                appendLine("    $k=${stableCategories[k]}")
            }
        }
    }
}
