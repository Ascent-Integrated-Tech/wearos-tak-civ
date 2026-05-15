/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.ipc

data class AtosCotPacket(
    val version: String?,
    val uid: String?,
    val type: String?,
    val time: String?,
    val start: String?,
    val stale: String?,
    val how: String?,
    val point: CotPoint?,
    val contact: CotContact?,
    val track: CotTrack?,
    val atosDetail: AtosDetail?,
    val link: CotLink?,
    val hasArchive: Boolean,
    val rawXmlLength: Int,
    val warnings: List<String> = emptyList()
)

data class CotPoint(
    val lat: Double?,
    val lon: Double?,
    val hae: Double?,
    val ce: Double?,
    val le: Double?
)

data class CotContact(
    val callsign: String?
)

data class CotTrack(
    val course: Double?,
    val speed: Double?,
    val vspeed: Double?
)

data class AtosDetail(
    val tagType: String?,
    val color: String?,
    val tagUid: Int?,
    val commandLink: String?,
    val tempC: Int?,
    val voltage: Double?,
    val alarm: Int?,
    val attributes: Map<String, String>
)

data class CotLink(
    val relation: String?,
    val uid: String?,
    val type: String?
)

sealed class AtosCotParseResult {
    data class Success(val packet: AtosCotPacket) : AtosCotParseResult()
    data class Failure(val reason: String) : AtosCotParseResult()
}

enum class AtosIpcRoute {
    LOCAL_INGEST_REQUEST,
    BROADCAST_REQUEST
}

object AtosReferenceTables {
    val tagTypeToCotType: Map<String, String> = mapOf(
        "PAX" to "a-f-G",
        "K9" to "a-f-G-A",
        "Vehicle" to "a-f-G-U-C-I-I",
        "Equipment" to "a-g-G-E",
        "Medical" to "a-f-G-U-S-M",
        "Weapon" to "a-f-G-E-W",
        "Custom" to "a-f-G-c",
        "Bundle" to "a-f-A",
        "Boat" to "a-f-S",
        "Fixed Wing" to "a-f-A-C-F",
        "Rotary Wing" to "a-f-A-C-H",
        "UAS" to "a-f-A-M"
    )

    val teamColorToArgb: Map<String, Long> = mapOf(
        "White" to 0xffffffffL,
        "Yellow" to 0xffffff00L,
        "Orange" to 0xffffa500L,
        "Magenta" to 0xffff00ffL,
        "Red" to 0xffff0000L,
        "Maroon" to 0xff800000L,
        "Purple" to 0xff800080L,
        "Blue" to 0xff0000ffL,
        "Cyan" to 0xff00ffffL,
        "Teal" to 0xff00b2b2L,
        "Green" to 0xff00ff00L,
        "Dark Green" to 0xff004c00L
    )
}
