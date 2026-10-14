package com.aitech.weartak_civ.service

import android.content.Context
import android.content.RestrictionsManager
import com.aitech.weartak_civ.DEFAULT_TAK_SERVER_KEEPALIVE_SECONDS
import com.aitech.weartak_civ.cot.CotEventTimeFactory
import com.aitech.weartak_civ.cot.CotTimeOffset

/**
 * Receive-silence keepalive for TAK Server streams, modeled on ATAK commo's stream monitor
 * (ping `t-x-c-t` after a quiet period, reset the link if nothing comes back).
 *
 * Pinging only on silence keeps the watch off the air while traffic is flowing, gives the
 * server's `reportTimeoutSeconds` reaper a bounded quietest-client interval, and lets the
 * watch detect a half-open socket that the OS has not reported as dead.
 */
class TakServerKeepalive(silenceSeconds: Int) {
    enum class Action { NONE, PING, RECONNECT }

    val silenceMs: Long = silenceSeconds.coerceAtLeast(0) * 1000L
    val enabled: Boolean = silenceMs > 0

    fun next(nowMs: Long, lastRxMs: Long, lastPingMs: Long): Action {
        if (!enabled) return Action.NONE
        val silence = nowMs - lastRxMs
        if (silence >= silenceMs + RESPONSE_TIMEOUT_MS) return Action.RECONNECT
        if (silence < silenceMs) return Action.NONE
        val pingedSinceRx = lastPingMs > lastRxMs
        return if (!pingedSinceRx || nowMs - lastPingMs >= PING_REPEAT_MS) Action.PING else Action.NONE
    }

    companion object {
        const val RESTRICTION_KEY = "tak_server_keepalive_seconds"
        const val MIN_SILENCE_SECONDS = 15
        const val CHECK_INTERVAL_MS = 5_000L
        const val PING_REPEAT_MS = 10_000L
        const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val PING_STALE_SECONDS = 20

        /** Managed-config value in seconds; 0 disables, values below the floor are raised to it. */
        fun normalize(seconds: Int): Int = when {
            seconds <= 0 -> 0
            else -> seconds.coerceAtLeast(MIN_SILENCE_SECONDS)
        }

        fun configuredSilenceSeconds(context: Context): Int {
            val restrictions = runCatching {
                (context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager)
                    ?.applicationRestrictions
            }.getOrNull()
            val seconds = if (restrictions != null && restrictions.containsKey(RESTRICTION_KEY)) {
                restrictions.getInt(RESTRICTION_KEY, DEFAULT_TAK_SERVER_KEEPALIVE_SECONDS)
            } else {
                DEFAULT_TAK_SERVER_KEEPALIVE_SECONDS
            }
            return normalize(seconds)
        }

        fun pingCot(deviceUid: String, nowMs: Long): String {
            val t = CotEventTimeFactory.at(nowMs, staleOffset = CotTimeOffset(seconds = PING_STALE_SECONDS))
            val uid = escapeAttribute("$deviceUid-ping")
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<event version=\"2.0\" uid=\"$uid\" type=\"t-x-c-t\" how=\"h-g-i-g-o\" " +
                "time=\"${t.timeCot}\" start=\"${t.startCot}\" stale=\"${t.staleCot}\">" +
                "<point lat=\"0.0\" lon=\"0.0\" hae=\"0.0\" ce=\"9999999\" le=\"9999999\"/>" +
                "<detail/></event>"
        }

        private fun escapeAttribute(value: String): String = value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
