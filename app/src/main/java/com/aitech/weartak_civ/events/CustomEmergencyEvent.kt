package com.aitech.weartak_civ.events

class CustomEmergencyEvent(val category: String, val description: String, val priority: Int, val sendSms: Boolean) {

    companion object {
        val CAT_MANUAL = "Manual SOS Alert"
        val CAT_Biometric = "Biometric Alert"
        val DESC_SOS_ALERT = "Manual SOS Alert"
        val DESC_HIGH_HEART_RATE_ALERT = "High Heart Rate Alert"
        val DESC_LOW_HEART_RATE_ALERT = "Low Heart Rate Alert"
    }
}