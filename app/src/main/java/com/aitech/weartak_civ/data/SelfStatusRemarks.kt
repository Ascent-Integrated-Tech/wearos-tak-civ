package com.aitech.weartak_civ.data

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val remarksClock = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC)

// Matches Companion WatchSelfStatus.Contribution.remarksLine, including actual sample clocks.
internal fun SelfStatusSnapshot.remarksLine(now: Long): String {
    fun fresh(time: Long?) = time != null && time > now - 120_000L && time <= now + 30_000L
    fun clock(time: Long) = remarksClock.format(Instant.ofEpochMilli(time)) + "Z"
    return buildList {
        ppe?.status?.takeIf { it != MoppStatus.NONE }?.let { add("MOPP${it.key}") }
        physio?.let { p ->
            p.hr?.takeIf { it in 20..250 && fresh(p.measuredAt) }?.let {
                add("HR: $it bpm @ ${clock(requireNotNull(p.measuredAt))}")
            }
            p.sktF?.takeIf { it.isFinite() && it in 59f..113f && fresh(p.sktFMeasuredAt) }?.let {
                add("Skin: ${String.format(Locale.US, "%.1f", it.toDouble())}F @ ${clock(requireNotNull(p.sktFMeasuredAt))}")
            }
            p.exert?.takeIf { it.isFinite() && it in 0.0..1.0 && fresh(p.exertMeasuredAt) }?.let {
                add("Exert: ${Math.round(it * 100)}% @ ${clock(requireNotNull(p.exertMeasuredAt))}")
            }
        }
    }.joinToString("; ")
}
