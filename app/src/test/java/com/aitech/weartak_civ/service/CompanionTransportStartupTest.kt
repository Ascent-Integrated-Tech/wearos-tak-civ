package com.aitech.weartak_civ.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionTransportStartupTest {
    @Test
    fun bondedPhoneWithoutCompanionHistoryKeepsStandaloneStaleAndDoesNotArmGrace() = runBlocking {
        MainActivity.device_uid = "startup-watch"
        val repository = DataRepository(RuntimeEnvironment.getApplication())
        repository.awaitCompanionHistoryLoaded()
        repository.clearCompanionAppReadinessHistory()
        val service = CompanionAppService()
        CompanionAppService::class.java.getDeclaredField("repo\$delegate").apply {
            isAccessible = true
            set(service, lazyOf(repository))
        }
        val adapter = mockk<BluetoothAdapter>()
        val peer = mockk<BluetoothDevice>()
        every { adapter.isEnabled } returns false
        every { adapter.bondedDevices } returns setOf(peer)
        every { peer.bondState } returns BluetoothDevice.BOND_BONDED
        val previousReportingInterval = CotDispatchService.actualReportingIntervalSecs
        CotDispatchService.actualReportingIntervalSecs = 15

        try {
            service.beginGraceForPreviouslyReadyCompanion()
            assertTrue(adapter.bondedDevices.any { it.bondState == BluetoothDevice.BOND_BONDED })
            assertFalse(repository.hasCompanionEverBeenReady())
            assertFalse(repository.companionDisconnectGraceActive.value)
            val standalonePli = repository.snapshotPli()
            assertTrue(standalonePli.time.stale - standalonePli.time.time > 45_000L)

            repository.setCompanionAppConnected()
            repository.setCompanionAppDisconnected()
            service.beginGraceForPreviouslyReadyCompanion()
            assertTrue(repository.companionEverReady.value == true)
            assertTrue(repository.companionDisconnectGraceActive.value)
            val companionPli = repository.snapshotPli()
            assertEquals(45_000L, companionPli.time.stale - companionPli.time.time)
        } finally {
            CotDispatchService.actualReportingIntervalSecs = previousReportingInterval
            repository.clearCompanionAppReadinessHistory()
        }
    }
}
