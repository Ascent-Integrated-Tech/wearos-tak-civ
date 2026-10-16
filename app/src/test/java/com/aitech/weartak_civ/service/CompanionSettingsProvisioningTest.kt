package com.aitech.weartak_civ.service

import android.app.Application
import android.bluetooth.BluetoothDevice
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.takserver.TestClientP12
import com.aitech.weartak_civ.presentation.MainActivity
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionSettingsProvisioningTest {
    @Test fun gatewayProfileWritesFailBeforeTouchingRepository() = runBlocking {
        val service = CompanionAppService()
        val cap = mockk<CompanionAppRepository>()
        val gateway = com.aitech.weartak_civ.cot.CompanionGatewaySession()
        gateway.negotiate(org.json.JSONObject().put("version", 1).put("session", "gateway-session"))
        every { cap.gateway } returns gateway
        CompanionAppService::class.java.getDeclaredField("capRepo\$delegate").apply {
            isAccessible = true
            set(service, lazyOf(cap))
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { service.applySettingsFromCompanion(CompanionAppRepository.SetSettingsPayload(
                takServerList = emptyList(), phoneManagedSettings = false, callsign = "PHONE"
            )) }
        }
        Unit
    }
    @Test fun p12UploadStoresCredentialsWithoutEnablingManagementAndCanReleaseExistingLock() = runBlocking {
        val activity = mockk<MainActivity>()
        every { activity.applicationContext } returns RuntimeEnvironment.getApplication()
        MainActivity.mainActivity = activity
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        val server = DataRepository.TakServerItem(
            true, "TAK", "tak.example", 8089, true, "", "",
            TestClientP12.p12("test-password"), "test-password"
        )
        val payload = Gson().fromJson(Gson().toJson(CompanionAppRepository.SetSettingsPayload(
            takServerList = listOf(server), reportIntSecs = 30, callsign = "ODIN-WEARTAK"
        )), CompanionAppRepository.SetSettingsPayload::class.java)
        val repo = mockk<DataRepository>()
        coEvery { repo.storeProvisionedTakServerList(payload.takServerList) } returns
            DataRepository.TakServerConfigurationRefresh()
        coEvery { repo.awaitTakServerList() } returns payload.takServerList
        coEvery { repo.clearPhoneManagedSettingsForPeer("AA:BB", false, any()) } returns (false to 15)
        coEvery { repo.storeConstantReportingInterval(30) } returns Unit
        coEvery { repo.storeCallsign("ODIN-WEARTAK") } returns Unit
        val device = mockk<BluetoothDevice>()
        every { device.address } returns "AA:BB"
        val service = CompanionAppService()
        fun inject(field: String, value: Any?) {
            CompanionAppService::class.java.getDeclaredField(field).apply {
                isAccessible = true
                set(service, value)
            }
        }
        inject("repo\$delegate", lazyOf(repo))
        val cap = mockk<CompanionAppRepository>()
        every { cap.gateway } returns com.aitech.weartak_civ.cot.CompanionGatewaySession()
        inject("capRepo\$delegate", lazyOf(cap))
        inject("connectedDevice", device)
        TAKConnectService.isRunning.set(false)
        service.applySettingsFromCompanion(payload)
        coVerify(exactly = 1) { repo.storeProvisionedTakServerList(payload.takServerList) }
        coVerify(exactly = 1) { repo.clearPhoneManagedSettingsForPeer("AA:BB", false, any()) }
        coVerify(exactly = 0) { repo.enablePhoneManagedSettings(any()) }
        coVerify(exactly = 0) { repo.storeEnableDynamicReportingStrategy(any()) }
        coVerify(exactly = 0) { repo.storeTeam(any()) }
        coVerify(exactly = 0) { repo.storeRole(any()) }
        inject("connectedDevice", null)
        assertFalse(payload.phoneManagedSettings)
        assertEquals(server.p12Cert, payload.takServerList.single().p12Cert)
    }
}
