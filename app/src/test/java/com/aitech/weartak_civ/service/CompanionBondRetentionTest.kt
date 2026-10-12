package com.aitech.weartak_civ.service

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Intent
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionBondRetentionTest {
    private val service = Robolectric.buildService(CompanionAppService::class.java).get()
    private val repo = mockk<DataRepository>()
    private val peer: BluetoothDevice = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        .getRemoteDevice("AA:BB:CC:DD:EE:01").also {
        // Use a real framework device so an unsolicited removeBond would change its state.
        shadowOf(it).setBondState(BluetoothDevice.BOND_BONDED)
    }

    init {
        set("repo\$delegate", lazyOf(repo))
        set("capRepo\$delegate", lazyOf(mockk<CompanionAppRepository>(relaxed = true)))
        coEvery { repo.clearPhoneManagedSettingsForPeer(any(), any()) } returns null
        connect()
    }

    private fun set(name: String, value: Any?) =
        CompanionAppService::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(service, value)
        }

    private fun get(name: String): Any? =
        CompanionAppService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.get(service)

    private fun connect() {
        set("activeAddr", peer.address)
        set("connectedDevice", peer)
    }

    private fun broadcast(action: String, device: BluetoothDevice = peer, state: Int? = null) {
        val intent = Intent(action).putExtra(BluetoothDevice.EXTRA_DEVICE, device)
        if (state != null) intent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, state)
            .putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_BONDED)
        (get("bondStateReceiver") as BroadcastReceiver).onReceive(service, intent)
    }

    private fun retained() {
        assertEquals(BluetoothDevice.BOND_BONDED, peer.bondState)
        coVerify(exactly = 0) { repo.clearPhoneManagedSettingsForPeer(any(), any()) }
    }

    @Test fun missingKeyRetainsBondAndManagementButInvalidatesReadySession() {
        set("notificationsEnabled", true)
        set("helloSentForConnection", true)
        broadcast("android.bluetooth.device.action.KEY_MISSING")
        retained()
        assertNull(get("activeAddr"))
        assertNull(get("connectedDevice"))
        assertEquals(false, get("notificationsEnabled"))
        assertEquals(false, get("helloSentForConnection"))
    }

    @Test fun repeatedPreSessionRetryNeverUnpairsOrReleasesManagement() {
        val callback = get("gattServerCallback") as BluetoothGattServerCallback
        repeat(3) {
            callback.onConnectionStateChange(peer, 133, BluetoothProfile.STATE_DISCONNECTED)
            retained()
            connect()
            callback.onConnectionStateChange(peer, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
            retained()
            assertNull(get("connectedDevice"))
            connect()
        }
    }

    @Test fun explicitUnpairReleasesOnlyMatchingPeerAndInvalidatesSession() {
        broadcast(BluetoothDevice.ACTION_BOND_STATE_CHANGED, state = BluetoothDevice.BOND_NONE)
        coVerify(timeout = 2000, exactly = 1) { repo.clearPhoneManagedSettingsForPeer(peer.address, any()) }
        assertNull(get("connectedDevice"))
        assertEquals(false, get("notificationsEnabled"))
    }

    @Test fun unrelatedMissingKeyAndErrorCannotResetCurrentPeer() {
        val other = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            .getRemoteDevice("AA:BB:CC:DD:EE:02")
        broadcast("android.bluetooth.device.action.KEY_MISSING", other)
        (get("gattServerCallback") as BluetoothGattServerCallback)
            .onConnectionStateChange(other, 133, BluetoothProfile.STATE_DISCONNECTED)
        assertSame(peer, get("connectedDevice"))
        retained()
    }

    @Test fun unrelatedUnpairUsesMatchingPeerReleaseWithoutResettingActivePeer() {
        val other = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            .getRemoteDevice("AA:BB:CC:DD:EE:02")
        broadcast(BluetoothDevice.ACTION_BOND_STATE_CHANGED, other, BluetoothDevice.BOND_NONE)
        coVerify(timeout = 2000, exactly = 1) { repo.clearPhoneManagedSettingsForPeer(other.address, any()) }
        coVerify(exactly = 0) { repo.clearPhoneManagedSettingsForPeer(peer.address, any()) }
        assertSame(peer, get("connectedDevice"))
    }

    @Test fun preservedPairingNeverAuthorizesStaleOrUnbondedWrites() {
        val rx = UUID.fromString("0000a11c-0000-1000-8000-00805f9b34fb")
        val cap = mockk<CompanionAppRepository>(relaxed = true)
        every { cap.RX_FROM_COMPANION_UUID } returns rx
        set("capRepo\$delegate", lazyOf(cap))
        val characteristic = BluetoothGattCharacteristic(rx,
            BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        val server = mockk<BluetoothGattServer>(relaxed = true)
        val callback = get("gattServerCallback") as BluetoothGattServerCallback
        broadcast("android.bluetooth.device.action.KEY_MISSING")
        set("gattServer", server)
        callback.onCharacteristicWriteRequest(peer, 1, characteristic, false, true, 0,
            "{\"msg_type\":\"set_settings\"}\n".toByteArray())
        verify { server.sendResponse(peer, 1, BluetoothGatt.GATT_FAILURE, 0, null) }
        assertEquals("", get("rxBuf").toString())
        connect()
        shadowOf(peer).setBondState(BluetoothDevice.BOND_NONE)
        callback.onCharacteristicWriteRequest(peer, 2, characteristic, false, true, 0,
            "{\"msg_type\":\"set_settings\"}\n".toByteArray())
        verify { server.sendResponse(peer, 2, BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, 0, null) }
        assertEquals("", get("rxBuf").toString())
        coVerify(exactly = 0) { repo.enablePhoneManagedSettings(any()) }
    }
}
