/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.R
import java.util.concurrent.atomic.AtomicBoolean

class CustomAccessibilityService: AccessibilityService() {

    companion object {
        private val TAG = "WearTAK-" + CustomAccessibilityService::class.java.simpleName
        val isRunning : AtomicBoolean = AtomicBoolean(false)
        // Currently we are not using this
        val isCustomAccessibilityServiceConnected = AtomicBoolean(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        Log.v(TAG, "onAccessibilityEvent received")
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt received")
    }

    // Gets automatically called by OS if WearTAK-CIV is switched on as part of Accessibility UI in
    // main Settings UI (Accessibility->Installed Apps->WearTAK-CIV->Move switch to "On"
    // So the app never calls onStartCommand
    override fun onServiceConnected() {
        super.onServiceConnected()
        isCustomAccessibilityServiceConnected.set(true)
        applicationContext.startForegroundService(Intent(applicationContext, CustomAccessibilityService::class.java))
        Log.d(TAG, "CustomAccessibilityService onServiceConnected received")
    }

    override fun onCreate() {
        Log.d(TAG, "CustomAccessibilityService onCreate called")

    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CustomAccessibilityService onDestroy called")
        isRunning.set(false)
        //isCustomAccessibilityServiceConnected.set(false)
    }


    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "CustomAccessibilityService onStartCommand called")
        if (!isRunning.getAndSet(true)) {
            // Start it only once
            startForegroundService()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        } else {
            "Custom Accessibility Service"
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
        val notification = notificationBuilder.setOngoing(true)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentText("WearTAK-CIV custom accessibility service running")
            .setPriority(Context.BIND_WAIVE_PRIORITY)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        startForeground(678, notification)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = "Custom Accessibility Service"
        val channelName = "Custom Accessibility Foreground Service"
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
        chan.lightColor = Color.GRAY
        chan.importance = NotificationManager.IMPORTANCE_NONE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }
}