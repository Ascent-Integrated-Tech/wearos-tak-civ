/*
 * Copyright (C) 2024-2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aitech.weartak_civ.service.LocationService

class AlarmReceiver : BroadcastReceiver() {
    private val TAG = "WearTAK-AlarmReceiver"

    override fun onReceive(context: Context, intent: Intent?) {
        //Log.d(TAG, "onReceive broadcast")

        // TODO: Check request code or send some data if possible intent. For now, we assume that we just need to call LocationService
        LocationService.getInstance()?.acquireWakelockAndRequestLocation()
    }

}
