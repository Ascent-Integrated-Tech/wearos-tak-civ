/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.weartak.weartak.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.weartak.weartak.Utils.startForegroundServiceCompat
import java.util.concurrent.atomic.AtomicBoolean

class CustomAccessibilityService: AccessibilityService() {

    companion object {
        private val TAG = "WearTAK-" + CustomAccessibilityService::class.java.simpleName
        val isRunning : AtomicBoolean = AtomicBoolean(false)
        val CUSTOM_ACCESS_SERVICE_NOTIF_ID = 112
        // Currently we are not using this
        val isCustomAccessibilityServiceConnected = AtomicBoolean(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        Log.v(TAG, "CustomAccessibilityService onAccessibilityEvent()")
    }

    override fun onInterrupt() {
        Log.d(TAG, "CustomAccessibilityService onInterrupt()")
    }

    // Gets automatically called by OS if WearTAK-CIV is switched on as part of Accessibility UI in
    // main Settings UI (Accessibility->Installed Apps->WearTAK-CIV->Move switch to "On"
    // So the app never calls onStartCommand
    override fun onServiceConnected() {
        super.onServiceConnected()
        isCustomAccessibilityServiceConnected.set(true)
        applicationContext.startForegroundService(Intent(applicationContext, CustomAccessibilityService::class.java))
        Log.d(TAG, "CustomAccessibilityService onServiceConnected()")
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CustomAccessibilityService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            notificationId = CUSTOM_ACCESS_SERVICE_NOTIF_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CustomAccessibilityService onDestroy()")
        isRunning.set(false)
        //isCustomAccessibilityServiceConnected.set(false)
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "CustomAccessibilityService onStartCommand()")
        return START_NOT_STICKY
    }

}