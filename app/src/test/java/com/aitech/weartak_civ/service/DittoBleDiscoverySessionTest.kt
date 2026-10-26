package com.aitech.weartak_civ.service

import android.app.Activity
import android.app.Application
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DittoPdlSource
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.test.*
import live.ditto.*
import live.ditto.transports.DittoTransportConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoBleDiscoverySessionTest {
    private class Fixture(scope: TestScope, initiallyEnabled: Boolean = true) : AutoCloseable {
        val application = mockk<Application>(relaxed = true)
        val repo = mockk<DataRepository>()
        val ditto = mockk<Ditto>(relaxed = true)
        val presence = mockk<Presence>()
        val observer = mockk<DittoPresenceObserver>(relaxed = true)
        val callback = slot<(DittoPresenceGraph) -> Unit>()
        val master = MutableStateFlow(initiallyEnabled)
        val companion = MutableStateFlow(false)
        val reconfiguring = MutableStateFlow(false)
        val wrist = MutableStateFlow<Boolean?>(null)
        val appId = MutableSharedFlow<String>(replay = 1).apply { tryEmit("") }
        val token = MutableStateFlow("")
        val source = MutableStateFlow(DittoPdlSource.COMMUNITY)
        val pdl = MutableStateFlow("")
        val changes = mutableListOf<Boolean>()
        var config = DittoTransportConfig().apply {
            enableAllPeerToPeer()
            peerToPeer.bluetoothLe.enabled = false
            peerToPeer.lan.mdnsEnabled = false
            connect.websocketUrls = mutableSetOf("wss://example.invalid/sync")
            connect.retryIntervalMs = 12_345L
        }
        val original = config.clone()
        val session: DittoBleDiscoverySession

        init {
            every { repo.latestDeviceWornObservation } returns wrist
            every { repo.getDittoAppId } returns appId
            every { repo.getDittoAuthToken } returns token
            every { repo.getDittoPdlSource } returns source
            every { repo.getDittoCustomPdl } returns pdl
            every { ditto.presence } returns presence
            every { presence.graph } returns graph()
            every { presence.observe(capture(callback)) } returns observer
            every { ditto.transportConfig } answers { config }
            every { ditto.transportConfig = any() } answers {
                config = firstArg()
                changes += config.peerToPeer.bluetoothLe.enabled
            }
            session = DittoBleDiscoverySession(application, repo, ditto, scope,
                combine(master, companion, reconfiguring) { enabled, connected, changing ->
                    enabled && !connected && !changing
                })
            session.start()
        }

        override fun close() = session.close()
    }

    @Test fun idleDisablesOnlyBleAndNeverRestartsSyncOrChangesOtherTransports() = runTest {
        Fixture(this).use { f ->
            runCurrent()
            assertTrue(f.config.peerToPeer.bluetoothLe.enabled)
            assertFalse(f.original.peerToPeer.bluetoothLe.enabled)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(listOf(true, false), f.changes)
            assertEquals(f.original.peerToPeer.lan, f.config.peerToPeer.lan)
            assertEquals(f.original.peerToPeer.wifiAware, f.config.peerToPeer.wifiAware)
            assertEquals(f.original.connect, f.config.connect)
            assertEquals(f.original.listen, f.config.listen)
            assertEquals(f.original.global, f.config.global)
            advanceTimeBy(24 * 60 * 60 * 1_000L)
            runCurrent()
            assertEquals(listOf(true, false), f.changes)
            verify(exactly = 0) { f.ditto.startSync() }
            verify(exactly = 0) { f.ditto.stopSync() }
        }
    }

    @Test fun resumedUiWristOnAndActualSettingsChangesReopenDiscovery() = runTest {
        Fixture(this).use { f ->
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            val lifecycle = slot<Application.ActivityLifecycleCallbacks>()
            verify { f.application.registerActivityLifecycleCallbacks(capture(lifecycle)) }
            lifecycle.captured.onActivityResumed(mockk<Activity>())
            runCurrent()
            assertEquals(listOf(true, false, true), f.changes)
            advanceTimeBy(30_000)
            runCurrent()
            f.wrist.value = true
            runCurrent()
            assertTrue(f.changes.last())
            advanceTimeBy(30_000)
            runCurrent()
            f.wrist.value = false
            runCurrent()
            assertFalse(f.changes.last())
            f.session.onUiOpened()
            runCurrent()
            assertTrue(f.changes.last())
            advanceTimeBy(30_000)
            runCurrent()
            f.appId.emit("changed")
            runCurrent()
            assertTrue(f.changes.last())
            advanceTimeBy(30_000)
            runCurrent()
            f.appId.emit("changed")
            runCurrent()
            assertFalse(f.changes.last())
            f.token.value = "changed"
            runCurrent()
            assertTrue(f.changes.last())
            advanceTimeBy(30_000)
            runCurrent()
            f.source.value = DittoPdlSource.CUSTOM
            runCurrent()
            assertTrue(f.changes.last())
            advanceTimeBy(30_000)
            runCurrent()
            f.pdl.value = "changed"
            runCurrent()
            assertTrue(f.changes.last())
        }
    }

    @Test fun localBlePeersKeepTransportOnButLanAndIndirectBlePeersDoNot() = runTest {
        Fixture(this).use { f ->
            runCurrent()
            f.callback.captured(graph(listOf(connection("local", "watch", "phone", DittoConnectionType.Bluetooth))))
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(listOf(true), f.changes)
            val lan = connection("lan", "watch", "tablet", DittoConnectionType.AccessPoint)
            val indirect = connection("indirect", "tablet", "phone", DittoConnectionType.Bluetooth)
            f.callback.captured(graph(listOf(lan, indirect)))
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(listOf(true, false), f.changes)
            f.callback.captured(graph(listOf(lan, indirect)))
            runCurrent()
            assertEquals(listOf(true, false), f.changes)
            f.callback.captured(graph(listOf(lan)))
            runCurrent()
            assertTrue(f.changes.last())
        }
    }

    @Test fun masterCompanionAndReconfigurationGatesOverrideAllTriggers() = runTest {
        Fixture(this, initiallyEnabled = false).use { f ->
            runCurrent()
            f.session.onActivityResumed(mockk<Activity>())
            f.wrist.value = true
            runCurrent()
            assertTrue(f.changes.isEmpty())
            f.master.value = true
            runCurrent()
            assertEquals(listOf(true), f.changes)
            f.companion.value = true
            runCurrent()
            assertEquals(listOf(true, false), f.changes)
            f.callback.captured(graph(listOf(connection("ble", "watch", "phone", DittoConnectionType.Bluetooth))))
            f.session.onActivityResumed(mockk<Activity>())
            f.pdl.value = "changed"
            runCurrent()
            assertFalse(f.changes.last())
            f.callback.captured(graph())
            f.companion.value = false
            runCurrent()
            assertTrue(f.changes.last())
            f.reconfiguring.value = true
            runCurrent()
            assertFalse(f.changes.last())
            f.session.onActivityResumed(mockk<Activity>())
            runCurrent()
            assertFalse(f.changes.last())
        }
    }

    @Test fun closingUnregistersEventsAndLateCallbacksCannotEnableBle() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.close()
        verify(exactly = 1) { f.observer.close() }
        verify(exactly = 1) { f.application.unregisterActivityLifecycleCallbacks(f.session) }
        f.callback.captured(graph(listOf(connection("ble", "watch", "phone", DittoConnectionType.Bluetooth))))
        f.session.onActivityResumed(mockk<Activity>())
        f.wrist.value = true
        f.appId.emit("changed")
        advanceUntilIdle()
        assertEquals(listOf(true, false), f.changes)
    }

    @Test fun snapshotCountsDirectBleConnectionsOnceAndIgnoresMetadataAndRemoteBle() {
        val direct = connection("direct", "phone", "watch", DittoConnectionType.Bluetooth)
        val indirect = connection("indirect", "phone", "tablet", DittoConnectionType.Bluetooth)
        val local = peer("watch", listOf(direct))
        val graph = presenceGraph(local, listOf(peer("phone", listOf(direct, indirect))))
        val snapshot = blePeerSnapshot(graph)
        assertEquals(1, snapshot.localBleConnections)
        assertEquals(2, snapshot.topology.size)
        assertEquals(snapshot, blePeerSnapshot(graph.copy(
            remotePeers = listOf(peer("renamed", listOf(direct, indirect)))
        )))
    }

    companion object {
        private fun connection(id: String, first: String, second: String, type: DittoConnectionType) =
            DittoConnection::class.java.declaredConstructors.single { it.parameterCount == 7 }.newInstance(
                id, byteArrayOf(), byteArrayOf(), first, second, type, null
            ) as DittoConnection

        // SDK snapshot constructors are Kotlin-internal; create real field-backed fixtures.
        private fun peer(key: String, connections: List<DittoConnection>) =
            DittoPeer::class.java.declaredConstructors.single { it.parameterCount == 12 }.newInstance(
                mockk<DittoAddress>(), connections, "Test", "4.14.6", true, false,
                emptyMap<String, Any?>(), "Android", byteArrayOf(), key, emptyMap<String, Any?>(), 0
            ) as DittoPeer

        private fun presenceGraph(local: DittoPeer, remote: List<DittoPeer>) =
            DittoPresenceGraph::class.java.getDeclaredConstructor(DittoPeer::class.java, List::class.java)
                .newInstance(local, remote)

        private fun graph(connections: List<DittoConnection> = emptyList()) =
            presenceGraph(peer("watch", connections), emptyList())
    }
}
