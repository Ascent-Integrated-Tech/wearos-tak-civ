package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.cot.CotAffiliation

internal data class MarkerVisualSpec(
    val icon: Int,
    val label: String,
    val tintArgb: Long? = null
)

private data class TypeSymbol(val icon: Int, val noGpsIcon: Int, val label: String)

private val typeSymbols = mapOf(
    "G-U-C-I" to TypeSymbol(R.drawable.ic_track_pax, R.drawable.ic_pax_no_gps, "Personnel"),
    "G-A" to TypeSymbol(R.drawable.ic_track_hound, R.drawable.ic_track_hound_no_gps, "K-9"),
    "G-U-C-I-I" to TypeSymbol(R.drawable.ic_track_veh, R.drawable.ic_track_veh_no_gps, "Vehicle"),
    "G-E" to TypeSymbol(R.drawable.ic_track_equipment, R.drawable.ic_track_equipment_no_gps, "Equipment"),
    "G-U-S-M" to TypeSymbol(R.drawable.ic_track_medical, R.drawable.ic_track_medical_no_gps, "Medical"),
    "G-E-W" to TypeSymbol(R.drawable.ic_track_weapon, R.drawable.ic_track_weapon_no_gps, "Weapon"),
    "G-c" to TypeSymbol(R.drawable.ic_track_custom, R.drawable.ic_track_custom_no_gps, "Custom"),
    "S" to TypeSymbol(R.drawable.ic_track_boat, R.drawable.ic_track_boat_no_gps, "Boat"),
    "A-C-F" to TypeSymbol(R.drawable.ic_track_fixed, R.drawable.ic_track_fixed_no_gps, "Fixed Wing"),
    "A-C-H" to TypeSymbol(R.drawable.ic_track_rotary, R.drawable.ic_track_rotary_no_gps, "Rotary Wing"),
    "A-M" to TypeSymbol(R.drawable.ic_track_uas, R.drawable.ic_track_uas_no_gps, "UAV")
)

private fun typeSymbol(type: String, atos: Boolean): TypeSymbol? {
    if (!CotAffiliation.isAtomType(type)) return null
    val suffix = type.substring(4)
    if (atos && suffix == "G") {
        return TypeSymbol(R.drawable.ic_track_pax, R.drawable.ic_pax_no_gps, "Personnel")
    }
    if (atos && suffix == "A") {
        return TypeSymbol(R.drawable.ic_track_bundle, R.drawable.ic_track_bundle_no_gps, "Bundle")
    }
    typeSymbols[suffix]?.let { return it }
    // Match complete hierarchy segments, with more specific aircraft types before A-M.
    fun within(parent: String) = suffix == parent || suffix.startsWith("$parent-")
    return when {
        within("A-C-F") || within("A-M-F") -> typeSymbols["A-C-F"]
        within("A-C-H") || within("A-M-H") -> typeSymbols["A-C-H"]
        within("S") -> typeSymbols["S"]
        within("G-E-V") || within("G-U-C-I-I") -> typeSymbols["G-U-C-I-I"]
        else -> null
    }
}

internal fun markerVisualSpec(
    type: String,
    cleared: Boolean = false,
    atos: Boolean = false
): MarkerVisualSpec {
    val affiliation = CotAffiliation.fromType(type)
    val tint = if (cleared) 0xFF808080L else affiliation.argb
    if (type == "b-m-p-s-p-i") return MarkerVisualSpec(R.drawable.spi1_icon, "SPI")
    if (CotAffiliation.isAtomType(type) &&
        (type.substring(4) == "A-M-F-Q" || type.substring(4).startsWith("A-M-F-Q-"))) {
        val icon = when {
            cleared -> R.drawable.ic_track_uas_no_gps
            affiliation == CotAffiliation.FRIENDLY -> R.drawable.drone_icon_friendly
            affiliation == CotAffiliation.HOSTILE -> R.drawable.drone_icon_hostile
            else -> R.drawable.drone_icon
        }
        return MarkerVisualSpec(icon, "${affiliation.label} UAV",
            if (!cleared && affiliation != CotAffiliation.NEUTRAL) null else tint)
    }
    typeSymbol(type, atos)?.let { symbol ->
        return MarkerVisualSpec(
            if (cleared) symbol.noGpsIcon else symbol.icon,
            "${affiliation.label} ${symbol.label}",
            tint
        )
    }
    val icon = when (affiliation) {
        CotAffiliation.FRIENDLY -> if (cleared) R.drawable.friendly_gray else R.drawable.friendly
        CotAffiliation.HOSTILE -> if (cleared) R.drawable.hostile_gray else R.drawable.hostile
        CotAffiliation.NEUTRAL -> if (cleared) R.drawable.neutral_gray else R.drawable.neutral
        CotAffiliation.UNKNOWN -> if (cleared) R.drawable.unknown_gray else R.drawable.unknown
    }
    return MarkerVisualSpec(icon, affiliation.label)
}
