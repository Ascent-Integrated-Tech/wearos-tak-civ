/*
 * Copyright (C) 2024-2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.weartak.weartak.manager

import android.telephony.SmsManager
import android.util.Log
import com.weartak.weartak.data.DataRepository
import com.weartak.weartak.presentation.MainActivity.Constants.callsign
import com.weartak.weartak.presentation.MainActivity.Constants.dataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SMSManager {

    companion object {
        val TAG = "WearTAK-" + SMSManager::class.java.simpleName
        private val ioScope = CoroutineScope(Dispatchers.IO)
        val smsManager = SmsManager.getDefault()

        fun sendSMSMessage(numbers: List<DataRepository.SmsNumber>, description: String) {
            ioScope.launch(Dispatchers.IO) {
                val loc = dataRepository.latestLocation.value
                var locMsg = "Location: Not Available"
                var altMsg = "Altitude: Not Available"

                if (null != loc) {
                    locMsg = "Location: https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
                    altMsg = "Altitude: ${loc.altitude}"
                }

                val messageBody = buildString {
                    appendLine("Emergency Alert!")
                    appendLine("Description: $description")
                    appendLine("Callsign: $callsign")
                    appendLine("$locMsg")
                    append("$altMsg")
                }
                Log.d(TAG, "Sending alert: $messageBody")

                for (number in numbers.map { it.telephoneNumber.trim() }.filter { it.isNotEmpty() }) {
                    try {
                        Log.d(TAG, "Message sent to: $number")
                        val messageParts = smsManager.divideMessage(messageBody)
                        Log.d(TAG, "messageParts.size: ${messageParts.size}")
                        smsManager.sendMultipartTextMessage(number, null, messageParts, null, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "ERROR: Failed to send message to: $number w/ error: ${e.message}", e)
                    }
                }
            }
        }
    }
}