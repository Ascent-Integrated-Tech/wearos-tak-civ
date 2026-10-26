package com.aitech.weartak_civ.service

import android.app.Application
import android.location.Location
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.cep.bus.CepBus
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.engine.CepEngine
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.CepPayload
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WeatherLifecycleTest {
    private lateinit var service: WeatherService
    private lateinit var call: Call

    @Before fun setup() {
        val repo = mockk<DataRepository>(relaxed = true)
        every { repo.latestLocation } returns MutableStateFlow(Location("test").apply {
            latitude = 12.345
            longitude = -67.89
        })
        MainActivity.dataRepository = repo
        service = Robolectric.buildService(WeatherService::class.java).get()
        call = mockk(relaxed = true)
        val client = mockk<OkHttpClient>()
        every { client.newCall(any()) } returns call
        WeatherService::class.java.getDeclaredField("httpClient\$delegate").apply {
            isAccessible = true
            set(service, lazyOf(client))
        }
        WeatherService.isRunning.set(true)
        WeatherService.latest = null
        ShadowLog.clear()
    }

    @After fun cleanup() {
        service.onDestroy()
    }

    private fun assertPrivateLogs() {
        val logs = ShadowLog.getLogsForTag("WearTAK-WeatherService")
        for (entry in logs) {
            assertFalse(entry.msg, entry.msg.contains("private-test-key"))
            assertFalse(entry.msg, entry.msg.contains("api.openweathermap.org"))
            assertFalse(entry.msg, entry.msg.contains("12.345"))
            assertFalse(entry.msg, entry.msg.contains("-67.89"))
            assertNull("Weather errors must not attach key-bearing exception chains", entry.throwable)
        }
    }

    @Test fun networkFailureLogsNoUrlKeyCoordinatesOrExceptionChainAndActuallyBacksOff() = runTest {
        every { call.execute() } throws IOException(
            "https://api.openweathermap.org/?appid=private-test-key&lat=12.345&lon=-67.89")
        val loop = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            service.runLoop("private-test-key")
        }
        advanceTimeBy(60_000)
        verify(exactly = 1) { call.execute() }
        assertTrue(ShadowLog.getLogsForTag("WearTAK-WeatherService").any { it.msg.contains("HTTP request failed") })
        assertPrivateLogs()
        loop.cancel()
    }

    @Test fun malformedResponseLogsNoBodyOrExceptionDetails() = runTest {
        every { call.execute() } returns Response.Builder()
            .request(Request.Builder().url("https://example.invalid").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("private-test-key api.openweathermap.org 12.345 -67.89".toResponseBody()).build()
        val loop = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            service.runLoop("private-test-key")
        }
        assertTrue(ShadowLog.getLogsForTag("WearTAK-WeatherService").any { it.msg.contains("JSON parse failed") })
        assertPrivateLogs()
        assertNull(WeatherService.latest)
        loop.cancel()
    }

    @Test fun successRetainsWeatherOutputsWithoutPrivateRequestLogging() = runTest {
        val engine = mockk<CepEngine>(relaxed = true)
        mockkObject(CepProvider)
        every { CepProvider.getOrCreateEngine(any()) } returns engine
        val events = mutableListOf<CepEvent<out CepPayload>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            CepBus.events.collect { events += it }
        }
        every { call.execute() } returns Response.Builder()
            .request(Request.Builder().url("https://example.invalid").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("""{"current":{"temp":9,"wind_speed":8,"wind_deg":90,"rain":{"1h":1}}}"""
                .toResponseBody()).build()
        try {
            val loop = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                service.runLoop("private-test-key")
            }
            assertEquals(9.0, WeatherService.latest?.tempC)
            assertEquals(true, WeatherService.latest?.wet)
            assertEquals(listOf(Topics.WIND, Topics.WATER), events.map { it.topic })
            verify(exactly = 1) { engine.ingest("ambient.temp_c", 9.0, any()) }
            assertPrivateLogs()
            service.onDestroy()
            assertNull(WeatherService.latest)
            loop.cancel()
        } finally { unmockkObject(CepProvider) }
    }

    @Test fun destroyCancelsInFlightCallAndCannotPublishCompletedSnapshot() = runTest {
        every { call.execute() } answers {
            service.onDestroy()
            Response.Builder().request(Request.Builder().url("https://example.invalid").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"current":{"temp":9,"wind_speed":8}}""".toResponseBody()).build()
        }
        service.runLoop("private-test-key")
        verify(exactly = 1) { call.cancel() }
        assertFalse(WeatherService.isRunning.get())
        assertNull(WeatherService.latest)
    }

    @Test fun directStartWithoutKeyStopsInsteadOfLeavingForegroundServiceAlive() {
        assumeTrue(WeatherService.OpenWeatherConfig.getApiKey().isNullOrBlank())
        service.onCreate()
        assertTrue(shadowOf(service).isStoppedBySelf)
        verify(exactly = 0) { call.execute() }
    }
}
