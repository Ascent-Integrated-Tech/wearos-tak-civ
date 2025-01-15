/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

/*
 *  DittoService handles Ditto's sync lifecycle (startSync, stopSync) and related operations.
 */

package com.aitech.weartak_civ.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.DittoApplication
import live.ditto.DittoError

class DittoService : Service() {

    override fun onCreate() {
        super.onCreate()

        try {
            val ditto = DittoApplication.getDittoInstance()
            ditto.startSync() // Start syncing
            Log.d("DittoService", "Sync started")
        } catch (e: DittoError) {
            Log.e("DittoService", "Error starting sync: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Stop syncing when the service is destroyed
        try {
            val ditto = DittoApplication.getDittoInstance()
            ditto.stopSync()
            Log.d("DittoService", "Sync stopped")
        } catch (e: DittoError) {
            Log.e("DittoService", "Error stopping sync: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }
}
