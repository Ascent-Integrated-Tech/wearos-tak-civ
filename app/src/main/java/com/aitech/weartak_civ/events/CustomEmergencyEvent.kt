/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.events

class CustomEmergencyEvent(val category: String, val description: String, val priority: Int, val sendSms: Boolean) {

    companion object {
        val CAT_MANUAL = "Manual SOS Alert"
        val CAT_Biometric = "Biometric Alert"
        val DESC_SOS_ALERT = "Manual SOS Alert"
        val DESC_HIGH_HEART_RATE_ALERT = "High Heart Rate Alert"
        val DESC_LOW_HEART_RATE_ALERT = "Low Heart Rate Alert"
        val ALERT_CANCELLED = "Alert Cancelled"

        //Added for Fall Detect Support
        val FALL_ALLERT = "Fall Alert"
    }
}