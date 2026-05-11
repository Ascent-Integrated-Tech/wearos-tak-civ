package com.aitech.weartak_civ.cep.engine.config

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Canonical JSON serialization used for hash identity and no-op detection
 * (per WEAR-508 design — Diff identity).
 *
 * Rules:
 *  - object keys sorted lexicographically
 *  - insignificant whitespace removed
 *  - integer values emitted as integers
 *  - floating-point values emitted via standard round-trip string form
 *  - arrays preserve order
 *
 * The output is the canonical String form. SHA-256 of the canonical string is the
 * canonical hash used for `cep_configuration_applied_hash` and `payloadHash`.
 */
object CanonicalJson {

    /** Return the canonical String form of [value]. */
    fun canonicalize(value: Any?): String {
        val sb = StringBuilder()
        writeValue(sb, value)
        return sb.toString()
    }

    /** Convenience: parse [raw] then canonicalize. Returns null if [raw] is not valid JSON. */
    fun canonicalizeOrNull(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            val trimmed = raw.trim()
            val parsed: Any = when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> return null
            }
            canonicalize(parsed)
        } catch (_: Throwable) {
            null
        }
    }

    /** SHA-256 of the canonical form. Lowercase hex; empty string for null/blank input. */
    fun sha256(canonical: String): String {
        if (canonical.isEmpty()) return ""
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** Convenience: canonicalize and hash a raw payload. Returns "" on parse failure. */
    fun hashRaw(raw: String?): String = sha256(canonicalizeOrNull(raw) ?: "")

    private fun writeValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> sb.append("null")
            is JSONObject -> writeObject(sb, value)
            is JSONArray -> writeArray(sb, value)
            is Map<*, *> -> writeMap(sb, value)
            is List<*> -> writeList(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Float, is Double -> sb.append(numberRoundTrip(value))
            is Number -> sb.append(value.toString())
            is String -> writeString(sb, value)
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeObject(sb: StringBuilder, obj: JSONObject) {
        val keys = obj.keys().asSequence().toMutableList()
        keys.sort()
        sb.append('{')
        var first = true
        for (k in keys) {
            if (!first) sb.append(',')
            first = false
            writeString(sb, k)
            sb.append(':')
            writeValue(sb, obj.opt(k))
        }
        sb.append('}')
    }

    private fun writeMap(sb: StringBuilder, map: Map<*, *>) {
        val keys = map.keys.map { it?.toString() ?: "null" }.toMutableList().also { it.sort() }
        sb.append('{')
        var first = true
        for (k in keys) {
            if (!first) sb.append(',')
            first = false
            writeString(sb, k)
            sb.append(':')
            writeValue(sb, map[k] ?: map.entries.firstOrNull { (it.key?.toString() ?: "null") == k }?.value)
        }
        sb.append('}')
    }

    private fun writeArray(sb: StringBuilder, arr: JSONArray) {
        sb.append('[')
        for (i in 0 until arr.length()) {
            if (i > 0) sb.append(',')
            writeValue(sb, arr.opt(i))
        }
        sb.append(']')
    }

    private fun writeList(sb: StringBuilder, list: List<*>) {
        sb.append('[')
        var first = true
        for (item in list) {
            if (!first) sb.append(',')
            first = false
            writeValue(sb, item)
        }
        sb.append(']')
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"'  -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u").append(String.format("%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }

    private fun numberRoundTrip(value: Number): String {
        val d = value.toDouble()
        if (d.isNaN() || d.isInfinite()) return "null"
        // Integer-valued doubles emit as integers per the design rule.
        if (d == d.toLong().toDouble() && !d.toString().contains('e', ignoreCase = true)) {
            return d.toLong().toString()
        }
        return d.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
