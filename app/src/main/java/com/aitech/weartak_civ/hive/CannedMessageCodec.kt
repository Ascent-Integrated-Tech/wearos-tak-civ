/*
 * Copyright (C) 2025-2026, Ascent Integrated Tech. All rights reserved.
 *
 * CannedMessage encoder/decoder for hive-lite tactical messaging.
 * This implements the 0xAF wire format for CannedMessages.
 */

package com.aitech.weartak_civ.hive

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CannedMessage encoder/decoder for hive-lite tactical messaging.
 *
 * Wire format (22 bytes):
 * - Marker: 1 byte (0xAF)
 * - MessageCode: 1 byte
 * - SourceNode: 4 bytes (little-endian u32)
 * - TargetNode: 4 bytes (little-endian u32, 0 = broadcast)
 * - Timestamp: 8 bytes (little-endian u64, ms since epoch)
 * - Sequence: 4 bytes (little-endian u32)
 */
object CannedMessageCodec {
    /** Wire format marker for CannedMessage */
    const val MARKER: Byte = 0xAF.toByte()

    /** Wire format size in bytes */
    const val WIRE_SIZE = 22

    /**
     * Decoded CannedMessage event.
     */
    data class CannedMessageEvent(
        val messageCode: Int,
        val sourceNode: Long,
        val targetNode: Long,
        val timestamp: Long,
        val sequence: Int
    )

    /**
     * Decode a CannedMessage from wire format.
     *
     * @param data Wire format bytes (must start with 0xAF marker)
     * @return Decoded event, or null if invalid
     */
    fun decode(data: ByteArray): CannedMessageEvent? {
        if (data.size < WIRE_SIZE || data[0] != MARKER) return null

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.get()  // skip marker

        return CannedMessageEvent(
            messageCode = buffer.get().toInt() and 0xFF,
            sourceNode = buffer.int.toLong() and 0xFFFFFFFFL,
            targetNode = buffer.int.toLong() and 0xFFFFFFFFL,
            timestamp = buffer.long,
            sequence = buffer.int
        )
    }

    /**
     * Encode a CannedMessage to wire format.
     *
     * @param messageCode The CannedMessage code (see CannedMessageType)
     * @param sourceNode This node's ID
     * @param targetNode Target node ID (0 for broadcast)
     * @param timestamp Message timestamp in milliseconds
     * @param sequence Sequence number (optional, for dedup)
     * @return Wire format bytes (22 bytes)
     */
    fun encode(
        messageCode: Int,
        sourceNode: Long,
        targetNode: Long = 0,
        timestamp: Long = System.currentTimeMillis(),
        sequence: Int = 0
    ): ByteArray {
        val buffer = ByteBuffer.allocate(WIRE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MARKER)
        buffer.put(messageCode.toByte())
        buffer.putInt(sourceNode.toInt())
        buffer.putInt(targetNode.toInt())
        buffer.putLong(timestamp)
        buffer.putInt(sequence)
        return buffer.array()
    }

    /**
     * Get human-readable label for a message code.
     */
    fun getLabel(code: Int): String {
        return CannedMessageType.fromCode(code)?.label ?: "Unknown ($code)"
    }
}

/**
 * Predefined CannedMessage types for tactical communication.
 *
 * Message codes are organized into categories:
 * - 0x00-0x0F: Acknowledgments
 * - 0x10-0x1F: Status updates
 * - 0x20-0x2F: Alerts
 * - 0x30-0x3F: Requests
 * - 0xF0-0xFF: Reserved/Custom
 */
enum class CannedMessageType(val code: Int, val label: String) {
    // ===== Acknowledgments (0x00-0x0F) =====
    ACK(0x00, "Ack"),
    ACK_WILCO(0x01, "Wilco"),
    ACK_NEGATIVE(0x02, "Negative"),
    ACK_SAY_AGAIN(0x03, "Say Again"),

    // ===== Status (0x10-0x1F) =====
    CHECK_IN(0x10, "Check In"),
    MOVING(0x11, "Moving"),
    HOLDING(0x12, "Holding"),
    ON_STATION(0x13, "On Station"),
    RETURNING(0x14, "Returning"),
    COMPLETE(0x15, "Complete"),

    // ===== Alerts (0x20-0x2F) =====
    EMERGENCY(0x20, "EMERGENCY"),
    ALERT(0x21, "Alert"),
    ALL_CLEAR(0x22, "All Clear"),
    CONTACT(0x23, "Contact"),
    UNDER_FIRE(0x24, "Under Fire"),

    // ===== Requests (0x30-0x3F) =====
    NEED_EXTRACT(0x30, "Need Extract"),
    NEED_SUPPORT(0x31, "Need Support"),
    NEED_MEDIC(0x32, "Need Medic"),
    NEED_RESUPPLY(0x33, "Need Resupply"),

    // ===== Reserved (0xF0-0xFF) =====
    CUSTOM(0xFF, "Custom");

    companion object {
        private val codeMap = entries.associateBy { it.code }

        fun fromCode(code: Int): CannedMessageType? = codeMap[code]
    }
}
