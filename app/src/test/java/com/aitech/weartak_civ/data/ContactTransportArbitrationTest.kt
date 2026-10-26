package com.aitech.weartak_civ.data

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class ContactTransportArbitrationTest {
    @Before fun setup() {
        com.aitech.weartak_civ.presentation.MainActivity.device_uid = "test-watch"
    }

    private fun user(source: String, time: Long = 100, received: Long = time) =
        DataRepository.ReceivedUser(
            uid = "same-contact", type = "a-f-G-U-C", team = "Blue",
            title = "Contact", role = "Team Member", timeReceived = time,
            staleTime = Long.MAX_VALUE, location = null, source = source,
            sourceServerId = source, receivedAt = received
        )

    @Test fun equalEventTimeDittoWinsInEitherArrivalOrderAndTakCopyRemainsFallback() = runBlocking {
        for (order in listOf(listOf("server", "ditto:peer"), listOf("ditto:peer", "server"))) {
            val repo = DataRepository(RuntimeEnvironment.getApplication())
            order.forEachIndexed { index, source ->
                repo.upsertReceivedUser(user(source, received = 200L + index))
            }
            assertEquals(1, repo.receivedUserList.value.size)
            assertEquals("ditto:peer", repo.receivedUserList.value.single().sourceServerId)
            @Suppress("UNCHECKED_CAST")
            val store = DataRepository::class.java.getDeclaredField("receivedUserStore").run {
                isAccessible = true
                get(repo) as SourceAwareCotStore<DataRepository.ReceivedUser>
            }
            store.removeSource("ditto:peer")
            assertEquals("server", repo.receivedUserList.value.single().sourceServerId)
        }
    }

    @Test fun newerEventAlwaysWinsAndDuplicateTakArrivalCannotFlipBack() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.upsertReceivedUser(user("ditto:peer"))
        val original = repo.receivedUserList.value
        repo.upsertReceivedUser(user("server", received = 999))
        assertSame(original, repo.receivedUserList.value)
        repo.upsertReceivedUser(user("server", time = 101, received = 1_000))
        assertEquals("server", repo.receivedUserList.value.single().sourceServerId)
        repo.upsertReceivedUser(user("ditto:peer", time = 100, received = 1_001))
        assertEquals(101L, repo.receivedUserList.value.single().timeReceived)
    }

    @Test fun equalTimeCompanionWinsWithoutDiscardingNewerMeshUpdates() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.upsertReceivedUser(user("ditto:peer"))
        repo.upsertReceivedUser(user(COMPANION_USER_SOURCE_ID))
        repo.upsertReceivedUser(user("server", received = 999))
        assertEquals(COMPANION_USER_SOURCE_ID, repo.receivedUserList.value.single().sourceServerId)
        repo.upsertReceivedUser(user("ditto:peer", time = 101))
        assertEquals("ditto:peer", repo.receivedUserList.value.single().sourceServerId)
    }
}
