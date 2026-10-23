package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.cot.TakServerCotEnvelope
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import javax.net.ssl.SSLSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TakDataPackageSessionTest {
    private val socket = mockk<SSLSocket> {
        every { isConnected } returns true
        every { isClosed } returns false
    }
    private val detail = TAKConnectService.TakServerDetail(
        "server", true, "Server", "tak.example", 8089, false, "", "", null, null
    )
    private val attempt = TAKConnectService.ConnectionAttempt(7).apply { socket = this@TakDataPackageSessionTest.socket }
    private val envelope = TakServerCotEnvelope("server", "tak.example", 1, "", attempt.sessionId)

    init {
        detail.runtime.generation = 7
        detail.runtime.currentAttempt = attempt
        detail.secureSocket = socket
    }

    @Test fun activeSocketSessionIsAcceptedDuringDisplayStatusTransition() {
        listOf(0, 1, 2, 3, 4).forEach { status ->
            detail.connectionStatus.set(status)
            assertSame(attempt, requireDataPackageSession(detail, envelope, false))
        }
    }

    @Test fun oldReaderOfferCannotBorrowNewSessionEvenWhenStatusSaysConnected() {
        detail.connectionStatus.set(4)
        detail.runtime.currentAttempt = TAKConnectService.ConnectionAttempt(8).apply { socket = this@TakDataPackageSessionTest.socket }
        detail.runtime.generation = 8
        assertThrows(IOException::class.java) { requireDataPackageSession(detail, envelope, false) }
    }

    @Test fun replacedSocketAndGenerationAreRejected() {
        detail.secureSocket = mockk()
        assertThrows(IOException::class.java) { requireDataPackageSession(detail, envelope, false) }
        detail.secureSocket = socket
        detail.runtime.generation++
        assertThrows(IOException::class.java) { requireDataPackageSession(detail, envelope, false) }
    }

    @Test fun disabledStoppedTerminatedOrClosedSourceIsRejected() {
        detail.isEnabled = false
        assertThrows(IOException::class.java) { requireDataPackageSession(detail, envelope, false) }
        detail.isEnabled = true
        assertThrows(IOException::class.java) { requireDataPackageSession(detail, envelope, true) }
        detail.runtime.terminated = true
        assertThrows(IOException::class.java) { requireDataPackageSession(detail, envelope, false) }
        detail.runtime.terminated = false
        every { socket.isClosed } returns true
        assertThrows(IOException::class.java) { requireDataPackageSession(detail, envelope, false) }
        every { socket.isClosed } returns false
        every { socket.isConnected } returns false
        assertThrows(IOException::class.java) { requireDataPackageSession(detail, envelope, false) }
    }

    @Test fun spoofedSourceOrNonSocketEnvelopeCannotAcquireAuthenticatedSession() {
        listOf(
            envelope.copy(sourceServerId = "other"),
            envelope.copy(sourceServerAddress = "other.example"),
            envelope.copy(sourceSessionId = null)
        ).forEach { wrong ->
            assertThrows(IOException::class.java) { requireDataPackageSession(detail, wrong, false) }
        }
    }
}
