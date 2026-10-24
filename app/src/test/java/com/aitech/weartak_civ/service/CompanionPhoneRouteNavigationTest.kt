package com.aitech.weartak_civ.service

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import com.aitech.weartak_civ.cot.CompanionGatewaySession
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionPhoneRouteNavigationTest {
    private val app = RuntimeEnvironment.getApplication()
    private val repo = DataRepository(app)
    private val bh = BloodhoundRepository(app)
    private val service = CompanionAppService()
    private var bonded = true
    private var ready = true
    private val gateway = CompanionGatewaySession()

    init {
        MainActivity.device_uid = "watch"
        val peer = mockk<BluetoothDevice>()
        every { peer.address } returns "AA:BB"
        every { peer.bondState } answers { if (bonded) BluetoothDevice.BOND_BONDED else BluetoothDevice.BOND_NONE }
        val cap = mockk<CompanionAppRepository>(relaxed = true)
        every { cap.gateway } returns gateway
        every { cap.companionAppReady } answers { MutableStateFlow(ready) }
        inject("repo\$delegate", lazyOf(repo))
        inject("bhRepo\$delegate", lazyOf(bh))
        inject("capRepo\$delegate", lazyOf(cap))
        inject("connectedDevice", peer)
        inject("notificationsEnabled", true)
        inject("gattServer", mockk<BluetoothGattServer>())
        inject("txCharacteristic", mockk<BluetoothGattCharacteristic>())
    }

    @Test fun bondedFramesStartAndProgressRouteAndDisconnectClearsActiveTarget() {
        receive(1, 0)
        assertEquals("route", repo.takRouteCopies.single().uid)
        assertEquals(0, bh.routeFollow.value!!.navigation.currentIndex)
        receive(2, 1)
        assertEquals(1, bh.routeFollow.value!!.navigation.currentIndex)
        receive(1, 0)
        assertEquals(1, bh.routeFollow.value!!.navigation.currentIndex)
        service.beginCompanionSubscription()
        assertNull(bh.activeUid.value)
        receive(3, 1)
        assertNotNull(bh.activeUid.value)
        repo.clearRoutesFromWatch(bh)
        receive(4, 1)
        assertNull(bh.activeUid.value)
        assertTrue(repo.takRouteCopies.isEmpty())
    }

    @Test fun unbondedUnreadyGatewayAndMalformedFramesCannotStartNavigation() {
        bonded = false
        receive(1, 0)
        bonded = true
        ready = false
        receive(2, 0)
        ready = true
        receive(3, 2)
        assertNull(bh.activeUid.value)
        gateway.negotiate(JSONObject().put("version", 1).put("session", "gateway"))
        gateway.confirm("gateway")
        receive(4, 0)
        assertNull(bh.activeUid.value)
        assertTrue(repo.takRouteCopies.isEmpty())
    }

    private fun receive(sequence: Long, index: Int) {
        val payload = JSONObject().put("version", 1).put("sessionId", "session")
            .put("sequence", sequence).put("action", "progress").put("routeUid", "route")
            .put("targetPointIndex", index).put("routeCot",
                """<event type="b-m-r" uid="route"><detail><link point="0,0"/><link point="0,0.001"/></detail></event>""")
        CompanionAppService::class.java.getDeclaredMethod("handleCompanionLine", String::class.java)
            .apply { isAccessible = true }.invoke(service,
                JSONObject().put("msg_type", "phone_route_navigation").put("payload", payload).toString())
    }

    private fun inject(name: String, value: Any) {
        CompanionAppService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }
}
