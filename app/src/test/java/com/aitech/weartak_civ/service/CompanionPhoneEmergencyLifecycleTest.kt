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
import com.aitech.weartak_civ.presentation.MainActivity.Constants.device_uid
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionPhoneEmergencyLifecycleTest {
    private val scheduler = TestCoroutineScheduler()
    private val jobs = mutableListOf<Job>()
    private val dataStore = object : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }
    private val repo = BloodhoundRepository(RuntimeEnvironment.getApplication(), dataStore)
    private val service = newService(repo)

    @After fun cleanup() {
        jobs.forEach { it.cancel() }
        runBlocking { repo.removeAllFromMenu() }
    }

    @Test fun bleAndTakDuplicatesCancelTogetherAndReplayCannotResurrectAlert(): Unit = runBlocking {
        val now = System.currentTimeMillis()
        val xml = cot("alert", now)
        receive(service, xml)
        assertEquals("Companion ATAK", repo.bloodhoundMap.value["alert"]?.server)
        repo.applyEmergencyEvent(CotMessageParser.parseEmergencyOrNull(xml)!!,
            "TAK", sourceServerId = "tak-test", deviceUid = device_uid)
        assertEquals(listOf("alert"), repo.bloodhoundMap.value.keys.toList())
        withTimeout(3_000) { repo.listBhItemsUi.first { it.size == 1 } }
        withTimeout(3_000) { repo.remoteEmergencyMapItems.first { it.size == 1 } }
        repo.activate("alert")
        receive(service, cot("alert", now + 1, cancelled = true))
        assertTrue(repo.bloodhoundMap.value.isEmpty())
        assertNull(repo.activeUid.value)
        withTimeout(3_000) { repo.remoteEmergencyMapItems.first { it.isEmpty() } }
        receive(service, xml)
        assertTrue(repo.bloodhoundMap.value.isEmpty())
        receive(service, cot("alert", now + 2))
        assertEquals(now + 2, repo.bloodhoundMap.value["alert"]?.bhEvent?.mapItem?.cotEvent?.time)
        repo.removeAllFromMenu()
    }

    @Test fun disconnectRetainsAlertsAndCancellationAfterReconnectClearsThem() = runBlocking {
        val now = System.currentTimeMillis()
        receive(service, cot("retained", now))
        val device = mockk<BluetoothDevice>()
        every { device.address } returns "AA:BB"
        every { device.bondState } returns BluetoothDevice.BOND_BONDED
        val capRepo = mockk<CompanionAppRepository>(relaxed = true)
        every { capRepo.gateway } returns com.aitech.weartak_civ.cot.CompanionGatewaySession()
        inject(service, "capRepo\$delegate", lazyOf(capRepo))
        inject(service, "activeAddr", "AA:BB")
        inject(service, "connectedDevice", device)
        inject(service, "notificationsEnabled", true)
        val callback = CompanionAppService::class.java.getDeclaredField("gattServerCallback").apply {
            isAccessible = true
        }.get(service) as BluetoothGattServerCallback
        callback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        assertTrue(repo.bloodhoundMap.value.containsKey("retained"))
        inject(service, "activeAddr", "AA:BB")
        inject(service, "connectedDevice", device)
        receive(service, cot("retained", now + 1, cancelled = true))
        assertTrue(repo.bloodhoundMap.value.isEmpty())

        val restartedRepo = BloodhoundRepository(RuntimeEnvironment.getApplication(), dataStore)
        receive(newService(restartedRepo), cot("retained", now))
        assertTrue(restartedRepo.bloodhoundMap.value.isEmpty())
    }

    @Test fun dismissedAndOwnWatchAlertsRemainSuppressedAfterReceiveSessionRestarts(): Unit = runBlocking {
        val now = System.currentTimeMillis()
        receive(service, cot("dismissed", now))
        repo.removeFromMenu("dismissed")
        receive(service, cot("dismissed", now + 1))
        assertTrue(repo.bloodhoundMap.value.isEmpty())
        val restartedRepo = BloodhoundRepository(RuntimeEnvironment.getApplication(), dataStore)
        val restartedService = newService(restartedRepo)
        receive(restartedService, cot("dismissed", now + 2))
        receive(restartedService, cot("self-alert", now, sender = device_uid))
        assertTrue(restartedRepo.bloodhoundMap.value.isEmpty())
        receive(restartedService, cot("dismissed", now + 3, cancelled = true))
        receive(restartedService, cot("dismissed", now + 4))
        assertTrue(restartedRepo.bloodhoundMap.value.containsKey("dismissed"))
        restartedRepo.removeAllFromMenu()
    }

    private fun newService(repository: BloodhoundRepository): CompanionAppService {
        device_uid = "watch-self"
        return CompanionAppService().also {
            val job = SupervisorJob()
            jobs.add(job)
            inject(it, "serviceScope", CoroutineScope(job + StandardTestDispatcher(scheduler)))
            inject(it, "bhRepo\$delegate", lazyOf(repository))
            val cap = mockk<CompanionAppRepository>(relaxed = true)
            every { cap.gateway } returns com.aitech.weartak_civ.cot.CompanionGatewaySession()
            inject(it, "capRepo\$delegate", lazyOf(cap))
        }
    }

    private fun inject(service: CompanionAppService, field: String, value: Any) {
        CompanionAppService::class.java.getDeclaredField(field).apply {
            isAccessible = true
            set(service, value)
        }
    }

    private fun receive(service: CompanionAppService, xml: String) {
        val json = JSONObject().put("msg_type", "phone_emergency_cot")
            .put("payload", JSONObject().put("cot", xml)).toString()
        CompanionAppService::class.java.getDeclaredMethod("handleCompanionLine", String::class.java).apply {
            isAccessible = true
            invoke(service, json)
        }
        scheduler.runCurrent()
    }

    private fun cot(uid: String, time: Long, cancelled: Boolean = false, sender: String = "remote-sender") = """
        <event version='2.0' uid='$uid' type='${if (cancelled) "b-a-o-can" else "b-a-o-tbl"}'
            time='${Instant.ofEpochMilli(time)}' start='${Instant.ofEpochMilli(time)}'
            stale='${Instant.ofEpochMilli(time + 300_000)}'>
            <point lat='38' lon='-77' hae='0' ce='10' le='10'/>
            <detail><link uid='$sender' relation='p-p'/>
            <emergency cancel='$cancelled'>Remote Phone</emergency></detail>
        </event>
    """.trimIndent()
}
