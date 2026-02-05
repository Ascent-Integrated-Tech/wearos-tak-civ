/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.weartak.weartak.receiver

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.weartak.weartak.presentation.MainActivity

class WearTakInitOnBoot : BroadcastReceiver() {
    companion object {
        private val TAG = "WearTAK-" + "InitOnBoot"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "onReceive of WearTAK boot completed w/ action ${intent?.action}")
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent?.action)) {
            // Create an intent to start MainActivity.
            val activityIntent = Intent(context, MainActivity::class.java).apply {
                // Needed when starting an Activity from a non-Activity context.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val options = ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()

            val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options)

            // Launch the activity using the PendingIntent.
            try {
                pendingIntent.send()
            } catch (e: PendingIntent.CanceledException) {
                Log.d(TAG, "pendingIntent send error: ${e.message}", e)
            }

            // Now able start activity from background by binding to system Assessibility Service. We no longer get the following error:
            // ActivityManager system_server  E  Resetting option setPendingIntentBackgroundActivityStartMode(1) to SYSTEM_DEFINED from the options provided by the pending intent creator (com.weartak.weartak) because this option is meant for the pending intent sender
            // ActivityTaskManager system_server E  Background activity launch blocked! [callingPackage: com.weartak.weartak; callingPackageTargetSdk: 34; callingUid: 10187; callingPid: -1; appSwitchState: 2; callingUidHasAnyVisibleWindow: false; callingUidProcState: RECEIVER; isCallingUidPersistentSystemProcess: false; forcedBalByPiSender: BSP.NONE; intent: Intent { flg=0x10000000 cmp=com.weartak.weartak/.presentation.MainActivity }; callerApp: null; balAllowedByPiCreator: BSP.ALLOW_BAL; balAllowedByPiCreatorWithHardening: BSP.ALLOW_BAL; resultIfPiCreatorAllowsBal: BAL_BLOCK; hasRealCaller: true; isCallForResult: false; isPendingIntent: true; autoOptInReason: sameUid; realCallingPackage: com.weartak.weartak; realCallingPackageTargetSdk: 34; realCallingUid: 10187; realCallingPid: 3245; realCallingUidHasAnyVisibleWindow: false; realCallingUidProcState: RECEIVER; isRealCallingUidPersistentSystemProcess: false; originatingPendingIntent: PendingIntentRecord{2973644 com.weartak.weartak startActivity}; realCallerApp: ProcessRecord{4ada452 3245:com.weartak.weartak/u0a187}; realInVisibleTask: false; balAllowedByPiSender: BSP.ALLOW_BAL; resultIfPiSenderAllowsBal: BAL_BLOCK]
        }
    }
}