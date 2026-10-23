package com.aitech.weartak_civ.service

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothProfile
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionTransportRoutingTest {
    @Test fun repositoryConnectionStateChangesSynchronouslyWithoutCollectorLag() {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        assertFalse(repo.isCompanionConnected.value)
        repeat(3) {
            repo.setCompanionAppConnected()
            assertTrue(repo.isCompanionConnected.value)
            repo.setCompanionAppDisconnected()
            assertFalse(repo.isCompanionConnected.value)
        }
    }

    @Test fun onlyBondedSubscribedPeerActivatesPhoneRoutingAndDisconnectImmediatelyRestoresFallback() = runTest {
        val service = Robolectric.buildService(CompanionAppService::class.java).get()
        val connected = MutableStateFlow(false)
        val repo = mockk<DataRepository>(relaxed = true)
        every { repo.isCompanionConnected } returns connected
        every { repo.setCompanionAppConnected() } answers { connected.value = true }
        every { repo.setCompanionAppDisconnected() } answers { connected.value = false }
        val cap = mockk<CompanionAppRepository>(relaxed = true)
        val tx = UUID.fromString("0000a11b-0000-1000-8000-00805f9b34fb")
        val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        every { cap.TX_TO_COMPANION_UUID } returns tx
        every { cap.CLIENT_CHARACTERISTIC_CONFIG_UUID } returns cccd
        every { cap.companionAppReady } returns MutableStateFlow(false)
        val server = mockk<BluetoothGattServer>(relaxed = true)
        fun inject(name: String, value: Any?) {
            CompanionAppService::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(service, value)
            }
        }
        inject("repo\$delegate", lazyOf(repo))
        inject("capRepo\$delegate", lazyOf(cap))
        inject("serviceScope", backgroundScope)
        inject("gattServer", server)
        val callback = CompanionAppService::class.java.getDeclaredField("gattServerCallback").run {
            isAccessible = true
            get(service) as BluetoothGattServerCallback
        }
        val peer = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:01")
        val other = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:02")
        val descriptor = BluetoothGattDescriptor(cccd, BluetoothGattDescriptor.PERMISSION_WRITE)
        BluetoothGattCharacteristic(tx, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0).addDescriptor(descriptor)
        fun subscribe(enable: Boolean) = callback.onDescriptorWriteRequest(
            peer, 1, descriptor, false, true, 0,
            if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        )

        shadowOf(peer).setBondState(BluetoothDevice.BOND_NONE)
        callback.onConnectionStateChange(peer, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        subscribe(true)
        assertFalse(connected.value)
        verify { server.sendResponse(peer, 1, BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, 0, null) }

        shadowOf(peer).setBondState(BluetoothDevice.BOND_BONDED)
        assertFalse("Bond alone must not suppress direct traffic", connected.value)
        subscribe(true)
        assertTrue(connected.value)
        callback.onConnectionStateChange(other, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        assertTrue("Stale peer must not disable current transport", connected.value)
        subscribe(false)
        assertFalse(connected.value)
        subscribe(true)
        assertTrue(connected.value)
        callback.onConnectionStateChange(peer, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        assertFalse("Bonded but disconnected must use watch networking", connected.value)
        assertEquals(BluetoothDevice.BOND_BONDED, peer.bondState)

        callback.onConnectionStateChange(peer, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        assertFalse(connected.value)
        subscribe(true)
        assertTrue("Reconnection resumes phone-only transport", connected.value)
        callback.onConnectionStateChange(peer, 133, BluetoothProfile.STATE_DISCONNECTED)
        assertFalse(connected.value)
    }
}
