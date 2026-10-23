package com.aitech.weartak_civ.service

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Looper
import com.aitech.weartak_civ.data.CompanionAppRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionAdvertisingRecoveryTest {
    private val service = CompanionAppService()
    private val adapter = mockk<BluetoothAdapter>(relaxed = true)
    private val advertiser = mockk<BluetoothLeAdvertiser>(relaxed = true)
    private val cap = mockk<CompanionAppRepository>(relaxed = true)

    @Before fun setup() {
        every { adapter.isEnabled } returns true
        every { cap.COMPANION_SERVICE_UUID } returns
            UUID.fromString("0000A11A-0000-1000-8000-00805F9B34FB")
        inject("bluetoothAdapter", adapter)
        inject("advertiser", advertiser)
        inject("capRepo\$delegate", lazyOf(cap))
    }

    @Test fun concurrentStartsBeforeCallbackIssueOneAdvertiseRequest() {
        startAdvertising()
        startAdvertising()

        verifyStarts(1)
    }

    @Test fun advertisingThatEndsSilentlyIsRefreshedWhileDisconnected() {
        startAdvertising()
        callback().onStartSuccess(null)

        idle(31)

        verify(exactly = 1) { advertiser.stopAdvertising(callback()) }
        verifyStarts(2)
    }

    @Test fun lostStartCallbackDoesNotLatchAdvertisingForever() {
        startAdvertising()

        idle(31)

        verifyStarts(2)
    }

    @Test fun refreshDoesNotRestartWhileCentralIsConnected() {
        startAdvertising()
        callback().onStartSuccess(null)
        inject("connectedDevice", mockk<BluetoothDevice>(relaxed = true))

        idle(31)

        verifyStarts(1)
    }

    @Test fun explicitStopCancelsRefresh() {
        startAdvertising()
        callback().onStartSuccess(null)
        invoke("stopAdvertising")

        idle(31)

        verify(exactly = 1) { advertiser.stopAdvertising(callback()) }
        verifyStarts(1)
    }

    @Test fun lateStartSuccessAfterStopDoesNotLatchAdvertising() {
        startAdvertising()
        invoke("stopAdvertising")
        callback().onStartSuccess(null)

        startAdvertising()

        verifyStarts(2)
    }

    @Test fun refreshSkipsWhileBluetoothIsOff() {
        startAdvertising()
        callback().onStartSuccess(null)
        every { adapter.isEnabled } returns false

        idle(31)

        verifyStarts(1)
    }

    private fun startAdvertising() = invoke("startAdvertising")

    private fun verifyStarts(count: Int) = verify(exactly = count) {
        advertiser.startAdvertising(any<AdvertiseSettings>(), any<AdvertiseData>(), any<AdvertiseCallback>())
    }

    private fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private fun callback(): AdvertiseCallback =
        CompanionAppService::class.java.getDeclaredField("advertiseCallback").run {
            isAccessible = true
            get(service) as AdvertiseCallback
        }

    private fun invoke(method: String) {
        CompanionAppService::class.java.getDeclaredMethod(method).apply {
            isAccessible = true
            invoke(service)
        }
    }

    private fun inject(field: String, value: Any?) {
        CompanionAppService::class.java.getDeclaredField(field).apply {
            isAccessible = true
            set(service, value)
        }
    }
}
