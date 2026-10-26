package com.aitech.weartak_civ.service

import android.app.Application
import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.aitech.weartak_civ.command.ChatCommandRequest
import com.aitech.weartak_civ.command.NetworkMasterCommandHandler
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NetworkMasterCommandRuntimeTest {
    private lateinit var repo: DataRepository
    private lateinit var restrictions: RestrictionsManager
    private lateinit var handle: suspend (ChatCommandRequest) -> Boolean
    private var sequence = 0
    private val replies = mutableListOf<String>()

    @Before fun setup() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        MainActivity.device_uid = "network-command-test"
        repo = DataRepository(app)
        repo.legacyDataStore().edit { it.clear() }
        repo.storeEnableChatCommands(true)
        repo.storeChatCommandCallsigns(listOf("ALPHA"))
        restrictions = app.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        shadowOf(restrictions).setApplicationRestrictions(Bundle())
        mockkObject(ChatCommandReplies)
        coEvery { ChatCommandReplies.send(any(), any(), any(), any()) } coAnswers {
            replies += arg<String>(3)
            true
        }
        coEvery { ChatCommandReplies.sendConfirmedWrite(any(), any(), any(), any()) } returns true
        handle = ChatCommandRuntime.build(app, repo)
    }

    @After fun cleanup() = unmockkAll()

    private suspend fun command(name: String, source: String = "server") {
        val now = System.currentTimeMillis()
        handle(ChatCommandRequest("sender", "ALPHA", "#weartakcommand $name",
            "id-${sequence++}", now, now, source))
    }

    private fun enabled(tak: Boolean) = booleanPreferencesKey(if (tak) "enable_tak_endpoint" else "enable_multicast")
    private fun marker(tak: Boolean) = booleanPreferencesKey(if (tak) "tak_endpoint_user_choice" else "multicast_user_choice")

    @Test fun allFourCommandsPersistExactLocalChoicesAndAlternateEnableWorks() = runBlocking {
        for (tak in listOf(true, false)) {
            val suffix = if (tak) "takservers" else "multicast"
            for (value in listOf(false, false, true, true)) {
                command("${if (value) "enable" else "disable"}$suffix", "ditto:peer")
                val prefs = DataRepository(RuntimeEnvironment.getApplication()).legacyDataStore().data.first()
                assertEquals(value, prefs[enabled(tak)])
                assertEquals(value, prefs[marker(tak)])
            }
        }
    }

    @Test fun presentPolicyTrueFalseOrEmptyRefusesBothCommandsEvenUnlockedAndRetainsChoice() = runBlocking {
        for (tak in listOf(true, false)) {
            val suffix = if (tak) "takservers" else "multicast"
            for (value in listOf(false, true)) {
                val bundle = Bundle().apply {
                    if (tak) putStringArray("select_data_endpoints", if (value) arrayOf("TAK") else emptyArray())
                    else putBoolean("multicast_service_enabled", value)
                }
                shadowOf(restrictions).setApplicationRestrictions(bundle)
                command("disable$suffix")
                command("enable$suffix")
                assertNull(repo.legacyDataStore().data.first()[marker(tak)])
                assertNull(repo.legacyDataStore().data.first()[enabled(tak)])
                assertTrue(replies.last().contains("administrator policy"))
            }
        }
        coVerify(exactly = 0) { ChatCommandReplies.sendConfirmedWrite(any(), any(), any(), any()) }
    }

    @Test fun malformedPresentPolicyFailsClosedAndMissingRelevantKeyDoesNotBlock() = runBlocking {
        shadowOf(restrictions).setApplicationRestrictions(Bundle().apply {
            putString("select_data_endpoints", "TAK")
            putString("multicast_service_enabled", "false")
        })
        command("disabletakservers")
        command("enablemulticast")
        assertTrue(replies.all { it.contains("invalid administrator policy") })
        assertNull(repo.legacyDataStore().data.first()[marker(true)])
        assertNull(repo.legacyDataStore().data.first()[marker(false)])
        shadowOf(restrictions).setApplicationRestrictions(Bundle().apply { putString("license_key", "unrelated") })
        command("enabletakservers")
        command("enablemulticast")
        assertEquals(true, repo.legacyDataStore().data.first()[marker(true)])
        assertEquals(true, repo.legacyDataStore().data.first()[marker(false)])
    }

    @Test fun managedEffectiveValueAndExistingOppositeLocalChoiceSurviveCommands() = runBlocking {
        for (value in listOf(false, true)) {
            shadowOf(restrictions).setApplicationRestrictions(Bundle())
            command("${if (value) "disable" else "enable"}takservers")
            command("${if (value) "disable" else "enable"}multicast")
            repo.storeManagedNetworkToggles(tak = value, multicast = value)
            shadowOf(restrictions).setApplicationRestrictions(Bundle().apply {
                putStringArray("select_data_endpoints", if (value) arrayOf("TAK") else emptyArray())
                putBoolean("multicast_service_enabled", value)
            })
            for (name in NetworkMasterCommandHandler.COMMANDS) command(name)
            val prefs = repo.legacyDataStore().data.first()
            for (tak in listOf(false, true)) {
                assertEquals(value, prefs[enabled(tak)])
                assertEquals(!value, prefs[marker(tak)])
            }
        }
    }

    @Test fun nullUnknownAndWrongTypedPresentPoliciesFailClosedWithoutChoices() = runBlocking {
        val policies = listOf(
            Bundle().apply { putStringArray("select_data_endpoints", null) },
            Bundle().apply { putStringArray("select_data_endpoints", arrayOf("unknown")) },
            Bundle().apply { putBoolean("select_data_endpoints", false) },
            Bundle().apply { putString("multicast_service_enabled", null) },
            Bundle().apply { putStringArray("multicast_service_enabled", emptyArray()) }
        )
        for (policy in policies) {
            shadowOf(restrictions).setApplicationRestrictions(policy)
            val tak = policy.containsKey("select_data_endpoints")
            for (action in listOf("enable", "disable")) {
                command("$action${if (tak) "takservers" else "multicast"}")
                assertTrue(replies.last().contains("invalid administrator policy"))
                assertNull(repo.legacyDataStore().data.first()[enabled(tak)])
                assertNull(repo.legacyDataStore().data.first()[marker(tak)])
            }
        }
    }

    @Test fun falseOrThrowingWriteCreatesNoMutationOrChoice() = runBlocking {
        for (throws in listOf(false, true)) {
            coEvery { ChatCommandReplies.sendConfirmedWrite(any(), any(), any(), any()) } coAnswers {
                if (throws) throw java.io.IOException("failed write")
                false
            }
            command("disabletakservers")
            command("disablemulticast", "multicast:239.2.3.1:6969")
            assertNull(repo.legacyDataStore().data.first()[enabled(true)])
            assertNull(repo.legacyDataStore().data.first()[marker(true)])
            assertNull(repo.legacyDataStore().data.first()[enabled(false)])
            assertNull(repo.legacyDataStore().data.first()[marker(false)])
        }
    }

    @Test fun lockOrMdmArrivingDuringWarningIsRecheckedInsideAtomicApply() = runBlocking {
        coEvery { ChatCommandReplies.sendConfirmedWrite(any(), any(), any(), any()) } coAnswers {
            repo.storeNetworkPreferencesLocked(true)
            true
        }
        command("disabletakservers")
        assertNull(repo.legacyDataStore().data.first()[marker(true)])
        assertTrue(replies.last().contains("locked"))
        repo.storeNetworkPreferencesLocked(false)
        coEvery { ChatCommandReplies.sendConfirmedWrite(any(), any(), any(), any()) } coAnswers {
            shadowOf(restrictions).setApplicationRestrictions(Bundle().apply {
                putBoolean("multicast_service_enabled", true)
            })
            true
        }
        command("disablemulticast")
        assertNull(repo.legacyDataStore().data.first()[marker(false)])
        assertTrue(replies.last().contains("administrator policy"))
    }

    @Test fun activePhoneManagementRejectsWithoutMarkingChoices() = runBlocking {
        repo.enablePhoneManagedSettings("AA:BB")
        repo.setPhoneManagementSessionActive(true)
        try {
            for (name in listOf("enabletakservers", "disabletakservers", "enablemulticast", "disablemulticast")) {
                command(name)
            }
            assertNull(repo.legacyDataStore().data.first()[marker(true)])
            assertNull(repo.legacyDataStore().data.first()[marker(false)])
            coVerify(exactly = 0) { ChatCommandReplies.sendConfirmedWrite(any(), any(), any(), any()) }
        } finally {
            repo.setPhoneManagementSessionActive(false)
            repo.clearPhoneManagedSettingsForPeer("AA:BB")
        }
    }
}
