package com.aitech.weartak_civ.manager

import android.telephony.SmsManager
import android.util.Log
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign

class SMSManager {

    companion object {
        private const val TAG = "SmsManagerService"

        fun sendSMSMessage(numbers: List<String>, description: String) {
            val smsManager = SmsManager.getDefault();

            //val callsign = self.getMetaString("callsign", self.uid)
            //val location: GeoPoint = self.point

            var latitude = "1.345"
            var longitude = "31.34"
            var altitude = "1234"

            val messageBody = buildString {
                appendLine("Emergency Alert!")
                appendLine("Description: $description")
                appendLine("Caller: $callsign")
                appendLine("Location: https://maps.google.com/?q=${latitude},${longitude}")
                append("Altitude: ${altitude}")
            }

            Log.d(TAG, "Sending alert:")
            Log.d(TAG, messageBody)

            for (number in numbers.map { it.trim() }.filter { it.isNotEmpty() }) {
                try {
                    val messageParts = smsManager.divideMessage(messageBody)
                    Log.d(TAG, "messageParts.size: ${messageParts.size}")

                    smsManager.sendMultipartTextMessage(number, null, messageParts, null, null)

                    Log.d(TAG, "Message sent to: $number")
                } catch (e: Exception) {
                    Log.e(TAG, "ERROR: Failed to send message to: $number w/ error: ${e.message}", e)
                }
            }
        }
    }
}