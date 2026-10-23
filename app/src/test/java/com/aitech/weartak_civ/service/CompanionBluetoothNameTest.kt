package com.aitech.weartak_civ.service

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.ContextWrapper
import android.os.ParcelUuid
import com.aitech.weartak_civ.cot.CompanionGatewaySession
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionBluetoothNameTest {
    private val service = CompanionAppService()
    private val adapter = mockk<BluetoothAdapter>(relaxed = true)

    init {
        every { adapter.name } returns "Galaxy Watch"
        inject("bluetoothAdapter", adapter)
    }

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        MainActivity.mainActivity = mockk { every { applicationContext } returns app }
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
    }

    @After fun neverRenamesAdapter() {
        verify(exactly = 0) { adapter.setName(any()) }
    }

    @Test fun provisioningCallsignWhileDisconnectedDoesNotRenameWatch() = runBlocking {
        applyProvisionedCallsign(connected = false)
    }

    @Test fun provisioningCallsignWhileConnectedDoesNotRenameWatch() = runBlocking {
        applyProvisionedCallsign(connected = true)
    }

    @Test fun provisioningDoesNotRestoreAnExistingWearTakBluetoothName() = runBlocking {
        every { adapter.name } returns "WT-OLD-CALLSIGN"

        applyProvisionedCallsign(connected = false)

        assertEquals("WT-OLD-CALLSIGN", adapter.name)
    }

    @Test fun watchInfoExchangesUpdatedCallsignIndependentlyOfSystemBluetoothName() = runBlocking {
        val manager = mockk<BluetoothManager>()
        every { manager.adapter } returns adapter
        every { adapter.address } returns "02:00:00:00:00:00"
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.BLUETOOTH_SERVICE) manager else super.getSystemService(name)
        }
        val snapshot = mockk<DataRepository.SystemSnapshot>(relaxed = true)
        var storedCallsign = "WATCH"
        every { snapshot.callsign } answers { storedCallsign }
        every { snapshot.standaloneCallsign } returns "ODIN-WEARTAK"
        every { snapshot.deviceId } returns "test-watch"
        val repo = mockk<DataRepository>()
        coEvery { repo.snapshotSystem() } returns snapshot
        val cap = CompanionAppRepository(context)
        CompanionAppRepository::class.java.getDeclaredField("repo\$delegate").apply {
            isAccessible = true
            set(cap, lazyOf(repo))
        }

        for (callsign in listOf("WATCH", "PHONE")) {
            storedCallsign = callsign
            val envelope = JSONObject(cap.envelopeToJson(cap.buildWatchInfoEnvelope()))
            assertEquals("watch_info", envelope.getString("msgType"))
            val payload = envelope.getJSONObject("payload")
            assertEquals("test-watch", payload.getString("uid"))
            assertEquals(callsign, payload.getString("cs"))
            assertEquals("ODIN-WEARTAK", payload.getString("standaloneCallsign"))
        }
        verify(exactly = 0) { adapter.name }
        assertEquals("Galaxy Watch", adapter.name)
    }

    private suspend fun applyProvisionedCallsign(connected: Boolean) {
        val repo = mockk<DataRepository>()
        coEvery { repo.storeProvisionedTakServerList(emptyList()) } returns
            DataRepository.TakServerConfigurationRefresh()
        coEvery { repo.awaitTakServerList() } returns emptyList()
        coEvery { repo.storeCallsign("PHONE") } returns Unit
        coEvery { repo.clearPhoneManagedSettingsForPeer("AA:BB", any()) } returns null
        val cap = mockk<CompanionAppRepository>()
        every { cap.gateway } returns CompanionGatewaySession()
        inject("repo\$delegate", lazyOf(repo))
        inject("capRepo\$delegate", lazyOf(cap))
        if (connected) {
            val peer = mockk<BluetoothDevice>()
            every { peer.address } returns "AA:BB"
            inject("connectedDevice", peer)
        }
        TAKConnectService.isRunning.set(false)

        service.applySettingsFromCompanion(CompanionAppRepository.SetSettingsPayload(
            takServerList = emptyList(), callsign = "PHONE"
        ))

        coVerify(exactly = 1) { repo.storeCallsign("PHONE") }
        assertEquals("PHONE", MainActivity.Constants.callsign)
    }

    @Test fun restoringCallsignDoesNotRenameWatchConnectedOrDisconnected() {
        val restored = DataRepository.PhoneManagedSettingsValues(
            true, 60, "WATCH", "Dark Green", "Dark Green", "K9", "MIL"
        )
        val restore = CompanionAppService::class.java.getDeclaredMethod(
            "applyRestoredPhoneManagedSettings", DataRepository.PhoneManagedSettingsValues::class.java
        ).apply { isAccessible = true }

        for (connected in listOf(false, true)) {
            inject("connectedDevice", if (connected) mockk<BluetoothDevice>() else null)
            MainActivity.Constants.callsign = "PHONE"
            restore.invoke(service, restored)
            assertEquals("WATCH", MainActivity.Constants.callsign)
        }
    }

    @Test fun advertisingUsesCallsignServiceDataAndDiscoveryUuidWithoutSystemName() {
        val uuid = UUID.fromString("0000A11A-0000-1000-8000-00805F9B34FB")
        val cap = mockk<CompanionAppRepository>(relaxed = true)
        every { cap.COMPANION_SERVICE_UUID } returns uuid
        inject("capRepo\$delegate", lazyOf(cap))
        val advertiser = mockk<BluetoothLeAdvertiser>(relaxed = true)
        inject("advertiser", advertiser)
        service.updateAdvertisingCallsign("WATCH")

        CompanionAppService::class.java.getDeclaredMethod("startAdvertising").apply {
            isAccessible = true
            invoke(service)
        }

        val settings = slot<AdvertiseSettings>()
        val data = slot<AdvertiseData>()
        val scanResponse = slot<AdvertiseData>()
        verify(exactly = 1) {
            advertiser.startAdvertising(capture(settings), capture(data), capture(scanResponse), any<AdvertiseCallback>())
        }
        assertTrue(settings.captured.isConnectable)
        assertFalse(data.captured.includeDeviceName)
        assertEquals(listOf(ParcelUuid(uuid)), data.captured.serviceUuids)
        assertFalse(scanResponse.captured.includeDeviceName)
        assertArrayEquals(byteArrayOf(1) + "WATCH".toByteArray(Charsets.UTF_8),
            scanResponse.captured.serviceData[ParcelUuid(uuid)])
        verify(exactly = 0) { adapter.name }
    }

    private fun inject(field: String, value: Any?) {
        CompanionAppService::class.java.getDeclaredField(field).apply {
            isAccessible = true
            set(service, value)
        }
    }
}
