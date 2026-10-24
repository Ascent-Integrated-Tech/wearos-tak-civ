package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DittoPdlSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoCommandSettingsTest {
    @Test fun atomicSavePreservesOmittedToggleAndCommunityCredential() = runBlocking {
        com.aitech.weartak_civ.presentation.MainActivity.device_uid = "test-watch"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        val original = MessageDigest.getInstance("SHA-256").digest(repo.communityDittoPdl().toByteArray())
        repo.storeEnableDittoService(false)
        repo.storeDittoCommandSettings(DittoPdlSource.CUSTOM, "synthetic-pdl")
        assertEquals("synthetic-pdl", repo.getEffectiveDittoPdl.first())
        assertEquals(DittoPdlSource.CUSTOM, repo.getDittoPdlSource.first())
        assertFalse(repo.getEnableDittoService.first())
        repo.storeDittoCommandSettings(DittoPdlSource.COMMUNITY, null)
        assertFalse(repo.getEnableDittoService.first())
        assertEquals(DittoPdlSource.COMMUNITY, repo.getDittoPdlSource.first())
        assertEquals("synthetic-pdl", repo.getDittoCustomPdl.first())
        assertArrayEquals(original,
            MessageDigest.getInstance("SHA-256").digest(repo.getEffectiveDittoPdl.first().toByteArray()))
    }
}
