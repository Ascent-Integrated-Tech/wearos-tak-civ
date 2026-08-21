package com.aitech.weartak_civ.cot

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class CotTimeOffset(
    val seconds: Int = 0,
    val minutes: Int = 0,
    val hours: Int = 0,
    val days: Int = 0,
    val years: Int = 0,
)

data class CotEventTime(
    val time: Long,
    val start: Long,
    val stale: Long,
    val timeCot: String,
    val startCot: String,
    val staleCot: String,
)

object CotEventTimeFactory {
    fun now(
        startOffset: CotTimeOffset = CotTimeOffset(),
        staleOffset: CotTimeOffset = CotTimeOffset(minutes = 5),
    ): CotEventTime = at(System.currentTimeMillis(), startOffset, staleOffset)

    internal fun at(
        nowMillis: Long,
        startOffset: CotTimeOffset = CotTimeOffset(),
        staleOffset: CotTimeOffset = CotTimeOffset(minutes = 5),
    ): CotEventTime {
        val base = Calendar.getInstance(UTC).apply { timeInMillis = nowMillis }
        val time = base.clone() as Calendar
        val start = (base.clone() as Calendar).applyOffset(startOffset)
        val stale = (base.clone() as Calendar).applyOffset(staleOffset)
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = UTC
        }

        return CotEventTime(
            time = time.timeInMillis,
            start = start.timeInMillis,
            stale = stale.timeInMillis,
            timeCot = formatter.format(time.time),
            startCot = formatter.format(start.time),
            staleCot = formatter.format(stale.time),
        )
    }

    private fun Calendar.applyOffset(offset: CotTimeOffset): Calendar = apply {
        if (offset.seconds != 0) add(Calendar.SECOND, offset.seconds)
        if (offset.minutes != 0) add(Calendar.MINUTE, offset.minutes)
        if (offset.hours != 0) add(Calendar.HOUR_OF_DAY, offset.hours)
        if (offset.days != 0) add(Calendar.DAY_OF_YEAR, offset.days)
        if (offset.years != 0) add(Calendar.YEAR, offset.years)
    }

    private val UTC = TimeZone.getTimeZone("UTC")
}
