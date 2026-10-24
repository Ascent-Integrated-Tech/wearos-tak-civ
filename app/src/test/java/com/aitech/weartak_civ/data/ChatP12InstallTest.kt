package com.aitech.weartak_civ.data

import android.app.Application
import androidx.datastore.preferences.core.edit
import com.aitech.weartak_civ.command.ChatP12Validator
import com.aitech.weartak_civ.data.takserver.TestClientP12
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChatP12InstallTest {
    private lateinit var repo: DataRepository
    private val secrets = InMemoryTakServerSecrets()
    private val firstId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val secondId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val p12 by lazy { ChatP12Validator.validate(TestClientP12.p12(" test password "), " test password ") }

    @Before fun setup() = runBlocking {
        MainActivity.device_uid = "test-watch"
        repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.takServerSecretsOverride = secrets.repository
        repo.legacyDataStore().edit { it.clear() }
        repo.storeTakServerList(listOf(
            InMemoryTakServerSecrets.server("one").copy(serverId = firstId),
            InMemoryTakServerSecrets.server("two").copy(serverId = secondId)))
    }

    @Test fun exactSelectedServerOnlyAndExactPasswordSurviveReadback() = runBlocking {
        val other = repo.awaitTakServerList().last()
        assertTrue(repo.installChatP12(firstId, p12) {})
        val after = repo.awaitTakServerList()
        assertEquals(p12.base64, after.first().p12Cert)
        assertEquals(p12.password, after.first().p12CertPassword)
        assertEquals(other, after.last())
        assertFalse(after.first().toString().contains(p12.password))
        assertFalse(repo.installChatP12("missing", p12) { error("must not apply") })
    }

    @Test fun localApplyFailureAndCancellationRestoreExactSnapshotAndRuntime() = runBlocking {
        val before = repo.awaitTakServerList()
        var calls = 0
        assertFalse(repo.installChatP12(firstId, p12) { if (++calls == 1) error("synthetic failure") })
        assertEquals(2, calls)
        assertEquals(before, repo.awaitTakServerList())
        calls = 0
        assertTrue(runCatching {
            repo.installChatP12(firstId, p12) { if (++calls == 1) throw CancellationException() }
        }.exceptionOrNull() is CancellationException)
        assertEquals(before, repo.awaitTakServerList())
        assertEquals(2, calls)
    }

    @Test fun recoveryFailureIsExplicitAndNeverContainsCredentials() = runBlocking {
        val failure = runCatching { repo.installChatP12(firstId, p12) { error("synthetic failure") } }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("configuration requires attention"))
        assertFalse(failure.toString().contains(p12.password))
    }
}
