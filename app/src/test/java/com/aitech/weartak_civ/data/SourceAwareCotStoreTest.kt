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
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAwareCotStoreTest {
    private data class Item(
        val sourceId: String,
        val uid: String,
        val receivedAt: Long,
        val value: String
    )

    private fun store() = SourceAwareCotStore(
        sourceIdOf = Item::sourceId,
        cotUidOf = Item::uid,
        receivedAtOf = Item::receivedAt
    )

    @Test
    fun keepsDuplicateUidsAndRestoresTheRemainingCopyAfterSourceClear() {
        val store = store()
        store.upsert(Item("server-a", "same-uid", 100L, "A"))
        store.upsert(Item("server-b", "same-uid", 200L, "B"))

        assertEquals(listOf("B"), store.visibleItems.value.map(Item::value))
        assertEquals(2, store.allItems.value.size)

        store.removeSource("server-b")

        assertEquals(listOf("A"), store.visibleItems.value.map(Item::value))
        assertEquals(1, store.allItems.value.size)
    }

    @Test
    fun clearSourceDoesNotRemoveOtherServerOrLocalItems() {
        val store = store()
        store.upsert(Item("server-a", "a", 100L, "A"))
        store.upsert(Item("server-b", "b", 100L, "B"))
        store.upsert(Item("non-tak:local", "local", 100L, "Local"))

        store.removeSource("server-a")

        assertEquals(setOf("B", "Local"), store.visibleItems.value.map(Item::value).toSet())
        assertTrue(store.allItems.value.keys.none { it.sourceId == "server-a" })
    }
}
