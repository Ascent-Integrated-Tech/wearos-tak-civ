package com.aitech.weartak_civ.service

import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Looper
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class CompanionForeignLinkTest {
    private val service = Robolectric.buildService(CompanionAppService::class.java).get()
    private val connected = MutableStateFlow(false)
    private val repo = mockk<DataRepository>(relaxed = true)
    private val cap = mockk<CompanionAppRepository>(relaxed = true)
    private val server = mockk<BluetoothGattServer>(relaxed = true)
    private val advertiser = mockk<BluetoothLeAdvertiser>(relaxed = true)
    private val peer = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:01")
    private val other = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:02")
    private val tx = UUID.fromString("0000a11b-0000-1000-8000-00805f9b34fb")
    private val rx = UUID.fromString("0000a11c-0000-1000-8000-00805f9b34fb")
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val callback get() = get("gattServerCallback") as BluetoothGattServerCallback

    @Before fun setup() {
        every { repo.isCompanionConnected } returns connected
        every { repo.setCompanionAppConnected() } answers { connected.value = true }
        every { repo.setCompanionAppDisconnected() } answers { connected.value = false }
        every { cap.COMPANION_SERVICE_UUID } returns UUID.fromString("0000a11a-0000-1000-8000-00805f9b34fb")
        every { cap.TX_TO_COMPANION_UUID } returns tx
        every { cap.RX_FROM_COMPANION_UUID } returns rx
        every { cap.CLIENT_CHARACTERISTIC_CONFIG_UUID } returns cccd
        every { cap.companionAppReady } returns MutableStateFlow(false)
        set("repo\$delegate", lazyOf(repo))
        set("capRepo\$delegate", lazyOf(cap))
        set("gattServer", server)
        set("advertiser", advertiser)
        shadowOf(peer).setBondState(BluetoothDevice.BOND_BONDED)
        shadowOf(other).setBondState(BluetoothDevice.BOND_BONDED)
    }

    private fun get(name: String): Any? = CompanionAppService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(service)

    private fun set(name: String, value: Any?) = CompanionAppService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(service, value)

    private fun connect(device: BluetoothDevice = peer) =
        callback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

    private fun subscribe(device: BluetoothDevice = peer, characteristic: UUID = tx,
                          value: ByteArray = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) {
        val descriptor = BluetoothGattDescriptor(cccd, BluetoothGattDescriptor.PERMISSION_WRITE)
        BluetoothGattCharacteristic(characteristic, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
            .addDescriptor(descriptor)
        callback.onDescriptorWriteRequest(device, 1, descriptor, false, true, 0, value)
    }

    private fun write(device: BluetoothDevice = peer, characteristic: UUID = rx) =
        callback.onCharacteristicWriteRequest(device, 2,
            BluetoothGattCharacteristic(characteristic, BluetoothGattCharacteristic.PROPERTY_WRITE, 0),
            false, true, 0, byteArrayOf())

    @Test fun dittoOnlyLinkNeverStopsAdvertisingArmsWatchdogOrResetsServer() {
        CompanionAppService::class.java.getDeclaredMethod("startAdvertising").apply {
            isAccessible = true
        }.invoke(service)
        (get("advertiseCallback") as AdvertiseCallback).onStartSuccess(null)
        repeat(20) {
            connect()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_100))
        }

        assertNull(get("connectedDevice"))
        assertNull(get("activeAddr"))
        assertEquals(false, get("handshakeWatchdogArmed"))
        assertEquals(true, get("advertising"))
        assertFalse(connected.value)
        verify(exactly = 0) { advertiser.stopAdvertising(any<AdvertiseCallback>()) }
        verify(exactly = 0) { server.cancelConnection(any()); server.close() }
        verify(exactly = 0) { repo.setCompanionAppDisconnected() }
    }

    @Test fun foreignErrorsDisconnectsAndMtuCannotResetAnIdleCompanionServer() {
        connect()
        callback.onMtuChanged(peer, 187)
        callback.onConnectionStateChange(peer, 133, BluetoothProfile.STATE_DISCONNECTED)
        callback.onConnectionStateChange(other, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))

        assertNull(get("connectedDevice"))
        assertEquals(false, get("handshakeWatchdogArmed"))
        verify(exactly = 0) { repo.setCompanionAppDisconnected() }
        verify(exactly = 0) { server.cancelConnection(any()); server.close() }
    }

    @Test fun companionSubscribeClaimsExistingDittoLinkAndRetainsNegotiatedMtu() = runTest {
        set("serviceScope", backgroundScope)
        connect()
        callback.onMtuChanged(peer, 187)
        subscribe()
        runCurrent()

        assertSame(peer, get("connectedDevice"))
        assertEquals(187, get("negotiatedMtu"))
        assertTrue(connected.value)
        assertEquals(false, get("handshakeWatchdogArmed"))
        verify { server.sendResponse(peer, 1, BluetoothGatt.GATT_SUCCESS, 0, null) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        verify(exactly = 0) { server.cancelConnection(any()); server.close() }
    }

    @Test fun rawCallbacksFromSameOrForeignLinkCannotDisplaceReadyCompanion() = runTest {
        set("serviceScope", backgroundScope)
        connect()
        subscribe()
        connect()
        connect(other)
        subscribe(other)
        runCurrent()
        callback.onMtuChanged(other, 517)
        callback.onConnectionStateChange(other, 133, BluetoothProfile.STATE_DISCONNECTED)

        assertTrue(connected.value)
        assertSame(peer, get("connectedDevice"))
        assertEquals(23, get("negotiatedMtu"))
        verify(exactly = 1) { repo.setCompanionAppDisconnected() }
        verify { server.sendResponse(other, 1, BluetoothGatt.GATT_FAILURE, 0, null) }
        verify(exactly = 0) { server.cancelConnection(any()); server.close() }
    }

    @Test fun rxWriteClaimsCompanionButDoesNotActivateRoutingBeforeSubscription() {
        connect()
        write()

        assertSame(peer, get("connectedDevice"))
        assertFalse(connected.value)
        assertEquals(true, get("handshakeWatchdogArmed"))
        verify { server.sendResponse(peer, 2, BluetoothGatt.GATT_SUCCESS, 0, null) }
    }

    @Test fun unbondedAndUnrelatedServiceWritesCannotClaimCompanionOrArmWatchdog() {
        connect()
        write(characteristic = UUID.randomUUID())
        subscribe(characteristic = UUID.randomUUID())
        subscribe(value = byteArrayOf(7, 0))
        subscribe(value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        shadowOf(peer).setBondState(BluetoothDevice.BOND_NONE)
        subscribe()
        write()

        assertNull(get("connectedDevice"))
        assertEquals(false, get("handshakeWatchdogArmed"))
        verify { server.sendResponse(peer, 1, BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, 0, null) }
        verify { server.sendResponse(peer, 2, BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, 0, null) }
    }

    @Test fun disconnectedLinkCannotBeClaimedByLateCompanionWrites() {
        connect()
        callback.onConnectionStateChange(peer, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        subscribe()
        write()

        assertNull(get("connectedDevice"))
        assertEquals(false, get("handshakeWatchdogArmed"))
        verify { server.sendResponse(peer, 1, BluetoothGatt.GATT_FAILURE, 0, null) }
        verify { server.sendResponse(peer, 2, BluetoothGatt.GATT_FAILURE, 0, null) }
    }
}
