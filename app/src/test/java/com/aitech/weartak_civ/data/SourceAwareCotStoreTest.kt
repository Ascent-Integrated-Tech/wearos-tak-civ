/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAwareCotStoreTest {
    private data class Item(
        val sourceId: String,
        val uid: String,
        val eventTime: Long,
        val receivedAt: Long,
        val value: String
    )

    private fun store() = SourceAwareCotStore(
        sourceIdOf = Item::sourceId,
        cotUidOf = Item::uid,
        eventTimeOf = Item::eventTime,
        receivedAtOf = Item::receivedAt
    )

    @Test
    fun keepsDuplicateUidsAndRestoresTheRemainingCopyAfterSourceClear() {
        val store = store()
        store.upsert(Item("server-a", "same-uid", 100L, 100L, "A"))
        store.upsert(Item("server-b", "same-uid", 200L, 200L, "B"))

        assertEquals(listOf("B"), store.visibleItems.value.map(Item::value))
        assertEquals(2, store.allItems.value.size)

        store.removeSource("server-b")

        assertEquals(listOf("A"), store.visibleItems.value.map(Item::value))
        assertEquals(1, store.allItems.value.size)
    }

    @Test
    fun clearSourceDoesNotRemoveOtherServerOrLocalItems() {
        val store = store()
        store.upsert(Item("server-a", "a", 100L, 100L, "A"))
        store.upsert(Item("server-b", "b", 100L, 100L, "B"))
        store.upsert(Item("non-tak:local", "local", 100L, 100L, "Local"))

        store.removeSource("server-a")

        assertEquals(setOf("B", "Local"), store.visibleItems.value.map(Item::value).toSet())
        assertTrue(store.allItems.value.keys.none { it.sourceId == "server-a" })
    }

    @Test
    fun newerEventTimeWinsEvenWhenOlderEventArrivesLater() {
        val store = store()
        store.upsert(Item("server-a", "same-uid", 200L, 100L, "Newer event"))
        store.upsert(Item("server-b", "same-uid", 100L, 200L, "Later arrival"))

        assertEquals(listOf("Newer event"), store.visibleItems.value.map(Item::value))
        assertEquals("server-a", store.visibleKey("same-uid")?.sourceId)
    }

    @Test
    fun olderSameSourceUpdateIsIgnored() {
        val store = store()
        val key = store.upsert(Item("server-a", "same-uid", 200L, 100L, "Newer event"))

        val result = store.upsertWithResult(
            Item("server-a", "same-uid", 100L, 200L, "Older event")
        )

        assertFalse(result.applied)
        assertEquals("Newer event", store.item(key)?.value)
        assertEquals(listOf("Newer event"), store.visibleItems.value.map(Item::value))
    }

    @Test
    fun equalEventTimesUseReceiptThenInsertionOrderAsTieBreakers() {
        val store = store()
        store.upsert(Item("server-a", "same-uid", 100L, 100L, "A"))
        store.upsert(Item("server-b", "same-uid", 100L, 200L, "B"))

        assertEquals(listOf("B"), store.visibleItems.value.map(Item::value))

        store.upsert(Item("server-a", "same-uid", 100L, 200L, "A refreshed"))

        assertEquals(listOf("A refreshed"), store.visibleItems.value.map(Item::value))
    }

    @Test
    fun olderRemovalDoesNotDeleteNewerSourceCopy() {
        val store = store()
        val key = store.upsert(Item("server-a", "same-uid", 200L, 100L, "Current"))

        assertNull(store.removeIfNotOlder(key, eventTime = 100L, receivedAt = 200L))
        assertEquals("Current", store.item(key)?.value)

        assertEquals(
            "Current",
            store.removeIfNotOlder(key, eventTime = 200L, receivedAt = 300L)?.value
        )
        assertNull(store.item(key))
    }
}
