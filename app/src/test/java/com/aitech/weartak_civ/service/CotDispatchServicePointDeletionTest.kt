package com.aitech.weartak_civ.service

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.aitech.weartak_civ.cot.CotEndpointDispatcher
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.CompanionPointRevisions
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CotDispatchServicePointDeletionTest {
    @Test fun clearIsImmediateLocalOnlyEvenDisconnectedAndCannotPublishExternalDeletion() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        MainActivity.mainActivity = mockk { every { applicationContext } returns app }
        val preferences = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            private val mutex = Mutex()
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                mutex.withLock { transform(data.value).also { data.value = it } }
        }
        val repo = DataRepository(app)
        val bloodhounds = BloodhoundRepository(app, preferences)
        MainActivity.Constants.bhRepo = bloodhounds
        val service = Robolectric.buildService(CotDispatchService::class.java).get()
        val calls = mutableListOf<String>()
        val dispatcher = CotEndpointDispatcher(
            sendTak = { _, _ -> calls += "TAK" },
            sendSitx = { calls += "SITX" },
            sendDitto = { _, _ -> calls += "Ditto" },
            sendMulticast = { calls += "multicast" },
            sendPliMulticast = { calls += "PLI multicast" },
            sendCompanion = { calls += "legacy companion CoT" },
            sendPlugin = { calls += "plugin" })
        field("dataRepository").set(service, repo)
        field("endpointDispatcher\$delegate").set(service, lazyOf(dispatcher))
        @Suppress("UNCHECKED_CAST")
        val queue = field("pendingMarkerCotList").get(service) as MutableList<DataRepository.DroppedMarker>
        @Suppress("UNCHECKED_CAST")
        val map = field("markerDropMap").get(service) as MutableMap<String, DataRepository.DroppedMarker>
        val requests = mutableListOf<DataRepository.PointDeletionRequest>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            repo.pointDeletionRequests.collect { requests.add(it) }
        }
        try {
            val now = System.currentTimeMillis()
            for (shared in listOf(false, true)) {
                val uid = if (shared) "shared" else "unshared"
                val marker = DataRepository.DroppedMarker(DataRepository.MarkerCotType.DROP,
                    uid, DataRepository.MarkerType.HOSTILE, "Point", "", now, null, true, true)
                repo.appendDroppedMarker(marker)
                map[uid] = marker
                queue.add(marker)
                bloodhounds.addMarkerTarget(uid, "Point after edit", "a-h-G", 38.0, -77.0, now + 1)
                bloodhounds.activate(uid)
                if (shared) {
                    repo.companionPointGeneration = 7
                    repo.companionPointRevisions.remember(uid,
                        CompanionPointRevisions.Revision("shared-revision", now, true, 7))
                }
                call(service, "handleClearMarkerUid", uid)
                assertTrue(repo.droppedMarkerList.value.isEmpty())
                assertFalse(bloodhounds.isUidValid(uid))
                assertNull(bloodhounds.activeUid.value)
                assertFalse(map.containsKey(uid))
                assertTrue(queue.isEmpty())
                assertTrue(repo.isDeletedLocalPoint(uid))
                assertTrue("External/network/legacy CoT dispatch must not be called", calls.isEmpty())
            }
            yield()
            assertEquals(listOf("shared"), requests.map { it.uid })
        } finally {
            collector.cancel()
            (field("serviceScope").get(service) as kotlinx.coroutines.CoroutineScope)
                .coroutineContext[Job]?.cancel()
            bloodhounds.removeAllFromMenu()
        }
    }

    private fun field(name: String) = CotDispatchService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }
    private suspend fun call(service: CotDispatchService, name: String, uid: String): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            CotDispatchService::class.java.declaredMethods.single { it.name == name }
                .apply { isAccessible = true }.invoke(service, uid, continuation)
        }
}
