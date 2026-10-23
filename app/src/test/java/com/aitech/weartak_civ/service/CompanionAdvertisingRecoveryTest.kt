package com.aitech.weartak_civ.service

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Looper
import android.os.ParcelUuid
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionAdvertisingRecoveryTest {
    private val service = Robolectric.buildService(CompanionAppService::class.java).get()
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
        inject("repo\$delegate", lazyOf(mockk<DataRepository>(relaxed = true)))
        inject("locationDiagnostics\$delegate", lazyOf(mockk<BondedLocationDiagnostics>(relaxed = true)))
    }

    @Test fun concurrentStartsBeforeCallbackIssueOneAdvertiseRequest() {
        startAdvertising()
        startAdvertising()

        verifyStarts(1)
    }

    @Test fun healthyIdleAdvertisingKeepsOneRegistrationAcrossThirtySecondAndTwoMinuteBoundaries() {
        startAdvertising()
        callback().onStartSuccess(null)

        repeat(20) {
            idle(31)
            startAdvertising()
        }

        verify(exactly = 0) { advertiser.stopAdvertising(any<AdvertiseCallback>()) }
        verifyStarts(1)
        assertEquals(listOf(AdvertiseSettings.ADVERTISE_MODE_BALANCED), startedModes())
    }

    @Test fun lostStartCallbackUsesWatchdogAndBoundedRetryRatherThanPeriodicRefresh() {
        startAdvertising()

        idleMillis(29_999)
        verifyStarts(1)
        verify(exactly = 0) { advertiser.stopAdvertising(any<AdvertiseCallback>()) }
        idleMillis(1)
        verifyStarts(1)
        verify(exactly = 1) { advertiser.stopAdvertising(any<AdvertiseCallback>()) }
        idleMillis(999)
        verifyStarts(1)
        idleMillis(1)

        verifyStarts(2)
    }

    @Test fun successfulAdvertisingDoesNotRestartWhileCentralIsConnected() {
        startAdvertising()
        callback().onStartSuccess(null)
        inject("connectedDevice", mockk<BluetoothDevice>(relaxed = true))

        idle(31)

        verifyStarts(1)
    }

    @Test fun explicitStopCancelsStartWatchdog() {
        startAdvertising()
        invoke("stopAdvertising")

        idle(600)

        verify(exactly = 1) { advertiser.stopAdvertising(any<AdvertiseCallback>()) }
        verifyStarts(1)
    }

    @Test fun lateStartSuccessAfterStopDoesNotLatchAdvertising() {
        startAdvertising()
        invoke("stopAdvertising")
        callback().onStartSuccess(null)

        startAdvertising()

        verifyStarts(2)
    }

    @Test fun destroyedServiceRejectsQueuedStartAndWatchdog() {
        startAdvertising()
        service.onDestroy()

        callback().onStartSuccess(null)
        callback().onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED)
        startAdvertising()
        idle(61)

        verify(exactly = 1) { advertiser.stopAdvertising(any<AdvertiseCallback>()) }
        verifyStarts(1)
    }

    @Test fun destroyedServiceRejectsLateDisconnectAndConnectionError() {
        val server = mockk<BluetoothGattServer>(relaxed = true)
        inject("gattServer", server)
        startAdvertising()
        callback().onStartSuccess(null)
        val peer = mockk<BluetoothDevice>(relaxed = true)
        inject("connectedDevice", peer)
        service.onDestroy()
        val gattCallback = CompanionAppService::class.java.getDeclaredField("gattServerCallback").run {
            isAccessible = true
            get(service) as BluetoothGattServerCallback
        }

        gattCallback.onConnectionStateChange(peer, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        gattCallback.onConnectionStateChange(peer, BluetoothGatt.GATT_FAILURE, BluetoothProfile.STATE_DISCONNECTED)
        idle(61)

        verify(exactly = 1) { server.close() }
        verify(exactly = 1) { server.cancelConnection(peer) }
        verifyStarts(1)
    }

    @Test fun lostCallbacksExhaustTheSameFiveRetryBudget() {
        startAdvertising()
        idle(600)
        verifyStarts(6)
        verify(exactly = 6) { advertiser.stopAdvertising(any<AdvertiseCallback>()) }
        startAdvertising()
        idle(600)
        verifyStarts(6)
    }

    @Test fun advertisingUsesBalancedWithoutTimedModeSwitch() {
        startAdvertising()

        assertEquals(listOf(AdvertiseSettings.ADVERTISE_MODE_BALANCED), startedModes())
    }

    @Test fun confirmedAdvertisingHasNoTimerEvenWhenBluetoothIsOff() {
        startAdvertising()
        callback().onStartSuccess(null)
        every { adapter.isEnabled } returns false

        idle(31)

        verifyStarts(1)
    }

    @Test fun dataTooLargeIsTerminalAndStateDrivenStartsCannotLoop() {
        every { cap.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ERROR) } answers {
            startAdvertising()
        }
        startAdvertising()
        callback().onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE)
        repeat(100) { startAdvertising() }
        idle(600)
        verifyStarts(1)
        verify { cap.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ERROR) }
        assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("WearTAK-CompanionAppService")
            .any { it.msg.contains("ADVERTISE_FAILED_DATA_TOO_LARGE") })
    }

    @Test fun unsupportedFailureIsTerminal() {
        startAdvertising()
        callback().onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED)
        startAdvertising()
        idle(60)
        verifyStarts(1)
    }

    @Test fun unknownFailureIsTerminal() {
        startAdvertising()
        callback().onStartFailure(999)
        startAdvertising()
        idle(60)
        verifyStarts(1)
    }

    @Test fun transientFailuresBackOffExponentiallyAndStopAfterFiveRetries() {
        every { cap.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ERROR) } answers {
            startAdvertising()
        }
        startAdvertising()
        for ((index, seconds) in listOf(1L, 2L, 4L, 8L, 16L).withIndex()) {
            callback().onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
            repeat(10) { startAdvertising() }
            idleMillis(seconds * 1000 - 1)
            verifyStarts(index + 1)
            idleMillis(1)
            verifyStarts(index + 2)
        }
        callback().onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        repeat(100) { startAdvertising() }
        idle(600)
        verifyStarts(6)
    }

    @Test fun successfulStartResetsRetryBudget() {
        startAdvertising()
        callback().onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS)
        idle(1)
        callback().onStartSuccess(null)
        invoke("stopAdvertising")
        startAdvertising()
        callback().onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        idleMillis(999)
        verifyStarts(3)
        idleMillis(1)
        verifyStarts(4)
    }

    @Test fun alreadyStartedKeepsAdvertisingWithoutRefreshOrRetry() {
        startAdvertising()
        callback().onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED)
        idle(600)
        verifyStarts(1)
        verify(exactly = 0) { advertiser.stopAdvertising(any<AdvertiseCallback>()) }
    }

    @Test fun successJustBeforeWatchdogDeadlineCancelsAllTimeoutRecovery() {
        startAdvertising()
        idleMillis(29_999)
        callback().onStartSuccess(null)
        idle(600)
        verifyStarts(1)
        verify(exactly = 0) { advertiser.stopAdvertising(any<AdvertiseCallback>()) }
    }

    @Test fun pendingStartWatchdogDoesNotInterruptAnInboundConnection() {
        startAdvertising()
        inject("connectedDevice", mockk<BluetoothDevice>(relaxed = true))
        idle(600)
        verifyStarts(1)
        verify(exactly = 0) { advertiser.stopAdvertising(any<AdvertiseCallback>()) }
    }

    @Test fun realGattConnectionAndDisconnectStillResumeAdvertising() {
        startAdvertising()
        callback().onStartSuccess(null)
        val peer = mockk<BluetoothDevice>(relaxed = true)
        every { peer.address } returns "AA:BB"
        val gattCallback = CompanionAppService::class.java.getDeclaredField("gattServerCallback").run {
            isAccessible = true
            get(service) as BluetoothGattServerCallback
        }
        gattCallback.onConnectionStateChange(peer, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        idle(2)
        gattCallback.onConnectionStateChange(peer, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        callback().onStartSuccess(null)
        idle(600)
        verifyStarts(2)
        verify(exactly = 1) { advertiser.stopAdvertising(any<AdvertiseCallback>()) }
    }

    @Test fun timeoutCancellationPermissionFailureDoesNotScheduleRetries() {
        every { advertiser.stopAdvertising(any<AdvertiseCallback>()) } throws SecurityException("revoked")
        startAdvertising()
        idle(600)
        startAdvertising()
        verifyStarts(1)
        verify(exactly = 1) { advertiser.stopAdvertising(any<AdvertiseCallback>()) }
    }

    @Test fun lateCallbackFromTimedOutStartCannotFailReplacement() {
        startAdvertising()
        val stale = callback()
        idle(31)
        verifyStarts(2)
        stale.onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE)
        callback().onStartSuccess(null)
        idle(600)
        verifyStarts(2)
    }

    @Test fun stopAndDestroyCancelPendingRetries() {
        startAdvertising()
        callback().onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        invoke("stopAdvertising")
        idle(60)
        verifyStarts(1)
        startAdvertising()
        callback().onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        service.onDestroy()
        idle(60)
        verifyStarts(2)
    }

    @Test fun retryDoesNotRestartWhileConnected() {
        startAdvertising()
        callback().onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        inject("connectedDevice", mockk<BluetoothDevice>(relaxed = true))
        idle(60)
        verifyStarts(1)
    }

    @Test fun synchronousInternalErrorsUseTheSameBoundedBackoff() {
        every { advertiser.startAdvertising(any(), any(), any(), any()) } throws IllegalStateException("off")
        startAdvertising()
        repeat(100) { startAdvertising() }
        idleMillis(999)
        verifyStarts(1)
        idleMillis(1)
        verifyStarts(2)
    }

    @Test fun synchronousInvalidPayloadDoesNotRetry() {
        every { advertiser.startAdvertising(any(), any(), any(), any()) } throws IllegalArgumentException("size")
        startAdvertising()
        repeat(100) { startAdvertising() }
        service.updateAdvertisingCallsign("NEW")
        idle(600)
        verifyStarts(1)
    }

    @Test fun permissionFailureIsTerminal() {
        every { advertiser.startAdvertising(any(), any(), any(), any()) } throws SecurityException("permission")
        startAdvertising()
        startAdvertising()
        idle(600)
        verifyStarts(1)
        verify { cap.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ERROR) }
    }

    @Test fun missingAdvertiserHasBoundedRetriesRatherThanErrorStateLoop() {
        inject("advertiser", null)
        every { adapter.bluetoothLeAdvertiser } returns null
        every { cap.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ERROR) } answers {
            startAdvertising()
        }
        startAdvertising()
        idle(600)
        verify(exactly = 6) { adapter.bluetoothLeAdvertiser }
        verifyStarts(0)
    }

    @Test fun chosenCallsignRefreshesScanResponseWithoutReadingOrChangingLongSystemName() {
        every { adapter.name } returns "Galaxy Watch Ultra2 (RK2Z)" + "X".repeat(100)
        service.updateAdvertisingCallsign("WATCH")
        startAdvertising()
        callback().onStartSuccess(null)
        service.updateAdvertisingCallsign("NEW")
        verifyStarts(2)
        assertEquals(listOf("WATCH", "NEW"), advertisedCallsigns())
        verify(exactly = 0) { adapter.name }
        verify(exactly = 0) { adapter.setName(any()) }
    }

    @Test fun callsignUpdateWhilePendingRestartsButSameEncodedValueDoesNot() {
        service.updateAdvertisingCallsign("ABCDEFGHIJKL")
        startAdvertising()
        service.updateAdvertisingCallsign("ABCDEFGHIJKLZ")
        verifyStarts(1)
        service.updateAdvertisingCallsign("")
        verifyStarts(2)
        assertEquals(listOf("ABCDEFGHIJKL", ""), advertisedCallsigns())
    }

    @Test fun lateCallbackFromReplacedCallsignAdvertisementIsIgnored() {
        startAdvertising()
        val stale = callback()
        service.updateAdvertisingCallsign("NEW")
        stale.onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE)
        stale.onStartSuccess(null)
        callback().onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        idle(1)
        verifyStarts(3)
        assertEquals("NEW", advertisedCallsigns().last())
    }

    @Test fun callsignChangesDoNotRestartWhileConnectedOrBypassFailureGate() {
        startAdvertising()
        callback().onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        service.updateAdvertisingCallsign("NEW")
        verifyStarts(1)
        idle(1)
        assertEquals("NEW", advertisedCallsigns().last())
        callback().onStartSuccess(null)
        inject("connectedDevice", mockk<BluetoothDevice>(relaxed = true))
        service.updateAdvertisingCallsign("BONDED")
        verifyStarts(2)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun standaloneCallsignChangesRefreshAdvertisingButManagedCallsignDoesNot() = runTest {
        val callsigns = MutableStateFlow("INITIAL")
        val effectiveCallsigns = MutableStateFlow("PHONE")
        val repo = mockk<DataRepository>(relaxed = true)
        every { repo.getCallsign } returns effectiveCallsigns
        every { repo.getStandaloneCallsign } returns callsigns
        inject("repo\$delegate", lazyOf(repo))
        inject("serviceScope", backgroundScope)
        service.startAdvertisingCallsignCollector()
        runCurrent()
        startAdvertising()
        callback().onStartSuccess(null)
        effectiveCallsigns.value = "PHONE-UPDATED"
        runCurrent()
        verifyStarts(1)
        callsigns.value = "UPDATED"
        runCurrent()
        assertEquals(listOf("INITIAL", "UPDATED"), advertisedCallsigns())
    }

    private fun advertisedCallsigns(): List<String> {
        val responses = mutableListOf<AdvertiseData>()
        verify { advertiser.startAdvertising(any(), any(), capture(responses), any()) }
        return responses.map {
            val payload = it.serviceData.getValue(ParcelUuid(cap.COMPANION_SERVICE_UUID))
            assertEquals(1.toByte(), payload[0])
            payload.copyOfRange(1, payload.size).toString(Charsets.UTF_8)
        }
    }

    private fun idleMillis(milliseconds: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))

    private fun startAdvertising() = invoke("startAdvertising")

    private fun startedModes(): List<Int> {
        val settings = mutableListOf<AdvertiseSettings>()
        verify { advertiser.startAdvertising(capture(settings), any<AdvertiseData>(), any<AdvertiseData>(), any<AdvertiseCallback>()) }
        return settings.map { it.mode }
    }

    private fun verifyStarts(count: Int) = verify(exactly = count) {
        advertiser.startAdvertising(any<AdvertiseSettings>(), any<AdvertiseData>(), any<AdvertiseData>(), any<AdvertiseCallback>())
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
