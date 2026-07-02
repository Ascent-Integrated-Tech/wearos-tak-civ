package com.aitech.weartak_civ.cep.model

import com.aitech.weartak_civ.cep.model.payloads.*

object TopicRegistry {
    private val map: Map<String, Class<out CepPayload>> = mapOf(
        Topics.HEART_RATE to HeartRatePayload::class.java,
        Topics.HRV       to HrvPayload::class.java,
        Topics.SKIN_TEMP to SkinTempPayload::class.java,
        Topics.AMBIENT   to AmbientPayload::class.java,
        Topics.ACCEL     to AccelPayload::class.java,
        Topics.ACTIVITY  to ActivityPayload::class.java,
        Topics.SPO2      to Spo2Payload::class.java,
        Topics.SPO2_TIER_EVENT to Spo2TierEventPayload::class.java,
        Topics.IMMERSION to ImmersionPayload::class.java,
        Topics.SLEEP     to SleepPayload::class.java,
        Topics.OFFBODY   to OffBodyPayload::class.java,
        Topics.WIND      to WindPayload::class.java,
        Topics.GYRO      to GyroPayload::class.java,
        Topics.PPG       to PpgPayload::class.java,
        Topics.HUMIDITY  to HumidityPayload::class.java,
        Topics.WATER     to WaterPayload::class.java,
        Topics.LIGHT     to LightPayload::class.java,
    )
    fun payloadClass(topic: String): Class<out CepPayload>? = map[topic]
}


