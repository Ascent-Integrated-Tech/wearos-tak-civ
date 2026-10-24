package com.aitech.weartak_civ.service

import android.app.Application
import android.location.Location
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoTrackLocationTest {
    @Test fun independentTrackPublisherWaitsForFixAndReusesLastKnownPosition() = runBlocking {
        MainActivity.device_uid = "test-watch"
        MainActivity.callsign = "WATCH"
        MainActivity.role = "Team Member"
        MainActivity.teamValue = "Blue"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        val service = spyk(Robolectric.buildService(DittoService::class.java).get())
        fun field(name: String) = DittoService::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("dataRepository").set(service, repo)
        field("dittoVersion").set(service, "test")
        DittoService.localPeerKeyString = "test-peer"
        val documents = mutableListOf<Map<String, Any?>>()
        every { service.writeDocument("track", any()) } answers { documents += secondArg<Map<String, Any?>>() }
        val scope = field("ioScope").get(service) as CoroutineScope
        suspend fun tick() {
            DittoService::class.java.getDeclaredMethod("updateTrackDocument").apply { isAccessible = true }.invoke(service)
            scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        }
        try {
            tick()
            assertTrue(documents.isEmpty())
            repo.storeLatestLocation(Location("gps"))
            tick()
            assertTrue(documents.isEmpty())
            val fix = Location("gps").apply {
                time = System.currentTimeMillis() - 60_000
                latitude = 41.879815
                longitude = -87.642469
                accuracy = 5f
            }
            repo.storeLatestLocation(fix)
            repo.storeLatestLocation(Location("gps").apply { latitude = Double.NaN })
            tick()
            assertEquals(1, documents.size)
            assertEquals(41.879815, documents.single()["j"] as Double, 0.0)
            assertEquals(-87.642469, documents.single()["l"] as Double, 0.0)
            assertTrue((documents.single()["h"] as Double) > 5.0)
            assertTrue((documents.single()["r"] as String).contains("timeMillis='${fix.time}'"))
            repo.setCompanionAppConnected()
            tick()
            assertEquals(1, documents.size)
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
    }
}
