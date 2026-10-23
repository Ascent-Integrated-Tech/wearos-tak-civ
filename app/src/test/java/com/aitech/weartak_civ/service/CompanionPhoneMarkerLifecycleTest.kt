package com.aitech.weartak_civ.service

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.CompanionPointRevisions
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionPhoneMarkerLifecycleTest {
    private val scheduler = TestCoroutineScheduler()
    private val job = SupervisorJob()
    private val app = RuntimeEnvironment.getApplication()
    private val dataStore = object : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }
    private val repository: DataRepository
    private val bloodhounds = spyk(BloodhoundRepository(app, dataStore))
    private val service = CompanionAppService()
    private val now = System.currentTimeMillis()
    private val gateway = com.aitech.weartak_civ.cot.CompanionGatewaySession()

    init {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        MainActivity.mainActivity = mockk { every { applicationContext } returns app }
        repository = DataRepository(app)
        inject("serviceScope", CoroutineScope(job + StandardTestDispatcher(scheduler)))
        inject("repo\$delegate", lazyOf(repository))
        inject("bhRepo\$delegate", lazyOf(bloodhounds))
        val cap = mockk<CompanionAppRepository>(relaxed = true)
        every { cap.gateway } returns gateway
        inject("capRepo\$delegate", lazyOf(cap))
    }

    @After fun cleanup() {
        job.cancel()
        runBlocking { bloodhounds.removeAllFromMenu() }
    }

    private fun remember(uid: String, time: Long, token: String = "revision", local: Boolean = false) {
        repository.companionPointRevisions.remember(uid, CompanionPointRevisions.Revision(token, time, local, 0))
    }

    @Test fun genericTypesReachReceivedMapStateFromPhoneAndGatewayWithStaleAndBoundsUnchanged() = runBlocking {
        for (type in listOf("a-n-A-C-F", "a-h-G-U-C", "a-s-S", "a-a-G-X")) {
            val phoneUid = "phone-$type"
            val xml = cot(phoneUid, now).replace("type='a-h-G'", "type='$type'")
            receive(xml)
            assertTrue("Phone marker missing for $type",
                repository.receivedMarkerList.value.any { it.uid == phoneUid })
            val marker = repository.receivedMarkerList.value.single { it.uid == phoneUid }
            assertEquals(type, marker.markerType)
            assertEquals("Target", marker.title)
            assertEquals("companion-phone", marker.sourceServerId)
            receive(cot("invalid-$type", now, lat = 91.0).replace("type='a-h-G'", "type='$type'"))
            assertFalse(repository.receivedMarkerList.value.any { it.uid == "invalid-$type" })
            receive(cot(phoneUid, now + 1, stale = now - 1).replace("type='a-h-G'", "type='$type'"))
            assertFalse(repository.receivedMarkerList.value.any { it.uid == phoneUid })
            receive(xml)
            assertFalse(repository.receivedMarkerList.value.any { it.uid == phoneUid })
        }
        gateway.negotiate(JSONObject().put("version", 1).put("session", "generic-session"))
        gateway.confirm("generic-session")
        for (type in listOf("a-n-A-C-F", "a-h-G-U-C", "a-s-S", "a-a-G-X")) {
            val xml = cot("gateway-$type", now).replace("type='a-h-G'", "type='$type'")
            service.receiveGatewayMap(xml, "generic-session", 0)
            assertTrue("Gateway marker missing for $type",
                repository.receivedMarkerList.value.any { it.uid == "gateway-$type" })
            assertEquals(type, repository.receivedMarkerList.value.single { it.uid == "gateway-$type" }.markerType)
        }
    }

    @Test fun explicitDeletionRemovesOnlyPhoneCopyAndClearsSelectedNavigationWithoutReplay() = runBlocking {
        tak(cot("shared", now - 1, lat = 37.0))
        receive(cot("shared", now))
        remember("shared", now)
        bloodhounds.activate("shared")
        assertTrue(repository.receivePointDeletion("shared", "revision", 0, bloodhounds))
        assertPoint("shared", now - 1, 37.0, "tak-test")
        assertNull(bloodhounds.activeUid.value)
        assertFalse(repository.receivePointDeletion("shared", "revision", 0, bloodhounds))
        receive(cot("shared", now))
        assertPoint("shared", now - 1, 37.0, "tak-test")
        receive(cot("shared", now + 1))
        assertPoint("shared", now + 1, 38.0, "companion-phone")
    }

    @Test fun exactRevisionFencesNewerEditsReconnectUnknownAndMalformedDeletes() = runBlocking {
        receive(cot("point", now))
        remember("point", now)
        assertFalse(repository.receivePointDeletion("unknown", "revision", 0, bloodhounds))
        assertFalse(repository.receivePointDeletion("point", "revision", 1, bloodhounds))
        assertFalse(repository.receivePointDeletion("point", "old-revision", 0, bloodhounds))
        receive(cot("point", now + 1))
        assertFalse(repository.receivePointDeletion("point", "revision", 0, bloodhounds))
        assertPoint("point", now + 1, 38.0, "companion-phone")
        listOf(JSONObject(), JSONObject().put("targetUid", "point").put("revision", 1),
            JSONObject().put("targetUid", "").put("revision", "x"),
            JSONObject().put("targetUid", "point").put("revision", "bad\nvalue"))
            .forEach { assertNull(CompanionPointRevisions.parseDeletion(it)) }
        val deletion = CompanionPointRevisions.deletion("point", "revision")
        assertEquals("marker_delete", deletion.getString("msg_type"))
        assertEquals("point" to "revision", CompanionPointRevisions.parseDeletion(deletion.getJSONObject("payload")))
    }

    @Test fun localReceivedDeletionPreservesExistingUidWideUxButOnlySharedSourceCanRelay() = runBlocking {
        receive(cot("point", now))
        remember("point", now)
        repository.companionPointGeneration = 0
        repository.deleteReceivedPointLocally("point", bloodhounds)
        assertEmpty()
        assertNull(repository.companionPointRevisions.current("point", 0))
        receive(cot("point", now))
        assertEmpty()
        tak(cot("tak-only", now + 1))
        repository.deleteReceivedPointLocally("tak-only", bloodhounds)
        assertEmpty()
    }

    @Test fun deletingOriginalWatchPointAfterPhoneEditClearsLocalAndPhoneTargetsButNotTak() = runBlocking {
        repository.appendDroppedMarker(DataRepository.DroppedMarker(
            DataRepository.MarkerCotType.EDIT, "shared", DataRepository.MarkerType.HOSTILE,
            "Target", "", now - 1, null, false, false))
        bloodhounds.addMarkerTarget("shared", "Target", "a-h-G", 38.0, -77.0, now - 1)
        tak(cot("shared", now - 2, lat = 37.0))
        receive(cot("shared", now))
        remember("shared", now, local = true)
        bloodhounds.activate("shared")
        val remote = mutableListOf<String>()
        val notifications = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.remotePointDeletions.collect { remote.add(it) }
        }
        assertTrue(repository.receivePointDeletion("shared", "revision", 0, bloodhounds))
        assertTrue(repository.droppedMarkerList.value.isEmpty())
        assertTrue(repository.isDeletedLocalPoint("shared"))
        assertPoint("shared", now - 2, 37.0, "tak-test")
        assertNull(bloodhounds.activeUid.value)
        kotlinx.coroutines.yield()
        assertEquals(listOf("shared"), remote)
        ready()
        service.sendJsonToCompanion(JSONObject().put("msgType", "marker")
            .put("payload", JSONObject().put("uid", "shared").put("type", "a-h-G")
                .put("tStart", Instant.ofEpochMilli(now).toString())
                .put("tStale", Instant.ofEpochMilli(now + 300_000).toString())).toString())
        assertNull(repository.companionPointRevisions.current("shared", 0))
        notifications.cancel()
    }

    @Test fun pendingLocalWatchEditBlocksOldDeletion() = runBlocking {
        repository.appendDroppedMarker(DataRepository.DroppedMarker(
            DataRepository.MarkerCotType.EDIT, "point", DataRepository.MarkerType.HOSTILE,
            "Edited", "", now, null, true, false))
        receive(cot("point", now))
        remember("point", now, local = true)
        assertFalse(repository.receivePointDeletion("point", "revision", 0, bloodhounds))
        assertEquals("Edited", repository.droppedMarkerList.value.single().title)
        assertPoint("point", now, 38.0, "companion-phone")
    }

    @Test fun locallyDeletingSharedDroppedPointPreservesOtherSourceCopiesAndStopsLocalNavigation() = runBlocking {
        repository.appendDroppedMarker(DataRepository.DroppedMarker(
            DataRepository.MarkerCotType.EDIT, "shared", DataRepository.MarkerType.HOSTILE,
            "Target", "", now, null, false, false))
        receive(cot("shared", now))
        tak(cot("shared", now + 1, lat = 37.0))
        bloodhounds.addMarkerTarget("shared", "Local edit", "a-h-G", 38.0, -77.0, now + 2)
        remember("shared", now, local = true)
        repository.companionPointGeneration = 0
        bloodhounds.activate("shared")
        assertTrue(repository.deleteDroppedPointLocally("shared", bloodhounds))
        assertTrue(repository.droppedMarkerList.value.isEmpty())
        assertNull(bloodhounds.activeUid.value)
        assertPoint("shared", now + 1, 37.0, "tak-test")
        assertFalse(repository.deleteDroppedPointLocally("shared", bloodhounds))
    }

    private fun ready(bonded: Boolean = true) {
        val device = mockk<BluetoothDevice>()
        every { device.address } returns "AA:BB"
        every { device.bondState } returns if (bonded) BluetoothDevice.BOND_BONDED else BluetoothDevice.BOND_NONE
        val cap = mockk<CompanionAppRepository>(relaxed = true)
        every { cap.gateway } returns gateway
        every { cap.companionAppReady } returns MutableStateFlow(true)
        inject("connectedDevice", device)
        inject("notificationsEnabled", true)
        inject("gattServer", mockk<BluetoothGattServer>())
        inject("txCharacteristic", mockk<BluetoothGattCharacteristic>())
        inject("capRepo\$delegate", lazyOf(cap))
    }

    @Test fun serviceDeletionRequiresBondReadinessAndUsesExplicitEnvelopeWithoutEcho() {
        receive(cot("point", now))
        remember("point", now)
        val json = CompanionPointRevisions.deletion("point", "revision").toString()
        handle(json)
        assertPoint("point", now, 38.0, "companion-phone")
        ready(bonded = false)
        handle(json)
        assertPoint("point", now, 38.0, "companion-phone")
        ready()
        handle(json)
        assertEmpty()
        handle(json)
        assertEmpty()
    }

    @Test fun queuedDeletionIsFencedWhenConnectionChangesBeforeRepositoryWork() {
        receive(cot("point", now))
        remember("point", now)
        ready()
        CompanionAppService::class.java.getDeclaredMethod("handleCompanionLine", String::class.java).apply {
            isAccessible = true
            invoke(service, CompanionPointRevisions.deletion("point", "revision").toString())
        }
        val generation = CompanionAppService::class.java.getDeclaredField("bloodhoundConnectionGeneration").apply {
            isAccessible = true
        }.get(service) as java.util.concurrent.atomic.AtomicLong
        generation.incrementAndGet()
        scheduler.runCurrent()
        assertPoint("point", now, 38.0, "companion-phone")
    }

    @Test fun serviceAddsRevisionOnlyToReadyLiveWatchMarkersAndNeverImportsLegacyClear() {
        val payload = JSONObject().put("uid", "watch-point").put("type", "a-h-G")
            .put("tStart", Instant.ofEpochMilli(now).toString())
            .put("tStale", Instant.ofEpochMilli(now + 300_000).toString())
        val envelope = JSONObject().put("msgType", "marker").put("payload", payload)
        ready(bonded = false)
        service.sendJsonToCompanion(envelope.toString())
        assertNull(repository.companionPointRevisions.current("watch-point", 0))
        ready()
        service.sendJsonToCompanion(envelope.toString())
        val revision = repository.companionPointRevisions.current("watch-point", 0)!!
        assertTrue(CompanionPointRevisions.validToken(revision.token))
        assertTrue(revision.local)
        assertEquals(now, revision.time)
        payload.put("uid", "cleared").put("tStale", Instant.ofEpochMilli(now - 1).toString())
        service.sendJsonToCompanion(envelope.toString())
        assertNull(repository.companionPointRevisions.current("cleared", 0))
    }

    @Test fun localDeleteEmitsOnceForSelectedCompanionCopyButNeverForSelectedTakCopy() = runBlocking {
        repository.companionPointGeneration = 0
        val requests = mutableListOf<DataRepository.PointDeletionRequest>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.pointDeletionRequests.collect { requests.add(it) }
        }
        receive(cot("shared", now))
        remember("shared", now)
        repository.deleteReceivedPointLocally("shared", bloodhounds)
        repository.deleteReceivedPointLocally("shared", bloodhounds)
        kotlinx.coroutines.yield()
        assertEquals(listOf("shared"), requests.map { it.uid })
        assertEquals("revision", requests.single().revision.token)
        receive(cot("other", now))
        remember("other", now)
        tak(cot("other", now + 1))
        repository.deleteReceivedPointLocally("other", bloodhounds)
        kotlinx.coroutines.yield()
        assertEquals(1, requests.size)
        collector.cancel()
    }

    @Test fun asynchronousDeletionFailureIsLoggedWithoutPayloadAndDoesNotKillReceiveScope() {
        receive(cot("failed", now))
        remember("failed", now)
        ready()
        val failure = IllegalStateException("test removal failure")
        every { bloodhounds.removeMarkerCopy("failed", "companion-phone", any(), any()) } throws failure
        ShadowLog.clear()
        handle(CompanionPointRevisions.deletion("failed", "revision").toString())
        assertTrue(ShadowLog.getLogs().any {
            it.msg == "Failed to process point deletion" && it.throwable === failure
        })
        assertTrue(job.isActive)
        receive(cot("next", now))
        remember("next", now)
        handle(CompanionPointRevisions.deletion("next", "revision").toString())
        assertEquals(listOf("failed"), repository.receivedMarkerList.value.map { it.uid })
    }

    @Test fun newMarkerAndCoordinateRefreshPreserveOriginalTimesAndNavigationState() = runBlocking {
        receive(cot("point", now, lat = 38.0))
        assertPoint("point", now, 38.0, "companion-phone")
        val marker = repository.receivedMarkerList.value.single()
        assertEquals("Companion ATAK", marker.source)
        assertEquals(now + 300_000, marker.staleTime)
        assertEquals("Target", marker.title)
        assertEquals(now - 1_000, bloodhounds.bloodhoundMap.value["point"]?.bhEvent?.timeIssued)
        assertEquals("Original remark", bloodhounds.bloodhoundMap.value["point"]?.bhEvent?.remark)
        bloodhounds.activate("point")
        receive(cot("point", now + 1, lat = 39.0))
        assertPoint("point", now + 1, 39.0, "companion-phone")
        assertEquals("point", bloodhounds.activeUid.value)
        assertTrue(bloodhounds.bloodhoundMap.value["point"]!!.state.acknowledged)
    }

    @Test fun olderUpdatesDoNotChangeCoordinatesOrPublishBloodhoundNotifications() {
        receive(cot("point", now + 2, lat = 39.0))
        val update = bloodhounds.bloodhoundEvent.value
        receive(cot("point", now, lat = 37.0))
        assertPoint("point", now + 2, 39.0, "companion-phone")
        assertSame(update, bloodhounds.bloodhoundEvent.value)
        tak(cot("point", now, lat = 36.0))
        assertPoint("point", now + 2, 39.0, "companion-phone")
        assertSame(update, bloodhounds.bloodhoundEvent.value)
    }

    @Test fun identicalTakAndBleDuplicatesExposeOnlyOnePointAndTarget() {
        val xml = cot("shared", now)
        tak(xml, receivedAt = now - 1_000)
        receive(xml)
        receive(xml)
        assertPoint("shared", now, 38.0, "companion-phone")
    }

    @Test fun takAndBleDuplicatesSelectSameNewestSourceAndBleRemovalRetainsTakTarget() = runBlocking {
        receive(cot("shared", now, lat = 38.0))
        tak(cot("shared", now + 2, lat = 40.0))
        assertPoint("shared", now + 2, 40.0, "tak-test")
        bloodhounds.activate("shared")
        receive(cot("shared", now + 3, stale = now - 1))
        assertPoint("shared", now + 2, 40.0, "tak-test")
        assertEquals("shared", bloodhounds.activeUid.value)
        receive(cot("shared", now + 1, lat = 39.0))
        assertPoint("shared", now + 2, 40.0, "tak-test")
        receive(cot("shared", now + 4, lat = 41.0))
        assertPoint("shared", now + 4, 41.0, "companion-phone")
        assertNull(bloodhounds.activeUid.value)
        receive(cot("shared", now + 5, stale = now - 1))
        assertPoint("shared", now + 2, 40.0, "tak-test")
    }

    @Test fun staleRemovalClearsBothRepositoriesAndNavigationWithoutReplayResurrection() = runBlocking {
        receive(cot("removed", now, lat = 38.0))
        bloodhounds.activate("removed")
        receive(cot("removed", now + 2, stale = now - 1))
        assertEmpty()
        assertNull(bloodhounds.activeUid.value)
        assertNull(bloodhounds.bloodhoundEvent.value)
        receive(cot("removed", now + 1))
        receive(cot("removed", now + 2))
        assertEmpty()
        receive(cot("removed", now + 3, lat = 41.0))
        assertPoint("removed", now + 3, 41.0, "companion-phone")
    }

    @Test fun staleBeforeFirstMarkerCreatesSourceWatermarkAndOldRemovalPreservesNewerPoint() {
        receive(cot("point", now + 2, stale = now - 1))
        receive(cot("point", now + 1))
        assertEmpty()
        receive(cot("point", now + 3, lat = 41.0))
        receive(cot("point", now + 2, stale = now - 1))
        assertPoint("point", now + 3, 41.0, "companion-phone")
    }

    @Test fun disconnectAndReconnectRetainPointsAndRemovalWatermarks() {
        receive(cot("retained", now))
        receive(cot("removed", now + 1, stale = now - 1))
        val device = mockk<BluetoothDevice>()
        every { device.address } returns "AA:BB"
        every { device.bondState } returns BluetoothDevice.BOND_BONDED
        val cap = mockk<CompanionAppRepository>(relaxed = true)
        every { cap.gateway } returns gateway
        inject("capRepo\$delegate", lazyOf(cap))
        inject("activeAddr", "AA:BB")
        inject("connectedDevice", device)
        inject("notificationsEnabled", true)
        val callback = CompanionAppService::class.java.getDeclaredField("gattServerCallback").apply {
            isAccessible = true
        }.get(service) as BluetoothGattServerCallback
        callback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        assertPoint("retained", now, 38.0, "companion-phone")
        inject("activeAddr", "AA:BB")
        inject("connectedDevice", device)
        receive(cot("removed", now))
        assertPoint("retained", now, 38.0, "companion-phone")
        receive(cot("retained", now + 1, stale = now - 1))
        assertEmpty()
    }

    @Test fun invalidPayloadsNeverEnterEitherRepository() {
        listOf("", "<event", cot(" ", now), cot("invalid", now).replace("lat='38.0'", "lat='NaN'"),
            cot("invalid", now).replace("lon='-77.0'", "lon='181'")).forEach(::receive)
        listOf("time", "start", "stale").forEach { attribute ->
            receive(cot("invalid", now).replace(Regex("\\b$attribute='[^']*'"), "$attribute='bad'"))
        }
        receive(cot("invalid", now).replace("stale='${Instant.ofEpochMilli(now + 300_000)}'", "stale='bad'"))
        assertEmpty()
    }

    @Test fun asynchronousRepositoryFailureIsLoggedAndLaterMarkersStillWork() {
        val failure = IOException("test repository failure")
        coEvery { bloodhounds.applyReceivedMarkerEvent(match { it.uid == "failed" }, any(), any(), any(), any()) } throws failure
        ShadowLog.clear()
        receive(cot("failed", now))
        assertEmpty()
        assertTrue(ShadowLog.getLogs().any {
            it.msg == "Failed to process phone marker uid=failed" && it.throwable === failure
        })
        assertTrue(job.isActive)
        receive(cot("next", now))
        assertPoint("next", now, 38.0, "companion-phone")
    }

    @Test fun cancellationIsNotSwallowedOrReportedAsFailure() {
        coEvery { bloodhounds.applyReceivedMarkerEvent(any(), any(), any(), any(), any()) } throws
            CancellationException("test cancellation")
        ShadowLog.clear()
        receive(cot("cancelled", now))
        assertEmpty()
        assertTrue(ShadowLog.getLogs().none { it.msg.startsWith("Failed to process phone marker") })
        assertTrue(job.isActive)
    }

    @Test fun cancelledReceiveScopeDoesNotIngestQueuedMarker() {
        job.cancel()
        receive(cot("cancelled", now))
        assertEmpty()
    }

    private fun tak(xml: String, receivedAt: Long = System.currentTimeMillis()) = runBlocking {
        repository.receiveMarkerEvent(CotMessageParser.parseMarkerOrNull(xml)!!, bloodhounds,
            "TAK", sourceServerId = "tak-test", receivedAt = receivedAt)
    }

    @Test fun gatewayImportsOnlyReceivedMapStateWithoutLocalMarkerOrProfileChangesOrEcho() = runBlocking {
        val before = repository.snapshotSystem()
        gateway.negotiate(JSONObject().put("version", 1).put("session", "gateway-session"))
        gateway.confirm("gateway-session")
        service.receiveGatewayMap(cot("gateway-marker", now), "gateway-session", 0)
        assertEquals("companion-gateway", repository.receivedMarkerList.value.single().sourceServerId)
        assertTrue(repository.droppedMarkerList.value.isEmpty())
        val after = repository.snapshotSystem()
        assertEquals(before.deviceId, after.deviceId)
        assertEquals(before.callsign, after.callsign)
        assertEquals(before.team, after.team)
        assertEquals(before.role, after.role)
        assertEquals(before.reportIntSecs, after.reportIntSecs)
        service.receiveGatewayMap(cot("ended", now), "old-session", 0)
        service.receiveGatewayMap(cot("ended-generation", now), "gateway-session", 1)
        assertEquals(1, repository.receivedMarkerList.value.size)
        gateway.reset()
        service.receiveGatewayMap(cot("after-disconnect", now), "gateway-session", 0)
        assertEquals(1, repository.receivedMarkerList.value.size)
    }

    @Test fun gatewayPositionsRetainRemoteIdentityAndNeverReplaceOwnPosition() = runBlocking {
        gateway.negotiate(JSONObject().put("version", 1).put("session", "gateway-session"))
        gateway.confirm("gateway-session")
        val position = cot("remote-unit", now).replace("a-h-G", "a-f-G-U-C")
            .replace("<contact callsign='Target'/>",
                "<contact callsign='REMOTE'/><__group name='Orange' role='Medic'/>")
        service.receiveGatewayMap(position, "gateway-session", 0)
        val remote = repository.receivedUserList.value.single()
        assertEquals("remote-unit", remote.uid)
        assertEquals("REMOTE", remote.title)
        assertEquals("Orange", remote.team)
        assertEquals("Medic", remote.role)
        service.receiveGatewayMap(position.replace("remote-unit", "test-watch"), "gateway-session", 0)
        assertEquals(1, repository.receivedUserList.value.size)
        assertEquals("test-watch", repository.snapshotSystem().deviceId)
    }

    private fun assertPoint(uid: String, time: Long, lat: Double, source: String) {
        val marker = repository.receivedMarkerList.value.single()
        val target = bloodhounds.bloodhoundMap.value.values.single()
        assertEquals(uid, marker.uid)
        assertEquals(uid, target.uid)
        assertEquals(time, marker.timeReceived)
        assertEquals(time, target.bhEvent.mapItem.cotEvent.time)
        assertEquals(lat, marker.location!!.lat, 0.0)
        assertEquals(lat, target.target.point.lat, 0.0)
        assertEquals(-77.0, marker.location.lon, 0.0)
        assertEquals(-77.0, target.target.point.lon, 0.0)
        assertEquals(source, marker.sourceServerId)
        assertEquals(source, target.sourceServerId)
    }

    private fun assertEmpty() {
        assertTrue(repository.receivedMarkerList.value.isEmpty())
        assertTrue(bloodhounds.bloodhoundMap.value.isEmpty())
    }

    private fun inject(field: String, value: Any) {
        CompanionAppService::class.java.getDeclaredField(field).apply {
            isAccessible = true
            set(service, value)
        }
    }

    private fun receive(xml: String) {
        val json = JSONObject().put("msg_type", "phone_marker_cot")
            .put("payload", JSONObject().put("cot", xml)).toString()
        handle(json)
    }

    private fun handle(json: String) {
        CompanionAppService::class.java.getDeclaredMethod("handleCompanionLine", String::class.java).apply {
            isAccessible = true
            invoke(service, json)
        }
        scheduler.runCurrent()
    }

    private fun cot(uid: String, time: Long, lat: Double = 38.0, stale: Long = time + 300_000) = """
        <event version='2.0' uid='$uid' type='a-h-G'
            time='${Instant.ofEpochMilli(time)}' start='${Instant.ofEpochMilli(time)}'
            stale='${Instant.ofEpochMilli(stale)}'>
            <point lat='$lat' lon='-77.0' hae='0' ce='10' le='10'/>
            <detail><contact callsign='Target'/>
            <link uid='phone' production_time='${Instant.ofEpochMilli(time - 1_000)}'/>
            <remarks>Original remark</remarks></detail>
        </event>
    """.trimIndent()
}
