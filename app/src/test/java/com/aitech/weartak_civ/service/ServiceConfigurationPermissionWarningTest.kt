package com.aitech.weartak_civ.service

import android.Manifest
import android.app.Application
import com.aitech.weartak_civ.service.ReactiveServiceManager.ServiceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import io.mockk.mockk

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ServiceConfigurationPermissionWarningTest {
    @Test
    fun logsOncePerFeatureAndResumesAfterPermissionStateChanges() {
        val app = RuntimeEnvironment.getApplication()
        val repository = mockk<com.aitech.weartak_civ.data.DataRepository>(relaxed = true)
        val configuration = ServiceConfiguration(
            app,
            repository,
            mockk(relaxed = true)
        )
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(configuration.permissionsGranted(ServiceType.AUDIO_CAP))
        assertTrue(configuration.permissionsGranted(ServiceType.ONNX_GUNSHOT))
        ShadowLog.clear()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)

        assertFalse(configuration.permissionsGranted(ServiceType.AUDIO_CAP))
        assertFalse(configuration.permissionsGranted(ServiceType.AUDIO_CAP))
        assertFalse(configuration.permissionsGranted(ServiceType.ONNX_GUNSHOT))
        assertEquals(
            1,
            ShadowLog.getLogsForTag("WearTAK-ServiceConfig")
                .count { it.msg.contains("Missing permissions for AUDIO_CAP") }
        )
        assertEquals(
            1,
            ShadowLog.getLogsForTag("WearTAK-ServiceConfig")
                .count { it.msg.contains("Missing permissions for ONNX_GUNSHOT") }
        )

        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(configuration.permissionsGranted(ServiceType.AUDIO_CAP))
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(configuration.permissionsGranted(ServiceType.AUDIO_CAP))
        assertEquals(
            2,
            ShadowLog.getLogsForTag("WearTAK-ServiceConfig")
                .count { it.msg.contains("Missing permissions for AUDIO_CAP") }
        )
    }
}
