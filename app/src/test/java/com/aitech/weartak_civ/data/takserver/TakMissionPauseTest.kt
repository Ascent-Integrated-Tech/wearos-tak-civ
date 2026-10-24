package com.aitech.weartak_civ.data.takserver

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class TakMissionPauseTest {
    @Test fun allRequestsAreBlockedWhilePausedAndExistingClientCanResume() = runBlocking {
        var enabled = false
        val client = mockk<OkHttpClient>()
        val api = TakMissionApi("tak.example.invalid", SSLContext.getDefault(),
            mockk<X509TrustManager>(), "server", "Test", client, { enabled })
        val operations: List<suspend () -> Unit> = listOf(
            { api.getAllMissions(); Unit },
            { api.getOneMission("mission"); Unit },
            { api.subscribeToMission("mission", "watch") },
            { api.unsubscribeFromMission("mission", "watch") },
            { api.getMissionChanges("mission"); Unit },
            { api.getMissionCot("mission"); Unit },
            { api.addMissionContent("mission", listOf("item")) }
        )
        operations.forEach { operation ->
            try { operation(); fail("Paused request must not execute") }
            catch (_: TakMissionPausedException) { }
        }
        verify(exactly = 0) { client.newCall(any()) }
        val call = mockk<Call>()
        every { client.newCall(any()) } returns call
        every { call.execute() } returns Response.Builder()
            .request(Request.Builder().url("https://tak.example.invalid").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("<events/>".toResponseBody()).build()
        enabled = true
        assertEquals("<events/>", api.getMissionCot("mission"))
        verify(exactly = 1) { client.newCall(any()) }
    }
}
