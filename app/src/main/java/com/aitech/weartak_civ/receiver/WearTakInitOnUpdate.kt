/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
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
import com.aitech.weartak_civ.Utils.startMainActivityIfNotRunning

class WearTakInitOnUpdate : BroadcastReceiver() {
    companion object {
        private val TAG = "WearTAK-InitOnUpdate"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "onReceive of WearTAK app update w/ action ${intent?.action}")
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent?.action)) {
            try {
                Thread.sleep(5000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }

            if (context != null) startMainActivityIfNotRunning(context)
        }
    }
}