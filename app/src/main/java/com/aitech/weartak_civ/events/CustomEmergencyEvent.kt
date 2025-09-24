package com.aitech.weartak_civ.events

class CustomEmergencyEvent(val category: String, val description: String, val priority: Int, val sendSms: Boolean) {

    companion object {
        val CAT_MANUAL = "Manual SOS Alert"
        val CAT_Biometric = "Biometric Alert"
        val DESC_SOS_ALERT = "SOS Alert Pressed by User"
        val DESC_HIGH_HEART_RATE_ALERT = "High Heart Rate Alert"
        val DESC_LOW_HEART_RATE_ALERT = "Low Heart Rate Alert"
        val CAT_SOS_ALERT_CANCELLED = "Manual SOS Alert Cancelled"
        val DESC_SOS_ALERT_CANCELLED = "SOS Alert Canceled by User"

        //Added for Fall Detect Support
        val CAT_FALL_DETECTED = "Fall Detected"
        val DESC_GOOGLE_SDK_FALL_DETECTED = "Hard fall detected with no motion for 30 seconds"
    }
}
