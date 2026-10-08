/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data

import android.content.Context
import com.aitech.weartak_civ.cot.CotMarkerDetail
import com.aitech.weartak_civ.cot.CotMarkerEvent
import com.aitech.weartak_civ.cot.CotMarkerMapItem
import com.aitech.weartak_civ.cot.CotPoint
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BloodhoundRepositorySourceTest {
    @Test
    fun clearingActiveSourceStopsSelectionAndRevealsRemainingUidCopy() {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        repository.upsertFromEvent(event(SERVER_A, "Alpha", receivedAt = 100L))
        repository.upsertFromEvent(event(SERVER_B, "Bravo", receivedAt = 200L))

        assertEquals("Bravo", repository.bloodhoundMap.value[UID]?.target?.name)
        repository.activate(UID)
        assertEquals(UID, repository.activeUid.value)

        repository.clearSource(SERVER_B)

        assertNull(repository.activeUid.value)
        assertEquals("Alpha", repository.bloodhoundMap.value[UID]?.target?.name)
        assertEquals(SERVER_A, repository.bloodhoundMap.value[UID]?.sourceServerId)
    }

    @Test
    fun removingBloodhoundFromMenuRemovesVisibleItemAndClearsActiveSelection() = kotlinx.coroutines.runBlocking {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        repository.upsertFromEvent(event(SERVER_A, "Alpha", receivedAt = 100L))
        repository.activate(UID)

        val removed = repository.removeFromMenu(UID)

        assertEquals("Alpha", removed?.target?.name)
        assertNull(repository.bloodhoundMap.value[UID])
        assertNull(repository.activeUid.value)
    }

    @Test
    fun removingTargetDropsEveryUidCopyAndClearsActiveSelection() {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        repository.upsertFromEvent(event(SERVER_A, "Alpha", receivedAt = 100L))
        repository.upsertFromEvent(event(SERVER_B, "Bravo", receivedAt = 200L))
        repository.activate(UID)

        val removed = repository.removeTarget(UID)

        assertEquals(2, removed.size)
        assertNull(repository.bloodhoundMap.value[UID])
        assertNull(repository.activeUid.value)
    }

    private fun event(
        serverId: String,
        title: String,
        receivedAt: Long
    ): BloodhoundRepository.BloodhoundEvent {
        val marker = CotMarkerEvent(
            version = "2.0",
            uid = UID,
            type = "a-h-G",
            how = "h-g-i-g-o",
            time = receivedAt,
            start = receivedAt,
            stale = Long.MAX_VALUE,
            access = null,
            point = CotPoint(41.0, -87.0, 100.0, 4.0, 5.0),
            detail = CotMarkerDetail(
                markerCallsign = title,
                link = null,
                creator = null,
                usericon = null,
                remarks = null,
                contact = null
            )
        )
        return BloodhoundRepository.BloodhoundEvent(
            mapItem = CotMarkerMapItem(marker),
            server = "$title.example",
            timeIssued = receivedAt,
            timeCompleted = null,
            sourceServerId = serverId,
            receivedAt = receivedAt
        )
    }

    private companion object {
        const val UID = "same-uid"
        const val SERVER_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val SERVER_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }
}
