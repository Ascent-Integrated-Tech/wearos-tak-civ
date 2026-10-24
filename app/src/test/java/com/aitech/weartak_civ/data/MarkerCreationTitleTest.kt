package com.aitech.weartak_civ.data

import android.app.Application
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MarkerCreationTitleTest {
    @Test fun newTitlesTrimOnlyTrailingAsciiWhitespaceAndKeepRemarks() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        for ((input, expected) in listOf(
            "Test " to "Test", "Bad \t\r\n" to "Bad", " Keep leading " to " Keep leading",
            " \t" to "", "Nonbreaking\u00a0" to "Nonbreaking\u00a0"
        )) {
            val next = async(start = CoroutineStart.UNDISPATCHED) { repo.markerDropRequests.first() }
            assertTrue(repo.requestMarkerDrop("friendly", input, "Remark  ", 123L, uid = "original"))
            val request = next.await()
            assertEquals(expected, request.title)
            assertEquals("Remark  ", request.remark)
            assertEquals("original", request.uid)
        }
        val legacy = DataRepository.DroppedMarker(DataRepository.MarkerCotType.DROP,
            "legacy", DataRepository.MarkerType.FRIENDLY, "Legacy ", "Old  ", 1L, null, false, false)
        repo.storeDroppedMarkerList(listOf(legacy))
        assertEquals("Legacy ", repo.droppedMarkerList.value.single().title)
        assertEquals("Old  ", repo.droppedMarkerList.value.single().remark)
    }
}
