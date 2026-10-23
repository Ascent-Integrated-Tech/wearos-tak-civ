package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.cot.StandardEmergencyType
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.device_uid
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CompanionPhoneEmergencyTest {
    private val repository = mockk<BloodhoundRepository>()
    private val service = CompanionAppService().also {
        val cap = mockk<com.aitech.weartak_civ.data.CompanionAppRepository>(relaxed = true)
        io.mockk.every { cap.gateway } returns com.aitech.weartak_civ.cot.CompanionGatewaySession()
        CompanionAppService::class.java.getDeclaredField("capRepo\$delegate").apply {
            isAccessible = true
            set(it, lazyOf(cap))
        }
        CompanionAppService::class.java.getDeclaredField("bhRepo\$delegate").apply {
            isAccessible = true
            set(it, lazy { repository })
        }
    }

    init {
        device_uid = "watch-self"
        coEvery { repository.applyEmergencyEvent(any(), any(), any(), any(), any()) } returns true
    }

    @After
    fun stopServiceCoroutines() {
        CompanionAppService::class.java.getDeclaredField("serviceJob").apply {
            isAccessible = true
            (get(service) as Job).cancel()
        }
    }

    @Test
    fun standardPhoneAlertsUseExistingEmergencyIngestion() {
        StandardEmergencyType.entries.forEach { type ->
            val uid = "phone-${type.name}"
            receive(envelope(cot(uid, type.cotType)))
            coVerify(timeout = 5_000, exactly = 1) {
                repository.applyEmergencyEvent(
                    match {
                        it.uid == uid && it.type == type.cotType && !it.cancelled &&
                            it.senderUid == "remote-sender" && it.senderCallsign == "Phone & Team" &&
                            it.point?.lat == 38.0 && it.point?.lon == -77.0
                    },
                    "Companion ATAK",
                    "companion-phone",
                    any(),
                    device_uid
                )
            }
        }
    }

    @Test
    fun cancellationRetainsOriginalUidAndUsesSameSource() {
        receive(envelope(cot("phone-alert", "b-a-o-can", cancelled = true), key = "msgType"))
        coVerify(timeout = 5_000, exactly = 1) {
            repository.applyEmergencyEvent(
                match { it.uid == "phone-alert" && it.cancelled },
                "Companion ATAK", "companion-phone", any(), device_uid
            )
        }
    }

    @Test
    fun genericPhoneEmergencyPreservesEventTimes() {
        val now = Instant.now().toEpochMilli()
        receive(envelope(cot("phone-generic", "b-a-o", time = now)))
        coVerify(timeout = 5_000, exactly = 1) {
            repository.applyEmergencyEvent(
                withArg {
                    assertEquals(now, it.time)
                    assertEquals(now, it.start)
                    assertEquals(now + 300_000L, it.stale)
                },
                "Companion ATAK", "companion-phone",
                withArg { assertTrue(it >= now) },
                device_uid
            )
        }
    }

    @Test
    fun malformedMissingAndNonEmergencyPayloadsAreNotIngested() {
        receive("""{"msg_type":"phone_emergency_cot"}""")
        receive(envelope(""))
        receive(envelope("<event"))
        receive(envelope(cot("marker", "b-m-p-s-m")))
        receive(envelope(cot("invalid", "b-a-o").replace("lat='38.0'", "lat='999'")))
        receive(envelope(cot("ignored", "b-a-o"), type = "unrecognized"))
        coVerify(exactly = 0) {
            repository.applyEmergencyEvent(any(), any(), any(), any(), any())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun asynchronousFailureIsReportedAndDoesNotStopLaterAlerts() {
        val scheduler = TestCoroutineScheduler()
        val job = SupervisorJob()
        CompanionAppService::class.java.getDeclaredField("serviceScope").apply {
            isAccessible = true
            set(service, CoroutineScope(job + StandardTestDispatcher(scheduler)))
        }
        val failure = IOException("test persistence failure")
        coEvery {
            repository.applyEmergencyEvent(match { it.uid == "failed" }, any(), any(), any(), any())
        } throws failure
        ShadowLog.clear()
        try {
            receive(envelope(cot("failed", "b-a-o")))
            scheduler.runCurrent()
            assertTrue(ShadowLog.getLogs().any {
                it.msg == "Failed to process phone emergency uid=failed" && it.throwable === failure
            })
            assertTrue(job.isActive)
            receive(envelope(cot("next", "b-a-o")))
            scheduler.runCurrent()
            coVerify(exactly = 1) {
                repository.applyEmergencyEvent(match { it.uid == "next" }, any(), any(), any(), any())
            }
        } finally {
            job.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun coroutineCancellationIsNotReportedAsIngestionFailure() {
        val scheduler = TestCoroutineScheduler()
        val job = SupervisorJob()
        CompanionAppService::class.java.getDeclaredField("serviceScope").apply {
            isAccessible = true
            set(service, CoroutineScope(job + StandardTestDispatcher(scheduler)))
        }
        coEvery { repository.applyEmergencyEvent(any(), any(), any(), any(), any()) } throws
            CancellationException("test cancellation")
        ShadowLog.clear()
        try {
            receive(envelope(cot("cancelled-job", "b-a-o")))
            scheduler.runCurrent()
            assertTrue(ShadowLog.getLogs().none {
                it.msg.startsWith("Failed to process phone emergency")
            })
        } finally {
            job.cancel()
        }
    }

    private fun receive(json: String) {
        CompanionAppService::class.java.getDeclaredMethod("handleCompanionLine", String::class.java).apply {
            isAccessible = true
            invoke(service, json)
        }
    }

    private fun envelope(xml: String, key: String = "msg_type", type: String = "phone_emergency_cot"): String =
        JSONObject().put(key, type).put("payload", JSONObject().put("cot", xml)).toString()

    private fun cot(
        uid: String,
        type: String,
        cancelled: Boolean = false,
        time: Long = Instant.now().toEpochMilli()
    ): String = """
        <event version='2.0' uid='$uid' type='$type'
            time='${Instant.ofEpochMilli(time)}' start='${Instant.ofEpochMilli(time)}'
            stale='${Instant.ofEpochMilli(time + 300_000L)}'>
            <point lat='38.0' lon='-77.0' hae='0' ce='10' le='10'/>
            <detail>
                <link uid='remote-sender' relation='p-p'/>
                <emergency cancel='$cancelled'>Phone &amp; Team</emergency>
            </detail>
        </event>
    """.trimIndent()
}
