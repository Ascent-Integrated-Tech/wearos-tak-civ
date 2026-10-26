package com.aitech.weartak_civ.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceAwareCotCoalescingTest {
    private data class Item(
        val source: String = "tcp",
        val uid: String = "track",
        val time: Long = 100,
        val receipt: Long = time,
        val position: Int = 0,
        val stale: Long = 10_000
    )

    @Test fun continuousTrafficHasFixedDeadlineAndBatchesMultipleUids() = runTest {
        var coalesced = 0L
        val store = SourceAwareCotStore(Item::source, Item::uid, Item::time, Item::receipt,
            publicationScope = backgroundScope,
            onReduction = { count, _ -> coalesced += count })
        store.upsert(Item())
        store.upsert(Item(uid = "other"))
        repeat(15) { index ->
            store.upsertWithResult(Item(time = 200L + index, position = index), coalesce = true)
            store.upsertWithResult(Item(uid = "other", time = 200L + index, position = index), coalesce = true)
            runCurrent()
            advanceTimeBy(100)
        }
        assertEquals(listOf(0, 0), store.visibleItems.value.map { it.position })
        runCurrent()
        assertEquals(listOf(14, 14), store.visibleItems.value.map { it.position })
        assertEquals(28L, coalesced)
        store.upsertWithResult(Item(time = 500, position = 50), coalesce = true)
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(50, store.visibleItems.value.first().position)
    }

    @Test fun noOpAdvancesOrderingButNeverSkipsRetentionChangesOrSourceWinnerChanges() = runTest {
        var skipped = 0L
        val store = SourceAwareCotStore(Item::source, Item::uid, Item::time, Item::receipt,
            publicationScope = backgroundScope,
            equivalent = { a, b -> a.copy(receipt = b.receipt) == b },
            onReduction = { _, count -> skipped += count })
        store.upsert(Item())
        val projection = store.visibleItems.value
        store.upsertWithResult(Item(receipt = 200), coalesce = true)
        assertSame(projection, store.visibleItems.value)
        assertEquals(200L, store.item(SourceCotKey("tcp", "track"))!!.receipt)
        assertFalse(store.upsertWithResult(Item(time = 99, position = 9), true).applied)
        assertEquals(1L, skipped)
        store.upsertWithResult(Item(time = 200), true)
        store.upsertWithResult(Item(time = 201, stale = 20_000), true)
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(20_000L, store.visibleItems.value.single().stale)
        assertEquals(1L, skipped)

        store.upsertWithResult(Item(source = "ble", time = 202), true)
        assertEquals("ble", store.visibleItems.value.single().source)
        store.upsertWithResult(Item(time = 203, stale = 20_000), true)
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals("tcp", store.visibleItems.value.single().source)
        assertEquals(1L, skipped)
    }

    @Test fun deletesAndSessionClearsCannotBeResurrectedByPendingPublication() = runTest {
        val store = SourceAwareCotStore(Item::source, Item::uid, Item::time, Item::receipt,
            publicationScope = backgroundScope)
        store.upsert(Item())
        store.upsertWithResult(Item(time = 300, position = 3), true)
        runCurrent()
        assertNull(store.removeIfNotOlder(SourceCotKey("tcp", "track"), 200, 400))
        assertEquals(300L, store.item(SourceCotKey("tcp", "track"))!!.time)
        store.removeIfNotOlder(SourceCotKey("tcp", "track"), 300, 500)
        assertTrue(store.visibleItems.value.isEmpty())
        assertFalse(store.upsertWithResult(Item(time = 300), true).applied)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(store.visibleItems.value.isEmpty())

        store.upsert(Item(time = 400))
        store.upsertWithResult(Item(time = 500), true)
        runCurrent()
        store.removeSource("tcp")
        store.upsert(Item(source = "new-session", time = 1))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("new-session"), store.visibleItems.value.map { it.source })
        store.upsertWithResult(Item(source = "new-session", time = 2), true)
        runCurrent()
        store.clear()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(store.snapshot().isEmpty())
        assertTrue(store.allItems.value.isEmpty())
    }

    @Test fun immediateActionsFlushLatestStateAndPreserveCrossTransportFallbackAndTies() = runTest {
        val store = SourceAwareCotStore(Item::source, Item::uid, Item::time, Item::receipt,
            publicationScope = backgroundScope)
        store.upsert(Item(source = "ble"))
        store.upsert(Item())
        store.upsertWithResult(Item(time = 200, position = 2), true)
        store.upsertWithResult(Item(source = "ble", time = 200, position = 3), true)
        assertEquals("ble", store.visibleKey("track")!!.sourceId)
        assertEquals(2, store.snapshot().size)
        // User-initiated/urgent mutations do not opt in to coalescing.
        store.upsert(Item(uid = "emergency", position = 9))
        assertEquals(3, store.visibleItems.value.first().position)
        assertEquals(9, store.visibleItems.value.last().position)
        store.removeSource("ble")
        assertEquals(2, store.visibleItems.value.first().position)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, store.visibleItems.value.first().position)
    }

    @Test fun hiddenSourcePublicationSignalsCompletedSnapshotsWithoutChangingVisibleWinner() = runTest {
        val store = SourceAwareCotStore(Item::source, Item::uid, Item::time, Item::receipt,
            publicationScope = backgroundScope)
        store.upsert(Item(time = 1_000))
        store.upsert(Item(source = "ble"))
        val visible = store.visibleItems.value
        val version = store.publicationVersion.value
        store.upsertWithResult(Item(source = "ble", time = 200), true)
        assertEquals(version, store.publicationVersion.value)
        assertTrue(store.hasPendingProjection(SourceCotKey("ble", "track")))
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(version + 1, store.publicationVersion.value)
        assertSame(visible, store.visibleItems.value)
        assertEquals(200L, store.allItems.value[SourceCotKey("ble", "track")]!!.time)
        assertFalse(store.hasPendingProjection(SourceCotKey("ble", "track")))
    }
}
