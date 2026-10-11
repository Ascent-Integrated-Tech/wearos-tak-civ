package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.CompanionAppRepository
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CompanionBloodhoundCollectorsTest {
    @Test
    fun targetUpdatesAreCollectedWhileActiveUidIsUnchanged() = runBlocking {
        val activeUid = MutableStateFlow<String?>("target")
        val activeItem = MutableStateFlow<BloodhoundRepository.BloodhoundItem?>(null)
        val connection = MutableStateFlow(CompanionAppRepository.BleConnectionState.NOT_CONNECTED)
        val repository = mockk<BloodhoundRepository>()
        val companion = mockk<CompanionAppRepository>()
        val sync = mockk<CompanionBloodhoundSync>(relaxed = true)
        every { repository.activeUid } returns activeUid
        every { repository.activeBhItemMap } returns activeItem
        every { companion.bleConnectionState } returns connection
        val service = CompanionAppService()
        setLazy(service, "bhRepo", repository)
        setLazy(service, "capRepo", companion)
        setLazy(service, "bloodhoundSync", sync)
        try {
            CompanionAppService::class.java.getDeclaredMethod("startBloodhoundSync").apply {
                isAccessible = true
                invoke(service)
            }
            withTimeout(5_000L) {
                while (activeUid.subscriptionCount.value == 0 ||
                    activeItem.subscriptionCount.value == 0 || connection.subscriptionCount.value == 0
                ) delay(10L)
            }
            verify(timeout = 5_000L, atLeast = 2) { sync.localTargetChanged() }
            clearMocks(sync, answers = false)
            activeItem.value = mockk(relaxed = true)
            verify(timeout = 5_000L, atLeast = 1) { sync.localTargetChanged() }
        } finally {
            CompanionAppService::class.java.getDeclaredField("serviceJob").apply {
                isAccessible = true
                (get(service) as Job).cancel()
            }
        }
    }

    private fun setLazy(service: CompanionAppService, name: String, value: Any) {
        CompanionAppService::class.java.getDeclaredField("$name\$delegate").apply {
            isAccessible = true
            set(service, lazy { value })
        }
    }
}
