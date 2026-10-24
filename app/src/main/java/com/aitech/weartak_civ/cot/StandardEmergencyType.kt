package com.aitech.weartak_civ.cot

enum class StandardEmergencyType(
    val displayName: String,
    val cotType: String,
    val cotDescription: String,
) {
    NINE_ONE_ONE("911 Alert", "b-a-o-tbl", "911 Alert"),
    RING_THE_BELL("Ring The Bell", "b-a-o-pan", "Ring The Bell"),
    GEOFENCE_BREACHED("Geofence Breached", "b-a-g", "Geo-fence Breached"),
    IN_CONTACT("In Contact", "b-a-o-opn", "Troops In Contact");

    companion object {
        fun fromCotType(type: String): StandardEmergencyType? =
            entries.firstOrNull { it.cotType == type }

        fun fromDisplayName(name: String): StandardEmergencyType? =
            entries.firstOrNull { it.displayName == name }
    }
}

data class ManualAlertOption(
    val shortCode: String,
    val displayName: String,
    val standardType: StandardEmergencyType? = null,
)

object ManualAlertTypes {
    val options = listOf(
        ManualAlertOption("911", StandardEmergencyType.NINE_ONE_ONE.displayName, StandardEmergencyType.NINE_ONE_ONE),
        ManualAlertOption("GATERU", "Gate Runner"),
        ManualAlertOption("GEOFENCE", StandardEmergencyType.GEOFENCE_BREACHED.displayName, StandardEmergencyType.GEOFENCE_BREACHED),
        ManualAlertOption("GNSHOT", "Gunshot"),
        ManualAlertOption("CONTACT", StandardEmergencyType.IN_CONTACT.displayName, StandardEmergencyType.IN_CONTACT),
        ManualAlertOption("INJURY", "Injury"),
        ManualAlertOption("RING", StandardEmergencyType.RING_THE_BELL.displayName, StandardEmergencyType.RING_THE_BELL),
        ManualAlertOption("UAS", "UAS"),
        ManualAlertOption("VEH", "Vehicle"),
    )
}
