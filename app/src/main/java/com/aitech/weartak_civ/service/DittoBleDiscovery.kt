package com.aitech.weartak_civ.service

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import live.ditto.Ditto
import live.ditto.DittoConnectionType
import live.ditto.DittoError
import live.ditto.DittoPresenceGraph
import live.ditto.DittoPresenceObserver

internal enum class BleDiscoveryReason {
    STARTUP, UI, WRIST_ON, SETTINGS, PRESENCE, WINDOW_EXPIRED, GATED, CLOSED
}

internal enum class BleDiscoveryAction { START, STOP, BACKOFF }

/**
 * One discovery window per event, not a periodic scan. A BLE-only peer arriving after
 * backoff is discoverable only on UI entry, wrist-on, Ditto settings/topology changes,
 * or service restart. LAN/WiFi peers alone never keep BLE enabled.
 */
internal class DittoBleDiscovery(
    private val scope: CoroutineScope,
    private val setEnabled: (Boolean) -> Boolean,
    private val record: (BleDiscoveryAction, BleDiscoveryReason, Int) -> Unit
) : AutoCloseable {
    private var allowed = false
    private var closed = false
    private var enabled = false
    private var bleConnections = 0
    private var topology: Set<String>? = null
    private var deadline: Job? = null
    private var generation = 0L

    @Synchronized
    fun setAllowed(value: Boolean) {
        if (closed || allowed == value) return
        allowed = value
        if (value) {
            request(BleDiscoveryReason.STARTUP)
        } else {
            cancelDeadline()
            changeEnabled(false, BleDiscoveryReason.GATED)
        }
    }

    @Synchronized
    fun request(reason: BleDiscoveryReason) {
        if (closed || !allowed) return
        if (!changeEnabled(true, reason)) return
        if (bleConnections == 0 && deadline == null) {
            val current = ++generation
            deadline = scope.launch {
                delay(WINDOW_MS)
                expire(current)
            }
        }
    }

    @Synchronized
    fun peersChanged(connections: Set<String>, localBleConnections: Int) {
        if (closed) return
        val previous = topology
        topology = connections
        bleConnections = localBleConnections
        if (bleConnections > 0) {
            cancelDeadline()
            if (allowed) changeEnabled(true, BleDiscoveryReason.PRESENCE)
        } else if (previous != null && previous != connections) {
            request(BleDiscoveryReason.PRESENCE)
        }
    }

    @Synchronized
    private fun expire(current: Long) {
        if (closed || !allowed || current != generation || bleConnections > 0) return
        deadline = null
        if (changeEnabled(false, BleDiscoveryReason.WINDOW_EXPIRED)) {
            record(BleDiscoveryAction.BACKOFF, BleDiscoveryReason.WINDOW_EXPIRED, bleConnections)
        }
    }

    private fun cancelDeadline() {
        generation++
        deadline?.cancel()
        deadline = null
    }

    private fun changeEnabled(value: Boolean, reason: BleDiscoveryReason): Boolean {
        if (enabled == value) return true
        if (!setEnabled(value)) return false
        enabled = value
        record(if (value) BleDiscoveryAction.START else BleDiscoveryAction.STOP, reason, bleConnections)
        return true
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        allowed = false
        cancelDeadline()
        changeEnabled(false, BleDiscoveryReason.CLOSED)
    }

    companion object {
        const val WINDOW_MS = 30_000L
    }
}

internal data class BlePeerSnapshot(val topology: Set<String>, val localBleConnections: Int)

internal fun blePeerSnapshot(graph: DittoPresenceGraph): BlePeerSnapshot {
    val local = graph.localPeer.peerKeyString
    val connections = (graph.localPeer.connections + graph.remotePeers.flatMap { it.connections })
        .distinctBy { it.id }
    return BlePeerSnapshot(
        connections.map { "${it.id}:${it.connectionType}" }.toSet(),
        connections.count {
            it.connectionType == DittoConnectionType.Bluetooth &&
                (it.peerKeyString1 == local || it.peerKeyString2 == local)
        }
    )
}

/** Owns event registrations only while DittoService owns sync, independently of diagnostics. */
internal class DittoBleDiscoverySession(
    private val application: Application,
    private val repo: DataRepository,
    private val ditto: Ditto,
    private val scope: CoroutineScope,
    private val allowed: Flow<Boolean>
) : Application.ActivityLifecycleCallbacks, AutoCloseable {
    private val jobs = mutableListOf<Job>()
    private val graphs = Channel<DittoPresenceGraph>(Channel.CONFLATED)
    private var observer: DittoPresenceObserver? = null
    private val discovery = DittoBleDiscovery(scope, ::setBleEnabled) { action, reason, count ->
        AppDiagnostics.record(DiagnosticEvent.BLE_DISCOVERY, mapOf(
            DiagnosticMetric.KIND to action.ordinal.toLong(),
            DiagnosticMetric.REASON to reason.ordinal.toLong(),
            DiagnosticMetric.CONNECTED_COUNT to count.toLong(),
            DiagnosticMetric.WINDOW_MS to DittoBleDiscovery.WINDOW_MS,
            DiagnosticMetric.RESULT to 1L
        ))
    }

    fun start() {
        updatePeers(ditto.presence.graph)
        jobs += scope.launch {
            for (graph in graphs) updatePeers(graph)
        }
        // The SDK invokes observers under its presence lock; never write config on that callback.
        observer = ditto.presence.observe { graph: DittoPresenceGraph -> graphs.trySend(graph); Unit }
        application.registerActivityLifecycleCallbacks(this)
        jobs += scope.launch {
            allowed.distinctUntilChanged().collect { discovery.setAllowed(it) }
        }
        jobs += scope.launch {
            repo.latestDeviceWornObservation.collect {
                if (it == true) discovery.request(BleDiscoveryReason.WRIST_ON)
            }
        }
        jobs += scope.launch {
            combine(
                repo.getDittoAppId, repo.getDittoAuthToken,
                repo.getDittoPdlSource, repo.getDittoCustomPdl
            ) { appId, token, source, pdl -> listOf(appId, token, source.name, pdl) }
                .distinctUntilChanged().drop(1).collect {
                    discovery.request(BleDiscoveryReason.SETTINGS)
                }
        }
    }

    private fun updatePeers(graph: DittoPresenceGraph) {
        val snapshot = blePeerSnapshot(graph)
        discovery.peersChanged(snapshot.topology, snapshot.localBleConnections)
    }

    private fun setBleEnabled(enabled: Boolean): Boolean {
        // Diagnostics describe requested SDK transport state, not hardware scan callbacks.
        return try {
            val config = ditto.transportConfig.clone()
            config.peerToPeer.bluetoothLe.enabled = enabled
            ditto.transportConfig = config
            true
        } catch (error: DittoError) {
            Log.e("WearTAK-DittoBle", "Failed to change Ditto BLE transport (enabled=$enabled)", error)
            AppDiagnostics.record(DiagnosticEvent.BLE_DISCOVERY, mapOf(
                DiagnosticMetric.ENABLED to if (enabled) 1L else 0L,
                DiagnosticMetric.RESULT to 0L,
                DiagnosticMetric.FAILURES to 1L
            ))
            false
        }
    }

    fun onUiOpened() {
        scope.launch { discovery.request(BleDiscoveryReason.UI) }
    }

    override fun onActivityResumed(activity: Activity) = onUiOpened()

    override fun close() {
        discovery.close()
        graphs.close()
        application.unregisterActivityLifecycleCallbacks(this)
        observer?.close()
        observer = null
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
