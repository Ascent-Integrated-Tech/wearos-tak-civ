package com.aitech.weartak_civ.service

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Looper
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.UUID

/**
 * Regression: after a watch Bluetooth stack restart the phone connected but logged
 * "Companion service A11A missing" because the Companion GATT server was never re-registered
 * while advertising (A11A) recovered on its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionGattServiceRecoveryTest {
    private val companionUuid = UUID.fromString("0000A11A-0000-1000-8000-00805F9B34FB")
    private val rxUuid = UUID.fromString("0000a11c-0000-1000-8000-00805f9b34fb")

    private val service = Robolectric.buildService(CompanionAppService::class.java).get()
    private val manager = mockk<BluetoothManager>(relaxed = true)
    private val adapter = mockk<BluetoothAdapter>(relaxed = true)
    private val advertiser = mockk<BluetoothLeAdvertiser>(relaxed = true)
    private val cap = mockk<CompanionAppRepository>(relaxed = true)
    private val openedServers = mutableListOf<BluetoothGattServer>()
    private val addedServices = mutableMapOf<BluetoothGattServer, MutableList<BluetoothGattService>>()
    private var bluetoothOn = true

    @Before fun setup() {
        every { adapter.isEnabled } answers { bluetoothOn }
        every { adapter.bluetoothLeAdvertiser } answers { if (bluetoothOn) advertiser else null }
        every { manager.adapter } returns adapter
        every { manager.openGattServer(any(), any()) } answers {
            if (!bluetoothOn) return@answers null
            val server = mockk<BluetoothGattServer>(relaxed = true)
            every { server.addService(any()) } answers {
                addedServices.getOrPut(server) { mutableListOf() }.add(firstArg())
                true
            }
            openedServers += server
            server
        }
        every { cap.COMPANION_SERVICE_UUID } returns companionUuid
        every { cap.TX_TO_COMPANION_UUID } returns UUID.fromString("0000a11b-0000-1000-8000-00805f9b34fb")
        every { cap.RX_FROM_COMPANION_UUID } returns rxUuid
        every { cap.CLIENT_CHARACTERISTIC_CONFIG_UUID } returns
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        inject("bluetoothManager", manager)
        inject("bluetoothAdapter", adapter)
        inject("capRepo\$delegate", lazyOf(cap))
        inject("repo\$delegate", lazyOf(mockk<DataRepository>(relaxed = true)))
        inject("locationDiagnostics\$delegate", lazyOf(mockk<BondedLocationDiagnostics>(relaxed = true)))
        invoke("initBle")
        assertCompanionServiceExposed()
    }

    @Test fun bluetoothRestartReopensServerAndReexposesA11a() {
        val stale = currentServer()
        startAdvertising()
        advertiseCallback().onStartSuccess(null)

        bluetoothOn = false
        service.onBluetoothAdapterStateChanged(BluetoothAdapter.STATE_TURNING_OFF)
        service.onBluetoothAdapterStateChanged(BluetoothAdapter.STATE_OFF)
        verify(exactly = 1) { stale.close() }
        assertEquals(null, field("gattServer"))
        startAdvertising()
        idle(120)
        verifyStarts(1)

        bluetoothOn = true
        service.onBluetoothAdapterStateChanged(BluetoothAdapter.STATE_ON)

        assertEquals(2, openedServers.size)
        assertCompanionServiceExposed()
        verifyStarts(2)
    }

    @Test fun restartWithoutOffBroadcastStillReplacesStaleServer() {
        val stale = currentServer()
        service.onBluetoothAdapterStateChanged(BluetoothAdapter.STATE_ON)

        verify(exactly = 1) { stale.close() }
        assertEquals(2, openedServers.size)
        assertCompanionServiceExposed()
    }

    @Test fun lostAdvertisingCallbackAfterStackRestartRebuildsGattServer() {
        val stale = currentServer()
        startAdvertising()

        idle(30)

        verify(exactly = 1) { stale.close() }
        assertEquals(2, openedServers.size)
        assertCompanionServiceExposed()
    }

    @Test fun sessionEndKeepsCompanionServiceExposed() {
        val server = currentServer()
        val peer = mockk<BluetoothDevice>(relaxed = true)
        every { peer.address } returns "AA:BB"
        every { peer.bondState } returns BluetoothDevice.BOND_BONDED
        val gatt = gattCallback()
        gatt.onConnectionStateChange(peer, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        gatt.onCharacteristicWriteRequest(peer, 1,
            BluetoothGattCharacteristic(rxUuid, BluetoothGattCharacteristic.PROPERTY_WRITE, 0),
            false, false, 0, byteArrayOf())
        idle(2)
        gatt.onConnectionStateChange(peer, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)

        verify(exactly = 0) { server.close() }
        assertEquals(1, openedServers.size)
        assertCompanionServiceExposed()
    }

    @Test fun handoffOrReleaseResetReexposesA11a() {
        val previous = currentServer()

        invokeForceBleReset("companion released")

        verify(exactly = 1) { previous.close() }
        assertEquals(2, openedServers.size)
        assertCompanionServiceExposed()
    }

    @Test fun resetWhileBluetoothOffReexposesA11aWhenAdapterReturns() {
        bluetoothOn = false
        invokeForceBleReset("handoff while radio off")
        assertEquals(null, field("gattServer"))

        bluetoothOn = true
        service.onBluetoothAdapterStateChanged(BluetoothAdapter.STATE_ON)

        assertCompanionServiceExposed()
        verifyStarts(1)
    }

    @Test fun failedServiceRegistrationIsRetriedAndBounded() {
        val gatt = gattCallback()
        val failed = addedServices.getValue(currentServer()).single()

        gatt.onServiceAdded(BluetoothGatt.GATT_FAILURE, failed)
        assertEquals(false, field("gattServiceAdded"))
        idle(1)
        assertEquals(2, openedServers.size)
        assertCompanionServiceExposed()

        repeat(5) {
            gatt.onServiceAdded(BluetoothGatt.GATT_FAILURE, addedServices.getValue(currentServer()).single())
            idle(1)
        }
        assertEquals(4, openedServers.size)
        verify { cap.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ERROR) }
    }

    private fun assertCompanionServiceExposed() {
        val server = currentServer()
        val uuids = addedServices[server].orEmpty().map { it.uuid }
        assertTrue("A11A missing on current GATT server: $uuids", companionUuid in uuids)
        assertEquals(true, field("gattServiceAdded"))
    }

    private fun currentServer(): BluetoothGattServer = field("gattServer") as BluetoothGattServer

    private fun startAdvertising() = invoke("startAdvertising")

    private fun verifyStarts(count: Int) = verify(exactly = count) {
        advertiser.startAdvertising(any<AdvertiseSettings>(), any<AdvertiseData>(), any<AdvertiseData>(), any<AdvertiseCallback>())
    }

    private fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private fun advertiseCallback(): AdvertiseCallback = field("advertiseCallback") as AdvertiseCallback

    private fun gattCallback(): BluetoothGattServerCallback =
        field("gattServerCallback") as BluetoothGattServerCallback

    private fun invokeForceBleReset(why: String) {
        CompanionAppService::class.java.getDeclaredMethod("forceBleReset", String::class.java).apply {
            isAccessible = true
            invoke(service, why)
        }
    }

    private fun invoke(method: String) {
        CompanionAppService::class.java.getDeclaredMethod(method).apply {
            isAccessible = true
            invoke(service)
        }
    }

    private fun field(name: String): Any? =
        CompanionAppService::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(service)
        }

    private fun inject(field: String, value: Any?) {
        CompanionAppService::class.java.getDeclaredField(field).apply {
            isAccessible = true
            set(service, value)
        }
    }
}
