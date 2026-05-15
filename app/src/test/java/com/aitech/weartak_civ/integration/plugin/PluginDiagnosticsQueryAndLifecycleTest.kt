package com.aitech.weartak_civ.integration.plugin

import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [PluginDiagnostics] query surface and category mapping.
 */
@RunWith(RobolectricTestRunner::class)
class PluginDiagnosticsQueryAndLifecycleTest {

    @After
    fun tearDown() {
        PluginDiagnostics.resetForTest()
    }

    @Test
    fun admissionCategoryForAidlBindFailure_mapsTrustAndUidAndBinder() {
        assertEquals(
            PluginDiagnostics.AdmissionCategory.UID_PACKAGE_MISMATCH,
            PluginDiagnostics.admissionCategoryForAidlBindFailure("trust_failure:${ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH}")
        )
        assertEquals(
            PluginDiagnostics.AdmissionCategory.TRUST_FAILURE,
            PluginDiagnostics.admissionCategoryForAidlBindFailure("trust_failure:${ReasonCodes.PLUGIN_CERT_CHANGED}")
        )
        assertEquals(
            PluginDiagnostics.AdmissionCategory.PLUGIN_INFO_MISMATCH,
            PluginDiagnostics.admissionCategoryForAidlBindFailure("plugin_info_mismatch")
        )
        assertEquals(
            PluginDiagnostics.AdmissionCategory.UID_PACKAGE_MISMATCH,
            PluginDiagnostics.admissionCategoryForAidlBindFailure("uid_package_mismatch")
        )
        assertEquals(
            PluginDiagnostics.AdmissionCategory.BINDER_PROTOCOL,
            PluginDiagnostics.admissionCategoryForAidlBindFailure("null_binder")
        )
        assertEquals(
            PluginDiagnostics.AdmissionCategory.BINDER_PROTOCOL,
            PluginDiagnostics.admissionCategoryForAidlBindFailure("unknown_reason")
        )
    }

    @Test
    fun recordBindFailure_incrementsBindFailuresAndBinderProtocolWhenRequested() {
        PluginDiagnostics.recordBindFailure("c", null, includeBinderProtocolCategory = true)
        val s = PluginDiagnostics.snapshot()
        assertEquals(1, s.bindFailures)
        assertEquals(1, s.categoryCounts[PluginDiagnostics.AdmissionCategory.BINDER_PROTOCOL] ?: 0)
    }

    @Test
    fun recordBindFailure_withoutCategory_doesNotBumpBinderProtocol() {
        PluginDiagnostics.recordBindFailure("c", null, includeBinderProtocolCategory = false)
        val s = PluginDiagnostics.snapshot()
        assertEquals(1, s.bindFailures)
        assertEquals(0, s.categoryCounts[PluginDiagnostics.AdmissionCategory.BINDER_PROTOCOL] ?: 0)
    }

    @Test
    fun recordAdmissionRejected_bumpsCategoryAndDenied() {
        PluginDiagnostics.recordAdmissionRejected("p", PluginDiagnostics.AdmissionCategory.TRUST_FAILURE, "x")
        val s = PluginDiagnostics.snapshot()
        assertEquals(1, s.deniedAdmission)
        assertEquals(1, s.categoryCounts[PluginDiagnostics.AdmissionCategory.TRUST_FAILURE] ?: 0)
    }

    @Test
    fun markRejected_bumpsLegacyRejectionCategory() {
        PluginDiagnostics.markRejected("x", "reason")
        val s = PluginDiagnostics.snapshot()
        assertEquals(1, s.deniedAdmission)
        assertEquals(1, s.categoryCounts[PluginDiagnostics.AdmissionCategory.LEGACY_REJECTION] ?: 0)
    }

    @Test
    fun recordBinderDeath_and_markDisconnected_bumpCategories() {
        PluginDiagnostics.recordBinderDeath("a")
        PluginDiagnostics.markDisconnected("b")
        val s = PluginDiagnostics.snapshot()
        assertEquals(1, s.binderDeaths)
        assertEquals(1, s.categoryCounts[PluginDiagnostics.AdmissionCategory.BINDER_DEATH] ?: 0)
        assertEquals(1, s.categoryCounts[PluginDiagnostics.AdmissionCategory.LIFECYCLE_DISCONNECT] ?: 0)
    }

    @Test
    fun markUnbound_bumpsExplicitUnbind() {
        PluginDiagnostics.markBound("a")
        PluginDiagnostics.markUnbound("a")
        val s = PluginDiagnostics.snapshot()
        assertEquals(1, s.unbindEvents)
        assertEquals(1, s.categoryCounts[PluginDiagnostics.AdmissionCategory.EXPLICIT_UNBIND] ?: 0)
    }

    @Test
    fun recordPackageRemoved_bumpsRemovalCounter() {
        PluginDiagnostics.recordPackageRemoved("com.removed")
        val s = PluginDiagnostics.snapshot()
        assertEquals(1, s.packageRemovals)
        assertEquals(1, s.categoryCounts[PluginDiagnostics.AdmissionCategory.PACKAGE_REMOVED] ?: 0)
    }

    @Test
    fun markDiscovered_incrementsDiscoveryRefreshCount() {
        PluginDiagnostics.markDiscovered(listOf("a"))
        PluginDiagnostics.markDiscovered(listOf("b"))
        assertEquals(2, PluginDiagnostics.snapshot().discoveryRefreshCount)
    }

    @Test
    fun integrationState_includesAllStableCategoryKeys() {
        PluginDiagnostics.recordBinderDeath("p")
        val st = PluginDiagnostics.integrationState()
        for (k in PluginDiagnostics.stableCategoryKeys()) {
            assertTrue(st.stableCategories.containsKey(k))
        }
        assertEquals(1, st.stableCategories[PluginDiagnostics.AdmissionCategory.BINDER_DEATH])
        assertTrue(st.toDebugString().contains("binder_death=1"))
    }
}
