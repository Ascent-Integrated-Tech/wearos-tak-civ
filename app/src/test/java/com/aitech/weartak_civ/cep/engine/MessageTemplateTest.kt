package com.aitech.weartak_civ.cep.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTemplateTest {

    @Test fun extractsKeysAndStripsPlaceholders() {
        val template = "SpO2 WARN: {spo2.pct}% <= baseline-4% (baseline={profile.spo2_active_baseline_pct}%)."

        assertEquals(
            listOf("spo2.pct", "profile.spo2_active_baseline_pct"),
            MessageTemplate.extractKeys(template)
        )
        assertEquals(
            "SpO2 WARN: % <= baseline-4% (baseline=%).",
            MessageTemplate.strip(template)
        )
    }
}
