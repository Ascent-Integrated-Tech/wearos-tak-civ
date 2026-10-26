package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.data.DataRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.EnumMap

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ServiceTableLoggingTest {
    @Test fun tableGenerationAndManagedConfigCollectorAreDebugOnly() {
        val manager = Robolectric.buildService(ReactiveServiceManager::class.java).get()
        val repo = mockk<DataRepository>(relaxed = true)
        val registry = mockk<ServiceRegistry>(relaxed = true)
        every { repo.serviceDebugStateFlow } returns MutableStateFlow(
            EnumMap<ReactiveServiceManager.ServiceType, ReactiveServiceManager.ServiceDebugState?>(
                ReactiveServiceManager.ServiceType::class.java))
        fun field(name: String) = ReactiveServiceManager::class.java.getDeclaredField(name).apply { isAccessible = true }
        // No unrelated reconciliation or license observers are started in this test.
        (field("scope").get(manager) as CoroutineScope).cancel()
        ShadowLog.clear()
        try {
            manager.logKnoxMgdServicesWithDiff(repo, registry, ReactiveServiceManager.RecType.KNOX)
            manager.logFullTableWithDiffs(repo, registry, ReactiveServiceManager.RecType.INIT)
            manager.logChangedRowsWithDiffs(repo, registry, ReactiveServiceManager.RecType.STATE_CHANGE,
                listOf(ReactiveServiceManager.ServiceType.WEATHER))
            manager.logFullServiceStatesDebug(repo, registry, "test")
            manager.startManagedConfigUpdateCollector(repo, registry)
            assertEquals(BuildConfig.DEBUG, ShadowLog.getLogsForTag("WearTAK-ReactiveServiceMgr")
                .any { it.msg.contains("R-KNOX") })
            if (!BuildConfig.DEBUG) {
                verify(exactly = 0) { repo.serviceDebugStateFlow }
                verify(exactly = 0) { registry.isServiceRunning(any()) }
            }
        } finally {
            (field("serviceLoop").get(manager) as ExecutorCoroutineDispatcher).close()
        }
    }
}
