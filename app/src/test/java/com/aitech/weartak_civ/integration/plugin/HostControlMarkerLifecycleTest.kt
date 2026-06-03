package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import android.os.Process
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.weartak.plugin.sdk.MarkerContract
import com.weartak.plugin.sdk.model.AlertRequestParcel
import com.weartak.plugin.sdk.model.MarkerParcel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostControlMarkerLifecycleTest {

    @Test
    fun requestMarkerPublish_routesToRepoDrop() {
        val repo = mockk<DataRepository>(relaxed = true)
        coEvery {
            repo.requestMarkerDrop(any(), any(), any(), any(), any(), any(), any())
        } returns true
        val control = controlWithRepo(repo)
        val marker = MarkerParcel(
            markerId = "uid-1",
            title = "Title",
            attributes = Bundle().apply {
                putString(MarkerContract.ATTR_MARKER_TYPE, "FRIENDLY")
                putString(MarkerContract.ATTR_REMARK, "remark")
                putDouble(MarkerContract.ATTR_LAT, 1.0)
                putDouble(MarkerContract.ATTR_LON, 2.0)
            },
        )

        control.requestMarkerPublish(marker)

        coVerify(exactly = 1) {
            repo.requestMarkerDrop(
                markerTypeRaw = "FRIENDLY",
                title = "Title",
                remark = "remark",
                timeMillis = any(),
                lat = 1.0,
                lon = 2.0,
                uid = "uid-1",
            )
        }
    }

    @Test
    fun requestMarkerUpdate_routesToRepoUpdate() {
        val repo = mockk<DataRepository>(relaxed = true)
        coEvery {
            repo.requestMarkerUpdate(any(), any(), any(), any(), any(), any(), any())
        } returns true
        val control = controlWithRepo(repo)
        val marker = MarkerParcel(
            markerId = "uid-2",
            title = "Updated",
            attributes = Bundle().apply {
                putString(MarkerContract.ATTR_MARKER_TYPE, "a-h-G")
                putString(MarkerContract.ATTR_REMARK, "new remark")
            },
        )

        control.requestMarkerUpdate(marker)

        coVerify(exactly = 1) {
            repo.requestMarkerUpdate(
                markerTypeRaw = "a-h-G",
                title = "Updated",
                remark = "new remark",
                timeMillis = any(),
                uid = "uid-2",
                lat = null,
                lon = null,
            )
        }
    }

    @Test
    fun requestMarkerClear_routesToRepoClear() {
        val repo = mockk<DataRepository>(relaxed = true)
        val control = controlWithRepo(repo)
        val marker = MarkerParcel(
            markerId = "uid-3",
            attributes = Bundle().apply {
                putString(MarkerContract.ATTR_MARKER_TYPE, "FRIENDLY")
            },
        )

        control.requestMarkerClear(marker)

        coVerify(exactly = 1) { repo.requestMarkerClear("uid-3") }
    }

    @Test
    fun formatPluginAlertDescription_preservesChannelInMessage() {
        val withChannel = HostControlImpl.formatPluginAlertDescription("body", "ops")
        assertEquals("[channel:ops] body", withChannel)
        assertEquals("body", HostControlImpl.formatPluginAlertDescription("body", null))
        assertEquals("body", HostControlImpl.formatPluginAlertDescription("body", "  "))
    }

    @Test
    fun getHostCapabilities_markersTrue_mapLayersFalse() {
        val control = controlWithRepo(mockk(relaxed = true))
        assertTrue(control.hostCapabilities.markers)
        assertEquals(false, control.hostCapabilities.mapLayers)
        assertEquals(false, control.hostCapabilities.sharedMemory)
    }

    private fun controlWithRepo(repo: DataRepository): HostControlImpl {
        val app = mockk<WearTakApplication>(relaxed = true)
        every { app.repo } returns repo
        return HostControlImpl(
            app = app,
            ingress = mockk(relaxed = true),
            pluginPackage = "com.plugin.pkg",
            expectedPluginUid = Process.myUid(),
        )
    }
}
