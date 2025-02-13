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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import com.cryptlex.android.lexactivator.LexActivator
import com.cryptlex.android.lexactivator.LexActivatorException


class LicenseManagerService : Service() {

    private lateinit var dataRepository: DataRepository
    val lexActivator = LexActivator.ActivateLicense()

    override fun onCreate() {
        super.onCreate()

        dataRepository = MainActivity.dataRepository // or another injection approach
        Log.d(TAG, "Oncreate Called")
    }
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopForeground(true)
    }

    private fun activateLicense() {
        try {
            LexActivator.SetLicenseKey(dataRepository.getLicense.toString().trim())
            val status = LexActivator.ActivateLicense();
            if (LexActivator.LA_OK == status || LexActivator.LA_EXPIRED == status || LexActivator.LA_SUSPENDED == status) {
                Log.d(TAG, "License Activated Successfully")
            } else {
                Log.d(TAG, "License was not activated successfully")
            }

        } catch (e: LexActivatorException) {
            Log.d(TAG, "Fuccck the license didn't activate fuckckkkkkkckkkkcc")
        }


    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        isRunning = true
        LexActivator.SetProductData("N0Y0QjY4RDJGNDdENDE5MkUwNEVCNENEMkVEQkYzRTY=.IctD5F0sYyF45H4CvirTFLi7YneCs2S6cD/f4KWyUYFhediUoGXZX42TbzQ72kcqTexcCTtzB5mnQHLDmOVwGhZra3yijLZzwiXlz4LrMUidJLLozM+sg5Zd1jBLGpuWpfiSfqorZIL+RGaHdJgoFvO9rTuF5f5kjJUHiKZlmfcT7K40s0Jig7wFVmEB5k/5q/aS5/Ko6K4ZVdPL5oFpdekPXGMANOTNqyb6n6v1t3Tpyjqruxd6rm8LdemN3GqyyChcWdAlu5dD6+bvZ+MKyG5/t1lME29a00K6yYu5h4bAwQo/1vBMHStjPjKPXYLwDAcIgPxxepd4AmRqxTa8mUrPsZotqrvm4DZ+6wAsFqEWEE8AbBkLCe8UIdf0bOjm1Y5AatNrDLjWTAM2okCzxHB9XtxM1BsoKgVbPctpPNbms14S8NAHhGtZErSUJJIgDowymc2RHBm+ivI8N8ledZai3IEBNp3/+sjpCazGaLyv5TsmbFKwf9HmpXgu5wpR6lewwbGGNEUrGlUCKWXANpvPv4ofITZwqwImJ3ZPhfLS5k1mutZhb3cp4XwLIgu5URgUhZ6PirWjSL0L1khcjbJ78r2QrpdyKFqD4u08T4VuZtAa0/k2rasRfDnDJuabdPA2ehaFc1ro2c+aMramFwJ59fpQTXEFPR/kUXjx+JyLryLQ+KCvnqvEg8WPSg+dHWIjoE+l4S/BhegcaGH903VX3lJd9FL9IVpVcRBFBSFzxLeGZGRl4mrcgMrist/neiA81qYKCFsyE2aV6akNlc5qJFRfEPBWkia5OaH9Bk6TesBt9sUtwnLftEN9y4I5");
        LexActivator.SetProductId("a0cd6a11-fd66-4d9f-9793-296ae46500ba", LexActivator.LA_USER);
        startForegroundService() // service needs to work in the background when screen is off
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }

    private fun startForegroundService() {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        } else {
            "LicenseManagerService"
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
        val notification = notificationBuilder.setOngoing(true)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentText("WearTAK-CIV License Manager service running")
            .setPriority(Context.BIND_WAIVE_PRIORITY)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        try {
            startForeground(LICENSE_MANAGER_SERVICE_NOTIFICATION_ID, notification)
        } catch (e: NullPointerException) {
            Log.e(TAG, "Failed to set License Manager ForegroundService", e )
        }
    }

    private fun createNotificationChannel(): String {
        val channelId = "License_Manager_Service"
        val channelName = "License Manager Background Service"
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
        chan.lightColor = Color.GRAY
        chan.importance = NotificationManager.IMPORTANCE_NONE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    companion object {

        private val TAG = "WearTAK-" + LicenseManagerService::class.java.simpleName
        private val LICENSE_MANAGER_SERVICE_NOTIFICATION_ID = 4444
        var isRunning : Boolean = false
    }

}