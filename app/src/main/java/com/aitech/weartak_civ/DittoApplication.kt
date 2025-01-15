/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

/*
 *  DittoApplication ensures a single, globally accessible Ditto instance that persists for the app's entire lifecycle. Use getDittoInstance() for the instance.
 */

package com.aitech.weartak_civ

import android.app.Application
import android.util.Log
import live.ditto.Ditto
import live.ditto.DittoError
import live.ditto.DittoIdentity
import live.ditto.android.DefaultAndroidDittoDependencies

class DittoApplication : Application() {

    companion object {
        private lateinit var ditto: Ditto

        fun getDittoInstance(): Ditto {
            if (!::ditto.isInitialized) {
                throw IllegalStateException("Ditto instance is not initialized!")
            }
            return ditto
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize Ditto
        try {
            val androidDependencies = DefaultAndroidDittoDependencies(applicationContext)
            val identity = DittoIdentity.OnlinePlayground(
                androidDependencies,
                appId = "YOUR_APP_ID",
                token = "YOUR_PLAYGROUND_TOKEN"
            )
            ditto = Ditto(androidDependencies, identity)
        } catch (e: DittoError) {
            Log.e("Ditto", "Failed to initialize Ditto: ${e.message}")
        }
    }
}
