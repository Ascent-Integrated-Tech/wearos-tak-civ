/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.command

import java.util.Locale

enum class ChatCommandDecision { ALLOWED, SENDER_NOT_ALLOWED, STALE, DUPLICATE }

/**
 * Decides whether a parsed chat command may run. Senders are matched by callsign (case-insensitive),
 * so the allow-list is a convenience filter, not authentication: anyone who can pick an allowed
 * callsign on the same server can send commands, which is why server enrollment still prompts.
 */
class ChatCommandAuthorizer(
    private val maxAgeMs: Long = 5 * 60_000L,
    private val maxRemembered: Int = 128
) {
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun authorize(
        senderCallsign: String,
        allowedCallsigns: Collection<String>,
        messageKey: String,
        sentAtMs: Long,
        nowMs: Long
    ): ChatCommandDecision {
        val sender = normalize(senderCallsign)
        if (sender.isEmpty() || allowedCallsigns.none { normalize(it) == sender }) {
            return ChatCommandDecision.SENDER_NOT_ALLOWED
        }
        if (sentAtMs <= 0 || nowMs - sentAtMs > maxAgeMs || sentAtMs - nowMs > maxAgeMs) {
            return ChatCommandDecision.STALE
        }
        val key = "$sender|$messageKey"
        if (!seen.add(key)) return ChatCommandDecision.DUPLICATE
        while (seen.size > maxRemembered) seen.remove(seen.first())
        return ChatCommandDecision.ALLOWED
    }

    companion object {
        fun normalize(callsign: String) = callsign.trim().lowercase()

        fun parseAllowList(text: String): List<String> =
            text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(::normalize)

        fun sortedAllowList(callsigns: Collection<String>): List<String> =
            callsigns.map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy(::normalize)
                .sortedWith(String.CASE_INSENSITIVE_ORDER)

        fun validatedCallsign(value: String): String? =
            ChatCommandValidation.callsign(value)?.uppercase(Locale.ROOT)
    }
}