package com.aitech.weartak_civ.service

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.HealthEvent
import com.aitech.weartak_civ.CAT_FALL_DETECTED
import com.aitech.weartak_civ.DESC_GOOGLE_SDK_FALL_DETECTED
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.util.Collections

/**
 * Regression for the p137 cold-start crash: the system can (re)start ActivityRecognitionService
 * before any MainActivity has initialized MainActivity.dataRepository. The service must use the
 * application-owned repository instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ActivityRecognitionServiceColdStartTest {
    private val repo = mockk<DataRepository>(relaxed = true)
    private val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var service: ActivityRecognitionService

    @Before
    fun setUp() {
        clearMainActivityRepository()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }

        val app = mockk<WearTakApplication>(relaxed = true)
        every { app.repo } returns repo
        val base = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = app
        }
        service = ActivityRecognitionService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(service, base)
    }

    @After
    fun tearDown() {
        service.onDestroy()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        clearMainActivityRepository()
    }

    @Test
    fun coldStartAndStickyRestartWithoutActivityRepositoryUseApplicationRepository() {
        service.onCreate()
        assertTrue(ActivityRecognitionService.isRunning.get())
        assertEquals(Service.START_STICKY, service.onStartCommand(null, Service.START_FLAG_REDELIVERY, 1))

        val result = ActivityRecognitionResult(DetectedActivity(DetectedActivity.WALKING, 80), 1_000L, 1_000L)
        val intent = Intent("com.aitech.weartak_civ.ACTION_RECOGNITION")
            .putExtra("com.google.android.location.internal.EXTRA_ACTIVITY_RESULT", result)
        assertTrue(ActivityRecognitionResult.hasResult(intent))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 2))

        verifyRepo { repo.storeLatestActivityRecognized(ActivityRecognized.WALKING) }
        verifyRepo { repo.storeLatestActivityConfidence(80) }

        val callback = passiveCallback()
        callback.onNewDataPointsReceived(
            DataPointContainer(listOf(IntervalDataPoint(DataType.STEPS_DAILY, 1_234L, Duration.ZERO, Duration.ofMinutes(1))))
        )
        verifyRepo {
            repo.updateDailyMetrics(any(), 1_234L, null, null, null)
        }

        callback.onHealthEventReceived(
            HealthEvent(HealthEvent.Type.FALL_DETECTED, Instant.now(), DataPointContainer(emptyList()))
        )
        verifyRepo {
            repo.addPhysioAndAutoGenAlertRequest(
                any(), CAT_FALL_DETECTED, DESC_GOOGLE_SDK_FALL_DETECTED, 1, true, any()
            )
        }

        assertTrue("Unexpected uncaught exceptions: $uncaught", uncaught.isEmpty())
    }

    private fun verifyRepo(block: suspend io.mockk.MockKVerificationScope.() -> Unit) {
        try {
            coVerify(timeout = 3_000, verifyBlock = block)
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}; uncaught service exceptions: $uncaught", uncaught.firstOrNull() ?: e)
        }
    }

    private fun passiveCallback(): PassiveListenerCallback =
        ActivityRecognitionService::class.java.getDeclaredField("passiveDailyListenerCallback")
            .apply { isAccessible = true }.get(service) as PassiveListenerCallback

    private fun clearMainActivityRepository() {
        MainActivity::class.java.getDeclaredField("dataRepository")
            .apply { isAccessible = true }.set(null, null)
    }
}
