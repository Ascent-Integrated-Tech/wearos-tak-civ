package com.aitech.weartak_civ.data

import com.aitech.weartak_civ.Utils
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class PhysioSample<T>(val value: T, val measuredAtMillis: Long)
data class SavedMoppStatus(val status: MoppStatus, val savedAtMillis: Long?)

data class SelfStatusPolicy(
    val moppEnabled: Boolean,
    val savedMopp: SavedMoppStatus?,
    val monitoring: Boolean,
    val heartRateEnabled: Boolean,
    val skinTemperatureEnabled: Boolean,
    val worn: Boolean,
    val age: Int,
    val restingHeartRate: Int
)

data class SelfStatusInputs(
    val policy: SelfStatusPolicy,
    val heartRate: PhysioSample<Int>?,
    val skinTemperature: PhysioSample<Float>?
)

internal data class SelfStatusPhysio(
    val hr: Int? = null, val measuredAt: Long? = null,
    val sktF: Float? = null, val sktFMeasuredAt: Long? = null,
    val exert: Double? = null, val exertMeasuredAt: Long? = null
)

internal data class SelfStatusSnapshot(val ppe: SavedMoppStatus?, val physio: SelfStatusPhysio?)

internal fun selfStatusSnapshot(input: SelfStatusInputs, now: Long): SelfStatusSnapshot {
    fun fresh(time: Long) = time > 0 && time >= now - 120_000L && time <= now + 30_000L
    val policy = input.policy
    val mayMeasure = policy.monitoring && policy.worn
    val hr = input.heartRate?.takeIf {
        mayMeasure && policy.heartRateEnabled && it.value in 20..250 && fresh(it.measuredAtMillis)
    }
    val skin = input.skinTemperature?.takeIf {
        mayMeasure && policy.skinTemperatureEnabled && it.value.isFinite() &&
            it.value in 59f..113f && fresh(it.measuredAtMillis)
    }
    val exertion = hr?.let {
        Utils.PhysioUtils.calculateExertion(it.value, policy.age, policy.restingHeartRate)
    }?.takeIf { it.isFinite() && it in 0.0..1.0 }
    return SelfStatusSnapshot(
        ppe = policy.savedMopp.takeIf { policy.moppEnabled },
        physio = if (hr == null && skin == null) null else SelfStatusPhysio(
            hr?.value, hr?.measuredAtMillis, skin?.value, skin?.measuredAtMillis,
            exertion, hr?.measuredAtMillis.takeIf { exertion != null }
        )
    )
}

internal class CompanionSelfStatus {
    private var generation: Long? = null
    private var session: String? = null
    private var sequence = 0L
    private var lastSentAt: Long? = null
    private var lastPolicy: SelfStatusPolicy? = null
    private var lastSnapshot: SelfStatusSnapshot? = null

    @Synchronized fun reset() {
        generation = null
        session = null
        sequence = 0
        lastSentAt = null
        lastPolicy = null
        lastSnapshot = null
    }

    @Synchronized fun negotiate(currentGeneration: Long) {
        if (generation == currentGeneration) return
        reset()
        generation = currentGeneration
        session = UUID.randomUUID().toString()
    }

    @Synchronized fun active(currentGeneration: Long): Boolean =
        generation == currentGeneration && lastSentAt != null

    @Synchronized fun acceptsSession(currentGeneration: Long, token: String?): Boolean =
        generation == currentGeneration && session != null && session == token

    @Synchronized fun sendIfDue(
        currentGeneration: Long,
        deviceUid: String,
        input: SelfStatusInputs,
        nowMillis: Long,
        elapsedMillis: Long,
        send: (String) -> Boolean
    ): Boolean? {
        if (generation != currentGeneration) return null
        require(deviceUid.isNotBlank() && deviceUid.length <= 512 && deviceUid.none(Char::isISOControl))
        val snapshot = selfStatusSnapshot(input, nowMillis)
        val previous = lastSnapshot
        val removed = previous != null && (
            (previous.ppe != null && snapshot.ppe == null) ||
                (previous.physio?.hr != null && snapshot.physio?.hr == null) ||
                (previous.physio?.sktF != null && snapshot.physio?.sktF == null) ||
                (previous.physio?.exert != null && snapshot.physio?.exert == null))
        val since = lastSentAt?.let { elapsedMillis - it }
        val urgent = since == null || lastPolicy != input.policy || removed
        if (!urgent && since != null && since < 55_000 &&
            (snapshot == previous || since < 15_000)) return null

        fun iso(time: Long) = Instant.ofEpochMilli(time).toString()
        val ppe = snapshot.ppe?.let {
            JSONObject().put("level", if (it.status == MoppStatus.NONE) "None" else "MOPP ${it.status.key}")
                .put("savedAt", it.savedAtMillis?.let(::iso) ?: JSONObject.NULL)
        }
        val physio = snapshot.physio?.let {
            JSONObject().apply {
                it.hr?.let { hr -> put("hr", hr); put("measuredAt", iso(requireNotNull(it.measuredAt))) }
                it.sktF?.let { skin -> put("sktF", skin.toDouble()); put("sktFMeasuredAt", iso(requireNotNull(it.sktFMeasuredAt))) }
                it.exert?.let { exert -> put("exert", exert); put("exertMeasuredAt", iso(requireNotNull(it.exertMeasuredAt))) }
            }
        }
        val payload = JSONObject().put("version", 1).put("session", session).put("seq", sequence)
            .put("deviceUid", deviceUid).put("model", "WEAROS")
            .put("ppe", ppe ?: JSONObject.NULL).put("physio", physio ?: JSONObject.NULL)
        val envelope = JSONObject().put("vers", 1).put("msgType", "self_status")
            .put("msgUid", deviceUid).put("src", "wearos").put("payload", payload).toString()
        if (!send(envelope)) return false
        sequence++
        lastSentAt = elapsedMillis
        lastPolicy = input.policy
        lastSnapshot = snapshot
        return true
    }
}
