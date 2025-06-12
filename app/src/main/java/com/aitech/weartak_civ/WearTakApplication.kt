/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

/*
 * Moving Ditto initialization to onCreate of DittoService as initializing Ditto takes a lot of resources and
 * causes ANR when the Application is started on boot. Also Ditto initialization should be done only if Ditto service
 * is started.
 * Use WearTakApplication to create a single, globally accessible instance that persists for the app's entire lifecycle.
 */

package com.aitech.weartak_civ

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import live.ditto.Ditto
import live.ditto.DittoError
import live.ditto.DittoIdentity
import live.ditto.android.DefaultAndroidDittoDependencies

@HiltAndroidApp
class WearTakApplication : Application() {

    companion object {
        val TAG = "WearTAK-Applicaton"
    }

    override fun onCreate() {
        super.onCreate()
        Log.v(TAG, "WearTakApplication created")
    }

}
