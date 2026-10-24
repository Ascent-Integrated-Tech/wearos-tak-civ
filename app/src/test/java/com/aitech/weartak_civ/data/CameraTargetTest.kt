package com.aitech.weartak_civ.data

import android.app.Application
import android.content.Context
import com.aitech.weartak_civ.cot.*
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CameraTargetTest {
    @Test fun sourceOwnedCameraMetadataSurvivesMarkerProjectionAndPickerAdapter() = runBlocking {
        MainActivity.device_uid = "watch"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        val bh = BloodhoundRepository(mockk<Context>(relaxed = true))
        val now = System.currentTimeMillis()
        val event = CotMarkerEvent("2.0", "camera", "b-m-p-s-p-i", "m-g", now, now, now + 60_000,
            null, CotPoint(41.0, -87.0, 0.0, 10.0, 10.0),
            CotMarkerDetail("North", null, null, null, null, null, isCamera = true))
        repo.applyReceivedMarkerEvent(event, "TAK", "source", now, now)
        assertTrue(repo.receivedMarkerList.value.single().isCamera)
        bh.applyReceivedMarkerEvent(event, "TAK", "source", now)
        assertTrue(withTimeout(5000) { bh.listBhItemsUi.first { it.isNotEmpty() } }.single().isCamera)
        val update = event.copy(time = now + 1, detail = event.detail.copy(isCamera = false))
        bh.applyReceivedMarkerEvent(update, "TAK", "source", now + 1)
        assertFalse(withTimeout(5000) { bh.listBhItemsUi.first { it.singleOrNull()?.isCamera == false } }.single().isCamera)
    }

    @Test fun userAndMarkerDetailActionsKeepCameraIdentity() = runBlocking {
        val bh = BloodhoundRepository(mockk<Context>(relaxed = true))
        val now = System.currentTimeMillis()
        val user = DataRepository.ReceivedUser("user", "a-f-G-U-C", "Blue", "User", "Team Member",
            now, now + 60_000, DataRepository.LocationSnapshot(now, 41.0, -87.0, 0.0, 10f, 10f, null, null),
            "TAK", isCamera = true)
        assertTrue(bh.addUserTarget(user))
        assertTrue(bh.bloodhoundMap.value.getValue("user").bhEvent.mapItem.cotEvent.detail.isCamera)
        bh.addMarkerTarget("marker", "Marker", "b-m-p-s-p-i", 41.0, -87.0, now, isCamera = true)
        assertTrue(withTimeout(5000) { bh.listBhItemsUi.first { it.size == 2 } }.all { it.isCamera })
    }
}
