package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.cep.engine.ProfileStore
import com.aitech.weartak_civ.cep.model.payloads.Spo2TierEventPayload
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

class Spo2SequencerTest {

    @Test fun syncProfileRunsBeforeTierPublish() {
        val profile = mockk<ProfileStore>()
        every { profile.userRiskProfile() } returns "trauma"
        every { profile.spo2Baseline(any(), any()) } returns 95.0
        every { profile.spo2BaselineProvisional(any(), any()) } returns true
        every { profile.updateSpo2BaselinesEma(any(), any(), any(), any(), any()) } returns true

        val events = mutableListOf<String>()
        val ops = object : Spo2EngineOps {
            override fun ingest(path: String, value: Double, ts: Long) = Unit
            override fun syncProfile() { events += "sync" }
            override fun publish(payload: Spo2TierEventPayload) { events += "publish" }
        }
        val sequencer = Spo2Sequencer(profile = profile)

        sequencer.onValid(
            ops = ops,
            pct = 89.0,
            ts = 1_000L,
            activityState = "STILL",
            altitudeBucket = 0,
            motionOk = true,
            ppgRedSustainedMs = 30_000L,
            statusOk = true,
        )
        sequencer.onValid(
            ops = ops,
            pct = 89.0,
            ts = 2_000L,
            activityState = "STILL",
            altitudeBucket = 0,
            motionOk = true,
            ppgRedSustainedMs = 30_000L,
            statusOk = true,
        )

        assertTrue(events.indexOf("sync") >= 0)
        assertTrue(events.indexOf("publish") > events.indexOf("sync"))
    }
}
