package com.aitech.weartak_civ.cot

import java.io.ByteArrayOutputStream

class CompanionJsonFramer {
    private val pending = ByteArrayOutputStream()
    @Synchronized fun clear() { pending.reset() }
    @Synchronized fun isEmpty(): Boolean = pending.size() == 0
    @Synchronized fun accept(bytes: ByteArray): List<String> {
        val lines = mutableListOf<String>()
        for (byte in bytes) {
            if (byte == 10.toByte()) {
                pending.toByteArray().toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }?.let(lines::add)
                pending.reset()
            } else {
                if (pending.size() >= 262144) {
                    pending.reset()
                    error("Companion JSON frame exceeds 256 KiB")
                }
                pending.write(byte.toInt())
            }
        }
        return lines
    }
}
