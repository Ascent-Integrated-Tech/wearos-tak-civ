package com.aitech.weartak_civ.data.takserver

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import okhttp3.Call
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test

class TakHttpSessionTest {
    @Test fun invalidationCancelsOnlyItsCallsAndRejectsDelayedRequests() {
        val old = TakHttpSession { true }
        val replacement = TakHttpSession { true }
        val pending = mockk<Call>(relaxed = true)
        val newCall = mockk<Call>(relaxed = true)
        old.register(pending)
        replacement.register(newCall)
        old.cancel()
        assertTrue(TakConnectionCloser.awaitPendingCloses())
        verify(exactly = 1) { pending.cancel() }
        verify(exactly = 0) { newCall.cancel() }
        val delayed = mockk<Call>(relaxed = true)
        assertThrows(IOException::class.java) { old.register(delayed) }
        verify(exactly = 1) { delayed.cancel() }
    }

    @Test fun liveGateRejectsBeforeExecutingAndResponseConsumptionRemainsCancellable() {
        var allowed = false
        val session = TakHttpSession { allowed }
        val rejected = mockk<Call>(relaxed = true)
        assertThrows(IOException::class.java) { rejected.withSessionResponse(session) {} }
        verify(exactly = 0) { rejected.execute() }
        allowed = true
        val call = mockk<Call>(relaxed = true)
        every { call.execute() } returns Response.Builder()
            .request(Request.Builder().url("https://example.invalid").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(okhttp3.ResponseBody.create(null, "response")).build()
        assertThrows(IOException::class.java) {
            call.withSessionResponse(session) { session.cancel() }
        }
        assertTrue(TakConnectionCloser.awaitPendingCloses())
        verify(exactly = 1) { call.cancel() }
    }

    @Test fun cancelNeverClosesTransportOnCallingThreadAndCloserIsSerialized() {
        val caller = Thread.currentThread()
        val call = mockk<Call>(relaxed = true)
        var cancelThread: Thread? = null
        every { call.cancel() } answers { cancelThread = Thread.currentThread() }
        val session = TakHttpSession { true }
        session.register(call)
        val release = java.util.concurrent.CountDownLatch(1)
        TakConnectionCloser.execute { release.await() }
        session.cancel()
        assertThrows(IOException::class.java) { session.ensureCurrent() }
        verify(exactly = 0) { call.cancel() }
        assertFalse(TakConnectionCloser.awaitPendingCloses(timeoutMs = 50))
        release.countDown()
        assertTrue(TakConnectionCloser.awaitPendingCloses())
        assertNotSame(caller, cancelThread)
        assertEquals(TakConnectionCloser.THREAD_NAME, cancelThread?.name)
    }

    @Test fun completedAndFailedCallsAreRemoved() {
        val session = TakHttpSession { true }
        val call = mockk<Call>(relaxed = true)
        every { call.execute() } throws IOException("offline")
        assertThrows(IOException::class.java) { call.withSessionResponse(session) {} }
        session.cancel()
        assertTrue(TakConnectionCloser.awaitPendingCloses())
        verify(exactly = 0) { call.cancel() }
    }
}
