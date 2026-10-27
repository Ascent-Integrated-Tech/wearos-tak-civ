package com.aitech.weartak_civ.service

import android.location.Location
import com.aitech.weartak_civ.data.DataRepository
import kotlin.math.abs

internal class DittoTrackPublicationGate {
    data class Sample(
        val location: DataRepository.LocationSnapshot,
        val battery: Int,
        val identity: List<String>,
        val remarks: String,
        val physio: DataRepository.PhysioData?,
        val intervalSeconds: Int,
    )

    private var last: Sample? = null
    private var lastElapsed = 0L

    fun reset() { last = null }

    fun due(sample: Sample, elapsed: Long): Boolean {
        val previous = last ?: return true
        if (sample.intervalSeconds != previous.intervalSeconds ||
            elapsed - lastElapsed >= sample.intervalSeconds.coerceAtLeast(1) * 1_000L ||
            elapsed < lastElapsed) return true
        val distance = FloatArray(1)
        Location.distanceBetween(previous.location.lat, previous.location.lon,
            sample.location.lat, sample.location.lon, distance)
        if (StationaryMotionPolicy.moved(sample.location.ce?.toDouble(), null, null,
                distance[0].toDouble(), previous.location.ce?.toDouble())) return true
        return sample.identity != previous.identity ||
            stableRemarks(sample.remarks) != stableRemarks(previous.remarks) ||
            changed(sample.battery.toDouble(), previous.battery.toDouble(), 5.0) ||
            changed(sample.physio?.heartRateBpm?.toDouble(), previous.physio?.heartRateBpm?.toDouble(), 5.0) ||
            changed(sample.physio?.skinTempF?.toDouble(), previous.physio?.skinTempF?.toDouble(), 0.5) ||
            changed(sample.physio?.exertion, previous.physio?.exertion, 0.05)
    }

    fun published(sample: Sample, elapsed: Long) {
        last = sample
        lastElapsed = elapsed
    }

    private fun changed(a: Double?, b: Double?, threshold: Double): Boolean =
        if (a == null || b == null) a != b else abs(a - b) + 1e-9 >= threshold

    // Sample clocks and small numeric jitter in generated remarks are not new tactical status.
    private fun stableRemarks(remarks: String) = remarks.split("; ").filterNot {
        it.startsWith("HR: ") || it.startsWith("Skin: ") || it.startsWith("Exert: ")
    }
}
