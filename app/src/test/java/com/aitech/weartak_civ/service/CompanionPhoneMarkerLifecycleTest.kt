package com.aitech.weartak_civ.service

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothProfile
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.CompanionAppRepository
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

    init {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        MainActivity.mainActivity = mockk { every { applicationContext } returns app }
        repository = DataRepository(app)
        inject("serviceScope", CoroutineScope(job + StandardTestDispatcher(scheduler)))
        inject("repo\$delegate", lazyOf(repository))
        inject("bhRepo\$delegate", lazyOf(bloodhounds))
    }

    @After fun cleanup() {
        job.cancel()
        runBlocking { bloodhounds.removeAllFromMenu() }
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
        inject("capRepo\$delegate", lazyOf(mockk<CompanionAppRepository>(relaxed = true)))
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
